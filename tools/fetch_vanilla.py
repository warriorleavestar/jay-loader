"""M3: official vanilla client jar + its libraries (jars only, Windows rules) for one version, sha1-checked against
Mojang's version manifest. Into mc/versions/<v>/ and mc/libraries-<v>/ (kept apart from 1.21.1's mc/libraries).
Also checks that the cached client mappings (mc/mappings-cache/<v>/mojang-client.txt) have the manifest's sha1.
  python -I tools/fetch_vanilla.py 1.20.1"""
import hashlib, json, os, shutil, sys, urllib.request
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
VER = sys.argv[1]
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MC = os.path.join(ROOT, 'mc')
def sha1(p):
    h = hashlib.sha1()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 16), b''): h.update(b)
    return h.hexdigest()
n = 0
def get(url, dest, sha):
    global n
    if os.path.exists(dest) and sha1(dest) == sha: return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'amber-ul-prototype'})
            with urllib.request.urlopen(req, timeout=60) as r, open(dest + '.part', 'wb') as f: shutil.copyfileobj(r, f)
            if sha1(dest + '.part') != sha: raise IOError('sha1 mismatch ' + url)
            os.replace(dest + '.part', dest); n += 1; return dest
        except Exception as e:
            if attempt == 3: raise
            print('retry', url, e)
def fetch_json(url):
    for attempt in range(6):
        try: return json.load(urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'amber-ul-prototype'}), timeout=60))
        except Exception as e:
            if attempt == 5: raise
            print('retry', url, e)
man = fetch_json('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')
v = next(x for x in man['versions'] if x['id'] == VER)
vj = get(v['url'], f'{MC}/versions/{VER}/{VER}.json', v['sha1'])
ver = json.load(open(vj))
d = ver['downloads']
get(d['client']['url'], f'{MC}/versions/{VER}/{VER}.jar', d['client']['sha1'])
cm = f'{MC}/mappings-cache/{VER}/mojang-client.txt'
print('cached client mappings sha1', 'OK' if os.path.exists(cm) and sha1(cm) == d['client_mappings']['sha1'] else 'MISMATCH/MISSING')
for lib in ver['libraries']:
    ok = True
    for r in lib.get('rules', []):
        if 'os' not in r or r['os'].get('name') == 'windows': ok = r['action'] == 'allow'
        elif r['action'] == 'allow': ok = False
    a = lib.get('downloads', {}).get('artifact')
    if ok and a: get(a['url'], f'{MC}/libraries-{VER}/{a["path"]}', a['sha1'])
print('downloaded', n, 'files; client sha1', d['client']['sha1'])
