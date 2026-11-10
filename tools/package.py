"""Packaged loader layout (bake interface v1.3): one folder + zip that a launcher ships or downloads, no dev checkout.
  python -I tools/package.py [--core-only]   (after build.sh; writes build/dist/amber-ul-<loaderVersion>/ and its .zip)
  --core-only: package without the bridge jars (a build without the local platform files, e.g. CI); the manifest's
  "bridges" then lists only the bridges present. Java: $JAVA, else tools/jre21, else java on PATH.

Layout:
  loader.jar        the loader (build/loader, deterministic zip)
  libs/*.jar        its libraries, exactly build/loader-cp.txt (Maven coordinates + url in manifest.libs)
  bridges/*.jar     fmlbridge (NeoForge), fabricbridge (Fabric API on NeoForge), fmlbridge-forge + fabricbridge-forge
                    (Forge 1.20.1), forgecompat (Forge 1.21.1 mods on NeoForge)
  manifest.json     versions, java, entry points, class path, sha256/sha1/size of every file
  LICENSE, THIRD_PARTY_NOTICES.md, licenses/   the project's licence, notices, and every bundled library's licence text
The manifest's version fields come from running `amber.ul.Bake version` from the packaged files themselves.
Licences: the package holds Amber's own code and the unmodified official library jars; it holds no Mojang, NeoForge or
Forge files. The bridge jars are Amber's code compiled against those platforms.
"""
import hashlib, json, os, shutil, subprocess, sys, zipfile

R = subprocess.run([sys.executable, '-I', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'proto_root.py')], capture_output=True, text=True, check=True).stdout.strip()
B = f'{R}/build'
JAVA = os.environ.get('JAVA') or (f'{R}/tools/jre21/bin/java.exe' if os.path.isfile(f'{R}/tools/jre21/bin/java.exe') else shutil.which('java'))
CORE_ONLY = '--core-only' in sys.argv
BRIDGES = ['fmlbridge.jar', 'fabricbridge.jar', 'fmlbridge-forge.jar', 'fabricbridge-forge.jar', 'forgecompat.jar']
EPOCH = (1980, 2, 1, 0, 0, 0)

def jar_dir(src, out):
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        mf = zipfile.ZipInfo('META-INF/MANIFEST.MF', EPOCH); mf.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(mf, 'Manifest-Version: 1.0\r\nCreated-By: amber-ul tools/package.py\r\n\r\n')
        for root, dirs, files in sorted(os.walk(src)):
            dirs.sort()
            for f in sorted(files):
                p = os.path.join(root, f); n = os.path.relpath(p, src).replace('\\', '/')
                if n == 'META-INF/MANIFEST.MF': continue
                zi = zipfile.ZipInfo(n, EPOCH); zi.compress_type = zipfile.ZIP_DEFLATED; zi.external_attr = 0o644 << 16
                z.writestr(zi, open(p, 'rb').read())

def digest(p):
    b = open(p, 'rb').read()
    return {'sha256': hashlib.sha256(b).hexdigest(), 'sha1': hashlib.sha1(b).hexdigest(), 'size': len(b)}

def main():
    bridges = [b for b in BRIDGES if os.path.isfile(f'{B}/{b}')]
    if not CORE_ONLY and len(bridges) < len(BRIDGES): sys.exit(f'package: build/{sorted(set(BRIDGES) - set(bridges))} missing (run build.sh with the local platform files, or pass --core-only)')
    libs = json.load(open(f'{B}/loader/amber-ul/loader-libs.json', encoding='utf-8'))['files']
    cp = [e for e in open(f'{B}/loader-cp.txt', encoding='utf-8').read().strip().split(';') if e]
    stage = f'{B}/dist/.stage'; shutil.rmtree(stage, ignore_errors=True); os.makedirs(f'{stage}/libs'); os.makedirs(f'{stage}/bridges')
    jar_dir(f'{B}/loader', f'{stage}/loader.jar')
    for e in cp: shutil.copyfile(e if os.path.isabs(e) else f'{R}/{e}', f'{stage}/libs/{os.path.basename(e)}')
    for b in bridges: shutil.copyfile(f'{B}/{b}', f'{stage}/bridges/{b}')
    if not bridges: os.rmdir(f'{stage}/bridges')
    # licences: the project's, the notices, the fetched library texts, and the MIT texts Mixin/MixinExtras carry in their jars
    shutil.copyfile(f'{R}/LICENSE', f'{stage}/LICENSE'); shutil.copyfile(f'{R}/THIRD_PARTY_NOTICES.md', f'{stage}/THIRD_PARTY_NOTICES.md')
    os.makedirs(f'{stage}/licenses')
    for f in sorted(os.listdir(f'{R}/licenses')):
        if f.endswith('.txt'): shutil.copyfile(f'{R}/licenses/{f}', f'{stage}/licenses/{f}')
    for e in cp:
        with zipfile.ZipFile(e if os.path.isabs(e) else f'{R}/{e}') as z:
            for n, out in (('LICENSE.txt', 'Mixin-MIT.txt'), ('LICENSE_MixinExtras', 'MixinExtras-MIT.txt')):
                if n in z.namelist(): open(f'{stage}/licenses/{out}', 'wb').write(z.read(n))
    for need in ('Mixin-MIT.txt', 'MixinExtras-MIT.txt'):
        if not os.path.isfile(f'{stage}/licenses/{need}'): sys.exit(f'package: licence text {need} not found in the library jars')
    classpath = ['loader.jar'] + [f'libs/{os.path.basename(e)}' for e in cp]
    # versions from the packaged loader itself (also proves the package runs on its own)
    out = subprocess.run([JAVA, '-cp', os.pathsep.join(f'{stage}/{c}' for c in classpath), 'amber.ul.Bake', 'version'], capture_output=True, check=True, cwd=stage)
    ver = [json.loads(l) for l in out.stdout.decode('utf-8').splitlines() if l.startswith('{')][-1]
    files = {}
    for root, _, fs in os.walk(stage):
        for f in fs:
            p = os.path.join(root, f); files[os.path.relpath(p, stage).replace('\\', '/')] = digest(p)
    by_name = {os.path.basename(l['path']): l for l in libs}
    man = {
        'schema': 'amber-ul/package@1',
        'loaderVersion': ver['loaderVersion'], 'pipelineVersion': ver['pipelineVersion'], 'protocol': ver['protocol'], 'interface': ver['interface'],
        'platforms': ver['platforms'], 'javaMin': ver['javaMin'],
        'entry': {'bake': 'amber.ul.Bake', 'launch': 'amber.ul.Launcher'},
        'classpath': classpath,
        'bridges': {b: f'bridges/{b}' for b in bridges},
        'libs': [{k: by_name[os.path.basename(c)][k] for k in ('group', 'artifact', 'version', 'url', 'sha1', 'sha256', 'size')} | {'path': c} for c in classpath[1:]],
        'files': dict(sorted(files.items())),
    }
    name = f"amber-ul-{ver['loaderVersion']}"
    open(f'{stage}/manifest.json', 'w', encoding='utf-8', newline='\n').write(json.dumps(man, indent=1) + '\n')
    dst = f'{B}/dist/{name}'; shutil.rmtree(dst, ignore_errors=True); os.replace(stage, dst)
    zp = f'{B}/dist/{name}.zip'
    with zipfile.ZipFile(zp + '.tmp', 'w', zipfile.ZIP_DEFLATED) as z:
        for n in ['manifest.json'] + sorted(files):
            zi = zipfile.ZipInfo(f'{name}/{n}', EPOCH); zi.compress_type = zipfile.ZIP_DEFLATED; zi.external_attr = 0o644 << 16
            z.writestr(zi, open(f'{dst}/{n}', 'rb').read())
    os.replace(zp + '.tmp', zp)
    print(json.dumps({'dir': os.path.abspath(dst), 'zip': os.path.abspath(zp), 'zipSha256': digest(zp)['sha256'], 'files': len(files) + 1}))

if __name__ == '__main__': main()
