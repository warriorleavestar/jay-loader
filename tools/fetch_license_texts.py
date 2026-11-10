"""Fetch the licence texts of the bundled libraries from their licensors' / projects' official sources into licenses/.
  python -I tools/fetch_license_texts.py [--check]
Each text is pinned by sha256 in licenses/sources.json; --check only verifies the files already there.
MIT texts of Mixin and MixinExtras are taken from their own jars by tools/package.py.
"""
import hashlib, json, os, sys, urllib.request

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(HERE, 'licenses')
SRC = os.path.join(OUT, 'sources.json')
# file -> official url (sha256 pinned in sources.json after the first fetch)
TEXTS = {
    'Apache-2.0.txt': 'https://www.apache.org/licenses/LICENSE-2.0.txt',
    'LGPL-3.0.txt': 'https://www.gnu.org/licenses/lgpl-3.0.txt',
    'GPL-3.0.txt': 'https://www.gnu.org/licenses/gpl-3.0.txt',
    'CC0-1.0.txt': 'https://creativecommons.org/publicdomain/zero/1.0/legalcode.txt',
    'ASM-BSD-3-Clause.txt': 'https://gitlab.ow2.org/asm/asm/-/raw/ASM_9_7_1/LICENSE.txt',
    'ANTLR-BSD-3-Clause.txt': 'https://raw.githubusercontent.com/antlr/antlr4/4.11.1/LICENSE.txt',
    'XZ-for-Java-COPYING.txt': 'https://raw.githubusercontent.com/tukaani-project/xz-java/v1.9/COPYING',
}


def main():
    os.makedirs(OUT, exist_ok=True)
    pinned = json.load(open(SRC, encoding='utf-8')) if os.path.exists(SRC) else {}
    for name, url in TEXTS.items():
        p = os.path.join(OUT, name)
        if '--check' in sys.argv:
            b = open(p, 'rb').read()
        else:
            b = urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'jay-loader-licence-fetch/0.1'}), timeout=60).read()
        h = hashlib.sha256(b).hexdigest()
        if name in pinned and pinned[name]['sha256'] != h: sys.exit(f'{name}: sha256 {h} != pinned {pinned[name]["sha256"]}')
        pinned[name] = {'url': url, 'sha256': h, 'size': len(b)}
        if '--check' not in sys.argv: open(p, 'wb').write(b)
        print('ok', name, len(b))
    json.dump(dict(sorted(pinned.items())), open(SRC, 'w', encoding='utf-8', newline='\n'), indent=1)


if __name__ == '__main__':
    main()
