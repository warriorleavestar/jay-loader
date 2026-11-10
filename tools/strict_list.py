"""Group a census result by module and injector: one line per (mixin, handler), target classes merged.
usage: python -I tools/strict_list.py mc/census/<label>.json"""
import json, re, sys, collections
d = json.load(open(sys.argv[1], encoding='utf-8'))
excluded = set(d.get('excluded', [])); fixed = set(d.get('fixed', [])); refuted = set(d.get('refuted', []))
groups = collections.defaultdict(lambda: collections.defaultdict(lambda: {'targets': set(), 'cat': set(), 'msg': ''}))
for e in d['failures']:
    mod = (e.get('config') or '?').replace('.client.mixins.json', '').replace('.mixins.json', '').replace('mixins.', '')
    h = e.get('handler')
    if not h:
        m = re.search(r'(?:Callback method|Redirector|Variable modifier method|Argument modifier method|Constant modifier method|@\w+ annotation on|@Inject::|->@\w+::)\s*(\w+)', e.get('message', ''))
        h = m.group(1) if m else '-'
    g = groups[mod][(e['mixin'].split('.')[-1], h, e['mixin'])]
    g['targets'].add((e.get('target') or '-').split('.')[-1]); g['cat'].add(e.get('category', '?')); g['msg'] = g['msg'] or e.get('message', '')[:220]
n = 0
for mod in sorted(groups):
    print(f'== {mod}')
    for (mx, h, fq), g in sorted(groups[mod].items()):
        st = 'fixed' if fq in fixed else 'REFUTED' if fq in refuted else 'excluded' if fq in excluded else 'kept'
        n += 1
        print(f'  {mx}#{h} [{",".join(sorted(g["cat"]))}] {st} -> {", ".join(sorted(g["targets"]))[:120]}')
print(f'{n} distinct injectors/mixins in {len(groups)} modules')
