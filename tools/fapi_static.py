"""Static read of Fabric API (no code executed): per module, its mixin classes and their target classes (intermediary ->
Mojmap), and which targets Forge 47 patches. Usage: fapi_static.py <fabric-api.jar> <merged.tiny> <forge patched client.jar> <out.json>"""
import io, json, struct, sys, zipfile

def cp_and_annotations(b):
    """Minimal class-file reader: constant pool, then class-level Runtime(In)VisibleAnnotations -> @Mixin targets."""
    pos = 8; n = struct.unpack('>H', b[pos:pos + 2])[0]; pos += 2; cp = [None] * n; i = 1
    while i < n:
        t = b[pos]; pos += 1
        if t == 1: l = struct.unpack('>H', b[pos:pos + 2])[0]; cp[i] = b[pos + 2:pos + 2 + l].decode('utf-8', 'replace'); pos += 2 + l
        elif t in (3, 4): pos += 4
        elif t in (5, 6): pos += 8; i += 1
        elif t == 7: cp[i] = ('class', struct.unpack('>H', b[pos:pos + 2])[0]); pos += 2
        elif t in (8, 16, 19, 20): pos += 2
        elif t in (9, 10, 11, 12, 17, 18): pos += 4
        elif t == 15: pos += 3
        else: raise ValueError(t)
        i += 1
    pos += 6; ni = struct.unpack('>H', b[pos:pos + 2])[0]; pos += 2 + 2 * ni
    for _ in range(2):   # fields, methods
        cnt = struct.unpack('>H', b[pos:pos + 2])[0]; pos += 2
        for _ in range(cnt):
            pos += 6; ac = struct.unpack('>H', b[pos:pos + 2])[0]; pos += 2
            for _ in range(ac): l = struct.unpack('>I', b[pos + 2:pos + 6])[0]; pos += 6 + l
    ac = struct.unpack('>H', b[pos:pos + 2])[0]; pos += 2; targets = []
    def elem(p):
        tag = chr(b[p]); p += 1
        if tag in 'BCDFIJSZs': return p + 2, ('const', cp[struct.unpack('>H', b[p:p + 2])[0]])
        if tag == 'e': return p + 4, None
        if tag == 'c': return p + 2, ('class', cp[struct.unpack('>H', b[p:p + 2])[0]])
        if tag == '@': return ann(p)
        if tag == '[':
            k = struct.unpack('>H', b[p:p + 2])[0]; p += 2; out = []
            for _ in range(k): p, v = elem(p); out.append(v)
            return p, ('arr', out)
        raise ValueError(tag)
    def ann(p):
        typ = cp[struct.unpack('>H', b[p:p + 2])[0]]; k = struct.unpack('>H', b[p + 2:p + 4])[0]; p += 4; vals = {}
        for _ in range(k):
            name = cp[struct.unpack('>H', b[p:p + 2])[0]]; p, v = elem(p + 2); vals[name] = v
        return p, ('ann', typ, vals)
    for _ in range(ac):
        name = cp[struct.unpack('>H', b[pos:pos + 2])[0]]; l = struct.unpack('>I', b[pos + 2:pos + 6])[0]; body = pos + 6; pos = body + l
        if name in ('RuntimeVisibleAnnotations', 'RuntimeInvisibleAnnotations'):
            k = struct.unpack('>H', b[body:body + 2])[0]; p = body + 2
            for _ in range(k):
                p, a = ann(p)
                if a[1] == 'Lorg/spongepowered/asm/mixin/Mixin;':
                    for key in ('value', 'targets'):
                        v = a[2].get(key)
                        if v: targets += [x[1] for x in v[1]]
    return [t[1:-1] if t.startswith('L') else t.replace('.', '/') for t in targets]

fapi, tiny, patched, out = sys.argv[1:5]
i2n = {}
for line in open(tiny, encoding='utf-8'):
    if line.startswith('c\t'): _, o, im, nm = line.rstrip('\n').split('\t'); i2n[im] = nm
pz = zipfile.ZipFile(patched); forge_patched = {n[:-6] for n in pz.namelist() if n.endswith('.class')}
z = zipfile.ZipFile(fapi); res = {}
for nested in sorted(n for n in z.namelist() if n.startswith('META-INF/jars/') and n.endswith('.jar')):
    mz = zipfile.ZipFile(io.BytesIO(z.read(nested)))
    fmj = json.loads(mz.read('fabric.mod.json')); mid = fmj['id']; mods = []
    for cfg in fmj.get('mixins', []):
        cname = cfg if isinstance(cfg, str) else cfg['config']
        c = json.loads(mz.read(cname)); pkg = c.get('package', '')
        for side in ('mixins', 'client', 'server'):
            for m in c.get(side) or []:
                cls = (pkg + '.' + m).replace('.', '/')
                try: tg = cp_and_annotations(mz.read(cls + '.class'))
                except KeyError: tg = ['?missing']
                named = [i2n.get(t, t) for t in tg]
                mods.append({'mixin': pkg + '.' + m, 'side': side, 'targets': named, 'forgePatched': [t for t in named if t in forge_patched]})
    res[mid] = {'version': fmj['version'], 'mixins': mods}
json.dump(res, open(out, 'w'), indent=1)
tot = sum(len(v['mixins']) for v in res.values()); hit = sum(1 for v in res.values() for m in v['mixins'] if m['forgePatched'])
print(f'modules {len(res)}, mixins {tot}, targeting a Forge-patched class {hit}')
for mid, v in res.items():
    h = [m for m in v['mixins'] if m['forgePatched']]
    print(f"{mid:45s} {v['version']:22s} mixins {len(v['mixins']):3d}  on Forge-patched {len(h):3d}  {sorted({t.split('/')[-1] for m in h for t in m['forgePatched']})[:6]}")
