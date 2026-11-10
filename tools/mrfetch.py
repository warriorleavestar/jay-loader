"""mrfetch.py <outdir> <slug>:<gameversion>:<loader> ...

Downloads the newest matching version's primary file of each project from the official Modrinth API/CDN, checks the
size and sha512 against the API, and writes or extends <outdir>/manifest.json (test data only, never redistribute).
"""
import hashlib, json, os, sys, time, urllib.parse, urllib.request

UA = {'User-Agent': 'amber-proto-survey/0.1'}


def get(u):
    return json.load(urllib.request.urlopen(urllib.request.Request(u, headers=UA)))


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    mp = os.path.join(out, 'manifest.json')
    man = json.load(open(mp, encoding='utf-8')) if os.path.exists(mp) else {
        "source": "https://api.modrinth.com/v2 (official Modrinth API/CDN)", "note": "test data only, never redistribute", "files": []}
    for spec in sys.argv[2:]:
        slug, gv, loader = spec.split(':')
        vs = get('https://api.modrinth.com/v2/project/%s/version?game_versions=%s&loaders=%s' % (
            slug, urllib.parse.quote(json.dumps([gv])), urllib.parse.quote(json.dumps([loader]))))
        v = vs[0]
        f = ([x for x in v['files'] if x['primary']] or v['files'])[0]
        assert f['url'].startswith('https://cdn.modrinth.com/'), f['url']
        data = urllib.request.urlopen(urllib.request.Request(f['url'], headers=UA)).read()
        h = hashlib.sha512(data).hexdigest()
        assert h == f['hashes']['sha512'] and len(data) == f['size'], 'hash/size mismatch ' + f['filename']
        with open(os.path.join(out, f['filename']), 'wb') as w:
            w.write(data)
        man['files'] = [e for e in man['files'] if e['file'] != f['filename']] + [{
            "project": slug, "version": v['version_number'], "versionId": v['id'], "loaders": v['loaders'], "file": f['filename'],
            "url": f['url'], "size": f['size'], "sha1": hashlib.sha1(data).hexdigest(), "sha512": h}]
        print('ok', f['filename'], f['size'])
        time.sleep(0.5)
    with open(mp, 'w', encoding='utf-8') as w:
        json.dump(man, w, indent=1)


if __name__ == '__main__':
    main()
