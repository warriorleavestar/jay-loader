"""forge-1.12.2 through the bake interface the way a launcher (Jay) drives it, launching only from the returned `launch`
block on Java 8 (docs/amber-bake-interface.md, v1.3.2):
  python -I tools/bake_1122_test.py [--package] [--no-launch]
- bake-instance on mc/1.12.2 (small mod set + the world test mod): state, launch block (Java 8, cache under the instance,
  problems file), then a cold and a warm launch into a creative flat world for 60 s; the warm one must hit the cache.
  API review: aliases stop after loading (a config changed in the warm run -> new key next start), no stats.txt without
  cacheDebug, transformerCache:false -> stock launch, unusable cache dir / bridge without the cache -> legacy_cache_disabled
  and a stock launch, no fml.ignore* flags.
- refusals: missing required mod -> missing_dep (exit 2), but not when another jar ships it as ContainedDeps; installDir
  without version.json / with a missing library / an escaping library path / a short coordinate / a non-Forge-1.12.2
  profile -> bad_platform; a gameJar that is not the 1.12.2 client -> bad_request.
- gc: a live forge-1.12.2 state (no cacheFiles) is accepted, the old cacheRoot/legacy-1.12.2 layout is removed.
- LegacyLaunch on Java 21: legacy_java_unsupported as an @amber-ul/problem line and in the problems file.
Inputs from tools/fetch_1122.py (mc/1.12.2, git-ignored); everything written under mc/bake1122/.
--package: the loader and bridges from build/dist (tools/package.py), as Jay ships them.
"""
import json, os, re, shutil, subprocess, sys, time, zipfile
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
B = os.path.join(os.environ.get('AMBER_UL_MC') or os.path.join(R, 'mc'), '1.12.2'); LIB = os.path.join(B, 'libraries'); T = os.path.join(os.environ.get('AMBER_UL_MC') or os.path.join(R, 'mc'), 'bake1122')
JAVA21 = os.environ.get('JAVA') or (os.path.join(R, 'tools', 'jre21', 'bin', 'java.exe') if os.path.isfile(os.path.join(R, 'tools', 'jre21', 'bin', 'java.exe')) else shutil.which('java')); JAVA8 = os.path.join(B, 'jdk8', 'bin', 'java.exe')
fails = []
def check(what, ok, detail=''):
    print(('OK   ' if ok else 'FAIL ') + what + ('' if ok else f'  {detail}'), flush=True)
    if not ok: fails.append(what)

if '--package' in sys.argv:
    out = subprocess.run([sys.executable, '-I', os.path.join(R, 'tools', 'package.py')], capture_output=True, text=True)
    if out.returncode: sys.exit('package.py failed: ' + out.stderr[-800:])
    PKG = json.loads(out.stdout.strip().splitlines()[-1])['dir']
    MAN = json.load(open(os.path.join(PKG, 'manifest.json'), encoding='utf-8'))
    LCP = os.pathsep.join(os.path.join(PKG, c) for c in MAN['classpath'])
    check('package manifest: javaLaunch forge-1.12.2 8..8', MAN.get('javaLaunch') == {'forge-1.12.2': {'min': 8, 'max': 8}}, MAN.get('javaLaunch'))
    with zipfile.ZipFile(os.path.join(PKG, 'bridges', 'fmlbridge-1.12.2.jar')) as z:
        majors = {int.from_bytes(z.read(n)[6:8], 'big') for n in z.namelist() if n.endswith('.class')}
        check('package: fmlbridge-1.12.2.jar is Java 8 bytecode with the transformer cache', majors == {52} and 'amber/ul/legacy/lw/CachingLaunchClassLoader.class' in z.namelist(), majors)
else:
    LCP = os.pathsep.join([os.path.join(R, 'build', 'loader')] + open(os.path.join(R, 'build', 'loader-cp.txt')).read().strip().split(';'))

def bake(stage, req, java=JAVA21):
    os.makedirs(T, exist_ok=True); rf = os.path.join(T, f'req-{stage}.json'); json.dump(req, open(rf, 'w', encoding='utf-8'))
    p = subprocess.run([java, '-cp', LCP, 'amber.ul.Bake', stage, '--request', rf], capture_output=True)
    return p.returncode, [json.loads(l) for l in p.stdout.decode('utf-8').splitlines() if l.startswith('{')], p.stderr.decode('utf-8', 'replace')

def rules_ok(lib):
    ok = True
    for r in lib.get('rules', []):
        if 'os' not in r or r['os'].get('name') == 'windows': ok = r['action'] == 'allow'
        elif r['action'] == 'allow': ok = False
    return ok

van = json.load(open(os.path.join(B, 'versions', '1.12.2', '1.12.2.json'), encoding='utf-8'))
vlibs, natives = [], []
for lib in van['libraries']:
    if not rules_ok(lib): continue
    dl = lib.get('downloads', {})
    if 'artifact' in dl: vlibs.append(os.path.join(LIB, *dl['artifact']['path'].split('/')))
    nat = lib.get('natives', {}).get('windows')
    if nat: natives.append((os.path.join(LIB, *dl['classifiers'][nat.replace('${arch}', '64')]['path'].split('/')), lib.get('extract', {}).get('exclude', [])))

# a launcher-style platform install: version.json + the libraries it lists (the universal jar included), nothing else
INST = os.path.join(T, 'forge-install')
fv = json.load(open(os.path.join(B, 'forge', 'version.json'), encoding='utf-8'))
for lib in fv['libraries']:
    rel = lib['downloads']['artifact']['path']; d = os.path.join(INST, 'libraries', *rel.split('/'))
    if not os.path.exists(d): os.makedirs(os.path.dirname(d), exist_ok=True); shutil.copy2(os.path.join(LIB, *rel.split('/')), d)
shutil.copy2(os.path.join(B, 'forge', 'version.json'), os.path.join(INST, 'version.json'))

GAME = os.path.join(T, 'game'); MODS = os.path.join(GAME, 'mods'); INSTD = os.path.join(T, 'instance'); CACHE = os.path.join(T, 'cache')
if not os.path.isdir(MODS):
    os.makedirs(MODS)
    for f in os.listdir(os.path.join(B, 'mods-small')):
        if f.endswith('.jar'): shutil.copy2(os.path.join(B, 'mods-small', f), MODS)
    shutil.copy2(os.path.join(R, 'legacytest', 'amber-worldtest-1122.jar'), MODS)
os.makedirs(os.path.join(CACHE, 'baked'), exist_ok=True)
mods = sorted(os.path.join(MODS, f) for f in os.listdir(MODS) if f.endswith('.jar'))
REQ = {'schema': 'amber-ul/bake-instance-request@1', 'mcVersion': '1.12.2', 'side': 'client', 'gameJar': os.path.join(B, 'versions', '1.12.2', '1.12.2.jar'),
       'libraries': vlibs, 'libRoot': LIB, 'mods': [{'path': m} for m in mods], 'javaMajor': 21, 'instanceDir': INSTD, 'gameDir': GAME,
       'cacheRoot': CACHE, 'platform': {'kind': 'forge-1.12.2', 'installDir': INST}}

code, out, err = bake('bake-instance', REQ)
res = next((x for x in out if x['type'] == 'result'), None)
check('bake-instance forge-1.12.2: exit 0, result', code == 0 and res is not None, f'{code} {out[-3:]} {err[-500:]}')
if res is None: sys.exit(1)
L = res['launch']; tc = L['cache']['transformerCache']
check('launch block: LegacyLaunch, Java 8..8, mods dir, problems file', L['mainClass'] == 'amber.ul.legacy.LegacyLaunch' and L['javaMin'] == 8 and L['javaMax'] == 8
      and L['modsDir'] == 'mods' and os.path.normcase(L['problemsFile']) == os.path.normcase(os.path.join(INSTD, '.amber-ul', 'runtime-problems.jsonl')), L)
check('transformer cache under <instance>/.amber-ul/legacy-1.12.2/<key>, not under cacheRoot',
      os.path.normcase(tc).startswith(os.path.normcase(os.path.join(INSTD, '.amber-ul', 'legacy-1.12.2')) + os.sep) and res['cache']['transformerCache'] == tc
      and any(a == '-Damber.ul.legacy.cache=' + tc for a in L['jvmArgs']), tc)
check('classpath: bridge first, Forge universal before the game jar, no vanilla duplicate of a Forge library',
      L['classpath'][0].endswith('fmlbridge-1.12.2.jar') and L['classpath'][-1].endswith('1.12.2.jar')
      and sum('launchwrapper' in c for c in L['classpath']) == 1 and sum('asm-all' in c or 'asm-debug-all' in c for c in L['classpath']) == 1, L['classpath'][:4])
check('resolved mods include the world test mod and JEI', {'amberworldtest', 'jei'} <= {m['id'] for m in res['resolvedMods']})

check('launch block: cache on by default, FML jar self-check kept (no fml.ignore* flags)', L['cache'].get('enabled') is True
      and not any(a.startswith('-Dfml.ignore') for a in L['jvmArgs']), L['jvmArgs'])
DEBUG = ['-Damber.ul.legacy.cacheDebug=true']   # stats.txt (and env-<key>.txt) next to the cache, for these checks only

def launch(label, timeout=900, extra=(), stop='world_60s', block=None, classpath=None, at_world=None, keep_world=False):
    L_ = block or L
    nat = os.path.join(T, 'natives'); shutil.rmtree(nat, ignore_errors=True); os.makedirs(nat)
    for jar, excl in natives:
        with zipfile.ZipFile(jar) as z:
            for n in z.namelist():
                if not (n.endswith('/') or any(n.startswith(e) for e in excl) or '/' in n): open(os.path.join(nat, n), 'wb').write(z.read(n))
    if not keep_world: shutil.rmtree(os.path.join(GAME, 'saves', 'amber-worldtest'), ignore_errors=True)
    shutil.rmtree(os.path.join(GAME, 'logs'), ignore_errors=True)
    game = ['--username', 'AmberTest', '--version', '1.12.2-forge', '--gameDir', GAME, '--assetsDir', os.path.join(B, 'assets'), '--assetIndex', '1.12',
            '--uuid', '00000000000000000000000000000001', '--accessToken', '0', '--userType', 'legacy', '--width', '960', '--height', '540']
    cmd = [JAVA8, '-Xmx3G', '-Djava.library.path=' + nat] + list(extra) + L_['jvmArgs'] + ['-cp', os.pathsep.join(classpath or L_['classpath']), L_['mainClass']] + L_['gameArgsPrefix'] + game
    latest = os.path.join(GAME, 'logs', 'latest.log'); t0 = time.time(); marks = {}
    with open(os.path.join(T, label + '.log'), 'w', encoding='utf-8') as lf:
        p = subprocess.Popen(cmd, cwd=GAME, stdout=lf, stderr=subprocess.STDOUT)
        try:
            while time.time() - t0 < timeout and p.poll() is None:
                time.sleep(0.5)
                text = open(latest, encoding='utf-8', errors='replace').read() if os.path.exists(latest) else ''
                for k, pat in {'title': r'Forge Mod Loader has successfully loaded', 'world': r'AMBER-WORLDTEST in world(?! 60)', 'world_60s': r'AMBER-WORLDTEST in world 60 s',
                               'failed': r'AMBER-WORLDTEST failed|Crash report saved|---- Minecraft Crash Report'}.items():
                    if k not in marks and re.search(pat, text): marks[k] = round(time.time() - t0, 1)
                if at_world and 'world' in marks: at_world(); at_world = None
                if stop in marks or 'failed' in marks: break
            time.sleep(3)   # one more stats.txt round (every 2 s)
        finally: p.kill(); p.wait()
    st = os.path.join(tc, 'stats.txt')
    stats = dict(l.strip().split('=', 1) for l in open(st, encoding='utf-8') if '=' in l) if os.path.exists(st) else {}
    out = open(os.path.join(T, label + '.log'), encoding='utf-8', errors='replace').read()
    used = re.search(r'\[amber-ul legacy\] transformer cache (.+): (\d+) classes', out)
    stats['_file'], stats['_loaded'] = (os.path.basename(used.group(1)), int(used.group(2))) if used else (None, None)
    stats['_log'] = out
    return marks, stats

if '--no-launch' not in sys.argv:
    shutil.rmtree(tc, ignore_errors=True)
    shutil.rmtree(os.path.join(CACHE, 'legacy-1.12.2'), ignore_errors=True)   # left by the gc check of an earlier run
    USER_CFG = os.path.join(GAME, 'config', 'amber-user-edit.cfg')
    if os.path.exists(USER_CFG): os.remove(USER_CFG)
    m, s = launch('cold', extra=DEBUG)
    check(f'cold launch from the launch block: title, world, 60 s {m}', 'world_60s' in m and 'failed' not in m, f'see {T}/cold.log, {GAME}/logs/latest.log')
    check(f'cold launch filled the cache in the instance (stored {s.get("stored")})', int(s.get('stored', 0)) > 1000, {k: v for k, v in s.items() if k != '_log'})
    check('alias recording stops when loading finishes', 'loading finished: no more cache aliases' in s['_log'])
    # API review #1: a config changed after loading (here: by the user while the game runs) is not an alias of this run
    def user_edit():
        os.makedirs(os.path.dirname(USER_CFG), exist_ok=True); open(USER_CFG, 'w').write('# changed by the user after loading\nB:feature=true\n')
    m, s = launch('warm', extra=DEBUG, at_world=user_edit)
    warm_file = s['_file']
    check(f'warm launch: title, world, 60 s {m}', 'world_60s' in m and 'failed' not in m, f'see {T}/warm.log')
    check(f'warm launch hit the cache (hits {s.get("hits")}, misses {s.get("misses")})', int(s.get('hits', 0)) > 1000 and int(s.get('hits', 0)) > 10 * int(s.get('misses', 1)), {k: v for k, v in s.items() if k != '_log'})
    stats_mtime = os.path.getmtime(os.path.join(tc, 'stats.txt'))
    m, s = launch('after-edit', stop='title', keep_world=True)   # no cacheDebug: no stats thread after loading
    check(f'config changed after loading -> new cache key on the next start ({warm_file} -> {s["_file"]}, {s["_loaded"]} cached classes)',
          'title' in m and s['_file'] and s['_file'] != warm_file and s['_loaded'] == 0, {k: v for k, v in s.items() if k != '_log'})
    check('without cacheDebug no stats.txt is written', os.path.getmtime(os.path.join(tc, 'stats.txt')) == stats_mtime)
    os.remove(USER_CFG)
    check('nothing written under cacheRoot by the game', not any(fs for _, _, fs in os.walk(CACHE)))

    # API review #3: the cache can be switched off in the bake request, and a cache that cannot be set up falls back to stock
    code, out, err = bake('bake-instance', dict(REQ, transformerCache=False))
    r2 = next((x for x in out if x['type'] == 'result'), None)
    L2 = r2['launch'] if r2 else None
    check('transformerCache:false -> launch.cache.enabled false, no cache property', code == 0 and L2 and L2['cache'].get('enabled') is False
          and not any(a.startswith('-Damber.ul.legacy.cache=') for a in L2['jvmArgs']), L2 and L2['cache'])
    if L2:
        m, s = launch('cache-off', stop='title', block=L2, keep_world=True)
        check(f'cache off: stock LaunchWrapper reaches the title screen {m}', 'title' in m and 'failed' not in m and s['_file'] is None, f'see {T}/cache-off.log')
    blocker = os.path.join(T, 'not-a-dir'); open(blocker, 'w').write('x')
    L3 = dict(L, jvmArgs=[('-Damber.ul.legacy.cache=' + os.path.join(blocker, 'cache')) if a.startswith('-Damber.ul.legacy.cache=') else a for a in L['jvmArgs']])
    m, s = launch('cache-broken', stop='title', block=L3, keep_world=True)
    probs3 = [json.loads(l) for l in open(L['problemsFile'], encoding='utf-8')] if os.path.exists(L['problemsFile']) else []
    check(f'cache dir unusable: legacy_cache_disabled warning, stock launch reaches the title screen {m}', 'title' in m and 'failed' not in m
          and '@amber-ul/problem {"type":"problem"' in s['_log'] and any(p.get('code') == 'legacy_cache_disabled' and p.get('severity') == 'warning' for p in probs3), probs3)
    nolw = os.path.join(T, 'bridge-without-cache.jar')
    with zipfile.ZipFile(L['classpath'][0]) as zi, zipfile.ZipFile(nolw, 'w') as zo:
        for n in zi.namelist():
            if '/lw/' not in n: zo.writestr(n, zi.read(n))
    m, s = launch('cache-missing', stop='title', classpath=[nolw] + L['classpath'][1:], keep_world=True)
    probs4 = [json.loads(l) for l in open(L['problemsFile'], encoding='utf-8')] if os.path.exists(L['problemsFile']) else []
    check(f'bridge without the cache part: legacy_cache_disabled warning, stock launch reaches the title screen {m}', 'title' in m and 'failed' not in m
          and any(p.get('code') == 'legacy_cache_disabled' for p in probs4), probs4)

    # v1.3.3 quick play, singleplayer: the world the warm run made is opened by core-legacy as soon as the main menu is up;
    # the world test mod is passive (it never opens a world), and nothing clicks anything
    code, out, err = bake('bake-instance', dict(REQ, quickPlay={'singleplayer': 'amber-worldtest'}))
    rq = next((x for x in out if x['type'] == 'result'), None); LQ = rq and rq['launch']
    check('quickPlay singleplayer (1.12.2): launch.quickPlay via amber-ul, -Damber.ul.legacy.quickPlay', code == 0 and LQ and LQ.get('quickPlay') == {'singleplayer': 'amber-worldtest', 'via': 'amber-ul'}
          and '-Damber.ul.legacy.quickPlay=amber-worldtest' in LQ['jvmArgs'] and LQ['gameArgsPrefix'] == [], f'{code} {LQ and LQ.get("quickPlay")} {out[-2:]}')
    if LQ:
        m, s = launch('quickplay', stop='world', block=LQ, extra=['-Damber.worldtest.open=false'], keep_world=True)
        log = open(os.path.join(GAME, 'logs', 'latest.log'), encoding='utf-8', errors='replace').read() if os.path.exists(os.path.join(GAME, 'logs', 'latest.log')) else ''
        check(f'quickPlay singleplayer (1.12.2): main menu up -> straight into the world, no menu interaction {m}', 'world' in m and 'failed' not in m
              and 'passive (not opening a world)' in log and 'quick play: main menu up' in s['_log'] and 'AMBER-WORLDTEST in world folder=amber-worldtest' in log,
              f'see {T}/quickplay.log')

# refusals
code, out, err = bake('bake-instance', dict(REQ, mods=[{'path': m} for m in mods if 'mixinbooter' not in os.path.basename(m).lower()]))   # UniversalTweaks requires mixinbooter
probs = [x for x in out if x['type'] == 'problem']
check('missing required mod -> missing_dep, exit 2', code == 2 and probs and all(p['code'] == 'missing_dep' for p in probs if p['severity'] == 'error'), f'{code} {probs[:2]} {err[-300:]}')
# API review #5: the same mod shipped only as another jar's ContainedDeps satisfies the dependency
mb = next(m for m in mods if 'mixinbooter' in os.path.basename(m).lower())
carrier = os.path.join(T, 'carrier-with-contained.jar')
with zipfile.ZipFile(carrier, 'w') as z:
    z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nContainedDeps: ' + os.path.basename(mb) + '\r\n\r\n')
    z.write(mb, 'META-INF/libraries/' + os.path.basename(mb))
code, out, err = bake('bake-instance', dict(REQ, mods=[{'path': m} for m in mods if m != mb] + [{'path': carrier}]))
check('dependency provided through ContainedDeps -> no missing_dep, exit 0', code == 0 and not any(x.get('code') == 'missing_dep' for x in out), f'{code} {[x for x in out if x["type"] == "problem"][:2]} {err[-300:]}')
bad = os.path.join(T, 'bad-install'); shutil.rmtree(bad, ignore_errors=True); os.makedirs(bad)
code, out, err = bake('bake-instance', dict(REQ, platform={'kind': 'forge-1.12.2', 'installDir': bad}))
check('installDir without version.json -> bad_platform', code != 0 and any(x.get('code') == 'bad_platform' for x in out), f'{code} {out[-2:]}')
shutil.copy2(os.path.join(INST, 'version.json'), bad)
code, out, err = bake('bake-instance', dict(REQ, platform={'kind': 'forge-1.12.2', 'installDir': bad}))
check('installDir with missing libraries -> bad_platform', code != 0 and any(x.get('code') == 'bad_platform' and 'libraries missing' in x.get('message', '') for x in out), f'{code} {out[-2:]}')
fv2 = dict(fv, libraries=fv['libraries'] + [{'name': 'example:serveronly:1.0', 'clientreq': False}]); json.dump(fv2, open(os.path.join(bad, 'version.json'), 'w'))
shutil.rmtree(bad); shutil.copytree(INST, bad); json.dump(fv2, open(os.path.join(bad, 'version.json'), 'w'))
code, out, err = bake('bake-instance', dict(REQ, platform={'kind': 'forge-1.12.2', 'installDir': bad}))
check('a clientreq:false library may be absent', code == 0, f'{code} {out[-2:]}')
# API review #4/#6: library paths stay under libraries/, the profile must be Forge 1.12.2 on LaunchWrapper, the game jar 1.12.2's
def bad_install(fvx):
    shutil.rmtree(bad, ignore_errors=True); shutil.copytree(INST, bad); json.dump(fvx, open(os.path.join(bad, 'version.json'), 'w'))
    return bake('bake-instance', dict(REQ, platform={'kind': 'forge-1.12.2', 'installDir': bad}))
code, out, err = bad_install(dict(fv, libraries=fv['libraries'] + [{'name': 'x:y:1', 'downloads': {'artifact': {'path': '../../../outside.jar'}}}]))
check('library path leaving libraries/ -> bad_platform', code != 0 and any(x.get('code') == 'bad_platform' and 'rejected' in x.get('message', '') for x in out), f'{code} {out[-2:]}')
code, out, err = bad_install(dict(fv, libraries=fv['libraries'] + [{'name': 'not-a-coordinate'}]))
check('library name with fewer than 3 parts -> bad_platform', code != 0 and any(x.get('code') == 'bad_platform' and 'group:artifact:version' in x.get('message', '') for x in out), f'{code} {out[-2:]}')
code, out, err = bad_install(dict(fv, mainClass='cpw.mods.modlauncher.Launcher'))
check('version.json that is not a LaunchWrapper profile -> bad_platform', code != 0 and any(x.get('code') == 'bad_platform' and 'LaunchWrapper' in x.get('message', '') for x in out), f'{code} {out[-2:]}')
code, out, err = bad_install(dict(fv, libraries=[l for l in fv['libraries'] if not l['name'].startswith('net.minecraftforge:forge:')]))
check('version.json without the Forge library -> bad_platform', code != 0 and any(x.get('code') == 'bad_platform' for x in out), f'{code} {out[-2:]}')
shutil.rmtree(bad, ignore_errors=True)
code, out, err = bake('bake-instance', dict(REQ, gameJar=os.path.join(R, 'legacytest', 'amber-worldtest-1122.jar')))
check('gameJar that is not the 1.12.2 client -> bad_request', code != 0 and any(x.get('code') == 'bad_request' and 'sha1' in x.get('message', '') for x in out), f'{code} {out[-2:]}')
# v1.3.3 quick play refusals and the multiplayer mapping (no server is started: only the launch block is checked)
code, out, err = bake('bake-instance', dict(REQ, quickPlay={'multiplayer': 'play.example.org:25570'}))
rq = next((x for x in out if x['type'] == 'result'), None)
check('quickPlay multiplayer (1.12.2): the game own --server/--port', code == 0 and rq and rq['launch']['gameArgsPrefix'] == ['--server', 'play.example.org', '--port', '25570']
      and rq['launch']['quickPlay'] == {'multiplayer': 'play.example.org:25570', 'via': 'game'} and not any('quickPlay' in a for a in rq['launch']['jvmArgs']), f'{code} {rq and rq["launch"]}')
for qp, what in [({'singleplayer': 'no-such-world'}, 'missing world'), ({'singleplayer': '../outside'}, 'path outside saves/'), ({'singleplayer': 'a', 'multiplayer': 'b:1'}, 'both modes'),
                 ({'multiplayer': 'host:0'}, 'port 0'), ({'multiplayer': '::1:25565'}, 'bare IPv6')]:
    code, out, err = bake('bake-instance', dict(REQ, quickPlay=qp))
    check(f'quickPlay refused: {what} -> bad_request', code == 1 and any(x.get('code') == 'bad_request' and 'quickPlay' in x.get('message', '') for x in out), f'{code} {out[-1:]}')
code, out, err = bake('bake-instance', REQ)   # back to the real install for the gc check

# gc: the legacy state has no cacheFiles; an old cacheRoot/legacy-1.12.2 dir is managed
old = os.path.join(CACHE, 'legacy-1.12.2', '0123456789abcdef'); os.makedirs(old, exist_ok=True); open(os.path.join(old, 'x.bin'), 'wb').write(b'x')
stf = L['stateFile']
code, out, err = bake('gc', {'schema': 'amber-ul/gc-request@1', 'cacheRoot': CACHE, 'liveStateFiles': [stf], 'apply': True, 'tmpMaxAgeHours': 0})
check('gc accepts a live forge-1.12.2 state without cacheFiles and removes the old legacy-1.12.2 layout', code == 0 and not os.path.exists(os.path.join(old, 'x.bin')), f'{code} {out[-2:]} {err[-300:]}')

# LegacyLaunch on a too-new Java: a problem line on stdout and in the problems file
pf = L['problemsFile']
p = subprocess.run([JAVA21] + [a for a in L['jvmArgs'] if a.startswith('-Damber.ul.')] + ['-cp', os.pathsep.join(L['classpath']), L['mainClass']], capture_output=True, timeout=120)
line = next((l for l in p.stdout.decode('utf-8', 'replace').splitlines() if l.startswith('@amber-ul/problem ')), '')
check('Java 21: @amber-ul/problem legacy_java_unsupported, exit 3', p.returncode == 3 and json.loads(line[len('@amber-ul/problem '):]).get('code') == 'legacy_java_unsupported' if line else False, f'{p.returncode} {line!r}')
pl = [json.loads(l) for l in open(pf, encoding='utf-8')] if os.path.exists(pf) else []
check('problems file: launch line, then the problem', len(pl) == 2 and pl[0]['type'] == 'launch' and pl[1]['code'] == 'legacy_java_unsupported', pl)

print(f'RESULT bake-1122 {"PASS" if not fails else "FAIL"}: {len(fails)} failed' + ('' if not fails else ' ' + str(fails)))
sys.exit(1 if fails else 0)
