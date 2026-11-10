"""M3 oracles: the official Forge tools MCPConfig 1.20.1's config.json names (installertools MERGE_MAPPING, FART rename),
from maven.minecraftforge.net, checked against the maven .sha1 (and .sha256 when published). Into mc/tools-forge/."""
import hashlib, os, urllib.request
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'mc', 'tools-forge'); os.makedirs(OUT, exist_ok=True)
REPO = 'https://maven.minecraftforge.net/'
TOOLS = [('net.minecraftforge', 'installertools', '1.2.0', 'fatjar'), ('net.minecraftforge', 'ForgeAutoRenamingTool', '1.0.2', 'all')]
def fetch(url, timeout=60):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'amber-ul-prototype'}), timeout=timeout) as r: return r.read()
for g, a, v, c in TOOLS:
    name = f'{a}-{v}-{c}.jar'; url = f'{REPO}{g.replace(".", "/")}/{a}/{v}/{name}'; dest = os.path.join(OUT, name)
    data = open(dest, 'rb').read() if os.path.exists(dest) else fetch(url)
    want1 = fetch(url + '.sha1').decode().split()[0].strip()
    got1 = hashlib.sha1(data).hexdigest()
    try: want256 = fetch(url + '.sha256').decode().split()[0].strip()
    except Exception: want256 = None
    got256 = hashlib.sha256(data).hexdigest()
    if got1 != want1 or (want256 and got256 != want256): raise SystemExit(f'checksum mismatch {name}: sha1 {got1} vs {want1}, sha256 {got256} vs {want256}')
    open(dest, 'wb').write(data)
    print(name, len(data), 'bytes, sha1 ok' + (', sha256 ok' if want256 else ' (no .sha256 published)'), got256)
