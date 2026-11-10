"""Download MC 1.21.1 client + libs + natives + assets + mappings + loader libs into <repo>/mc.
Reuses files already present in AMBER_UL_REUSE_DIR (a game dir, read-only) when sha1 matches."""
import hashlib, json, os, shutil, sys, urllib.request, concurrent.futures as cf
MC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'mc').replace(os.sep, '/'); AMBER = os.environ.get('AMBER_UL_REUSE_DIR', '/nonexistent'); VER = '1.21.1'
def sha1(p):
    h = hashlib.sha1()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 16), b''): h.update(b)
    return h.hexdigest()
reused = downloaded = 0
def get(url, dest, sha=None, reuse=None):
    global reused, downloaded
    if os.path.exists(dest) and (sha is None or sha1(dest) == sha): return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if reuse and os.path.exists(reuse) and (sha is None or sha1(reuse) == sha):
        shutil.copyfile(reuse, dest); reused += 1; return dest
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'amber-ul-prototype'})
            with urllib.request.urlopen(req, timeout=60) as r, open(dest + '.part', 'wb') as f: shutil.copyfileobj(r, f)
            if sha and sha1(dest + '.part') != sha: raise IOError('sha1 mismatch ' + url)
            os.replace(dest + '.part', dest); downloaded += 1; return dest
        except Exception as e:
            if attempt == 3: raise
            print('retry', url, e)
man = json.load(urllib.request.urlopen('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'))
v = next(x for x in man['versions'] if x['id'] == VER)
vj = get(v['url'], f'{MC}/versions/{VER}/{VER}.json', v['sha1'])
ver = json.load(open(vj))
d = ver['downloads']
get(d['client']['url'], f'{MC}/versions/{VER}/{VER}.jar', d['client']['sha1'])
get(d['client_mappings']['url'], f'{MC}/mappings/client_mappings.txt', d['client_mappings']['sha1'])
jobs = []
for lib in ver['libraries']:
    rules = lib.get('rules')
    if rules:
        ok = False
        for r in rules:
            if 'os' not in r or r['os'].get('name') == 'windows': ok = r['action'] == 'allow'
        if not ok: continue
    a = lib['downloads'].get('artifact')
    if a: jobs.append((a['url'], f"{MC}/libraries/{a['path']}", a['sha1'], f"{AMBER}/libraries/{a['path']}"))
ai = ver['assetIndex']
idx = get(ai['url'], f"{MC}/assets/indexes/{ai['id']}.json", ai['sha1'], f"{AMBER}/assets/indexes/{ai['id']}.json")
for o in json.load(open(idx))['objects'].values():
    h = o['hash']; p = f"{h[:2]}/{h}"
    jobs.append((f'https://resources.download.minecraft.net/{p}', f'{MC}/assets/objects/{p}', h, f'{AMBER}/assets/objects/{p}'))
FM = 'https://maven.fabricmc.net/'; MV = 'https://repo1.maven.org/maven2/'
extra = [
  (FM, 'net/fabricmc/intermediary/1.21.1/intermediary-1.21.1-v2.jar'),
  (FM, 'net/fabricmc/sponge-mixin/0.15.4+mixin.0.8.7/sponge-mixin-0.15.4+mixin.0.8.7.jar'),
  (FM, 'net/fabricmc/tiny-remapper/0.10.4/tiny-remapper-0.10.4.jar'),
  (FM, 'net/fabricmc/mapping-io/0.6.1/mapping-io-0.6.1.jar'),
  (FM, 'net/fabricmc/access-widener/2.1.0/access-widener-2.1.0.jar'),
  (MV, 'io/github/llamalad7/mixinextras-fabric/0.4.1/mixinextras-fabric-0.4.1.jar'),
  (MV, 'org/ow2/asm/asm/9.7.1/asm-9.7.1.jar'), (MV, 'org/ow2/asm/asm-tree/9.7.1/asm-tree-9.7.1.jar'),
  (MV, 'org/ow2/asm/asm-commons/9.7.1/asm-commons-9.7.1.jar'), (MV, 'org/ow2/asm/asm-analysis/9.7.1/asm-analysis-9.7.1.jar'),
  (MV, 'org/ow2/asm/asm-util/9.7.1/asm-util-9.7.1.jar'),
]
for base, p in extra: jobs.append((base + p, f'{MC}/loaderlibs/{p.split("/")[-1]}', None, None))
with cf.ThreadPoolExecutor(16) as ex:
    for f in cf.as_completed([ex.submit(get, *j) for j in jobs]): f.result()
print(f'done: {len(jobs)} files, reused from Amber {reused}, downloaded {downloaded}')
