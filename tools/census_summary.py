"""Summarise census JSONs: NeoForge vs vanilla control, by category and mod. usage: census_summary.py <nf.json> <vanilla.json> [<total mixins>]"""
import json, sys, collections
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
nf = json.load(open(sys.argv[1], encoding='utf-8')); va = json.load(open(sys.argv[2], encoding='utf-8'))
vset = {f['mixin'] for f in va['failures']}
spec = [f for f in nf['failures'] if f['mixin'] not in vset]
print(f"NeoForge 失败 {len(nf['failures'])}, 原版对照失败 {len(va['failures'])}, NeoForge 特有 {len(spec)}")
print('| 类别 | NeoForge 特有失败 |'); print('|---|---|')
for c, n in collections.Counter(f['category'] for f in spec).most_common(): print(f'| {c} | {n} |')
mods = collections.Counter((f['mod'] if f['mod'] != '?' else f['config'].split('.')[0]) for f in spec)
print('按模组:', ', '.join(f'{m} {n}' for m, n in mods.most_common()))
