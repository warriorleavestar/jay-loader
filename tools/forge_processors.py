"""M4: replicate the Forge 1.20.1 installer's CLIENT processors inside the prototype dir (never .minecraft).
Same scheme as neoforge_processors.py. Inputs: mc/forge/inst/install_profile.json (tools/fetch_forge.py), the downloaded
libraries, our sha1-checked 1.20.1 client.jar. DOWNLOAD_MOJMAPS = copy of the sha1-checked Mojang client mappings we have.
Every output with an expected hash in the profile (MC_SLIM_SHA, MC_EXTRA_SHA, PATCHED_SHA) is checked.
Output: mc/forge/libraries/... and mc/forge/platform-cache/<ver>-client-<key>.json."""
import hashlib, json, os, re, shutil, subprocess, sys, zipfile
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
ROOT = subprocess.run([sys.executable, '-I', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'proto_root.py')],
                      capture_output=True, text=True, check=True).stdout.strip().replace('/', os.sep)
FG = os.path.join(ROOT, 'mc', 'forge')
JAVA = os.path.join(ROOT, 'tools', 'jre21', 'bin', 'java.exe')
LIB = os.path.join(FG, 'libraries')
prof = json.load(open(os.path.join(FG, 'inst', 'install_profile.json')))
SIDE = 'client'
MCV = prof['minecraft']

def maven_path(coord):
    ext = 'jar'
    if '@' in coord: coord, ext = coord.split('@')
    p = coord.split(':'); g, a, v = p[0], p[1], p[2]; c = p[3] if len(p) > 3 else None
    return os.path.join(LIB, *g.split('.'), a, v, f"{a}-{v}{'-' + c if c else ''}.{ext}")

def resolve(val):
    if val.startswith('[') and val.endswith(']'): return maven_path(val[1:-1])
    if val.startswith("'") and val.endswith("'"): return val[1:-1]
    if val.startswith('/'): return os.path.join(FG, 'inst', val[1:].replace('/', os.sep))
    return val
vars_ = {k: resolve(v[SIDE]) for k, v in prof['data'].items()}
inst = [f for f in os.listdir(FG) if f.endswith('-installer.jar')][0]
vars_.update({'SIDE': SIDE, 'MINECRAFT_JAR': os.path.join(ROOT, 'mc', 'versions', MCV, f'{MCV}.jar'), 'ROOT': os.path.join(FG, 'work'),
              'INSTALLER': os.path.join(FG, inst), 'MINECRAFT_VERSION': MCV, 'LIBRARY_DIR': LIB})
def arg(a):
    a = re.sub(r'\{(\w+)\}', lambda m: vars_.get(m.group(1), m.group(0)), a)
    if a.startswith('[') and a.endswith(']'): return maven_path(a[1:-1])
    return a
def sha1(p): return hashlib.sha1(open(p, 'rb').read()).hexdigest()

mc_sha1 = sha1(vars_['MINECRAFT_JAR'])
tools_ = sorted({pr['jar'] for pr in prof['processors'] if not pr.get('sides') or SIDE in pr['sides']})
KEY = hashlib.sha256(json.dumps([prof['version'], mc_sha1, tools_, SIDE]).encode()).hexdigest()[:16]
MARK = os.path.join(FG, 'platform-cache', f'{prof["version"]}-{SIDE}-{KEY}.json')
if os.path.exists(MARK): print('cached:', open(MARK).read()); sys.exit(0)
for pr in prof['processors']:
    if pr.get('sides') and SIDE not in pr['sides']: continue
    args = [arg(x) for x in pr['args']]
    if 'DOWNLOAD_MOJMAPS' in args:
        out = args[args.index('--output') + 1]; os.makedirs(os.path.dirname(out), exist_ok=True)
        want = json.load(open(os.path.join(ROOT, 'mc', 'versions', MCV, f'{MCV}.json')))['downloads']['client_mappings']['sha1']
        src = os.path.join(ROOT, 'mc', 'mappings-cache', MCV, 'mojang-client.txt')
        if sha1(src) != want: sys.exit('local mojmaps sha1 mismatch')
        shutil.copyfile(src, out); print('DOWNLOAD_MOJMAPS -> copied local mojang-client.txt (sha1 ok)'); continue
    jar = maven_path(pr['jar'])
    with zipfile.ZipFile(jar) as z: main = re.search(r'Main-Class: *(\S+)', z.read('META-INF/MANIFEST.MF').decode()).group(1)
    cp = os.pathsep.join([jar] + [maven_path(c) for c in pr.get('classpath', [])])
    outs = [args[i + 1] for i, x in enumerate(args) if x in ('--output', '--slim', '--extra')]
    if outs and all(os.path.exists(o) for o in outs): print('skip (done)', pr['jar']); continue
    for o in outs: os.makedirs(os.path.dirname(o), exist_ok=True)
    print('RUN', pr['jar'], ' '.join(args[:4]), flush=True)
    r = subprocess.run([JAVA, '-cp', cp, main] + args, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if r.returncode != 0: print(r.stdout[-3000:], r.stderr[-3000:]); sys.exit(f'processor failed: {pr["jar"]}')
for k in ('MC_SLIM', 'MC_EXTRA', 'PATCHED'):
    got = sha1(vars_[k]); want = vars_.get(k + '_SHA')
    print(k, got, 'OK' if got == want else f'MISMATCH (want {want})')
    if want and got != want: sys.exit(f'{k} hash mismatch')
os.makedirs(os.path.dirname(MARK), exist_ok=True)
res = {'forge': prof['version'], 'side': SIDE, 'mcJarSha1': mc_sha1, 'tools': tools_, 'key': KEY,
       'patched': vars_['PATCHED'], 'srg': vars_['MC_SRG'], 'extra': vars_['MC_EXTRA'], 'slim': vars_['MC_SLIM'], 'mappings': vars_['MERGED_MAPPINGS']}
tmp = MARK + '.tmp'; json.dump(res, open(tmp, 'w'), indent=1); os.replace(tmp, MARK)
print(json.dumps(res, indent=1))
