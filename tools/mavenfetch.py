"""mavenfetch.py <outdir> <url> ...

Downloads files from the official Forge maven (https://maven.minecraftforge.net/ only), checks each against the
maven's own .sha1 file, and writes or extends <outdir>/manifest.json (test data only, never redistribute).
"""
import hashlib, json, os, sys, urllib.request

UA = {'User-Agent': 'amber-proto-survey/0.1'}


def fetch(u):
    return urllib.request.urlopen(urllib.request.Request(u, headers=UA)).read()


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    mp = os.path.join(out, 'manifest.json')
    man = json.load(open(mp, encoding='utf-8')) if os.path.exists(mp) else {"note": "test data only, never redistribute", "files": []}
    for url in sys.argv[2:]:
        assert url.startswith('https://maven.minecraftforge.net/'), url
        data = fetch(url)
        want = fetch(url + '.sha1').decode().split()[0].strip().lower()
        got = hashlib.sha1(data).hexdigest()
        assert got == want, 'sha1 mismatch ' + url
        name = url.rsplit('/', 1)[1]
        with open(os.path.join(out, name), 'wb') as w:
            w.write(data)
        man['files'] = [e for e in man['files'] if e['file'] != name] + [{
            "source": "official Forge maven", "file": name, "url": url, "size": len(data), "sha1": got, "sha512": hashlib.sha512(data).hexdigest()}]
        print('ok', name, len(data))
    with open(mp, 'w', encoding='utf-8') as w:
        json.dump(man, w, indent=1)


if __name__ == '__main__':
    main()
