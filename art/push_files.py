# -*- coding: utf-8 -*-
import json, base64, os, re, sys, urllib.request, urllib.error

TOKEN = re.search(r'Bearer ([A-Za-z0-9_]+)', open('/root/.secrets/gh.cfg').read()).group(1)
OWNER, REPO = 'xiaoyu-godot2026', 'mole-native'
API = f'https://api.github.com/repos/{OWNER}/{REPO}'

def req(method, url, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    for k, v in (('Authorization', f'Bearer {TOKEN}'), ('Accept', 'application/vnd.github+json'),
                 ('Content-Type', 'application/json'), ('User-Agent', 'dsh-build')):
        r.add_header(k, v)
    with urllib.request.urlopen(r, timeout=90) as resp:
        return json.loads(resp.read().decode() or '{}')

paths = [ln.strip() for ln in open(sys.argv[1], encoding='utf-8') if ln.strip()]
head = req('GET', f'{API}/commits?per_page=1')[0]
base_tree = head['commit']['tree']['sha']
tree = []
for p in paths:
    raw = open(p, 'rb').read()
    blob = req('POST', f'{API}/git/blobs',
               {'content': base64.b64encode(raw).decode(), 'encoding': 'base64'})
    tree.append({'path': p, 'mode': '100644', 'type': 'blob', 'sha': blob['sha']})
t = req('POST', f'{API}/git/trees', {'base_tree': base_tree, 'tree': tree})
c = req('POST', f'{API}/git/commits', {'message': sys.argv[2], 'tree': t['sha'], 'parents': [head['sha']]})
req('PATCH', f'{API}/git/refs/heads/main', {'sha': c['sha'], 'force': True})
print('PUSHED', c['sha'][:10], len(paths), 'files')
