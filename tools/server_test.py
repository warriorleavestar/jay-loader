"""Dedicated server test (phase 5, path B): Amber launches a NeoForge dedicated server, an Amber client joins it over TCP.

Server: net.minecraft.server.Main through amber.ul.Launcher (NeoForge server patches, Dist.DEDICATED_SERVER, Mixin side
SERVER, client-only Fabric mods skipped) in mc/testserver/ (the only place with eula=true: owner approval 2026-11-05 03:32,
"允许，只限测试服务端"); online-mode=false, server-ip=127.0.0.1, so it is reachable from this machine only.
Client: registers every test entry in reverse order (amber.regtest.skew), so its local numeric ids differ, then joins.
Pass = client ids after the join equal the server's ids for every probed entry (registry sync did real work), and the
Fabric networking probe (configuration task + play payloads both ways) completes.

usage: python -I tools/server_test.py <mods dir> [--port 25601] [--label srvtest] [--analyze-only]
"""
import argparse, os, re, subprocess, sys, time
P = argparse.ArgumentParser(); P.add_argument('mods'); P.add_argument('--port', default='25601'); P.add_argument('--label', default='srvtest')
P.add_argument('--analyze-only', action='store_true'); P.add_argument('--jvm', action='append', default=[])
a = P.parse_args(); sys.stdout.reconfigure(encoding='utf-8', errors='replace')
R = subprocess.run([sys.executable, '-I', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'proto_root.py')], capture_output=True, text=True, check=True).stdout.strip().replace('\\', '/')
MC = f'{R}/mc'; RUN = [sys.executable, '-I', f'{R}/tools/run_mc.py']
SRV = f'{MC}/testserver'
assert SRV.startswith(R + '/'), SRV   # the EULA file only ever goes into the prototype's own test-server dir
os.makedirs(SRV, exist_ok=True)
open(f'{SRV}/eula.txt', 'w', encoding='utf-8').write('# Amber prototype dedicated test server only (owner approval 2026-11-05, test server dir only)\neula=true\n')
props = {'online-mode': 'false', 'server-ip': '127.0.0.1', 'server-port': a.port, 'enable-query': 'false', 'enable-rcon': 'false',
         'enable-status': 'true', 'gamemode': 'creative', 'difficulty': 'peaceful', 'spawn-protection': '0', 'max-players': '2',
         'level-name': 'world', 'motd': 'Amber test server (localhost only)', 'view-distance': '6', 'simulation-distance': '6',
         'enforce-secure-profile': 'false', 'white-list': 'false', 'sync-chunk-writes': 'false'}
open(f'{SRV}/server.properties', 'w', encoding='utf-8').write(''.join(f'{k}={v}\n' for k, v in props.items()))
os.makedirs(f'{MC}/game-srvB', exist_ok=True)
if not os.path.exists(f'{MC}/game-srvB/options.txt'):
    src = open(f'{MC}/game/options.txt', encoding='utf-8').read()
    open(f'{MC}/game-srvB/options.txt', 'w', encoding='utf-8').write(re.sub(r'(?m)^pauseOnLostFocus:.*$', 'pauseOnLostFocus:false', src))
ls, lc = f'{a.label}-S', f'{a.label}-C'
logS, logC = f'{MC}/runs/{ls}.log', f'{MC}/runs/{lc}.log'
def grep(path, pat):
    try: return [l for l in open(path, encoding='utf-8', errors='replace') if pat in l]
    except FileNotFoundError: return []
if not a.analyze_only:
    for f in (logS, logC):
        if os.path.exists(f): os.remove(f)
    S = subprocess.Popen(RUN + [ls, 'amber', '--neoforge', '--server', '--mods', a.mods, '--cache', f'{MC}/ulcache-5s', '--gamedir', SRV,
                                '--marker', 'NET server got ping', '--timeout', '900', '--wait-after', '20'] + [f'--jvm={j}' for j in a.jvm],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    t0 = time.time()
    while not grep(logS, 'Done ('):
        if S.poll() is not None or time.time() - t0 > 600: print('server never finished starting; see', logS); S.kill(); sys.exit(2)
        time.sleep(2)
    print(f'server up after {time.time() - t0:.0f} s; starting client')
    C = subprocess.run(RUN + [lc, 'amber', '--neoforge', '--mods', a.mods, '--cache', f'{MC}/ulcache-5rB', '--gamedir', f'{MC}/game-srvB',
                              '--username', 'AmberJoin', '--uuid', '00000000000000000000000000000002', '--marker', "NET client got pong", '--timeout', '600',
                              '--wait-after', '8', f'--jvm=-Damber.autojoin=127.0.0.1:{a.port}', '--jvm=-Damber.regtest.skew=true'] + [f'--jvm={j}' for j in a.jvm],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    print('client', C.stdout.strip().splitlines()[-1] if C.stdout.strip() else C.returncode)
    try: S.wait(120)
    except subprocess.TimeoutExpired: subprocess.run(['taskkill', '/F', '/PID', str(S.pid), '/T'], capture_output=True)
    print('server', [l.strip() for l in open(f'{MC}/runs/results.jsonl', encoding='utf-8') if f'"{ls}"' in l][-1:])

def table(path, side):
    ls_ = grep(path, f'REGIDS {side} ')
    if not ls_: return None
    body = ls_[-1].split(f'REGIDS {side} ', 1)[1].strip()
    return dict(kv.split('=', 1) for kv in body.split(';') if '=' in kv)
srv, loc, cli = table(logS, 'server'), table(logC, 'local'), table(logC, 'client-remote')
for name, pat in (('server dist', 'DEDICATED_SERVER'), ('server skipped client-only mods', '跳过仅客户端模组')):
    print(f'  {name}: {len(grep(logS, pat))} log lines')
if not (srv and loc and cli): print('missing REGIDS lines', bool(srv), bool(loc), bool(cli)); sys.exit(1)
ids = [k for k in srv if '|' in k]
print(f'{"entry":45} {"server":>9} {"C local":>8} {"C joined":>9}')
bad = differ = 0
for k in ids:
    s, l, c = srv[k], loc.get(k), cli.get(k)
    bad += s != c or s == '-1'; differ += l != s
    print(f'{k:45} {s:>9} {l:>8} {c:>9}' + ('' if s == c else '   MISMATCH'))
print('flags server:', {k: v for k, v in srv.items() if '|' not in k}, ' client joined:', {k: v for k, v in cli.items() if '|' not in k})
ok = bad == 0 and differ > 0
print(f'RESULT srv-regsync {"PASS" if ok else "FAIL"}: {len(ids)} entries, {bad} mismatched after join, {differ} differed before join')
NET = [(logS, 'NET server CONFIGURE canSend(cfg_hello)=true'), (logC, 'NET client got config hello'),
       (logS, "NET server got config ack 'ack from client'"), (logS, 'NET server JOIN AmberJoin canSend(pong)=true'),
       (logC, 'NET client JOIN canSend(ping)=true'), (logS, 'NET server got ping n=41 from AmberJoin'),
       (logC, "NET client got pong n=42 'pong from server'")]
nok = 0
for path, pat in NET:
    hit = bool(grep(path, pat)); nok += hit
    print(f'  {"ok " if hit else "MISSING"} {"S" if path == logS else "C"}: {pat}')
print(f'RESULT srv-nettest {"PASS" if nok == len(NET) else "FAIL"}: {nok}/{len(NET)} steps, dedicated server')
sys.exit(0 if ok and nok == len(NET) else 1)
