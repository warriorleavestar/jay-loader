"""Download the loader's libraries listed in libs.lock.json into deps/ (official Maven repositories only, sha256 checked).
  python -I tools/fetch_libs.py
Files already present with the right sha256 are kept.
"""
import hashlib, json, os, sys, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ALLOWED = ('https://repo1.maven.org/maven2/', 'https://maven.fabricmc.net/')


def main():
    lock = json.load(open(os.path.join(ROOT, 'libs.lock.json'), encoding='utf-8'))['files']
    for f in lock:
        url, dest = f['url'], os.path.join(ROOT, f['path'])
        if not url.startswith(ALLOWED): sys.exit(f'fetch_libs: {url} is not an allowed repository')
        if os.path.exists(dest) and hashlib.sha256(open(dest, 'rb').read()).hexdigest() == f['sha256']: continue
        b = urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'jay-loader-build/0.1'}), timeout=120).read()
        h = hashlib.sha256(b).hexdigest()
        if h != f['sha256']: sys.exit(f'fetch_libs: sha256 mismatch for {url}: {h}')
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        open(dest + '.part', 'wb').write(b); os.replace(dest + '.part', dest)
        print('fetched', os.path.basename(dest), len(b))
    print('libs ok', len(lock))


if __name__ == '__main__':
    main()
