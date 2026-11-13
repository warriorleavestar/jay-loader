"""Bake interface v1.3 check (docs/amber-bake-interface.md): drives amber.ul.Bake the way Amber does and launches from
the returned `launch` block only.
  python -I tools/bake_cli_test.py [forge|vanilla|sandbox|all] [--package]
--package: runs tools/package.py and uses only the packaged folder (build/dist/amber-ul-<v>/: loader.jar, libs/, bridges/,
manifest.json; its sha256s are checked first) instead of the dev build.
Everything is written under mc/bakecli/ (test data, git-ignored); the Mojang/Forge inputs are read from mc/.
Forge: mapping tables copied into an Amber-style mappingsDir with no manifest (checked against the bundled table),
census run (bakes everything, loads every Mixin target). Vanilla: Fabric mod set to the title screen.
warm + gc (v1.1): warm on both states; gc dry runs and refusals on both caches; gc apply only on mc/bakecli/forge-cache
(the vanilla cache mc/ulcache-world is shared with other test states, so it only gets a dry run).
"""
import json, os, re, shutil, subprocess, sys, threading, time
BS = chr(92)
# the prototype root as the JVM sees it (tools/proto_root.py: ASCII path or its junction)
R = subprocess.run([sys.executable, '-I', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'proto_root.py')], capture_output=True, text=True, check=True).stdout.strip()
MC = (os.environ.get('AMBER_UL_MC') or f'{R}/mc').replace(chr(92), '/'); T = f'{MC}/bakecli'; JAVA = os.environ.get('JAVA') or (f'{R}/tools/jre21/bin/java.exe' if os.path.isfile(f'{R}/tools/jre21/bin/java.exe') else shutil.which('java'))
fails = []
PKG = None
# v1.3.1: what a full build (all five bridges) accepts, per (kind, mc)
FULL_ACCEPTS = {('vanilla', '1.21.1'): (True, ['fabric', 'quilt']), ('neoforge', '1.21.1'): (True, ['neoforge', 'fabric', 'quilt', 'forge']),
                ('forge', '1.20.1'): (True, ['forge', 'neoforge', 'fabric', 'quilt']),
                ('forge-1.12.2', '1.12.2'): (True, ['forge'])}   # v1.3.2 (docs/forge-1.12.2-plan.md)
def accepts_of(a): return {(p['platform'], p['mc']): (p['available'], p['accepts']) for p in (a or [])}
if '--package' in sys.argv:
    out = subprocess.run([sys.executable, '-I', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'package.py')], capture_output=True, text=True)
    if out.returncode: sys.exit('package.py failed: ' + out.stderr[-800:])
    PKG = json.loads(out.stdout.strip().splitlines()[-1])['dir'].replace('\\', '/')
    MAN = json.load(open(f'{PKG}/manifest.json', encoding='utf-8'))
    LCP = ';'.join(f'{PKG}/{c}' for c in MAN['classpath'])
    import hashlib
    bad = [n for n, d in MAN['files'].items() if hashlib.sha256(open(f'{PKG}/{n}', 'rb').read()).hexdigest() != d['sha256']]
    on_disk = sorted(os.path.relpath(os.path.join(d, f), PKG).replace('\\', '/') for d, _, fs in os.walk(PKG) for f in fs if f != 'manifest.json')
    print(('OK   ' if not bad and on_disk == sorted(MAN['files']) else 'FAIL ') + f'package {PKG}: manifest sha256 of {len(MAN["files"])} files, nothing unlisted', flush=True)
    if bad or on_disk != sorted(MAN['files']): fails.append('package manifest')
    ok = all(os.path.exists(f'{PKG}/bridges/{b}') for b in ('fmlbridge.jar', 'fabricbridge.jar', 'fmlbridge-forge.jar', 'fabricbridge-forge.jar', 'forgecompat.jar', 'fmlbridge-1.12.2.jar')) and MAN['javaMin'] == {'vanilla': 21, 'neoforge': 21, 'forge': 21, 'forge-1.12.2': 21} and MAN.get('javaLaunch') == {'forge-1.12.2': {'min': 8, 'max': 8}}
    print(('OK   ' if ok else 'FAIL ') + 'package: all bridge jars under bridges/, javaMin 21 per platform, javaLaunch', flush=True)
    if not ok: fails.append('package bridges/javaMin')
    ok = MAN.get('interfaceRevision') == '1.3.3' and accepts_of(MAN.get('accepts')) == FULL_ACCEPTS
    print(('OK   ' if ok else 'FAIL ') + 'package manifest (v1.3.1): accepts per platform from the packaged bridges ' + str(MAN.get('accepts')), flush=True)
    if not ok: fails.append('package layout')
else:
    LCP = ';'.join([f'{R}/build/loader'] + open(f'{R}/build/loader-cp.txt').read().strip().split(';'))
CP_NAMES = sorted(os.path.basename(e) for e in LCP.split(';')[1:])

def bake(stage, req):
    os.makedirs(T, exist_ok=True); rf = f'{T}/req-{stage}.json'
    json.dump(req, open(rf, 'w', encoding='utf-8'))
    p = subprocess.run([JAVA, '-cp', LCP, 'amber.ul.Bake', stage, '--request', rf], capture_output=True)
    lines = [json.loads(l) for l in p.stdout.decode('utf-8').splitlines() if l.startswith('{')]
    return p.returncode, lines, p.stderr.decode('utf-8', 'replace')

def check(what, ok, detail=''):
    print(('OK   ' if ok else 'FAIL ') + what + ('' if ok else f'  {detail}'), flush=True)
    if not ok: fails.append(what)

def libs(mcv, libdir):
    ver = json.load(open(f'{MC}/versions/{mcv}/{mcv}.json')); out = []
    for lib in ver['libraries']:
        ok = True
        for r in lib.get('rules', []):
            if 'os' not in r or r['os'].get('name') == 'windows': ok = r['action'] == 'allow'
            elif r['action'] == 'allow': ok = False
        if ok and 'artifact' in lib['downloads']: out.append(f"{libdir}/{lib['downloads']['artifact']['path']}")
    return ver, out

def launch(label, res, ver, mcv, gamedir, marker, extra_largs, extra_jvm, timeout=600):
    L = res['launch']
    game = ['--username', 'AmberTest', '--version', mcv, '--gameDir', gamedir, '--assetsDir', f'{MC}/assets', '--assetIndex', ver['assetIndex']['id'],
            '--uuid', '00000000000000000000000000000001', '--accessToken', '0', '--userType', 'legacy', '--versionType', 'release']
    jvm = ['-Xmx3G', '-Dstdout.encoding=UTF-8', '-Dfile.encoding=UTF-8', f'-Dlog4j.configurationFile=file:///{MC}/log4j2-amber.xml',
           f'-Dorg.lwjgl.system.SharedLibraryExtractPath={MC}/natives', f'-Djna.tmpdir={MC}/natives'] + extra_jvm
    cmd = [JAVA] + L['jvmArgs'] + jvm + ['-cp', ';'.join(L['classpath']), L['mainClass']] + extra_largs + L['gameArgsPrefix'] + game
    os.makedirs(gamedir, exist_ok=True); log = open(f'{T}/{label}.log', 'w', encoding='utf-8')
    p = subprocess.Popen(cmd, cwd=gamedir, stdout=subprocess.PIPE, stderr=subprocess.STDOUT); hit = threading.Event(); seen = []
    def pump():
        for raw in p.stdout:
            line = raw.decode('utf-8', 'replace').rstrip(); log.write(line + '\n'); log.flush()
            if '映射图' in line: seen.append(line.strip())
            if '@amber-ul/problem ' in line: seen.append('PROBLEM ' + line.split('@amber-ul/problem ', 1)[1])
            if marker in line: seen.append(line.strip()); hit.set()
    threading.Thread(target=pump, daemon=True).start()
    ok = hit.wait(timeout)
    p.kill(); p.wait(); log.close()
    return ok, seen

def norm(p): return os.path.normcase(os.path.normpath(p))

def problems_file(label, res, seen):
    """v1.3: the launch wrote launch.problemsFile (first line type launch, every line JSON with schema + code); the
    stdout lines behind @amber-ul/problem are the same objects."""
    pf = res['launch'].get('problemsFile')
    try: lines = [json.loads(l) for l in open(pf, encoding='utf-8') if l.strip()]
    except Exception as e: lines = None; err = str(e)
    ok = bool(lines) and lines[0].get('type') == 'launch' and all(l.get('schema') == 'amber-ul/runtime-problem@1' for l in lines) and all(l.get('code') for l in lines[1:])
    printed = []
    for s_ in seen:
        if s_.startswith('PROBLEM '):
            try: printed.append(json.loads(s_[8:]))
            except ValueError: ok = False
    check(f'{label}: runtime problems file is JSON lines with stable codes ({len(lines or []) - 1} problem(s), {len(printed)} on stdout)',
          ok and all(p_ in lines for p_ in printed), f'{pf} {lines[:2] if lines else err if lines is None else lines}')

def rebake_reused(label, req):
    """v1.3: the same request again reuses everything and says so (no warm needed)."""
    code, out, err = bake('bake-instance', req)
    res = [x for x in out if x['type'] == 'result']
    c = res[0].get('cache') if res else None
    check(f'bake-instance {label} again: cache.state reused, warm false', code == 0 and c and c['state'] == 'reused' and c['warm'] is False and c['newCacheFiles'] == 0, f'{code} {c} {err[-300:]}')

def st_files(sf): return json.load(open(sf, encoding='utf-8')).get('cacheFiles', [])

def warm(label, sf):
    code, out, err = bake('warm', {'schema': 'amber-ul/warm-request@1', 'stateFile': sf})
    res = [x for x in out if x['type'] == 'result']
    cf = st_files(sf)
    check(f'warm {label}: all bakes hit, state lists cacheFiles that exist', code == 0 and res and res[0]['stats']['bakeMisses'] == 0 and cf and all(os.path.exists(f) for f in cf),
          f'{code} {res[0]["stats"] if res else out[-2:]} missing={[f for f in cf if not os.path.exists(f)][:3]} {err[-300:]}')
    return set(norm(f) for f in cf)

def gc(req):
    code, out, err = bake('gc', dict({'schema': 'amber-ul/gc-request@1'}, **req))
    res = [x for x in out if x['type'] == 'result']
    return code, (res[0] if res else None), out, err

def forge():
    mcv = '1.20.1'; ver, ls = libs(mcv, f'{MC}/libraries-{mcv}')
    md = f'{T}/amberdata/mappings/{mcv}'; os.makedirs(md, exist_ok=True)   # Amber's layout: requirements' paths, no manifest
    src = f'{MC}/mappings-cache/{mcv}'
    for a, b in [('mojang-client.txt', 'client_mappings.txt'), ('intermediary-1.20.1-v2.jar', None), ('mcp_config-1.20.1-20230612.114412.zip', None)]:
        if not os.path.exists(f'{md}/{b or a}'): shutil.copyfile(f'{src}/{a}', f'{md}/{b or a}')
    code, out, err = bake('requirements', {'schema': 'amber-ul/requirements-request@1', 'mcVersion': mcv, 'side': 'client', 'platform': 'forge',
                                           'versionJson': f'{MC}/versions/{mcv}/{mcv}.json'})
    res = [x for x in out if x['type'] == 'result']
    names = sorted(os.path.basename(f['path']) for f in res[0]['files'] if f['kind'] == 'mappings') if res else []
    check('requirements forge 1.20.1: the three official tables, client_mappings once', code == 0 and names == ['client_mappings.txt', 'intermediary-1.20.1-v2.jar', 'mcp_config-1.20.1-20230612.114412.zip'], f'{code} {names} {err[-300:]}')
    shutil.rmtree(f'{T}/forge-cache', ignore_errors=True); shutil.rmtree(f'{T}/amberdata/mapcache', ignore_errors=True)
    mods = sorted(f'{MC}/mods-forge-world/{f}' for f in os.listdir(f'{MC}/mods-forge-world') if f.endswith('.jar'))
    req = {'schema': 'amber-ul/bake-instance-request@1', 'mcVersion': mcv, 'side': 'client', 'gameJar': f'{MC}/versions/{mcv}/{mcv}.jar',
           'libraries': ls, 'libRoot': f'{MC}/libraries-{mcv}', 'mods': [{'path': m} for m in mods], 'javaMajor': 21,
           'instanceDir': f'{T}/forge-inst', 'gameDir': f'{MC}/game-forge', 'cacheRoot': f'{T}/forge-cache', 'mappingsDir': md,
           'mappingCache': f'{T}/amberdata/mapcache', 'platform': {'kind': 'forge', 'installDir': f'{MC}/forge'}}
    t = time.time(); code, out, err = bake('bake-instance', req)
    res = [x for x in out if x['type'] == 'result']
    check(f'bake-instance forge ({time.time() - t:.0f} s)', code == 0 and res, f'{code} {[x for x in out if x["type"] in ("error", "problem")][:3]} {err[-500:]}')
    if not res: return
    c = res[0].get('cache', {})
    check('bake-instance forge on a clean cache: cache.state new/rebuilt, warm true, javaMin 21, problemsFile next to the state',
          c.get('state') in ('new', 'rebuilt') and c.get('warm') is True and res[0]['launch'].get('javaMin') == 21
          and norm(os.path.dirname(res[0]['launch'].get('problemsFile', ''))) == norm(os.path.dirname(res[0]['launch']['stateFile'])), f'{c} {res[0]["launch"].get("javaMin")}')
    rebake_reused('forge', req)
    code, out, _ = bake('bake-instance', dict(req, javaMajor=17))
    check('bake-instance forge with javaMajor 17: java_too_old, exit 1', code == 1 and any(x.get('code') == 'java_too_old' for x in out), str(out[-1:]))
    st = json.load(open(res[0]['launch']['stateFile'], encoding='utf-8'))
    check('state: platform forge + mappingCache', st.get('platform', {}).get('kind') == 'forge' and st.get('mappingCache', '').endswith('mapcache'), str(st.get('platform')))
    check('launch: securejarhandler on the class path, mapping cache + add-opens in jvmArgs',
          any('securejarhandler' in c for c in res[0]['launch']['classpath']) and any(j.startswith('-Damber.ul.mappingCache=') for j in res[0]['launch']['jvmArgs']) and '--add-opens' in res[0]['launch']['jvmArgs'])
    graphs = [f for d, _, fs in os.walk(f'{T}/amberdata/mapcache') for f in fs if f.endswith('.amap')]
    check('mapping graph in the requested cache', len(graphs) >= 1, str(graphs))
    sf = res[0]['launch']['stateFile']; root = f'{T}/forge-cache'; mapc = f'{T}/amberdata/mapcache'
    v1 = f'{T}/forge-state-v1.json'; s1 = dict(st); s1.pop('libraries', None); s1.pop('cacheFiles', None); json.dump(s1, open(v1, 'w', encoding='utf-8'))
    code, out, _ = bake('warm', {'schema': 'amber-ul/warm-request@1', 'stateFile': v1})
    check('warm refuses a v1 state (no libraries): stale_state', code == 1 and any(x.get('code') == 'stale_state' for x in out), str(out[-1:]))
    live = warm('forge', sf)
    check('warm forge: the mapping graph is in cacheFiles', any(f.endswith('.amap') for f in live), str(sorted(live)[:5]))
    # refusals: nothing happens without a clear live set and a real cache root
    code, r, out, _ = gc({'cacheRoot': root, 'liveStateFiles': []})
    check('gc refuses an empty live set without allowNoLiveStates', code == 1 and r is None, str(out[-1:]))
    code, r, out, _ = gc({'cacheRoot': root, 'liveStateFiles': [f'{T}/no-such-state.json']})
    check('gc refuses a missing live state file', code == 1 and r is None, str(out[-1:]))
    code, r, out, _ = gc({'cacheRoot': root, 'liveStateFiles': [v1]})
    check('gc refuses a live state without cacheFiles', code == 1 and any(x.get('code') == 'stale_state' for x in out), str(out[-1:]))
    code, r, out, _ = gc({'cacheRoot': f'{T}/forge-inst', 'liveStateFiles': [sf]})
    check('gc refuses a directory that is not a loader cache root', code == 1 and r is None, str(out[-1:]))
    # planted entries: an unreferenced baked mod, a stale and a fresh temp file, a stray graph in the mapping cache
    junk = f'{root}/baked/mods/{"0" * 32}.jar'; stale = f'{root}/baked/mods/bake-1-2.pre.jar'; fresh = f'{root}/baked/mods/x.jar.tmp7'
    g = [os.path.join(d, f) for d, _, fs in os.walk(mapc) for f in fs if f.endswith('.amap')][0]; stray = os.path.join(os.path.dirname(g), 'f' * 64 + '.amap')
    outside = f'{T}/outside-keep.jar'
    os.makedirs(os.path.dirname(junk), exist_ok=True)
    for f in (junk, stale, fresh, stray, outside): open(f, 'wb').write(b'x')
    os.utime(stale, (time.time() - 3 * 3600, time.time() - 3 * 3600))
    code, r, out, err = gc({'cacheRoot': root, 'liveStateFiles': [sf], 'mappingCache': mapc})
    listed = {norm(x['path']): x['reason'] for x in r['remove']} if r else {}
    want = {norm(junk): 'unreferenced', norm(stale): 'stale temp', norm(stray): 'unreferenced'}
    check('gc dry run (default): lists exactly the planted junk, keeps the fresh temp, deletes nothing',
          code == 0 and r['apply'] is False and listed == want and all(os.path.exists(f) for f in (junk, stale, fresh, stray)), f'{code} {listed} {err[-300:]}')
    check('gc dry run: nothing a live state uses is listed', not (set(listed) & live), str(set(listed) & live))
    code, r, out, _ = gc({'cacheRoot': root, 'liveStateFiles': [], 'allowNoLiveStates': True, 'mappingCache': mapc})
    every = {norm(x['path']) for x in r['remove']} if r else set()
    check('gc with no live states (allowNoLiveStates, dry run): every live cache file would go, nothing outside the roots',
          code == 0 and {f for f in live if os.path.isfile(f)} <= every and all(any(e.startswith(d + os.sep) for e in every) for d in live if os.path.isdir(d)) and all(p.startswith(norm(root)) or p.startswith(norm(mapc)) for p in every), f'{code} {len(every)} missing={sorted(f for f in live - every if os.path.isfile(f))[:3]}')
    code, r, out, _ = gc({'cacheRoot': root, 'liveStateFiles': [sf], 'mappingCache': mapc, 'apply': True})
    check('gc apply: removes the planted junk only (outside file, fresh temp, live files stay)',
          code == 0 and r['removed'] == 3 and not any(os.path.exists(f) for f in (junk, stale, stray)) and os.path.exists(fresh) and os.path.exists(outside) and all(os.path.exists(f) for f in st_files(sf)),
          f'{code} {r and r.get("removed")} {r and r.get("failed")}')
    os.remove(fresh); os.remove(outside)
    ok, seen = launch('forge-launch', res[0], ver, mcv, f'{MC}/game-forge', '普查完成', ['--census', f'{T}/forge-census.json'],
                      ['-Damber.ul.mixinErrors=warn', '-Damber.ul.censusFresh=true'])
    check('launch after gc apply, from the state file (census, platform from the state)', ok and any('失败 0' in s for s in seen), str(seen[-3:]))
    problems_file('forge launch', res[0], seen)
    # bake caches all hit, so the launch normally needs no graph at all; it must never rebuild one
    check('launch builds no mapping graph (bake caches hit; graph from the mapping cache if needed)', not any('新建' in s for s in seen if '映射图' in s), str([s for s in seen if '映射图' in s]))
    if QP:
        def newest():
            d = f'{MC}/game-forge/saves'; ws = [w for w in os.listdir(d) if w.startswith('amber-qp-') and os.path.isfile(f'{d}/{w}/level.dat')] if os.path.isdir(d) else []
            return max(ws, key=lambda w: os.path.getmtime(f'{d}/{w}/level.dat')) if ws else None
        quickplay('forge', req, ver, mcv, f'{MC}/game-forge', ['-Damber.forgeworldtest=amber-qp'], newest)
    if MT:
        d = f'{MC}/game-forge/saves'; ws = [x for x in os.listdir(d) if x.startswith('amber-qp-') and os.path.isfile(f'{d}/{x}/level.dat')] if os.path.isdir(d) else []
        maintenance('forge', req, ver, mcv, f'{MC}/game-forge', max(ws, key=lambda x: os.path.getmtime(f'{d}/{x}/level.dat')) if ws else None)

def vanilla():
    mcv = '1.21.1'; ver, ls = libs(mcv, f'{MC}/libraries')
    code, out, err = bake('version', {})
    v = [x for x in out if x['type'] == 'result']
    check('version: interface 1.3, platforms, mappingCache, javaMin 21 per platform', code == 0 and v and v[0].get('interface') == '1.3' and v[0]['platforms'].get('forge') == ['1.20.1'] and 'mappingCache' in v[0]
          and v[0].get('javaMin') == {'vanilla': 21, 'neoforge': 21, 'forge': 21, 'forge-1.12.2': 21} and v[0].get('javaLaunch') == {'forge-1.12.2': {'min': 8, 'max': 8}}, str(v))
    check('version (v1.3.3): interfaceRevision 1.3.3, accepts per platform matches the bridges present (full build)',
          code == 0 and v and v[0].get('interfaceRevision') == '1.3.3' and accepts_of(v[0].get('accepts')) == FULL_ACCEPTS
          and all(p['missingBridges'] == [] for p in v[0]['accepts'] if not p.get('planned')), str(v and v[0].get('accepts')))
    libv = v[0].get('libs', {}) if v else {}
    want = {n.rsplit('-', 1)[0]: n.rsplit('-', 1)[1][:-4] for n in CP_NAMES if not n.startswith('intermediary')}
    check('version.libs: the versions on the real class path (asm, sponge-mixin, mixinextras ...)', all(libv.get(a) == ver_ for a, ver_ in want.items()) and libv.get('asm') == '9.10.1',
          f'{libv} want {want}')
    code, out, err = bake('requirements', {'schema': 'amber-ul/requirements-request@1', 'mcVersion': '1.21.1', 'side': 'client', 'versionJson': f'{MC}/versions/1.21.1/1.21.1.json'})
    rq = [x for x in out if x['type'] == 'result']
    ll = [f for f in rq[0]['files'] if f['kind'] == 'loader-lib'] if rq else []
    import hashlib
    local = {os.path.basename(e): e for e in LCP.split(';')[1:]}
    sha_ok = all(hashlib.sha1(open(local[os.path.basename(f['path'])], 'rb').read()).hexdigest() == f['sha1'] and os.path.getsize(local[os.path.basename(f['path'])]) == f['size'] for f in ll)
    check('requirements: loader-lib entries are exactly the class path jars, with matching sha1 + size; javaMin 21',
          code == 0 and sorted(os.path.basename(f['path']) for f in ll) == CP_NAMES and sha_ok and rq[0].get('javaMin') == 21 and all(f['url'].startswith(('https://repo1.maven.org/maven2/', 'https://maven.fabricmc.net/')) for f in ll),
          f'{code} {sorted(os.path.basename(f["path"]) for f in ll)} vs {CP_NAMES} sha={sha_ok}')
    code, out, err = bake('requirements', {'schema': 'amber-ul/requirements-request@1', 'mcVersion': '1.21.1', 'side': 'client', 'platform': 'forge'})
    check('requirements: forge on 1.21.1 refused (incompatible_loader)', code == 1 and any(x.get('code') == 'incompatible_loader' for x in out), str(out[:2]))
    mods = sorted(f'{MC}/mods-world/{f}' for f in os.listdir(f'{MC}/mods-world') if f.endswith('.jar'))
    req = {'schema': 'amber-ul/bake-instance-request@1', 'mcVersion': mcv, 'side': 'client', 'gameJar': f'{MC}/versions/{mcv}/{mcv}.jar',
           'libraries': ls, 'mods': [{'path': m} for m in mods], 'javaMajor': 21, 'instanceDir': f'{T}/van-inst', 'gameDir': f'{MC}/game',
           'cacheRoot': f'{MC}/ulcache-world', 'mappingsDir': f'{MC}/mappings'}   # a v1 request: no platform, no mappingCache
    code, out, err = bake('bake-instance', req)
    res = [x for x in out if x['type'] == 'result']
    check('bake-instance with a v1 request (vanilla base, Fabric mods)', code == 0 and res, f'{code} {err[-400:]}')
    if not res: return
    rebake_reused('vanilla', req)
    sf = res[0]['launch']['stateFile']; live = warm('vanilla', sf)
    code, r, out, err = gc({'cacheRoot': f'{MC}/ulcache-world', 'liveStateFiles': [sf]})   # shared cache: dry run only, never apply
    listed = {norm(x['path']) for x in r['remove']} if r else set()
    check('gc dry run on the shared vanilla cache: lists only files this state does not use, deletes nothing',
          code == 0 and r['apply'] is False and not (listed & live) and all(os.path.exists(p) for p in listed), f'{code} {err[-300:]}')
    ok, seen = launch('van-launch', res[0], ver, mcv, f'{MC}/game', 'TitleScreen.init', [], [])
    check('launch from the state file to the title screen', ok, str(seen[-3:]))
    problems_file('vanilla launch', res[0], seen)
    if QP: quickplay('vanilla', req, ver, mcv, f'{MC}/game', ['-Damber.autoworld=amber-qp'], lambda: 'amber-qp' if os.path.isfile(f'{MC}/game/saves/amber-qp/level.dat') else None)
    if MT: maintenance('vanilla', req, ver, mcv, f'{MC}/game', 'amber-qp' if os.path.isfile(f'{MC}/game/saves/amber-qp/level.dat') else None)

def quickplay(kind, req, ver, mcv, gamedir, create_jvm, find_world):
    """v1.3.3 quick play on 1.20.1 / 1.21.1: a world made by the world test mod in one run is entered in the next one with
    launch.quickPlay alone (the game's own --quickPlaySingleplayer): the title screen is never shown, nothing is clicked."""
    code, out, err = bake('bake-instance', req); res = [x for x in out if x['type'] == 'result']
    if not res: check(f'quickPlay {kind}: bake', False, f'{code} {err[-300:]}'); return
    ok, seen = launch(f'{kind}-qp-create', res[0], ver, mcv, gamedir, 'joined the game', [], create_jvm)
    world = find_world()
    check(f'quickPlay {kind}: test world created ({world})', ok and world and os.path.isfile(f'{gamedir}/saves/{world}/level.dat'), str(seen[-3:]))
    if not world: return
    code, out, err = bake('bake-instance', dict(req, quickPlay={'singleplayer': world})); res = [x for x in out if x['type'] == 'result']
    L = res[0]['launch'] if res else {}
    check(f'quickPlay {kind}: launch.quickPlay, --quickPlaySingleplayer after the state args', code == 0 and L.get('quickPlay') == {'singleplayer': world, 'via': 'game'}
          and L['gameArgsPrefix'][-2:] == ['--quickPlaySingleplayer', world] and L['gameArgsPrefix'][:3][0] == '--state', f'{code} {L.get("gameArgsPrefix")} {out[-1:]}')
    check(f'quickPlay {kind}: only quickPlay changed -> every bake reused (bakeMisses 0)', res and res[0].get('stats', {}).get('bakeMisses') == 0, str(res and res[0].get('stats')))
    if not res: return
    t = time.time(); ok, seen = launch(f'{kind}-qp', res[0], ver, mcv, gamedir, 'joined the game', [], [])
    log = open(f'{T}/{kind}-qp.log', encoding='utf-8', errors='replace').read()
    check(f'quickPlay {kind}: straight into world {world} ({time.time() - t:.0f} s), title screen never opened, the quick play hook ran', ok and 'TitleScreen.init' not in log
          and re.search(r'\[amber-ul\] @\d+ ms .*QuickPlay', log) is not None
          and not any(m in log for m in ('[amber-worldtest] creating world', '[amber-worldtest] opening existing', '[amber-fwt] title screen')), str(seen[-3:]))
    code, out, err = bake('bake-instance', dict(req, quickPlay={'multiplayer': '[::1]:25566'})); res = [x for x in out if x['type'] == 'result']
    check(f'quickPlay {kind}: multiplayer -> --quickPlayMultiplayer [::1]:25566 (block only, no server started)', code == 0 and res and res[0]['launch']['gameArgsPrefix'][-2:] == ['--quickPlayMultiplayer', '[::1]:25566'], f'{code} {out[-1:]}')
    code, out, err = bake('bake-instance', dict(req, quickPlay={'singleplayer': 'no-such-world'}))
    check(f'quickPlay {kind}: missing world -> bad_request before any bake', code == 1 and any(x.get('code') == 'bad_request' for x in out), f'{code} {out[-1:]}')

QP = any(a == 'quickplay' for a in sys.argv[1:]) or next((a for a in sys.argv[1:] if not a.startswith('--')), 'all') == 'all'
MT = any(a == 'maintenance' for a in sys.argv[1:]) or next((a for a in sys.argv[1:] if not a.startswith('--')), 'all') == 'all'
OLD_WORLD = f'{MC}/1.12.2/game-official-small/saves/amber-worldtest'   # saved by 1.12.2 (DataVersion 1343): "an old save"

def run_maint(label, res, ver, mcv, gamedir, cancel_when=None, timeout=1800, extra=(), cancel_after=None, stats=None):
    """Maintenance launch: the game must quit by itself. Returns (exit code or None on timeout, progress objects, log text).
    cancel_when(progress) -> True: create launch.maintenance.cancelFile (what a launcher's Cancel button does);
    cancel_after: create it that many seconds after the start (while the game is still loading). stats: a list that gets
    the @amber-ul/maintenance-stats objects. Progress lines must be pure ASCII (no -Dstdout.encoding here: a launcher
    does not pass one)."""
    L = res['launch']
    game = ['--username', 'AmberTest', '--version', mcv, '--gameDir', gamedir, '--assetsDir', f'{MC}/assets', '--assetIndex', ver['assetIndex']['id'],
            '--uuid', '00000000000000000000000000000001', '--accessToken', '0', '--userType', 'legacy', '--versionType', 'release']
    jvm = ['-Xmx3G', '-Dfile.encoding=UTF-8', f'-Dlog4j.configurationFile=file:///{MC}/log4j2-amber.xml',
           f'-Dorg.lwjgl.system.SharedLibraryExtractPath={MC}/natives', f'-Djna.tmpdir={MC}/natives']
    cmd = [JAVA] + L['jvmArgs'] + jvm + list(extra) + ['-cp', ';'.join(L['classpath']), L['mainClass']] + L['gameArgsPrefix'] + game
    log = open(f'{T}/{label}.log', 'w', encoding='utf-8'); prog = []; cancelled = []
    if cancel_after is not None:
        def later(): time.sleep(cancel_after); open(L['maintenance']['cancelFile'], 'w').close()
        threading.Thread(target=later, daemon=True).start()
    p = subprocess.Popen(cmd, cwd=gamedir, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    def pump():
        for raw in p.stdout:
            line = raw.decode('utf-8', 'replace').rstrip(); log.write(line + '\n'); log.flush()
            if stats is not None and '@amber-ul/maintenance-stats ' in line:
                try: stats.append(json.JSONDecoder().raw_decode(line.split('@amber-ul/maintenance-stats ', 1)[1])[0])
                except ValueError: pass
            if '@amber-ul/progress ' in line:
                try: o = json.JSONDecoder().raw_decode(line.split('@amber-ul/progress ', 1)[1])[0]
                except ValueError: continue
                if not line.split('@amber-ul/progress ', 1)[1].isascii(): o['_not_ascii'] = True
                prog.append(o)
                if cancel_when and not cancelled and cancel_when(o): cancelled.append(1); open(L['maintenance']['cancelFile'], 'w').close()   # once: the game deletes it
    th = threading.Thread(target=pump, daemon=True); th.start()
    try: code = p.wait(timeout)
    except subprocess.TimeoutExpired: p.kill(); p.wait(); code = None
    th.join(10); log.close()
    return code, prog, open(f'{T}/{label}.log', encoding='utf-8', errors='replace').read()

def level_dv(path):
    """Data.DataVersion of a level.dat (small NBT walk, enough for this check)."""
    import gzip, struct
    b = gzip.open(path).read()
    def s(i): n = struct.unpack('>H', b[i:i + 2])[0]; return b[i + 2:i + 2 + n].decode('utf-8', 'replace'), i + 2 + n
    def skip(t, i):
        if t == 1: return i + 1
        if t == 2: return i + 2
        if t in (3, 5): return i + 4
        if t in (4, 6): return i + 8
        if t == 7: return i + 4 + struct.unpack('>i', b[i:i + 4])[0]
        if t == 8: return s(i)[1]
        if t == 9:
            et = b[i]; n = struct.unpack('>i', b[i + 1:i + 5])[0]; i += 5
            for _ in range(n): i = skip(et, i)
            return i
        if t == 10:
            while b[i] != 0: t2 = b[i]; _, i = s(i + 1); i = skip(t2, i)
            return i + 1
        if t == 11: return i + 4 + 4 * struct.unpack('>i', b[i:i + 4])[0]
        if t == 12: return i + 4 + 8 * struct.unpack('>i', b[i:i + 4])[0]
        raise ValueError(t)
    def walk(i, want):
        while b[i] != 0:
            t = b[i]; name, i = s(i + 1)
            if want == 'Data' and t == 10 and name == 'Data': return walk(i, 'DataVersion')
            if want == 'DataVersion' and t == 3 and name == 'DataVersion': return struct.unpack('>i', b[i:i + 4])[0]
            i = skip(t, i)
        return -1
    return walk(s(1)[1], 'Data')

def maintenance(kind, req, ver, mcv, gamedir, fresh_world):
    """v1.3.3 maintenance mode on 1.20.1 / 1.21.1: the normal client opens the world in its integrated server, generates
    the spawn chunks (always) and the requested radius (resumable, cancellable), upgrades an old save with the game's
    own upgrader first, and quits by itself. No dedicated server, no eula.txt."""
    saves = f'{gamedir}/saves'; game_dv = {'1.21.1': 3955, '1.20.1': 3465}[mcv]
    eula_before = os.path.exists(f'{gamedir}/eula.txt')
    def prep(name, src):
        shutil.rmtree(f'{saves}/{name}', ignore_errors=True); shutil.copytree(src, f'{saves}/{name}', ignore=shutil.ignore_patterns('session.lock', 'amber-ul-pregen.json'))
    def steps(prog, step): return [p for p in prog if p.get('step') == step]
    def regions(name): d = f'{saves}/{name}/region'; return sum(os.path.getsize(f'{d}/{f}') for f in os.listdir(d)) if os.path.isdir(d) else 0
    def tail(prog, n=6): return [(p['step'], p['done'], p['total'], p.get('message')) for p in prog[-n:]]
    if not fresh_world: check(f'maintenance {kind}: needs the quick play world', False); return
    # 1. current-version world: spawn (always) + radius 12
    w = 'amber-mt'; prep(w, f'{saves}/{fresh_world}'); before = regions(w)
    code, out, err = bake('bake-instance', dict(req, maintenance={'world': w, 'pregenRadius': 12})); res = [x for x in out if x['type'] == 'result']
    L = res[0]['launch'] if res else {}; M = L.get('maintenance', {})
    check(f'maintenance {kind}: launch.maintenance (upgrade not needed, spawn 8, radius 12), jvm properties, no quick play args',
          code == 0 and M.get('world') == w and M.get('upgrade') == 'not needed' and M.get('spawnRadius') == 8 and M.get('pregenRadius') == 12 and M.get('gameDataVersion') == game_dv
          and f'-Damber.ul.maintenance={w}' in L['jvmArgs'] and '-Damber.ul.maintenance.radius=12' in L['jvmArgs'] and not any(a.startswith('--quickPlay') for a in L['gameArgsPrefix']), f'{code} {M} {out[-1:]}')
    if not res: return
    t = time.time(); code, prog, log = run_maint(f'{kind}-mt', res[0], ver, mcv, gamedir)
    pfp = f'{saves}/{w}/amber-ul-pregen.json'; pf = json.load(open(pfp)) if os.path.exists(pfp) else {}
    sp, pg = steps(prog, 'spawn'), steps(prog, 'pregen')
    check(f'maintenance {kind}: spawn 289 then pregen 625 chunks, done, game quit by itself ({time.time() - t:.0f} s, exit {code})',
          code == 0 and sp and sp[-1]['done'] == sp[-1]['total'] == 289 and pg and pg[-1]['done'] == pg[-1]['total'] == 625 and steps(prog, 'done') and steps(prog, 'quit')
          and not steps(prog, 'upgrade') and pf.get('complete') is True and pf.get('done') == 625 and regions(w) > before, f'{tail(prog)} {pf} see {T}/{kind}-mt.log')
    check(f'maintenance {kind}: progress lines are JSON with schema and stage', prog and all(p.get('schema') == 'amber-ul/maintenance-progress@1' and p.get('stage') == 'maintenance' and p.get('world') == w for p in prog), prog[:2])
    check(f'maintenance {kind}: integrated server only (no dedicated server, eula.txt untouched)', 'Starting integrated minecraft server' in log
          and L['mainClass'] != 'net.minecraft.server.Main' and os.path.exists(f'{gamedir}/eula.txt') == eula_before and not os.path.exists(f'{saves}/{w}/eula.txt'), f'see {T}/{kind}-mt.log')
    # 2. a bigger radius resumes at 625; cancel part-way; the next run continues
    code, out, err = bake('bake-instance', dict(req, maintenance={'world': w, 'pregenRadius': 24})); res = [x for x in out if x['type'] == 'result']
    if not res: check(f'maintenance {kind}: bake radius 24', False, f'{code} {out[-1:]}'); return
    code, prog, log = run_maint(f'{kind}-mt-cancel', res[0], ver, mcv, gamedir, cancel_when=lambda p: p.get('step') == 'pregen' and p['done'] >= 1000)
    pf = json.load(open(pfp)); rs = steps(prog, 'resume')
    check(f'maintenance {kind}: radius 24 resumes at 625; cancel file -> stops, keeps its place ({pf.get("done")}/{pf.get("total")}), quits (exit {code})',
          code == 0 and rs and rs[0]['done'] == 625 and steps(prog, 'cancelled') and not steps(prog, 'done') and pf.get('complete') is False and 1000 <= pf.get('done', 0) < 2401
          and not os.path.exists(res[0]['launch']['maintenance']['cancelFile']), f'{tail(prog)} {pf}')
    stopped = pf.get('done')
    code, prog, log = run_maint(f'{kind}-mt-resume', res[0], ver, mcv, gamedir)
    pf = json.load(open(pfp)); rs = steps(prog, 'resume')
    check(f'maintenance {kind}: next run continues at {stopped} and finishes 2401 (exit {code})', code == 0 and rs and rs[0]['done'] == stopped and steps(prog, 'done')
          and pf.get('complete') is True and pf.get('done') == 2401, f'{tail(prog)} {pf}')
    # 3. an old save (1.12.2): the game's own upgrader first
    if os.path.isfile(f'{OLD_WORLD}/level.dat'):
        w2 = 'amber-mt-old'; prep(w2, OLD_WORLD)
        code, out, err = bake('bake-instance', dict(req, maintenance={'world': w2})); res = [x for x in out if x['type'] == 'result']
        M = res[0]['launch']['maintenance'] if res else {}
        check(f'maintenance {kind}: 1.12.2 save -> upgrade needed (world data version 1343)', code == 0 and M.get('upgrade') == 'needed' and M.get('worldDataVersion') == 1343, f'{code} {M} {out[-1:]}')
        if res:
            t = time.time(); code, prog, log = run_maint(f'{kind}-mt-upgrade', res[0], ver, mcv, gamedir)
            up = steps(prog, 'upgrade'); dv = level_dv(f'{saves}/{w2}/level.dat')
            check(f'maintenance {kind}: the game own upgrader ran, then spawn, done, quit ({time.time() - t:.0f} s, exit {code}); level.dat data version now {dv}',
                  code == 0 and up and up[-1].get('message') == 'upgraded' and steps(prog, 'spawn') and steps(prog, 'done') and dv == game_dv, f'{tail(prog)} see {T}/{kind}-mt-upgrade.log')
    else: check(f'maintenance {kind}: old save for the upgrade test ({OLD_WORLD}; run tools/bake_1122_test.py once)', False)
    # 4. paused the whole time (as when the window loses focus): chunks are still unloaded, the loaded count stays bounded
    w3 = 'amber-mt-pause'; prep(w3, f'{saves}/{fresh_world}')
    code, out, err = bake('bake-instance', dict(req, maintenance={'world': w3, 'pregenRadius': 48})); res = [x for x in out if x['type'] == 'result']
    if res:
        st = []; t = time.time()
        code, prog, log = run_maint(f'{kind}-mt-pause', res[0], ver, mcv, gamedir, extra=['-Damber.ul.maintenance.debugPause=true', '-Damber.ul.maintenance.stats=true'], stats=st)
        late = [x for x in st if x['done'] > 2000]
        mx = max((x['loadedChunks'] for x in st), default=-1); heap = [x['heapMB'] for x in st]
        check(f'maintenance {kind}: paused the whole run (pause menu), radius 48 = 9409 chunks: done, loaded chunks stay <= 6000 (max {mx}), heap {min(heap, default=0)}-{max(heap, default=0)} MB ({time.time() - t:.0f} s, exit {code})',
              code == 0 and steps(prog, 'done') and late and all(x['paused'] is True for x in late) and 0 < mx <= 6000, f'{tail(prog)} {st[-3:]} see {T}/{kind}-mt-pause.log')
    else: check(f'maintenance {kind}: bake radius 48 (pause run)', False, f'{code} {out[-1:]}')
    # 5. a cancel made while the game is still loading is kept: the run stops before the world is opened
    code, out, err = bake('bake-instance', dict(req, maintenance={'world': w, 'pregenRadius': 30})); res = [x for x in out if x['type'] == 'result']
    if res:
        code, prog, log = run_maint(f'{kind}-mt-early-cancel', res[0], ver, mcv, gamedir, cancel_after=3)
        check(f'maintenance {kind}: cancel file made during loading -> cancelled before the world opens, quits (exit {code})', code == 0 and steps(prog, 'cancelled')
              and not steps(prog, 'open') and not steps(prog, 'spawn') and not os.path.exists(res[0]['launch']['maintenance']['cancelFile']), f'{tail(prog)}')
        # 5b. a cancel made while the world is opening (the open runs on the client thread and is not waited for): cancelled, quits
        code, prog, log = run_maint(f'{kind}-mt-open-cancel', res[0], ver, mcv, gamedir, cancel_when=lambda p: p.get('step') == 'open' and p['done'] == 0)
        cn = steps(prog, 'cancelled')
        check(f'maintenance {kind}: cancel file made while the world opens -> cancelled while opening, no chunks generated, quits (exit {code})', code == 0 and cn
              and 'opening' in (cn[-1].get('message') or '') and not steps(prog, 'spawn') and not steps(prog, 'failed') and not os.path.exists(res[0]['launch']['maintenance']['cancelFile']), f'{tail(prog)}')
    else: check(f'maintenance {kind}: bake radius 30 (cancel runs)', False, f'{code} {out[-1:]}')
    # 6. a world and an instance folder outside any code page: ASCII progress lines, the cancel file still works
    w4 = '维护-' + chr(0x1F642); prep(w4, f'{saves}/{fresh_world}'); inst4 = f'{T}/实例-{kind}'
    code, out, err = bake('bake-instance', dict(req, instanceDir=inst4, gameDir=gamedir, maintenance={'world': w4, 'pregenRadius': 24})); res = [x for x in out if x['type'] == 'result']
    check(f'maintenance {kind}: non-ASCII world and instance folder bake (cancel file percent-encoded in the JVM argument)', code == 0 and res
          and all(a.isascii() for a in res[0]['launch']['jvmArgs'] if a.startswith('-Damber.ul.maintenance')), f'{code} {out[-1:]}')
    if res:
        code, prog, log = run_maint(f'{kind}-mt-unicode', res[0], ver, mcv, gamedir, cancel_when=lambda p: p.get('step') == 'pregen' and p['done'] >= 400)
        check(f'maintenance {kind}: non-ASCII names: progress lines pure ASCII JSON with the right world name, cancel works (exit {code})', code == 0 and prog
              and all(p.get('world') == w4 and not p.get('_not_ascii') for p in prog) and steps(prog, 'cancelled') and not os.path.exists(res[0]['launch']['maintenance']['cancelFile']), f'{tail(prog)}')
    shutil.rmtree(f'{saves}/{w4}', ignore_errors=True); shutil.rmtree(f'{saves}/{w3}', ignore_errors=True)
    # 7. refusals (bake only)
    import gzip, struct
    newer = 'amber-mt-newer'; os.makedirs(f'{saves}/{newer}', exist_ok=True)
    with gzip.open(f'{saves}/{newer}/level.dat', 'wb') as f: f.write(b'\x0a\x00\x00' + b'\x0a\x00\x04Data' + b'\x03\x00\x0bDataVersion' + struct.pack('>i', 99999) + b'\x00\x00')
    for m, extra, what in [({'world': w}, {'quickPlay': {'singleplayer': w}}, 'with quickPlay'), ({'world': w, 'pregenRadius': -1}, {}, 'radius -1'),
                           ({'world': w, 'pregenRadius': 1.5}, {}, 'radius 1.5'), ({'world': w, 'pregenRadius': 100000}, {}, 'radius too big'),
                           ({'world': w, 'eula': True}, {}, 'unknown key'), ({'world': 'no-such-world'}, {}, 'missing world'), ({'world': '..'}, {}, 'path outside saves/'),
                           ({'world': newer}, {}, 'world from a newer game'), ({'world': w, 'upgrade': 'yes'}, {}, 'upgrade not a bool'),
                           ({'world': '.'}, {}, 'world .'), ({'world': 'a/b'}, {}, 'world a/b'), ({'world': 'a' + BS + 'b'}, {}, 'world a' + BS + 'b'), ({'world': ''}, {}, 'empty world'),
                           ({'world': 'C:' + BS + 'x'}, {}, 'absolute world path'), ({}, {}, 'no world'), (5, {}, 'not an object'), ({'world': w}, {'side': 'server'}, 'side server')]:
        code, out, err = bake('bake-instance', dict(req, maintenance=m, **extra))
        check(f'maintenance {kind} refused: {what} -> bad_request', code == 1 and any(x.get('code') == 'bad_request' and 'aintenance' in x.get('message', '') for x in out), f'{code} {out[-1:]}')
    shutil.rmtree(f'{saves}/{newer}', ignore_errors=True)

def tree(root):
    out = {}
    for d, _, fs in os.walk(root):
        for f in fs:
            st = os.stat(os.path.join(d, f)); out[norm(os.path.join(d, f))] = (st.st_size, st.st_mtime_ns)
    return out

def sandbox():
    """Sandbox: a launch from a state writes only inside its instance (Jay runs the game in a sandbox that can write only
    there). Shared cacheRoot + mappingCache are baked and warmed, then one baked mod is taken out of the shared cache so the
    launch has to rebake it. user.home and java.io.tmpdir point under a regular file (any write there fails); LWJGL/JNA
    extract into the instance. The launch must reach the title screen, leave the shared caches byte-for-byte as they were,
    put the rebaked jar in <instance>/.amber-ul/cache-overlay and the class cache in <instance>/.amber-ul/classcache."""
    mcv = '1.21.1'; ver, ls = libs(mcv, f'{MC}/libraries')
    base = f'{T}/sandbox'; shared = f'{base}/shared'; inst = f'{base}/inst'
    if os.path.isdir(base): shutil.rmtree(base)   # this test's own data only
    os.makedirs(inst)
    open(f'{inst}/options.txt', 'w').write('onboardAccessibility:false' + chr(10))   # a fresh game dir shows the accessibility screen first
    mods = sorted(f'{MC}/mods-world/{f}' for f in os.listdir(f'{MC}/mods-world') if f.endswith('.jar'))
    req = {'schema': 'amber-ul/bake-instance-request@1', 'mcVersion': mcv, 'side': 'client', 'gameJar': f'{MC}/versions/{mcv}/{mcv}.jar',
           'libraries': ls, 'mods': [{'path': m} for m in mods], 'javaMajor': 21, 'instanceDir': inst, 'gameDir': inst,
           'cacheRoot': f'{shared}/cache', 'mappingsDir': f'{MC}/mappings', 'mappingCache': f'{shared}/mapcache'}
    code, out, err = bake('bake-instance', req)
    res = [x for x in out if x['type'] == 'result']
    check('sandbox: bake-instance into a fresh shared cache', code == 0 and res, f'{code} {err[-400:]}')
    if not res: return
    sf = res[0]['launch']['stateFile']; warm('sandbox', sf)
    baked = sorted(f for f in st_files(sf) if norm(f).startswith(norm(f'{shared}/cache/baked/mods/')) and f.endswith('.jar'))
    check('sandbox: the state lists baked mod jars in the shared cache', bool(baked), str(st_files(sf)[:5]))
    if not baked: return
    os.remove(baked[0])   # cache files may go at any time; the launch has to rebake this one
    before = tree(shared)
    nowrite = f'{base}/not-a-dir'; open(nowrite, 'w').close()
    jvm = [f'-Duser.home={nowrite}/home', f'-Djava.io.tmpdir={nowrite}/tmp',
           f'-Dorg.lwjgl.system.SharedLibraryExtractPath={inst}/natives', f'-Djna.tmpdir={inst}/natives']
    ok, seen = launch('sandbox-launch', res[0], ver, mcv, inst, 'TitleScreen.init', [], jvm)
    check('sandbox: launch with HOME/TEMP unwritable reaches the title screen', ok, str(seen[-3:]))
    after = tree(shared)
    changed = sorted(set(before) ^ set(after)) + sorted(f for f in before if f in after and before[f] != after[f])
    check('sandbox: the launch wrote nothing into the shared cacheRoot / mappingCache', not changed, str(changed[:5]))
    ov = tree(f'{inst}/.amber-ul/cache-overlay/cache')
    check('sandbox: the missing baked mod was rebuilt in the instance overlay', any(f.endswith(norm('/baked/mods/' + os.path.basename(baked[0]))) for f in ov), str(list(ov)[:5]))
    check('sandbox: launch_rebaked reported', any('launch_rebaked' in s_ for s_ in seen), str(seen[-3:]))
    check('sandbox: class cache folder inside the instance (it is saved after the title screen; the test kills the game there)', os.path.isdir(f'{inst}/.amber-ul/classcache'), '')
    check('sandbox: nothing created in the unwritable user.home / tmpdir', os.path.isfile(nowrite) and os.path.getsize(nowrite) == 0, '')
    code, out, err = bake('warm', {'schema': 'amber-ul/warm-request@1', 'stateFile': sf})
    check('sandbox: warm afterwards rebuilds the jar in the shared cache', code == 0 and os.path.exists(baked[0]), f'{code} {err[-300:]}')

what = next((a for a in sys.argv[1:] if not a.startswith('--') and a not in ('quickplay', 'maintenance')), 'all')
if what in ('vanilla', 'all'): vanilla()
if what in ('forge', 'all'): forge()
if what in ('sandbox', 'all'): sandbox()
print(f'RESULT bakecli {"PASS" if not fails else "FAIL"}: {len(fails)} failed {fails}')
sys.exit(1 if fails else 0)
