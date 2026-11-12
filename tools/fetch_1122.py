"""Forge 1.12.2 recon downloads (docs/forge-1.12.2-plan.md, phase 1). Official sources only, every file hash-checked,
everything under mc/1.12.2/ (gitignored test data, never redistributed, never .minecraft):
  jdk8/                 Eclipse Temurin 8 JDK (Adoptium API; sha256 from the API) unzipped here. No PATH/JAVA_HOME/registry changes.
  versions/1.12.2/      vanilla client jar + json (Mojang piston-meta, sha1)
  libraries/            vanilla libraries incl. Windows natives (sha1), then Forge's legacy libraries (forge maven .sha1)
  forge/                Forge installer (maven .sha1) + its install_profile.json + universal jar
  mappings/             mcp_config 1.12.2 (SRG) + mcp_stable 39-1.12 (MCP names), forge maven .sha1
  usage: python -I tools/fetch_1122.py [jdk|vanilla|forge|mappings|all]"""
import hashlib, json, os, shutil, sys, urllib.request, zipfile
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(os.environ.get('AMBER_UL_MC') or os.path.join(ROOT, 'mc'), '1.12.2')
MC, FORGE = '1.12.2', '1.12.2-14.23.5.2860'
FMAVEN = 'https://maven.minecraftforge.net/'
UA = {'User-Agent': 'amber-ul-prototype'}
LOG = []

def fetch(url, timeout=180):
    for i in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r: return r.read()
        except Exception as e:
            if i == 3: raise
            print('retry', url, e, flush=True)

def digest(p, algo):
    h = hashlib.new(algo)
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''): h.update(b)
    return h.hexdigest()

def get(url, dest, algo, want):
    """Download url to dest unless it is already there with the expected hash; fails on mismatch."""
    want = want.lower()
    if os.path.exists(dest) and digest(dest, algo) == want: return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    data = fetch(url); got = hashlib.new(algo, data).hexdigest()
    if got != want: sys.exit(f'{algo} mismatch {url}: got {got}, want {want}')
    open(dest + '.part', 'wb').write(data); os.replace(dest + '.part', dest)
    LOG.append({'url': url, 'path': os.path.relpath(dest, OUT).replace('\\', '/'), 'size': len(data), algo: got, 'sha256': hashlib.sha256(data).hexdigest()})
    print('ok', os.path.relpath(dest, OUT), len(data), flush=True)
    return dest

def maven_get(url, dest):
    return get(url, dest, 'sha1', fetch(url + '.sha1').decode().split()[0].strip())

def jdk():
    api = 'https://api.adoptium.net/v3/assets/latest/8/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    a = json.loads(fetch(api))[0]; pkg = a['binary']['package']
    assert pkg['link'].startswith('https://github.com/adoptium/'), pkg['link']
    z = get(pkg['link'], os.path.join(OUT, 'dl', pkg['name']), 'sha256', pkg['checksum'])
    dest = os.path.join(OUT, 'jdk8')
    if not os.path.exists(os.path.join(dest, 'bin', 'java.exe')):
        tmp = dest + '.tmp'; shutil.rmtree(tmp, ignore_errors=True)
        with zipfile.ZipFile(z) as zf:
            for n in zf.namelist():
                if '..' in n.split('/') or n.startswith('/'): sys.exit('bad zip entry ' + n)
            zf.extractall(tmp)
        top = os.path.join(tmp, os.listdir(tmp)[0]); shutil.rmtree(dest, ignore_errors=True); os.replace(top, dest); shutil.rmtree(tmp)
    print('jdk8', a['release_name'], pkg['checksum'], flush=True)

def rules_ok(lib):
    ok = True
    for r in lib.get('rules', []):
        if 'os' not in r or r['os'].get('name') == 'windows': ok = r['action'] == 'allow'
        elif r['action'] == 'allow': ok = False
    return ok

def vanilla():
    man = json.loads(fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'))
    v = next(x for x in man['versions'] if x['id'] == MC)
    vj = get(v['url'], os.path.join(OUT, 'versions', MC, MC + '.json'), 'sha1', v['sha1'])
    ver = json.load(open(vj, encoding='utf-8')); d = ver['downloads']
    get(d['client']['url'], os.path.join(OUT, 'versions', MC, MC + '.jar'), 'sha1', d['client']['sha1'])
    get(d['server']['url'], os.path.join(OUT, 'versions', MC, MC + '-server.jar'), 'sha1', d['server']['sha1'])
    ai = ver['assetIndex']
    get(ai['url'], os.path.join(OUT, 'assets', 'indexes', ai['id'] + '.json'), 'sha1', ai['sha1'])
    for lib in ver['libraries']:
        if not rules_ok(lib): continue
        dl = lib.get('downloads', {})
        if 'artifact' in dl: a = dl['artifact']; get(a['url'], os.path.join(OUT, 'libraries', *a['path'].split('/')), 'sha1', a['sha1'])
        nat = lib.get('natives', {}).get('windows')
        if nat:
            c = dl['classifiers'][nat.replace('${arch}', '64')]
            get(c['url'], os.path.join(OUT, 'libraries', *c['path'].split('/')), 'sha1', c['sha1'])

def assets():
    ai = json.load(open(os.path.join(OUT, 'versions', MC, MC + '.json'), encoding='utf-8'))['assetIndex']
    idx = json.load(open(os.path.join(OUT, 'assets', 'indexes', ai['id'] + '.json'), encoding='utf-8'))
    for o in idx['objects'].values():
        h = o['hash']; get(f'https://resources.download.minecraft.net/{h[:2]}/{h}', os.path.join(OUT, 'assets', 'objects', h[:2], h), 'sha1', h)

def maven_path(coord):
    g, a, v = coord.split(':')[:3]; cls = coord.split(':')[3] if coord.count(':') > 2 else None
    return f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}{'-' + cls if cls else ''}.jar"

def forge():
    name = f'forge-{FORGE}-installer.jar'
    inst = maven_get(f'{FMAVEN}net/minecraftforge/forge/{FORGE}/{name}', os.path.join(OUT, 'forge', name))
    # 14.23.5.2860 ships the 1.13-style installer (spec 0, no processors): install_profile.json + version.json, the
    # universal jar bundled under maven/, every other library listed in version.json with url + sha1
    with zipfile.ZipFile(inst) as z:
        for n in ('install_profile.json', 'version.json'): open(os.path.join(OUT, 'forge', n), 'wb').write(z.read(n))
        ver = json.loads(z.read('version.json'))
        for n in z.namelist():
            if n.startswith('maven/') and n.endswith('.jar') and '..' not in n.split('/'):
                data = z.read(n); dest = os.path.join(OUT, 'libraries', *n.split('/')[1:])
                os.makedirs(os.path.dirname(dest), exist_ok=True); open(dest, 'wb').write(data)
                print('bundled', n, len(data), hashlib.sha256(data).hexdigest(), flush=True)
    for lib in ver['libraries']:
        a = lib.get('downloads', {}).get('artifact')
        if not a or not a.get('url'): continue   # the universal jar (bundled above)
        assert a['url'].startswith(FMAVEN) or a['url'].startswith('https://libraries.minecraft.net/'), a['url']
        get(a['url'], os.path.join(OUT, 'libraries', *a['path'].split('/')), 'sha1', a['sha1'])

def mappings():
    for u in [f'{FMAVEN}de/oceanlabs/mcp/mcp_config/1.12.2/mcp_config-1.12.2.zip',
              f'{FMAVEN}de/oceanlabs/mcp/mcp_config/1.12.2-20200226.224830/mcp_config-1.12.2-20200226.224830.zip',   # the one Forge 2860's profile names
              f'{FMAVEN}de/oceanlabs/mcp/mcp_stable/39-1.12/mcp_stable-39-1.12.zip']:
        maven_get(u, os.path.join(OUT, 'mappings', u.rsplit('/', 1)[1]))

if __name__ == '__main__':
    what = sys.argv[1] if len(sys.argv) > 1 else 'all'
    steps = {'jdk': jdk, 'vanilla': vanilla, 'assets': assets, 'forge': forge, 'mappings': mappings}
    for k in (steps if what == 'all' else [what]): steps[k]()
    mp = os.path.join(OUT, 'downloads.json')
    old = json.load(open(mp, encoding='utf-8'))['files'] if os.path.exists(mp) else []
    paths = {e['path'] for e in LOG}
    json.dump({'note': 'test data only, never redistribute', 'files': [e for e in old if e['path'] not in paths] + LOG}, open(mp, 'w', encoding='utf-8'), indent=1)
