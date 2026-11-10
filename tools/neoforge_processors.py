"""Replicate the NeoForge installer's CLIENT processors against a dummy target inside the prototype dir
(never against .minecraft). Inputs: mc/neoforge/inst/install_profile.json, the downloaded libraries, our client.jar.
DOWNLOAD_MOJMAPS is replaced by copying the Mojang client mappings we already have (same file, offline).
Output: mc/neoforge/libraries/... (the PATCHED / MC_EXTRA artifacts, as the installer would write them)."""
import hashlib, json, os, re, shutil, subprocess, sys, zipfile
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
# prototype root as the JVM must see it (junction when the real path isn't representable in the ANSI code page)
ROOT = subprocess.run([sys.executable, '-I', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'proto_root.py')],
                      capture_output=True, text=True, check=True).stdout.strip().replace('/', os.sep)
NF = os.path.join(ROOT, 'mc', 'neoforge')
JAVA = os.path.join(ROOT, 'tools', 'jre21', 'bin', 'java.exe')
LIB = os.path.join(NF, 'libraries')
prof = json.load(open(os.path.join(NF, 'inst', 'install_profile.json')))
SIDE = sys.argv[1] if len(sys.argv) > 1 else 'client'   # 'server': mc/server-dl/server-1.21.1.jar (user-approved download, sha1-checked)
assert SIDE in ('client', 'server')

def maven_path(coord):
    # group:artifact:version[:classifier][@ext]
    ext = 'jar'
    if '@' in coord: coord, ext = coord.split('@')
    p = coord.split(':'); g, a, v = p[0], p[1], p[2]; c = p[3] if len(p) > 3 else None
    return os.path.join(LIB, *g.split('.'), a, v, f"{a}-{v}{'-' + c if c else ''}.{ext}")

data = {k: v[SIDE] for k, v in prof['data'].items()}
def resolve(val):
    if val.startswith('[') and val.endswith(']'): return maven_path(val[1:-1])
    if val.startswith("'") and val.endswith("'"): return val[1:-1]
    if val.startswith('/'): return os.path.join(NF, 'inst', val[1:].replace('/', os.sep))
    return val
vars_ = {k: resolve(v) for k, v in data.items()}
vars_.update({'SIDE': SIDE, 'MINECRAFT_JAR': os.path.join(ROOT, 'mc', 'versions', '1.21.1', '1.21.1.jar') if SIDE == 'client' else os.path.join(ROOT, 'mc', 'server-dl', 'server-1.21.1.jar'),
              'ROOT': os.path.join(NF, 'work' if SIDE == 'client' else 'work-server'), 'INSTALLER': os.path.join(NF, 'neoforge-21.1.265-installer.jar'),
              'MINECRAFT_VERSION': '1.21.1', 'LIBRARY_DIR': LIB})
def arg(a):
    a = re.sub(r'\{(\w+)\}', lambda m: vars_.get(m.group(1), m.group(0)), a)
    if a.startswith('[') and a.endswith(']'): return maven_path(a[1:-1])
    return a

# cache key: NeoForge version + MC jar sha1 + processor tool versions (one run per key; later a bake-instance step)
mc_sha1 = hashlib.sha1(open(vars_['MINECRAFT_JAR'], 'rb').read()).hexdigest()
tools_ = sorted({pr['jar'] for pr in prof['processors'] if not pr.get('sides') or SIDE in pr['sides']})
KEY = hashlib.sha256(json.dumps([prof['version'], mc_sha1, tools_, SIDE]).encode()).hexdigest()[:16]
MARK = os.path.join(NF, 'platform-cache', f'{prof["version"]}-{SIDE}-{KEY}.json')
if os.path.exists(MARK):
    print('cached:', open(MARK).read()); sys.exit(0)
for pr in prof['processors']:
    if pr.get('sides') and SIDE not in pr['sides']: continue
    args = [arg(x) for x in pr['args']]
    if 'DOWNLOAD_MOJMAPS' in args:
        out = args[args.index('--output') + 1]; os.makedirs(os.path.dirname(out), exist_ok=True)
        src = os.path.join(ROOT, 'mc', 'mappings', 'client_mappings.txt') if SIDE == 'client' else os.path.join(ROOT, 'mc', 'server-dl', 'server_mappings-1.21.1.txt')
        shutil.copyfile(src, out)
        print('DOWNLOAD_MOJMAPS -> copied local', os.path.basename(src)); continue
    jar = maven_path(pr['jar'])
    with zipfile.ZipFile(jar) as z: main = re.search(r'Main-Class: *(\S+)', z.read('META-INF/MANIFEST.MF').decode()).group(1)
    cp = os.pathsep.join([jar] + [maven_path(c) for c in pr.get('classpath', [])])
    # skip if all outputs exist
    outs = [args[i + 1] for i, x in enumerate(args) if x in ('--output', '--slim', '--extra')]
    if outs and all(os.path.exists(o) for o in outs): print('skip (done)', pr['jar']); continue
    for o in outs: os.makedirs(os.path.dirname(o), exist_ok=True)
    print('RUN', pr['jar'], ' '.join(args[:4]), flush=True)
    r = subprocess.run([JAVA, '-cp', cp, main] + args, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if r.returncode != 0: print(r.stdout[-3000:], r.stderr[-3000:]); sys.exit(f'processor failed: {pr["jar"]}')
os.makedirs(os.path.dirname(MARK), exist_ok=True)
res = {'neoforge': prof['version'], 'side': SIDE, 'mcJarSha1': mc_sha1, 'tools': tools_, 'key': KEY,
       'patched': vars_['PATCHED'], 'srg': vars_['MC_SRG'], 'extra': vars_['MC_EXTRA'], 'mappings': vars_['MERGED_MAPPINGS']}
tmp = MARK + '.tmp'; json.dump(res, open(tmp, 'w'), indent=1); os.replace(tmp, MARK)
print(json.dumps(res, indent=1))
