"""M4: Forge 1.20.1 (recommended 47.4.28) from the official maven, into mc/forge/ (prototype dir only, never .minecraft).
Installer checked against maven's .sha1 (and .sha256 when published); every library from install_profile.json and
version.json checked against the sha1 those files list. Then the installer's json/data files are extracted to mc/forge/inst/.
usage: python -I tools/fetch_forge.py [version]"""
import hashlib, json, os, sys, urllib.request, zipfile
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VER = sys.argv[1] if len(sys.argv) > 1 else '1.20.1-47.4.28'
OUT = os.path.join(ROOT, 'mc', 'forge'); LIB = os.path.join(OUT, 'libraries'); INST = os.path.join(OUT, 'inst')
REPO = 'https://maven.minecraftforge.net/'
UA = {'User-Agent': 'amber-ul-prototype'}
def fetch(url, timeout=120):
    for i in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r: return r.read()
        except Exception:
            if i == 3: raise

def installer():
    name = f'forge-{VER}-installer.jar'; url = f'{REPO}net/minecraftforge/forge/{VER}/{name}'; dest = os.path.join(OUT, name)
    data = open(dest, 'rb').read() if os.path.exists(dest) else fetch(url)
    want1 = fetch(url + '.sha1').decode().split()[0].strip()
    try: want256 = fetch(url + '.sha256').decode().split()[0].strip()
    except Exception: want256 = None
    got1, got256 = hashlib.sha1(data).hexdigest(), hashlib.sha256(data).hexdigest()
    if got1 != want1 or (want256 and got256 != want256): sys.exit(f'checksum mismatch {name}: {got1} vs {want1}, {got256} vs {want256}')
    os.makedirs(OUT, exist_ok=True); open(dest, 'wb').write(data)
    print(name, len(data), 'bytes, sha1 ok' + (', sha256 ok' if want256 else ' (no .sha256 published)'), got256)
    return dest

def extract(inst):
    os.makedirs(INST, exist_ok=True)
    with zipfile.ZipFile(inst) as z:
        for n in z.namelist():
            if n.endswith('/'): continue
            if n in ('install_profile.json', 'version.json') or n.startswith('data/') or n.startswith('maven/'):
                p = os.path.join(INST, *n.split('/'))
                if '..' in n.split('/'): continue
                os.makedirs(os.path.dirname(p), exist_ok=True); open(p, 'wb').write(z.read(n))
    # the installer's bundled maven/ artifacts (forge universal/shim jars) go into the library tree as the installer does
    m = os.path.join(INST, 'maven')
    if os.path.isdir(m):
        for d, _, fs in os.walk(m):
            for f in fs:
                rel = os.path.relpath(os.path.join(d, f), m); dst = os.path.join(LIB, rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                if not os.path.exists(dst): open(dst, 'wb').write(open(os.path.join(d, f), 'rb').read())

def libraries():
    libs = json.load(open(os.path.join(INST, 'install_profile.json')))['libraries'] + json.load(open(os.path.join(INST, 'version.json')))['libraries']
    seen = set(); n = size = 0
    for l in libs:
        a = l.get('downloads', {}).get('artifact')
        if not a or a['path'] in seen: continue
        seen.add(a['path']); dst = os.path.join(LIB, *a['path'].split('/'))
        if os.path.exists(dst) and hashlib.sha1(open(dst, 'rb').read()).hexdigest() == a['sha1']: continue
        if not a.get('url'):
            if os.path.exists(dst): print('bundled (no url, sha1 differs!)', a['path']); sys.exit(1)
            print('bundled, not in installer?', a['path']); sys.exit(1)
        data = fetch(a['url'])
        if hashlib.sha1(data).hexdigest() != a['sha1']: sys.exit(f'sha1 mismatch {a["url"]}')
        os.makedirs(os.path.dirname(dst), exist_ok=True); open(dst, 'wb').write(data); n += 1; size += len(data)
        print('  ok', a['path'], len(data))
    print(f'libraries: {len(seen)} listed, {n} downloaded ({size / 1e6:.1f} MB), all sha1 checked')

inst = installer(); extract(inst); libraries()
