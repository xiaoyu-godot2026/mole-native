#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
打鲸鲸 · 音效合成器（纯 Python，无第三方依赖）
生成 app/src/main/res/raw/ 下的 wav 音效 + 背景音乐，以及一个试听文件。

用法: python3 art/gen_audio.py
"""
import math, os, random, struct, wave

SR = 22050
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "res", "raw"))
PREVIEW = os.path.normpath(os.path.join(HERE, "..", "..", "audio_preview.wav"))

random.seed(20261025)


# ---------------- 基础工具 ----------------
def norm(s, peak=0.9):
    m = max((abs(v) for v in s), default=0.0)
    if m < 1e-9:
        return s
    g = peak / m
    return [v * g for v in s]


def write_wav(path, s, peak=0.9):
    s = norm(s, peak)
    buf = bytearray()
    for v in s:
        iv = int(max(-1.0, min(1.0, v)) * 32767)
        buf += struct.pack("<h", iv)
    w = wave.open(path, "wb")
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(bytes(buf))
    w.close()


def silence(dur):
    return [0.0] * int(SR * dur)


def place(dst, src, at_s, gain=1.0):
    off = int(at_s * SR)
    need = off + len(src)
    if need > len(dst):
        dst.extend([0.0] * (need - len(dst)))
    for i, v in enumerate(src):
        dst[off + i] += v * gain
    return dst


def mix(*tracks):
    n = max(len(t) for t in tracks)
    out = [0.0] * n
    for t in tracks:
        for i, v in enumerate(t):
            out[i] += v
    return out


def fade(s, fin=0.004, fout=0.03):
    s = list(s)
    a = int(fin * SR)
    b = int(fout * SR)
    for i in range(min(a, len(s))):
        s[i] *= i / a
    for i in range(min(b, len(s))):
        s[len(s) - 1 - i] *= i / b
    return s


# ---------------- 音色 ----------------
def metal(f0, dur=0.45, dec_scale=1.0):
    """铁盆撞击：非谐分音 + 瞬态噪声"""
    n = int(SR * dur)
    out = [0.0] * n
    partials = [
        (1.00, 1.00, 0.34),
        (2.76, 0.52, 0.20),
        (5.40, 0.30, 0.110),
        (8.93, 0.15, 0.062),
        (13.30, 0.06, 0.034),
    ]
    for ratio, amp, dec in partials:
        d = dec * dec_scale
        w = 2.0 * math.pi * f0 * ratio / SR
        k = math.exp(-1.0 / (d * SR))
        e = 1.0
        ph = random.uniform(0, 6.28)
        c = math.cos(ph)
        s = math.sin(ph)
        for i in range(n):
            out[i] += amp * s * e
            e *= k
            cs = c * math.cos(w) - s * math.sin(w)
            s = s * math.cos(w) + c * math.sin(w)
            c = cs
    m = min(n, int(0.011 * SR))
    k = math.exp(-1.0 / (0.0026 * SR))
    e = 1.0
    for i in range(m):
        out[i] += 0.55 * random.uniform(-1, 1) * e
        e *= k
    return fade(out)


def whoosh(dur=0.62):
    """盆被打飞：带通扫频噪声 + 金属下坠"""
    n = int(SR * dur)
    out = [0.0] * n
    y1 = y2 = 0.0
    r = 0.982
    for i in range(n):
        t = i / SR
        f = 240.0 + 2600.0 * min(1.0, t / dur) ** 1.7
        b = 2.0 * r * math.cos(2.0 * math.pi * f / SR)
        x = random.uniform(-1, 1)
        y = (1.0 - r) * x + b * y1 - r * r * y2
        y2, y1 = y1, y
        env = min(1.0, t / 0.025) * math.exp(-t / 0.26)
        out[i] = y * env * 26.0
    drop = chirp(1500.0, 520.0, dur, dec=0.17, vib=0.0)
    for i in range(n):
        out[i] += drop[i] * 0.9
    return fade(out)


def chirp(f0, f1, dur, dec=0.25, vib=0.0, vibhz=6.0):
    n = int(SR * dur)
    out = [0.0] * n
    ph = 0.0
    k = math.exp(-1.0 / (dec * SR))
    e = 1.0
    for i in range(n):
        t = i / SR
        u = min(1.0, t / dur)
        f = f0 + (f1 - f0) * (u ** 0.85)
        if vib > 0.0:
            f *= 1.0 + vib * math.sin(2.0 * math.pi * vibhz * t)
        ph += 2.0 * math.pi * f / SR
        out[i] = math.sin(ph) * e
        e *= k
    return fade(out)


def bell(f, dur=0.30, dec=0.16, harm=0.30):
    n = int(SR * dur)
    out = [0.0] * n
    w1 = 2.0 * math.pi * f / SR
    w2 = 2.0 * math.pi * f * 2.01 / SR
    k1 = math.exp(-1.0 / (dec * SR))
    k2 = math.exp(-1.0 / (dec * 0.45 * SR))
    e1 = e2 = 1.0
    for i in range(n):
        out[i] = math.sin(w1 * i) * e1 + harm * math.sin(w2 * i) * e2
        e1 *= k1
        e2 *= k2
    return fade(out)


def thud(dur=0.22):
    n = int(SR * dur)
    out = [0.0] * n
    ph = 0.0
    k = math.exp(-1.0 / (0.075 * SR))
    e = 1.0
    for i in range(n):
        t = i / SR
        f = 150.0 * math.exp(-t * 9.0) + 62.0
        ph += 2.0 * math.pi * f / SR
        out[i] = math.sin(ph) * e
        e *= k
    m = min(n, int(0.012 * SR))
    ke = math.exp(-1.0 / (0.002 * SR))
    ee = 1.0
    for i in range(m):
        out[i] += 0.4 * random.uniform(-1, 1) * ee
        ee *= ke
    return fade(out)


# ---------------- 具体音效 ----------------
def sfx_all():
    d = {}

    # 铁盆三档：越敲音越高
    d["sfx_hit1"] = metal(520.0, 0.45)
    d["sfx_hit2"] = metal(650.0, 0.42)
    d["sfx_hit3"] = mix(metal(790.0, 0.40), place(silence(0.12), thud(0.2), 0.0, 0.5))

    # 盆被打飞
    d["sfx_knock"] = whoosh(0.62)

    # 打晕：下滑 + 两下叮
    dz = chirp(880.0, 240.0, 0.60, dec=0.42, vib=0.045, vibhz=5.0)
    place(dz, bell(1568.0, 0.25, 0.10, 0.18), 0.02, 0.45)
    place(dz, bell(2093.0, 0.22, 0.09, 0.15), 0.26, 0.40)
    d["sfx_dizzy"] = dz

    # 漏敲/空点：闷响
    d["sfx_miss"] = thud(0.20)

    # 连击：上行三连
    cb = []
    for j, f in enumerate([784.0, 988.0, 1319.0]):
        place(cb, bell(f, 0.20, 0.075, 0.35), j * 0.075, 0.8)
    d["sfx_combo"] = cb

    # 开局：上行两音
    st = []
    place(st, bell(523.0, 0.35, 0.13, 0.35), 0.0, 0.85)
    place(st, bell(784.0, 0.55, 0.20, 0.40), 0.11, 0.85)
    d["sfx_start"] = st

    # 结束：下行三音
    ov = []
    for j, f in enumerate([784.0, 587.0, 392.0]):
        place(ov, bell(f, 0.45, 0.16, 0.30), j * 0.13, 0.8)
    d["sfx_over"] = ov

    return d


# ---------------- 背景音乐 ----------------
def mf(m):
    return 440.0 * (2.0 ** ((m - 69) / 12.0))


def bgm():
    """32 秒无缝循环：暖 pad + 零星琶音 + 海浪底噪"""
    bar = 8.0
    chords = [[57, 60, 64], [53, 57, 60], [48, 52, 55], [55, 59, 62]]
    total = bar * len(chords)
    n = int((total + 1.0) * SR)
    out = [0.0] * n

    # pad：每个和弦 4 个分音，慢起慢落
    for ci, ch in enumerate(chords):
        t0 = ci * bar
        for note in ch:
            f = mf(note)
            dur = bar * 1.05
            ln = int(dur * SR)
            off = int(t0 * SR)
            att = int(1.1 * SR)
            rel = int(1.6 * SR)
            w = 2.0 * math.pi * f / SR
            w2 = 2.0 * math.pi * f * 1.002 / SR
            for i in range(ln):
                if off + i >= n:
                    break
                e = 1.0
                if i < att:
                    e = i / att
                elif i > ln - rel:
                    e = max(0.0, (ln - i) / rel)
                v = math.sin(w * i) + 0.6 * math.sin(w2 * i) + 0.18 * math.sin(2 * w * i)
                out[off + i] += v * e * 0.11

    # 琶音：A 小调五声
    scale = [69, 72, 74, 76, 79, 81, 84]
    t = 0.0
    while t < total:
        f = mf(random.choice(scale))
        b = bell(f, 0.9, 0.33, 0.22)
        place(out, b, t, 0.075 + random.random() * 0.05)
        t += random.choice([0.5, 0.75, 1.0])

    # 海浪底噪（慢速起伏）
    y = 0.0
    for i in range(n):
        tt = i / SR
        x = random.uniform(-1, 1)
        y = y * 0.985 + x * 0.015
        lfo = 0.55 + 0.45 * math.sin(2.0 * math.pi * tt / 5.5)
        out[i] += y * 0.55 * lfo

    # 无缝循环：末尾 1 秒交叉淡入到开头
    m = int(1.0 * SR)
    N = n - m
    res = out[:N]
    for i in range(m):
        a = i / m
        res[i] = out[i] * a + out[N + i] * (1.0 - a)
    return res


# ---------------- 试听 ----------------
def preview(all_sfx, order):
    out = []
    for name in order:
        out.extend(all_sfx[name])
        out.extend(silence(0.45))
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    d = sfx_all()
    order = [
        "sfx_hit1", "sfx_hit2", "sfx_hit3", "sfx_knock",
        "sfx_dizzy", "sfx_miss", "sfx_combo", "sfx_start", "sfx_over",
    ]
    for name in order:
        s = d[name]
        write_wav(os.path.join(OUT, name + ".wav"), s, 0.85)
        print("%-12s %5.2fs  %6.1f KB" % (name, len(s) / SR, os.path.getsize(os.path.join(OUT, name + ".wav")) / 1024))

    b = bgm()
    p = os.path.join(OUT, "bgm.wav")
    write_wav(p, b, 0.72)
    print("%-12s %5.2fs  %6.1f KB" % ("bgm", len(b) / SR, os.path.getsize(p) / 1024))

    write_wav(PREVIEW, preview(d, order), 0.85)
    print("试听文件:", PREVIEW)


if __name__ == "__main__":
    main()
