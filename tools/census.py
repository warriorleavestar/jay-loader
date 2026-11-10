"""Offline mixin census (phase 4). For one mod set and one platform (vanilla | neoforge):
round 1 applies every mixin to every target (Mixin audit, no game window); each failing mixin is added to an exclusion
list and the census reruns, so failures hidden behind another failure on the same class surface too. Stops when a round
finds nothing new. Output: <out>.json (all failures with category) + printed summary.
usage: python -I tools/census.py <label> <mods dir> <vanilla|neoforge|neoforge-server|forge> [--exclude-in file] [--fix] [--strict-require]
(neoforge-server: the dedicated-server base and Mixin side SERVER, own isolation key and fixes)"""
import glob, json, os, subprocess, sys, collections
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
label, mods, platform = sys.argv[1], sys.argv[2], sys.argv[3]
STRICT = '--strict-require' in sys.argv   # Forge: Fabric mods keep their own injector require (reports silent misses); writes the same isolation file, rerun without it before a lenient launch
FIX = '--fix' in sys.argv; fixed_ok = set(); refuted = set()   # refuted: a fix that failed verification in the next round
fixed_handlers = {}   # mixin -> handlers the fixer claimed fixed (a later failure of ANOTHER handler of the same mixin is new work, not a refutation)
def base(h): return (h or '').replace('amber$bridge$', '')
here = os.path.dirname(os.path.abspath(__file__))
R = subprocess.run([sys.executable, '-I', os.path.join(here, 'proto_root.py')], capture_output=True, text=True).stdout.strip()
outdir = f'{R}/mc/census'; os.makedirs(outdir, exist_ok=True)
excl = f'{outdir}/{label}-exclude.json'; json.dump([], open(excl, 'w'))
allf = []
CP = ';'.join([f'{R}/build/loader'] + [(p if ':' in p else f'{R}/' + p) for p in open(f'{R}/build/loader-cp.txt').read().strip().split(';')])
for rnd in range(1, 15):
    cj = f'{outdir}/{label}-r{rnd}.json'
    cmd = [sys.executable, '-I', f'{R}/tools/run_mc.py', f'census-{label}-r{rnd}', 'amber', '--mods', mods, '--cache', f'{R}/mc/ulcache-census-{platform}',
           '--census', cj, '--marker', '普查完成', '--wait-after', '0', '--timeout', '900',
           '--jvm=-Damber.ul.mixinErrors=warn', '--jvm=-Damber.ul.ignoreDeps=true', f'--jvm=-Damber.ul.mixinExclude={excl}']
    if platform.startswith('neoforge'): cmd.insert(5, '--neoforge')
    if platform == 'neoforge-server': cmd.insert(6, '--server')
    if platform == 'forge': cmd.insert(5, '--forge')   # M4: Forge 1.20.1 platform (bake-time fixer for Fabric mods on Forge with --fix; Forge mods target Forge's own patched game)
    if rnd == 1: cmd.append('--jvm=-Damber.ul.censusFresh=true')
    if STRICT: cmd.append('--jvm=-Damber.ul.strictFabricRequire=true')
    subprocess.run(cmd, capture_output=True)
    if not os.path.exists(cj): print('round', rnd, 'produced no census (crash?) see mc/runs/census-%s-r%d.log' % (label, rnd)); break
    c = json.load(open(cj, encoding='utf-8'))
    ex = set(json.load(open(excl)))
    new = [f for f in c['failures'] if f['mixin'] not in ex]
    reopened = set()
    for f in c['failures']:
        if f['mixin'] in fixed_ok:
            fixed_ok.discard(f['mixin'])
            if f.get('handler') is None or base(f.get('handler')) in fixed_handlers.get(f['mixin'], set()): refuted.add(f['mixin'])   # transactional: the fix itself failed -> drop it, exclude
            else: reopened.add(f['mixin'])   # another handler of the same mixin: give the fixer the whole mixin again
    new = [f for f in c['failures'] if f['mixin'] not in ex or f['mixin'] in refuted | reopened]
    for f in new: f['round'] = rnd
    allf += new
    print(f'round {rnd}: {len(c["failures"])} failures ({len(new)} new), changed classes {c["changedClasses"]}, {c["ms"]} ms', flush=True)
    if not new and not (FIX and rnd == 1): break   # round 1 with --fix runs the fixer anyway (its delegator scan needs no failure)
    exn = ex | {f['mixin'] for f in new} | refuted
    if FIX and (platform.startswith('neoforge') or platform == 'forge') and c.get('isolationFile'):
        # bake-time fixer over all failures so far; fixed mixins leave the exclusion list (and are retried next round)
        fixjar = c['isolationFile'][:-5] + '.fixes.jar'
        if platform == 'forge':
            # Forge 47 (Fabric mods on Forge): clean = vanilla 1.20.1 in Mojmap (made once from the official jar), patched = the
            # Forge platform game after binpatches + JS coremods (-fcm-), both in the platform's runtime names (Mojmap)
            srg = f'{R}/mc/ulcache-census-forge/baked/clean-1.20.1-named.jar'; maps = f'{R}/mc/mappings-1.20.1/merged.tiny'
            if not os.path.exists(srg):
                libs = glob.glob(f'{R}/mc/libraries-1.20.1/**/*.jar', recursive=True)
                subprocess.run([f'{R}/tools/jre21/bin/java.exe', '-cp', CP, 'amber.ul.tools.RemapJar', f'{R}/mc/versions/1.20.1/1.20.1.jar', srg, maps, 'official', 'named'] + libs, check=True, capture_output=True)
            pat = sorted(glob.glob(f'{R}/mc/ulcache-census-forge/baked/game-forge/*-fcm-*.jar'), key=os.path.getmtime)[-1]
        else:
            maps = f'{R}/mc/mappings/merged.tiny'
            srg = glob.glob(f'{R}/mc/neoforge/libraries/net/minecraft/' + ('server' if platform == 'neoforge-server' else 'client') + '/*/*-srg.jar')[0]
            pat = sorted(glob.glob(f'{R}/mc/ulcache-census-{platform}/baked/game-neoforge/*-cm-*.jar'), key=os.path.getmtime)[-1]
        fail_json = f'{outdir}/{label}-fixer-input.json'
        json.dump({'failures': [f for f in allf if f['mixin'] not in refuted], 'noScan': sorted(refuted)}, open(fail_json, 'w', encoding='utf-8'))   # fixed ones too: a mixin is re-fixed from its original bytes
        mods_ = c.get('bakedJars') or [j for j in glob.glob(f'{R}/mc/ulcache-census-{platform}/baked/mods/*.jar') if not j.endswith('.pre.jar')]   # exactly this run's jars
        r = subprocess.run([f'{R}/tools/jre21/bin/java.exe', f'-Damber.ul.mappings={maps}', '-cp', CP,
                            'amber.ul.bake.MixinFixer', fail_json, srg, pat, fixjar + '.new'] + mods_, capture_output=True, text=True, encoding='utf-8', errors='replace')
        print(r.stdout.strip().splitlines()[0] if r.stdout.strip() else r.stderr[-500:], flush=True)
        open(f'{outdir}/{label}-fixer-r{rnd}.txt', 'w', encoding='utf-8').write(r.stdout + r.stderr)
        if os.path.exists(fixjar + '.new'):
            # merge with fixes from earlier rounds
            import zipfile
            old = {}
            if os.path.exists(fixjar):
                with zipfile.ZipFile(fixjar) as z: old = {n: z.read(n) for n in z.namelist()}
            with zipfile.ZipFile(fixjar + '.new') as z: newc = {n: z.read(n) for n in z.namelist()}
            log = json.loads(newc.get('amber-fixes.json', b'[]')); prevlog = json.loads(old.get('amber-fixes.json', b'[]'))
            old = {n: b for n, b in old.items() if n == 'amber-fixes.json' or n not in newc}; old.update(newc); old['amber-fixes.json'] = json.dumps(prevlog + log, indent=1).encode()
            with zipfile.ZipFile(fixjar, 'w') as z:
                for n, b in old.items(): z.writestr(n, b)
            os.remove(fixjar + '.new')
            bad = {e['mixin'] for e in log if not e['fixed'] and any(f['mixin'] == e['mixin'] and base(f.get('handler')) == e.get('handler') for f in allf)}
            for e in log:
                if e['fixed'] and e['mixin'] not in refuted: fixed_handlers.setdefault(e['mixin'], set()).add(e.get('handler'))
            for m in fixed_handlers:
                if m not in refuted and m not in bad: fixed_ok.add(m); exn.discard(m)
                elif m in bad: fixed_ok.discard(m); exn.add(m)   # some failing handler of this mixin is still unfixable: the mixin stays isolated
    json.dump(sorted(exn), open(excl, 'w'), indent=1)
# dedupe by (mixin, target)
seen = {}; 
for f in allf: seen.setdefault((f['mixin'], f['target']), f)
res = {'label': label, 'platform': platform, 'failures': list(seen.values()), 'excluded': json.load(open(excl)), 'fixed': sorted(fixed_ok), 'refuted': sorted(refuted)}
json.dump(res, open(f'{outdir}/{label}.json', 'w', encoding='utf-8'), indent=1, ensure_ascii=False)
cats = collections.Counter(f['category'] for f in seen.values()); mods_ = collections.Counter(f['mod'] for f in seen.values())
print(f'== {label} [{platform}]: {len(seen)} failing (mixin,target) pairs, {len(res["excluded"])} mixins excluded, {len(fixed_ok)} fixed, {len(refuted)} fixes refuted by verification')
print('   by category:', dict(cats)); print('   by mod:', dict(mods_))
