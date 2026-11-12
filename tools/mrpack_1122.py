"""Unpacks a Modrinth .mrpack (already downloaded from the official Modrinth CDN and sha512-checked) into a 1.12.2 test
dir: index files from cdn.modrinth.com checked against the index's sha512, overrides/ (client side too) copied. Test data
only, never redistributed (mc/ is gitignored).
  python -I tools/mrpack_1122.py <pack.mrpack> <outdir>     -> <outdir>/mods, <outdir>/config, ..."""
import hashlib, json, os, sys, urllib.request, zipfile
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
UA = {'User-Agent': 'amber-proto-survey/0.1'}
pack, out = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(pack); idx = json.loads(z.read('modrinth.index.json'))
os.makedirs(out, exist_ok=True)
def safe(rel):
    parts = rel.replace(chr(92), '/').split('/')
    if rel.startswith('/') or '..' in parts or ':' in rel: sys.exit('unsafe path in pack: ' + rel)
    return os.path.join(out, *parts)
n = 0
for f in idx['files']:
    if f.get('env', {}).get('client') == 'unsupported': continue
    dest = safe(f['path'])
    if os.path.exists(dest) and hashlib.sha512(open(dest, 'rb').read()).hexdigest() == f['hashes']['sha512']: continue
    url = next(u for u in f['downloads'] if u.startswith('https://cdn.modrinth.com/'))
    data = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120).read()
    if hashlib.sha512(data).hexdigest() != f['hashes']['sha512']: sys.exit('sha512 mismatch ' + url)
    os.makedirs(os.path.dirname(dest), exist_ok=True); open(dest, 'wb').write(data); n += 1
for prefix in ('overrides/', 'client-overrides/'):
    for name in z.namelist():
        if name.startswith(prefix) and not name.endswith('/'):
            dest = safe(name[len(prefix):]); os.makedirs(os.path.dirname(dest), exist_ok=True); open(dest, 'wb').write(z.read(name))
print('downloaded', n, 'index files; mods now', len([x for x in os.listdir(os.path.join(out, 'mods')) if x.endswith('.jar')]), 'jars; deps', idx.get('dependencies'))
