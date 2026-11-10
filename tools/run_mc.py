"""Launch MC 1.21.1 (vanilla main or via amber.ul.Launcher), time markers in stdout, screenshot, close the game.

usage: python -I tools/run_mc.py <label> vanilla|amber [--mods DIR] [--cache DIR] [--classcache safe|full|off]
                                 [--wait-after SECONDS] [--screenshot] [--marker TEXT] [--timeout S]
Root = env AMBER_PROTO, else tools/proto_root.py (real path, or an English-named junction when the JVM can't represent it).
"""
import argparse, json, os, subprocess, sys, threading, time
P = argparse.ArgumentParser()
P.add_argument('label'); P.add_argument('mode', choices=['vanilla', 'amber', 'neoforge-official'])
P.add_argument('--mods', default=None); P.add_argument('--cache', default=None)
P.add_argument('--classcache', default='safe'); P.add_argument('--wait-after', type=float, default=4)
P.add_argument('--screenshot', action='store_true'); P.add_argument('--marker', default='Sound engine started')
P.add_argument('--timeout', type=float, default=300); P.add_argument('--state', default=None); P.add_argument('--neoforge', action='store_true'); P.add_argument('--census', default=None); P.add_argument('--jvm', action='append', default=[])
P.add_argument('--server', action='store_true', help='dedicated server (net.minecraft.server.Main --nogui); cwd = --gamedir; stopped with "stop" on stdin');
P.add_argument('--mc', default='1.21.1'); P.add_argument('--forge', action='store_true', help='M4: Forge 1.20.1 platform (mc/forge), implies --mc 1.20.1')
P.add_argument('--gamedir', default=None); P.add_argument('--username', default='AmberTest'); P.add_argument('--uuid', default='00000000000000000000000000000001')
import subprocess as _sp
R = (os.environ.get('AMBER_PROTO') or _sp.run([sys.executable, '-I', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'proto_root.py')], capture_output=True, text=True, check=True).stdout.strip()).replace(chr(92), '/').rstrip('/')
a = P.parse_args(); sys.stdout.reconfigure(encoding="utf-8", errors="replace")
MC = f'{R}/mc'; JAVA = f'{R}/tools/jre21/bin/java.exe'
a.mods = a.mods or f'{MC}/mods-none'; a.cache = a.cache or f'{MC}/ulcache'
# the game runs with cwd = its game dir: relative --mods/--cache would silently point at nothing (0 mods)
a.mods = os.path.abspath(a.mods).replace(chr(92), '/'); a.cache = os.path.abspath(a.cache).replace(chr(92), '/')
if a.forge: a.mc = '1.20.1'
MCV = a.mc; LIBDIR = f'{MC}/libraries' if MCV == '1.21.1' else f'{MC}/libraries-{MCV}'
ver = json.load(open(f'{MC}/versions/{MCV}/{MCV}.json'))
libs = []
for lib in ver['libraries']:
    ok = True
    for r in lib.get('rules', []):
        if 'os' not in r or r['os'].get('name') == 'windows': ok = r['action'] == 'allow'
        elif r['action'] == 'allow': ok = False
    if ok and 'artifact' in lib['downloads']: libs.append(f"{LIBDIR}/{lib['downloads']['artifact']['path']}")
game_dir = a.gamedir or f'{MC}/game'; os.makedirs(game_dir, exist_ok=True); os.makedirs(f'{MC}/natives', exist_ok=True)
game_args = ['--username', a.username, '--version', MCV, '--gameDir', game_dir, '--assetsDir', f'{MC}/assets',
             '--assetIndex', ver['assetIndex']['id'], '--uuid', a.uuid, '--accessToken', '0',
             '--userType', 'legacy', '--versionType', 'release']
jvm = ['-Xmx2G', '-Dstdout.encoding=UTF-8', '-Dfile.encoding=UTF-8', f'-Dlog4j.configurationFile=file:///{MC}/log4j2-amber.xml',
       f'-Dorg.lwjgl.system.SharedLibraryExtractPath={MC}/natives', f'-Dio.netty.native.workdir={MC}/natives',
       f'-Djna.tmpdir={MC}/natives', '-Dminecraft.launcher.brand=amber-ul-proto'] + a.jvm
if a.mode == 'neoforge-official':   # NeoForge's own launch (bootstraplauncher/modlauncher/FML) from the prototype's install, as a baseline
    NF = f'{MC}/neoforge'; nfv = json.load(open(f'{NF}/inst/version.json'))
    def sub(x): return x.replace('${library_directory}', f'{NF}/libraries').replace('${classpath_separator}', ';').replace('${version_name}', 'neoforge-21.1.265')
    njvm = [sub(x) for x in nfv['arguments']['jvm']]
    mp = njvm[njvm.index('-p') + 1].split(';')
    nlibs = [f"{NF}/libraries/{l['downloads']['artifact']['path']}" for l in nfv['libraries']]
    cp = [x for x in nlibs if x not in mp] + [x for x in libs if not any(k in x for k in ('/org/ow2/asm/',))]
    ga = [x for x in nfv['arguments']['game']] + game_args
    # mods dir for NeoForge's own discovery: <gameDir>/mods (copied by the caller)
    cmd = [JAVA] + jvm + njvm + ['-cp', ';'.join(cp), nfv['mainClass']] + ga
elif a.mode == 'vanilla':
    cmd = [JAVA] + jvm + ['-cp', ';'.join(libs + [f'{MC}/versions/1.21.1/1.21.1.jar']), 'net.minecraft.client.main.Main'] + game_args
else:
    loader_cp = [f'{R}/build/loader'] + [(f'{R}/' + p if ':' not in p else p) for p in open(f'{R}/build/loader-cp.txt').read().strip().split(';') if 'gson' not in p]
    largs = (['--state', a.state] if a.state else ['--client', f'{MC}/versions/{MCV}/{MCV}.jar', '--mappings', f'{MC}/mappings/merged.tiny' if MCV == '1.21.1' else f'{MC}/mappings-{MCV}/merged.tiny', '--mods', a.mods, '--cache', a.cache, '--mc', MCV])
    if a.forge:   # M4 Forge platform: securejarhandler + JarJarFileSystems on the system class path (FileSystemProviders)
        largs += ['--forge', f'{MC}/forge', '--libroot', LIBDIR]
        fv = json.load(open(f'{MC}/forge/inst/version.json'))
        loader_cp += [f"{MC}/forge/libraries/{l['downloads']['artifact']['path']}" for l in fv['libraries']
                      if any(x in l['downloads']['artifact']['path'] for x in ('cpw/mods/securejarhandler/', 'net/minecraftforge/JarJarFileSystems/'))]
        jvm += ['--add-opens', 'java.base/java.util.jar=ALL-UNNAMED', '--add-opens', 'java.base/java.lang.invoke=ALL-UNNAMED',
                '--add-exports', 'java.base/sun.security.util=ALL-UNNAMED', '--add-exports', 'jdk.naming.dns/com.sun.jndi.dns=java.naming']
    if a.neoforge:   # NeoForge platform: FML/securejarhandler need these on the class path (unnamed module)
        largs += ['--neoforge', f'{MC}/neoforge']
        nfv = json.load(open(f'{MC}/neoforge/inst/version.json'))   # securejarhandler + JarJarFileSystems: FileSystemProviders need the system class loader
        loader_cp += [f"{MC}/neoforge/libraries/{l['downloads']['artifact']['path']}" for l in nfv['libraries']
                      if any(x in l['downloads']['artifact']['path'] for x in ('cpw/mods/securejarhandler/', 'net/neoforged/JarJarFileSystems/'))]
        jvm += ['--add-opens', 'java.base/java.util.jar=ALL-UNNAMED', '--add-opens', 'java.base/java.lang.invoke=ALL-UNNAMED',
                '--add-exports', 'java.base/sun.security.util=ALL-UNNAMED', '--add-exports', 'jdk.naming.dns/com.sun.jndi.dns=java.naming']
    if a.census: largs += ['--census', a.census]
    if a.server: largs += ['--main', 'net.minecraft.server.Main']; game_args = ['--nogui']   # the server's world/config live in the cwd (--gamedir)
    cmd = [JAVA] + jvm + ['-cp', ';'.join(loader_cp + libs), 'amber.ul.Launcher'] + largs + ['--classcache', a.classcache, '--'] + game_args
os.makedirs(f'{MC}/runs', exist_ok=True)
logf = open(f'{MC}/runs/{a.label}.log', 'w', encoding='utf-8')
t0 = time.time()
proc = subprocess.Popen(cmd, cwd=game_dir, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.PIPE if a.server else None)
marks = {}; hit = threading.Event()
WATCH = {'sound': 'Sound engine started', 'atlas_blocks': 'blocks.png-atlas', 'title_hook': 'TitleScreen.init',
         'amber_title': '到达标题画面', 'mixin_err': 'Mixin apply', 'error': 'Exception'}
def pump():
    for raw in proc.stdout:
        line = raw.decode('utf-8', 'replace').rstrip()
        t = time.time() - t0
        logf.write(f'{t:8.3f} {line}\n'); logf.flush()
        for k, s in WATCH.items():
            if s in line and k not in marks: marks[k] = round(t, 3)
        if any(x in line for x in ('[amber-ul]', 'Sound engine', 'mixin', 'Mixin', 'ERROR', 'Exception', 'FATAL', 'Error')): print(f'{t:7.2f} {line[:300]}', flush=True)
        if a.marker in line: hit.set()
threading.Thread(target=pump, daemon=True).start()
deadline = time.time() + a.timeout
while not hit.is_set() and proc.poll() is None and time.time() < deadline: hit.wait(0.5)   # stop early if the JVM exits
ok = hit.is_set() and proc.poll() is None
if ok:
    time.sleep(a.wait_after)
    if a.screenshot:
        png = f'{MC}/runs/{a.label}.png'
        subprocess.run(['powershell', '-NoProfile', '-Command',
            'Add-Type -AssemblyName System.Windows.Forms,System.Drawing; $b=[System.Windows.Forms.Screen]::PrimaryScreen.Bounds; '
            '$bmp=New-Object System.Drawing.Bitmap $b.Width,$b.Height; $g=[System.Drawing.Graphics]::FromImage($bmp); '
            f'$g.CopyFromScreen($b.Location,[System.Drawing.Point]::Empty,$b.Size); $bmp.Save("{png}")'])
        print('screenshot', png)
if proc.poll() is None and a.server:   # console server: the "stop" command saves the world and exits
    try: proc.stdin.write(b'stop' + bytes([10])); proc.stdin.flush()
    except OSError: pass
    try: proc.wait(60)
    except subprocess.TimeoutExpired: pass
if proc.poll() is None:
    subprocess.run(['taskkill', '/PID', str(proc.pid)], capture_output=True)   # WM_CLOSE -> graceful quit (shutdown hooks run)
    try: proc.wait(40)
    except subprocess.TimeoutExpired: subprocess.run(['taskkill', '/F', '/PID', str(proc.pid)], capture_output=True); proc.wait()
time.sleep(0.5)
res = {'label': a.label, 'reached': ok, 'exit': proc.returncode, 'marks_s': marks}
print('RESULT', json.dumps(res, ensure_ascii=False))
with open(f'{MC}/runs/results.jsonl', 'a', encoding='utf-8') as f: f.write(json.dumps(res, ensure_ascii=False) + '\n')
