#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打鲸鲸 一站式发布工具。

用法:
  python3 tools/release.py check                     本地 ↔ 远端 main 差异
  python3 tools/release.py push "提交说明"            只推有差异的文件（含删除）
  python3 tools/release.py push "说明" --ver 3.5      顺便改版本号
  python3 tools/release.py build                     等 CI 编完并取回 APK
  python3 tools/release.py deploy /tmp/x.apk [名字]   推到手机 Download
  python3 tools/release.py all "说明" --ver 3.5       一条龙

设计要点：diff 的真值在远端 main（本地 git 停在首版，不能当基线）。
只有 WATCH 范围内的文件会被同步，art/ 里的中间产物永远不会误传。
"""
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OWNER, REPO = 'xiaoyu-godot2026', 'mole-native'
API = 'https://api.github.com/repos/%s/%s' % (OWNER, REPO)
BRIDGE = 'http://127.0.0.1:3090'
HDR = '/root/.dsh/.bridge_headers'

WATCH_DIRS = ('app/', 'docs/', 'tools/', '.github/')
WATCH_FILES = ('README.md', 'build.gradle', 'settings.gradle', 'gradle.properties',
               'art/README.md', 'art/gen_audio.py', 'art/push_files.py')
SKIP_EXT = ('.apk', '.zip', '.log')
LAST_APK = None


def token():
    txt = open('/root/.secrets/gh.cfg').read()
    m = re.search(r'Bearer ([A-Za-z0-9_]+)', txt)
    if not m:
        sys.exit('找不到 GitHub token（/root/.secrets/gh.cfg）')
    return m.group(1)


TOKEN = token()


def gh(method, path, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    r = urllib.request.Request(API + path, data=data, method=method)
    r.add_header('Authorization', 'Bearer ' + TOKEN)
    r.add_header('Accept', 'application/vnd.github+json')
    r.add_header('Content-Type', 'application/json')
    r.add_header('User-Agent', 'dsh-release')
    with urllib.request.urlopen(r, timeout=90) as resp:
        body = resp.read().decode()
        return json.loads(body) if body else {}


def watched(p):
    return p.startswith(WATCH_DIRS) or p in WATCH_FILES


def blob_sha(path):
    h = hashlib.sha1()
    data = open(path, 'rb').read()
    h.update(b'blob %d\x00' % len(data))
    h.update(data)
    return h.hexdigest(), len(data)


def local_watch():
    out = {}
    for dirpath, dirnames, filenames in os.walk(ROOT):
        rel = os.path.relpath(dirpath, ROOT).replace(os.sep, '/')
        if rel == '.':
            rel = ''
        dirnames[:] = [d for d in dirnames if d not in ('.git', 'build', '.gradle', '.idea')]
        for fn in filenames:
            if fn.endswith(SKIP_EXT):
                continue
            relp = (rel + '/' + fn) if rel else fn
            if not watched(relp):
                continue
            try:
                out[relp] = blob_sha(os.path.join(dirpath, fn))
            except OSError:
                pass
    return out


def remote_tree():
    t = gh('GET', '/git/trees/main?recursive=1')
    return {e['path']: e['sha'] for e in t.get('tree', []) if e['type'] == 'blob'}


def diff_set():
    rem = remote_tree()
    loc = local_watch()
    add = sorted(p for p in loc if p not in rem or rem[p] != loc[p][0])
    dele = sorted(p for p in rem if watched(p) and p not in loc)
    return add, dele


def cmd_check():
    add, dele = diff_set()
    if not add and not dele:
        print('== 本地与远端一致 ==')
        return 0
    print('待推送 %d 个：' % len(add))
    for p in add:
        print('  M ' + p)
    if dele:
        print('待删除 %d 个：' % len(dele))
        for p in dele:
            print('  D ' + p)
    return 1


def bump(ver):
    p = os.path.join(ROOT, 'app/build.gradle')
    src = open(p, encoding='utf-8').read()
    m = re.search(r'versionCode\s+(\d+)', src)
    if not m:
        sys.exit('build.gradle 里找不到 versionCode')
    code = int(m.group(1)) + 1
    src = re.sub(r'versionCode\s+\d+', 'versionCode %d' % code, src, count=1)
    src = re.sub(r'versionName\s+"[^"]*"', 'versionName "%s"' % ver, src, count=1)
    open(p, 'w', encoding='utf-8').write(src)
    print('版本 -> %s (%d)' % (ver, code))
    return code


def cmd_push(msg):
    add, dele = diff_set()
    if not add and not dele:
        print('没有差异，无需推送')
        return 0
    head = gh('GET', '/commits/main')
    entries = []
    for p in add:
        raw = open(os.path.join(ROOT, p), 'rb').read()
        blob = gh('POST', '/git/blobs', {'content': base64.b64encode(raw).decode(), 'encoding': 'base64'})
        entries.append({'path': p, 'mode': '100644', 'type': 'blob', 'sha': blob['sha']})
    for p in dele:
        entries.append({'path': p, 'mode': '100644', 'type': 'blob', 'sha': None})
    tree = gh('POST', '/git/trees', {'base_tree': head['commit']['tree']['sha'], 'tree': entries})
    commit = gh('POST', '/git/commits', {'message': msg, 'tree': tree['sha'], 'parents': [head['sha']]})
    gh('PATCH', '/git/refs/heads/main', {'sha': commit['sha'], 'force': True})
    print('PUSHED %s  (+%d -%d)' % (commit['sha'][:10], len(add), len(dele)))
    print(commit['sha'])
    return 0


def cmd_build():
    global LAST_APK
    head = gh('GET', '/commits/main')['sha']
    print('等待 CI：' + head[:10])
    run_id, started = None, time.time()
    while time.time() - started < 480:
        runs = gh('GET', '/actions/runs?branch=main&per_page=10').get('workflow_runs', [])
        hit = [r for r in runs if r.get('head_sha') == head]
        if not hit:
            time.sleep(6)
            continue
        r = hit[0]
        run_id = r['id']
        st, cc = r['status'], r.get('conclusion')
        if st == 'completed':
            if cc != 'success':
                print('编译失败：' + str(cc) + '  ' + r['html_url'])
                return 2
            print('编译完成 %ds' % int(time.time() - started))
            break
        time.sleep(8)
    if run_id is None:
        print('超时：没等到 CI 跑到这个提交')
        return 2
    arts = gh('GET', '/actions/runs/%d/artifacts' % run_id).get('artifacts', [])
    apk_art = [a for a in arts if a['name'] == 'game-apk']
    if not apk_art:
        print('没有 game-apk 产物')
        return 2
    url = apk_art[0]['archive_download_url']
    zpath = '/tmp/ci-artifact.zip'
    subprocess.run(['curl', '-sSL', '-H', 'Authorization: Bearer ' + TOKEN, '-o', zpath, url], check=True)
    out = None
    with zipfile.ZipFile(zpath) as z:
        for n in z.namelist():
            if n.endswith('.apk'):
                out = '/tmp/' + os.path.basename(n)
                with open(out, 'wb') as f:
                    f.write(z.read(n))
    if not out:
        print('压缩包里没有 apk')
        return 2
    print('APK %s  %.2f MB' % (out, os.path.getsize(out) / 1048576.0))
    LAST_APK = out
    print(out)
    return 0


def cmd_deploy(path, name=None):
    if not os.path.isfile(path):
        sys.exit('文件不存在: ' + path)
    if not os.path.isfile(HDR):
        sys.exit('桥凭据不存在: ' + HDR)
    if name:
        dst = '/tmp/' + name
        if os.path.abspath(dst) != os.path.abspath(path):
            subprocess.run(['cp', path, dst], check=True)
        path = dst
    r = subprocess.run(['curl', '-s', '-G', '-H', '@' + HDR, BRIDGE + '/app/export',
                        '--data-urlencode', 'path=' + os.path.abspath(path)],
                       capture_output=True, text=True, timeout=120)
    print(r.stdout.strip())
    return 0 if 'content://' in r.stdout else 3


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 1
    cmd = args[0]
    rest = args[1:]
    ver = None
    if '--ver' in rest:
        i = rest.index('--ver')
        ver = rest[i + 1]
        rest = rest[:i] + rest[i + 2:]
    if cmd == 'check':
        return cmd_check()
    if cmd == 'bump':
        bump(ver or sys.exit('需要 --ver'))
        return 0
    if cmd == 'push':
        if not rest:
            sys.exit('需要提交说明')
        if ver:
            bump(ver)
        return cmd_push(rest[0])
    if cmd == 'build':
        return cmd_build()
    if cmd == 'deploy':
        return cmd_deploy(rest[0], rest[1] if len(rest) > 1 else None)
    if cmd == 'all':
        msg = rest[0] if rest else 'update'
        name = rest[1] if len(rest) > 1 else None
        if ver:
            bump(ver)
        rc = cmd_push(msg)
        if rc:
            return rc
        rc = cmd_build()
        if rc:
            return rc
        return cmd_deploy(LAST_APK, name)
    print(__doc__)
    return 1


if __name__ == '__main__':
    sys.exit(main())
