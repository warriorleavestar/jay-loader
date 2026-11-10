"""M4 corpus: popular Forge 1.20.1 mods from Modrinth only (api.modrinth.com metadata, cdn.modrinth.com files).

Usage: python -I tools/fetch_forge_corpus.py [--plan]   (--plan: resolve versions and print, download nothing)
Writes mc/corpus-forge-1.20.1/<file>.jar and manifest.json (slug, project, version, file, url, sha512, sha1, size,
licence, why). Every file is checked against the API's sha512 before it is kept. Not for redistribution: the
directory is git-ignored. Dependencies the picked versions require are added (same rule) so the pack can load.
"""
import hashlib, json, os, sys, time, urllib.parse, urllib.request
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
import subprocess
R = subprocess.run([sys.executable, '-I', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'proto_root.py')], capture_output=True, text=True).stdout.strip()
OUT = f'{R}/mc/corpus-forge-1.20.1'
UA = 'amber-ul-proto/0.1 (local test corpus)'
API = 'https://api.modrinth.com/v2'

# slug -> why it is in the set
PICK = {
    # libraries
    'architectury-api': 'library', 'cloth-config': 'library (config screens)', 'geckolib': 'library (animation, mixins)',
    'curios': 'library (capabilities, AT)', 'kotlin-for-forge': 'language provider (IModLanguageProvider)', 'balm': 'library (multi-loader)',
    'puzzles-lib': 'library', 'resourceful-lib': 'library', 'yungs-api': 'library', 'citadel': 'library (coremod?)',
    'moonlight': 'library (heavy mixins)', 'collective': 'library', 'patchouli': 'library (book api)', 'jei': 'api + AT',
    # mixin-heavy / performance
    'create': 'mixin-heavy content, AT', 'embeddium': 'renderer rewrite (mixin-heavy)', 'ferrite-core': 'memory (mixins into state classes)',
    'modernfix': 'mixin-heavy (mixin plugin, config)', 'entityculling': 'mixins (client)', 'immediatelyfast': 'mixins (rendering)',
    'memoryleakfix': 'mixins', 'canary': 'lithium port (mixin plugin)', 'clumps': 'small mixins',
    # content
    'farmers-delight': 'content (registries, AT)', 'supplementaries': 'content (heavy mixins)', 'quark': 'content (zeta, ASM/coremod)',
    'jade': 'client/server addon', 'appleskin': 'client hud', 'waystones': 'content (balm)', 'iron-chests': 'content (menus, BE renderers)',
    'sophisticated-backpacks': 'content (capabilities)', 'mowzies-mobs': 'content (geckolib)', 'twilightforest': 'content (dimension, AT)',
    'naturalist': 'content (geckolib)', 'comforts': 'content',
}
LOADER, GAME = 'forge', '1.20.1'

def get(url):
    for i in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA}), timeout=60) as r: return r.read()
        except Exception as e:
            if i == 3: raise
            time.sleep(2 ** (i + 1))

def j(path): return json.loads(get(API + path))

def best_version(project):
    q = urllib.parse.urlencode({'loaders': json.dumps([LOADER]), 'game_versions': json.dumps([GAME])})
    vs = j(f'/project/{project}/version?{q}')
    rel = [v for v in vs if v['version_type'] == 'release'] or vs
    return rel[0] if rel else None   # newest first

def main():
    plan = '--plan' in sys.argv
    os.makedirs(OUT, exist_ok=True)
    man_path = f'{OUT}/manifest.json'
    manifest = json.load(open(man_path, encoding='utf-8')) if os.path.exists(man_path) else {}
    queue = [(s, w) for s, w in PICK.items()]; seen = set(); missing = []
    while queue:
        slug, why = queue.pop(0)
        if slug in seen: continue
        seen.add(slug)
        try: proj = j(f'/project/{slug}')
        except Exception as e: missing.append((slug, f'not on Modrinth ({e})')); continue
        v = best_version(proj['id'])
        if not v: missing.append((slug, 'no forge 1.20.1 version')); continue
        f = next((x for x in v['files'] if x['primary']), v['files'][0])
        url = f['url']
        if not url.startswith('https://cdn.modrinth.com/'): missing.append((slug, f'file not on cdn.modrinth.com: {url}')); continue
        entry = {'slug': slug, 'project_id': proj['id'], 'title': proj['title'], 'version': v['version_number'], 'version_id': v['id'],
                 'file': f['filename'], 'url': url, 'sha512': f['hashes']['sha512'], 'sha1': f['hashes']['sha1'], 'size': f['size'],
                 'license': (proj.get('license') or {}).get('id'), 'why': why}
        print(f"{slug:26} {v['version_number']:28} {f['size']/1e6:6.2f} MB  {entry['license']}  {f['filename']}", flush=True)
        for d in v.get('dependencies', []):
            if d['dependency_type'] == 'required' and d.get('project_id'):
                dp = j(f"/project/{d['project_id']}")
                if dp['slug'] not in seen: queue.append((dp['slug'], f'required by {slug}'))
        if plan: manifest[slug] = entry; continue
        dst = f"{OUT}/{f['filename']}"
        if not (os.path.exists(dst) and hashlib.sha512(open(dst, 'rb').read()).hexdigest() == f['hashes']['sha512']):
            data = get(url)
            h = hashlib.sha512(data).hexdigest()
            if h != f['hashes']['sha512'] or hashlib.sha1(data).hexdigest() != f['hashes']['sha1']:
                missing.append((slug, 'HASH MISMATCH, not kept')); continue
            open(dst, 'wb').write(data)
        manifest[slug] = entry
    if not plan: json.dump(manifest, open(man_path, 'w', encoding='utf-8'), indent=1, ensure_ascii=False)
    print(f'\n{len(manifest)} mods, {sum(e["size"] for e in manifest.values())/1e6:.1f} MB' + (' (plan only)' if plan else ''))
    for s, why in missing: print('MISSING', s, why)

main()
