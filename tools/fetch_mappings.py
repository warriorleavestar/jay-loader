"""Fetch the official mapping files the universal loader needs, sha1-verified, into mc/mappings-cache/ (+ manifest.json).

Sources (user approval 2026-11-05 04:55 UTC: mapping files only, these hosts only):
  - Mojang client/server mappings (client.txt / server.txt) from the version jsons in Mojang's version manifest
  - Fabric intermediary (v2 jar) from maven.fabricmc.net
  - NeoForm (mappings / data zip) from maven.neoforged.net (newest final NeoForm build per MC version; rc only if no final)
  - Forge MCPConfig (SRG; data zip) from maven.minecraftforge.net (newest build per MC version) - SRG for the Forge personality
Manifest entries carry the namespace each file provides (official->named, official->intermediary, official->srg ...),
so Amber's own cross-namespace tables can be composed locally from them. Files with Mojang names stay local (licence).
Each file is checked against the published sha1 (Mojang: version json; mavens: the .sha1 next to the artifact).
Existing files with a matching sha1 are kept. Nothing is executed.

usage: python -I tools/fetch_mappings.py [mc versions...]   (default: 1.21.1 1.20.1 1.20.4 1.21.4)
"""
import hashlib, json, os, re, subprocess, sys, time, urllib.request
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
R = subprocess.run([sys.executable, '-I', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'proto_root.py')], capture_output=True, text=True, check=True).stdout.strip()
OUT = os.path.join(R, 'mc', 'mappings-cache'); os.makedirs(OUT, exist_ok=True)
MANIFEST = os.path.join(OUT, 'manifest.json')
VERSIONS = sys.argv[1:] or ['1.21.1', '1.20.1', '1.20.4', '1.21.4']
UA = {'User-Agent': 'amber-ul-proto-mappings/0.1'}
ALLOWED = ('https://piston-meta.mojang.com/', 'https://piston-data.mojang.com/', 'https://launchermeta.mojang.com/',
           'https://maven.fabricmc.net/', 'https://maven.neoforged.net/', 'https://maven.minecraftforge.net/',
           # community mappings: Parchment, Yarn, Quilt mappings + hashed
           'https://maven.parchmentmc.org/', 'https://maven.quiltmc.org/')

def get(url):
    assert url.startswith(ALLOWED), url
    for i in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r: return r.read()
        except Exception as e:
            if getattr(e, 'code', None) == 404: raise
            if i == 3: raise
            time.sleep(2 ** (i + 1))

def sha1(b): return hashlib.sha1(b).hexdigest()

man = json.load(open(MANIFEST, encoding='utf-8')) if os.path.exists(MANIFEST) else {'files': {}}
def maven_hash(url):
    # published checksum next to the artifact: .sha1, else .sha512 -> (algo, hex)
    for algo in ('sha1', 'sha512'):
        try: return algo, get(f'{url}.{algo}').decode().split()[0].lower()
        except Exception as e:
            if getattr(e, 'code', None) != 404: raise
    raise SystemExit(f'no published checksum for {url}')
def pom_licence(url):
    # licence from the artifact's POM (metadata only); None when there is no POM / no <licenses>
    pom = re.sub(r'\.(zip|jar)$', '.pom', re.sub(r'-(mergedv2|v2|intermediary-v2)\.jar$', '.jar', url))
    try: x = get(pom).decode('utf-8', 'replace')
    except Exception: return None
    n = re.findall(r'<license>\s*<name>([^<]+)</name>', x)
    return ', '.join(n) or None
def fetch(rel, url, want, ns=None, mc=None, licence=None, algo='sha1'):
    path = os.path.join(OUT, rel); os.makedirs(os.path.dirname(path), exist_ok=True)
    h = lambda b: hashlib.new(algo, b).hexdigest()
    lic = licence or (man['files'].get(rel, {}).get('licence') if man['files'].get(rel, {}).get('licence', '').find('unknown') < 0 else None) or pom_licence(url) or 'unknown (see upstream)'
    entry = lambda size, b: {'url': url, algo: want, 'sha1': sha1(b), 'size': size, 'ns': ns, 'mc': mc, 'licence': lic}
    if os.path.exists(path):
        b = open(path, 'rb').read()
        if h(b) == want: print(f'  ok (cached) {rel}'); man['files'][rel] = entry(len(b), b); return
    b = get(url)
    if h(b) != want: raise SystemExit(f'{algo} mismatch for {url}: {h(b)} != {want}')
    tmp = path + '.part'; open(tmp, 'wb').write(b); os.replace(tmp, path)
    man['files'][rel] = entry(len(b), b)
    print(f'  fetched {rel} ({len(b):,} bytes, {algo} ok, licence {lic})')

MOJANG_LIC = 'Mojang mappings licence: use permitted, redistribution not (local cache only)'
def maven_versions(meta_url):
    try: return re.findall(r'<version>([^<]+)</version>', get(meta_url).decode())
    except Exception as e:
        if getattr(e, 'code', None) == 404: return []
        raise
def newest_build(vs, mc):
    # '<mc>+build.N' -> highest N
    b = [(int(m.group(1)), x) for x in vs for m in [re.fullmatch(re.escape(mc) + r'\+build\.(\d+)', x)] if m]
    return max(b)[1] if b else None
yarn_all = maven_versions('https://maven.fabricmc.net/net/fabricmc/yarn/maven-metadata.xml')
qm_all = maven_versions('https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-mappings/maven-metadata.xml')
vm = json.loads(get('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'))
byid = {v['id']: v for v in vm['versions']}
nf_meta = get('https://maven.neoforged.net/releases/net/neoforged/neoform/maven-metadata.xml').decode()
nf_all = re.findall(r'<version>([^<]+)</version>', nf_meta)
mcp_all = re.findall(r'<version>([^<]+)</version>', get('https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/maven-metadata.xml').decode())
def newest(vs, mc):
    # '<mc>-<yyyymmdd.hhmmss>' (final) beats '<mc>-rcN-<date>' / '-preN-'; newest date wins within a group
    c = [x for x in vs if x == mc or x.startswith(mc + '-')]
    final = [x for x in c if re.fullmatch(re.escape(mc) + r'(-\d{8}\.\d{6})?', x)]
    pool = final or c
    return max(pool, key=lambda x: re.findall(r'\d{8}\.\d{6}', x) or ['']) if pool else None
for mc in VERSIONS:
    print(mc)
    if mc not in byid: print('  (not in Mojang version manifest)'); continue
    v = byid[mc]
    vj_bytes = get(v['url'])
    if sha1(vj_bytes) != v['sha1']: raise SystemExit(f'version json sha1 mismatch for {mc}')
    dl = json.loads(vj_bytes)['downloads']
    for side in ('client', 'server'):
        m = dl.get(f'{side}_mappings')
        if m: fetch(f'{mc}/mojang-{side}.txt', m['url'], m['sha1'], f'official->named ({side}, proguard)', mc, MOJANG_LIC)
        else: print(f'  (no {side} mappings published for {mc})')
    base = f'https://maven.fabricmc.net/net/fabricmc/intermediary/{mc}/intermediary-{mc}-v2.jar'
    try: fetch(f'{mc}/intermediary-{mc}-v2.jar', base, get(base + '.sha1').decode().split()[0], 'official->intermediary (tiny v2)', mc, 'CC0-1.0 (FabricMC/intermediary LICENSE)')
    except Exception as e: print(f'  (no intermediary for {mc}: {e})')
    nv = newest(nf_all, mc)
    if nv:
        url = f'https://maven.neoforged.net/releases/net/neoforged/neoform/{nv}/neoform-{nv}.zip'
        fetch(f'{mc}/neoform-{nv}.zip', url, get(url + '.sha1').decode().split()[0], 'official->srg/named (NeoForm data: joined.tsrg, config)', mc)
        man.setdefault('neoform', {})[mc] = nv
    else: print(f'  (no NeoForm build for {mc})')
    cv = newest(mcp_all, mc)
    if cv:
        url = f'https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/{cv}/mcp_config-{cv}.zip'
        fetch(f'{mc}/mcp_config-{cv}.zip', url, get(url + '.sha1').decode().split()[0], 'official->srg (Forge MCPConfig: joined.tsrg, config)', mc)
        man.setdefault('mcp_config', {})[mc] = cv
    else: print(f'  (no MCPConfig build for {mc})')
    def opt(rel, url, ns, label, lic=None):
        try: algo, want = maven_hash(url)
        except Exception as e: print(f'  (no {label} for {mc}: {e})'); return False
        fetch(rel, url, want, ns, mc, lic, algo=algo); return True
    yv = newest_build(yarn_all, mc)
    if yv and opt(f'{mc}/yarn-{yv}-mergedv2.jar', f'https://maven.fabricmc.net/net/fabricmc/yarn/{yv}/yarn-{yv}-mergedv2.jar', 'official->intermediary->named (Yarn, tiny v2 merged)', 'Yarn', 'CC0-1.0 (FabricMC/yarn LICENSE)'):
        man.setdefault('yarn', {})[mc] = yv
    elif not yv: print(f'  (no Yarn build for {mc})')
    pv = [x for x in maven_versions(f'https://maven.parchmentmc.org/org/parchmentmc/data/parchment-{mc}/maven-metadata.xml') if re.fullmatch(r'\d{4}\.\d{2}\.\d{2}', x)]
    if pv:
        v_ = max(pv)
        if opt(f'{mc}/parchment-{mc}-{v_}.zip', f'https://maven.parchmentmc.org/org/parchmentmc/data/parchment-{mc}/{v_}/parchment-{mc}-{v_}.zip', 'named(Mojmap) params+javadoc (Parchment, JSON)', 'Parchment'):
            man.setdefault('parchment', {})[mc] = v_
    else: print(f'  (no Parchment release for {mc})')
    qv = newest_build(qm_all, mc)
    if qv and opt(f'{mc}/quilt-mappings-{qv}-intermediary-v2.jar', f'https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-mappings/{qv}/quilt-mappings-{qv}-intermediary-v2.jar', 'intermediary->named (Quilt Mappings, tiny v2)', 'Quilt Mappings', 'CC0-1.0 (QuiltMC/quilt-mappings LICENSE)'):
        man.setdefault('quilt_mappings', {})[mc] = qv
    elif not qv: print(f'  (no Quilt Mappings build for {mc})')
    opt(f'{mc}/hashed-{mc}.jar', f'https://maven.quiltmc.org/repository/release/org/quiltmc/hashed/{mc}/hashed-{mc}.jar', 'official->hashed (Quilt hashed, tiny v2)', 'Quilt hashed')
man['files'] = {k: v for k, v in man['files'].items() if os.path.exists(os.path.join(OUT, k))}   # drop entries for removed files
man['updated'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
json.dump(man, open(MANIFEST, 'w', encoding='utf-8'), indent=1, sort_keys=True)
print(f'manifest: {len(man["files"])} files -> {MANIFEST}')
