"""Loader library list from the real build class path (bake interface v1.3).
  python -I tools/loader_libs.py <loader-cp.txt> <out.json>
Every jar on build/loader-cp.txt gets its Maven coordinates (official repositories only), sha1, sha256 and size, so
`requirements` and the packaged manifest can't drift from what the loader is compiled and run against. A jar with no
known coordinates fails the build.
"""
import hashlib, json, os, re, sys

CENTRAL = 'https://repo1.maven.org/maven2'
FABRIC = 'https://maven.fabricmc.net'
# artifact name -> (group, repository)
COORDS = {
    'asm': ('org.ow2.asm', CENTRAL), 'asm-analysis': ('org.ow2.asm', CENTRAL), 'asm-commons': ('org.ow2.asm', CENTRAL),
    'asm-tree': ('org.ow2.asm', CENTRAL), 'asm-util': ('org.ow2.asm', CENTRAL),
    'tomlj': ('org.tomlj', CENTRAL), 'antlr4-runtime': ('org.antlr', CENTRAL), 'xz': ('org.tukaani', CENTRAL),
    'gson': ('com.google.code.gson', CENTRAL), 'mixinextras-fabric': ('io.github.llamalad7', CENTRAL),
    'access-widener': ('net.fabricmc', FABRIC), 'mapping-io': ('net.fabricmc', FABRIC), 'sponge-mixin': ('net.fabricmc', FABRIC),
    'tiny-remapper': ('net.fabricmc', FABRIC), 'intermediary': ('net.fabricmc', FABRIC),
}

def main(cp_file, out):
    files = []
    for e in open(cp_file, encoding='utf-8').read().strip().split(';'):
        if not e: continue
        name = os.path.basename(e)
        m = re.fullmatch(r'(.+?)-(\d[^-]*(?:\+[^-]+)?(?:-v2)?)\.jar', name)
        if not m or m.group(1) not in COORDS: sys.exit(f'loader_libs: no Maven coordinates for {name}; add it to COORDS')
        art, ver = m.group(1), m.group(2)
        group, repo = COORDS[art]
        vdir = ver[:-3] if ver.endswith('-v2') else ver   # intermediary-<mc>-v2.jar lives in <mc>/ (classifier v2)
        b = open(e, 'rb').read()
        files.append({'kind': 'loader-lib', 'group': group, 'artifact': art, 'version': ver,
                      'url': f"{repo}/{group.replace('.', '/')}/{art}/{vdir}/{name}",
                      'sha1': hashlib.sha1(b).hexdigest(), 'sha256': hashlib.sha256(b).hexdigest(), 'size': len(b), 'path': f'libs/{name}'})
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump({'schema': 'amber-ul/loader-libs@1', 'files': files}, open(out, 'w', encoding='utf-8'), indent=1)

if __name__ == '__main__': main(sys.argv[1], sys.argv[2])
