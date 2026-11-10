"""Asset index + objects for one version json (M4: 1.20.1 = index 5). sha1-checked; objects already present (this
dirs listed in AMBER_UL_ASSET_REUSE, ';'-separated, read-only) are reused.  usage: python -I tools/fetch_assets.py <version.json> <mc dir>"""
import hashlib, json, os, shutil, sys, urllib.request, concurrent.futures as cf
VJ, MC = sys.argv[1], sys.argv[2]
REUSE = [d for d in os.environ.get('AMBER_UL_ASSET_REUSE', '').split(';') if d]
def sha1(p):
    h = hashlib.sha1()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 16), b''): h.update(b)
    return h.hexdigest()
stats = {'have': 0, 'reused': 0, 'downloaded': 0}
def get(url, dest, sha, rel):
    if os.path.exists(dest) and sha1(dest) == sha: stats['have'] += 1; return
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    for r in REUSE:
        src = f'{r}/{rel}'
        if os.path.exists(src) and sha1(src) == sha: shutil.copyfile(src, dest); stats['reused'] += 1; return
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'amber-ul-prototype'})
            with urllib.request.urlopen(req, timeout=60) as resp, open(dest + '.part', 'wb') as f: shutil.copyfileobj(resp, f)
            if sha1(dest + '.part') != sha: raise IOError('sha1 mismatch ' + url)
            os.replace(dest + '.part', dest); stats['downloaded'] += 1; return
        except Exception as e:
            if attempt == 3: raise
            print('retry', url, e)
ai = json.load(open(VJ))['assetIndex']
get(ai['url'], f"{MC}/assets/indexes/{ai['id']}.json", ai['sha1'], f"indexes/{ai['id']}.json")
objs = json.load(open(f"{MC}/assets/indexes/{ai['id']}.json"))['objects']
jobs = {o['hash']: f"objects/{o['hash'][:2]}/{o['hash']}" for o in objs.values()}
with cf.ThreadPoolExecutor(8) as ex:
    for f in [ex.submit(get, f'https://resources.download.minecraft.net/{rel[8:]}', f'{MC}/assets/{rel}', h, rel) for h, rel in jobs.items()]: f.result()
print('asset index', ai['id'], len(jobs), 'objects', stats)
