"""Forge 1.12.2 recon run (docs/forge-1.12.2-plan.md, step 1): launches the game the way the official Forge profile does
(LaunchWrapper + FMLTweaker on Temurin 8), or through core-legacy's LegacyLaunch with --amber. Everything under mc/1.12.2
(game dir mc/1.12.2/game[-<tag>]), offline user, never .minecraft. Times the run until the title screen / world from the log.
  python -I tools/run_1122.py [--amber] [--mods <dir>] [--tag <name>] [--timeout 300] [--server-world]"""
import json, os, re, shutil, subprocess, sys, time, zipfile
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
B = os.path.join(os.environ.get('AMBER_UL_MC') or os.path.join(ROOT, 'mc'), '1.12.2'); LIB = os.path.join(B, 'libraries')
JAVA = os.path.join(B, 'jdk8', 'bin', 'java.exe')
args = sys.argv[1:]
def opt(k, d=None):
    return args[args.index(k) + 1] if k in args else d
AMBER = '--amber' in args or '--cache' in args or '--verify' in args; CACHE = '--cache' in args or '--verify' in args; TAG = opt('--tag', 'amber' if AMBER else 'official'); TIMEOUT = int(opt('--timeout', '300'))
GAME = os.path.join(B, 'game-' + TAG)

def rules_ok(lib):
    ok = True
    for r in lib.get('rules', []):
        if 'os' not in r or r['os'].get('name') == 'windows': ok = r['action'] == 'allow'
        elif r['action'] == 'allow': ok = False
    return ok

def classpath_and_natives():
    van = json.load(open(os.path.join(B, 'versions', '1.12.2', '1.12.2.json'), encoding='utf-8'))
    fg = json.load(open(os.path.join(B, 'forge', 'version.json'), encoding='utf-8'))
    cp, seen, natives = [], set(), []
    def add(path):
        key = re.sub(r'/[^/]+/[^/]+$', '', path)   # group/artifact: Forge's entry wins over vanilla's (as the launcher merges them)
        if key in seen: return
        seen.add(key); p = os.path.join(LIB, *path.split('/'))
        if not os.path.exists(p): sys.exit('missing library ' + path + ' (run tools/fetch_1122.py)')
        cp.append(p)
    for lib in fg['libraries']:
        a = lib['downloads']['artifact']; add(a['path'])
    for lib in van['libraries']:
        if not rules_ok(lib): continue
        dl = lib.get('downloads', {})
        if 'artifact' in dl: add(dl['artifact']['path'])
        nat = lib.get('natives', {}).get('windows')
        if nat: natives.append((os.path.join(LIB, *dl['classifiers'][nat.replace('${arch}', '64')]['path'].split('/')), lib.get('extract', {}).get('exclude', [])))
    cp.append(os.path.join(B, 'versions', '1.12.2', '1.12.2.jar'))
    return cp, natives, fg

def extract_natives(natives, dest):
    shutil.rmtree(dest, ignore_errors=True); os.makedirs(dest)
    for jar, excl in natives:
        with zipfile.ZipFile(jar) as z:
            for n in z.namelist():
                if n.endswith('/') or any(n.startswith(e) for e in excl) or '..' in n.split('/') or '/' in n: continue
                open(os.path.join(dest, n), 'wb').write(z.read(n))

def main():
    cp, natives, fg = classpath_and_natives()
    nat = os.path.join(GAME, 'natives'); os.makedirs(GAME, exist_ok=True); extract_natives(natives, nat)
    if opt('--pack'):
        # an unpacked mrpack (tools/mrpack_1122.py): its mods are used like --mods; config/, scripts/... are copied into the
        # game dir once (the game rewrites configs; copying again would undo that and change the cache key every run)
        pk = opt('--pack')
        for d in os.listdir(pk):
            if d != 'mods' and os.path.isdir(os.path.join(pk, d)) and not os.path.exists(os.path.join(GAME, d)): shutil.copytree(os.path.join(pk, d), os.path.join(GAME, d))
        if not opt('--mods'): args.extend(['--mods', os.path.join(pk, 'mods')])
    if '--world' in args: shutil.rmtree(os.path.join(GAME, 'saves', 'amber-worldtest'), ignore_errors=True)   # a fresh world every run
    md = os.path.join(GAME, 'mods')
    if opt('--mods') or '--world' in args or '--no-mods' in args:
        # the mods dir is rebuilt only when it would differ (copying changes mtimes, which is part of the cache key)
        want = {}
        if opt('--mods'): want.update({f: os.path.join(opt('--mods'), f) for f in os.listdir(opt('--mods')) if f.endswith('.jar')})
        if '--world' in args: want['amber-worldtest-1122.jar'] = os.path.join(ROOT, 'legacytest', 'amber-worldtest-1122.jar')
        have = {f for f in os.listdir(md) if f.endswith('.jar')} if os.path.isdir(md) else set()
        if have != set(want) or any(os.path.getsize(os.path.join(md, f)) != os.path.getsize(p) for f, p in want.items()):
            shutil.rmtree(md, ignore_errors=True); os.makedirs(md)
            for f, p in want.items(): shutil.copy2(p, os.path.join(md, f))
    main_class = fg['mainClass']
    if AMBER:
        cp.insert(0, os.path.join(ROOT, 'build', 'core-legacy.jar')); main_class = 'amber.ul.legacy.LegacyLaunch'
    margs = ['--username', 'AmberTest', '--version', '1.12.2-forge', '--gameDir', GAME, '--assetsDir', os.path.join(B, 'assets'),
             '--assetIndex', '1.12', '--uuid', '00000000000000000000000000000000', '--accessToken', '0', '--userType', 'legacy',
             '--width', '1280', '--height', '720']
    if not AMBER: margs += ['--tweakClass', 'net.minecraftforge.fml.common.launcher.FMLTweaker', '--versionType', 'Forge']
    extra = ['-Damber.ul.legacy.cache=' + os.path.join(GAME, '.amber-ul', 'legacy-cache'), '-Damber.ul.legacy.cacheDebug=true'] if CACHE else []
    if '--verify' in args: extra.append('-Damber.ul.legacy.cacheVerify=true')
    if '--cache-reset' in args: shutil.rmtree(os.path.join(GAME, '.amber-ul', 'legacy-cache'), ignore_errors=True)
    cmd = [JAVA, '-Xmx' + opt('--xmx', '3G')] + extra + [ '-XX:+UseG1GC', '-Djava.library.path=' + nat, '-Dfml.ignoreInvalidMinecraftCertificates=true',
           '-Dfml.ignorePatchDiscrepancies=true', '-Dlog4j2.formatMsgNoLookups=true', '-cp', os.pathsep.join(cp), main_class] + margs
    # 1.12's log4j config sends the game log to logs/latest.log, not to stdout: time the run by polling that file
    log = os.path.join(GAME, 'run.log'); latest = os.path.join(GAME, 'logs', 'latest.log'); marks = {}
    shutil.rmtree(os.path.join(GAME, 'logs'), ignore_errors=True)
    patterns = {'launchwrapper': r'Loading tweak class name', 'fml_loading': r'Forge Mod Loader version', 'sound': r'Sound engine started',
                'title_screen': r'Forge Mod Loader has successfully loaded', 'in_world': r'AMBER-WORLDTEST in world(?! 60)',
                'world_60s': r'AMBER-WORLDTEST in world 60 s', 'world_failed': r'AMBER-WORLDTEST failed', 'crash': r'Crash report saved|---- Minecraft Crash Report ----|the game will display an error screen and halt'}
    t0 = time.time()
    with open(log, 'w', encoding='utf-8', errors='replace') as lf:
        p = subprocess.Popen(cmd, cwd=GAME, stdout=lf, stderr=subprocess.STDOUT)
        st = os.path.join(GAME, '.amber-ul', 'legacy-cache', 'stats.txt')
        if os.path.exists(st): os.remove(st)
        try:
            while time.time() - t0 < TIMEOUT and p.poll() is None:
                time.sleep(0.25)
                text = open(latest, encoding='utf-8', errors='replace').read() if os.path.exists(latest) else ''
                for k, pat in patterns.items():
                    if k not in marks and re.search(pat, text): marks[k] = round(time.time() - t0, 2); print(f'{marks[k]:7.2f}s {k}', flush=True)
                if 'crash' in marks or 'world_failed' in marks: break
                if '--world' in args:
                    if 'world_60s' in marks: break
                elif 'title_screen' in marks and time.time() - t0 > marks['title_screen'] + 5: break
            if p.poll() is not None: marks['exit'] = p.returncode
        finally:
            p.kill(); p.wait()
    res = {'tag': TAG, 'amber': AMBER, 'marks': marks, 'log': log}
    st = os.path.join(GAME, '.amber-ul', 'legacy-cache', 'stats.txt')
    if CACHE and os.path.exists(st): res['cache'] = dict(l.strip().split('=', 1) for l in open(st, encoding='utf-8') if '=' in l)
    print(json.dumps(res))
    ok = 'title_screen' in marks and 'crash' not in marks and ('world_60s' in marks if '--world' in args else True)
    return 0 if ok else 1

if __name__ == '__main__': sys.exit(main())
