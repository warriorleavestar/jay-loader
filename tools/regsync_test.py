"""Registry sync proof over a real TCP connection (phase 5, path A: no dedicated server, no EULA).

Client A opens a new world and listens on 127.0.0.1:<port> only (amber.servetcp; offline, no LAN broadcast).
Client B registers every test entry in reverse order (amber.regtest.skew), so its local numeric ids differ from A's,
then joins A over TCP; NeoForge's neoforge:sync_registries runs on that non-memory connection. Pass = B's ids after the
join equal A's server ids for every probed entry, and B's local ids differed before (the sync did real work).

usage: python -I tools/regsync_test.py <mods dir> [--port 25599]
"""
import argparse, os, re, subprocess, sys, time
P = argparse.ArgumentParser(); P.add_argument('mods'); P.add_argument('--port', default='25599'); P.add_argument('--label', default='regsync'); P.add_argument('--analyze-only', action='store_true'); P.add_argument('--jvm', action='append', default=[])
a = P.parse_args(); sys.stdout.reconfigure(encoding='utf-8', errors='replace')
R = subprocess.run([sys.executable, '-I', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'proto_root.py')], capture_output=True, text=True, check=True).stdout.strip().replace('\\', '/')
MC = f'{R}/mc'; RUN = [sys.executable, '-I', f'{R}/tools/run_mc.py']
for d in ('game-regA', 'game-regB'):
    os.makedirs(f'{MC}/{d}', exist_ok=True)
    if not os.path.exists(f'{MC}/{d}/options.txt'):   # no first-launch onboarding screen; host keeps ticking without focus
        src = open(f'{MC}/game/options.txt', encoding='utf-8').read()
        open(f'{MC}/{d}/options.txt', 'w', encoding='utf-8').write(re.sub(r'(?m)^pauseOnLostFocus:.*$', 'pauseOnLostFocus:false', src))
la, lb = f'{a.label}-A', f'{a.label}-B'
logA = f'{MC}/runs/{la}.log'
def grep(path, pat):
    try: return [l for l in open(path, encoding='utf-8', errors='replace') if pat in l]
    except FileNotFoundError: return []
if not a.analyze_only:
  A = subprocess.Popen(RUN + [la, 'amber', '--neoforge', '--mods', a.mods, '--cache', f'{MC}/ulcache-5r', '--gamedir', f'{MC}/game-regA',
                             '--marker', 'in world 240', '--timeout', '900', f'--jvm=-Damber.autoworld=RegHost{int(time.time())}', f'--jvm=-Damber.servetcp={a.port}'] + [f'--jvm={j}' for j in a.jvm],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
  t0 = time.time()
  while not grep(logA, 'TCP listening'):
      if A.poll() is not None or time.time() - t0 > 600: print('host never listened'); A.kill(); sys.exit(2)
      time.sleep(2)
  print(f'host listening after {time.time() - t0:.0f} s; starting joiner')
  B = subprocess.run(RUN + [lb, 'amber', '--neoforge', '--mods', a.mods, '--cache', f'{MC}/ulcache-5rB', '--gamedir', f'{MC}/game-regB',
                            '--username', 'AmberJoin', '--uuid', '00000000000000000000000000000002', '--marker', 'REGIDS client-remote', '--timeout', '600',
                            '--wait-after', '8', f'--jvm=-Damber.autojoin=127.0.0.1:{a.port}', '--jvm=-Damber.regtest.skew=true'] + [f'--jvm={j}' for j in a.jvm], capture_output=True, text=True, encoding='utf-8', errors='replace')
  print('joiner', B.stdout.strip().splitlines()[-1] if B.stdout.strip() else B.returncode)
  subprocess.run(['taskkill', '/PID', str(A.pid), '/T'], capture_output=True)
  try: A.wait(60)
  except subprocess.TimeoutExpired: subprocess.run(['taskkill', '/F', '/PID', str(A.pid), '/T'], capture_output=True)

def table(path, side):
    ls = grep(path, f'REGIDS {side} ')
    if not ls: return None
    body = ls[-1].split(f'REGIDS {side} ', 1)[1].strip()
    return dict(kv.split('=', 1) for kv in body.split(';') if '=' in kv)
srv, hostlocal = table(logA, 'server'), table(logA, 'local')
loc, cli = table(f'{MC}/runs/{lb}.log', 'local'), table(f'{MC}/runs/{lb}.log', 'client-remote')
if not (srv and loc and cli): print('missing REGIDS lines', bool(srv), bool(loc), bool(cli)); sys.exit(1)
ids = [k for k in srv if '|' in k]
print(f'{"entry":45} {"A server":>9} {"B local":>8} {"B joined":>9}')
bad = differ = 0
for k in ids:
    s, l, c = srv[k], loc.get(k), cli.get(k)
    bad += s != c or s == '-1'; differ += l != s
    print(f'{k:45} {s:>9} {l:>8} {c:>9}' + ('' if s == c else '   MISMATCH'))
print('flags A:', {k: v for k, v in srv.items() if '|' not in k}, ' B joined:', {k: v for k, v in cli.items() if '|' not in k})
ok = bad == 0 and differ > 0
print(f'RESULT regsync {"PASS" if ok else "FAIL"}: {len(ids)} entries, {bad} mismatched after join, {differ} differed before join')
# Fabric networking over the same TCP connection (regtest's NetTest): configuration task + play payloads both ways
NET = [(logA, 'NET server CONFIGURE canSend(cfg_hello)=true'), (f'{MC}/runs/{lb}.log', 'NET client got config hello'),
       (logA, "NET server got config ack 'ack from client'"), (logA, 'NET server JOIN AmberJoin canSend(pong)=true'),
       (f'{MC}/runs/{lb}.log', 'NET client JOIN canSend(ping)=true'), (logA, 'NET server got ping n=41 from AmberJoin'),
       (f'{MC}/runs/{lb}.log', "NET client got pong n=42 'pong from server'")]
nok = 0
for path, pat in NET:
    hit = bool(grep(path, pat)); nok += hit
    print(f'  {"ok " if hit else "MISSING"} {"A" if path == logA else "B"}: {pat}')
print(f'RESULT nettest {"PASS" if nok == len(NET) else "FAIL"}: {nok}/{len(NET)} steps over TCP')
ok = ok and nok == len(NET)
sys.exit(0 if ok else 1)
