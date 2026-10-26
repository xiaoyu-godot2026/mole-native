/* 打鲸鲸 · H5 版（浏览器直接玩，不需要安装）
 * 由 mole-native 原生版（Kotlin + Canvas）平移而来，逻辑与数值保持一致。
 * 素材：char_*.png / basin_*.png / sfx_*.wav / bgm.mp3
 */
'use strict';

/* ---------------- 常量（与原生版一致） ---------------- */
const COLS = 3, ROWS = 3, NC = COLS * ROWS;
const SCORE_SILVER = 10, SCORE_BLUE = 18, SCORE_GREEN = 14;
const DEATH_SHOW_MS = 340, DEATH_DIZZY_MS = 620;
const ROUND_MS = 30000;
const RISE_MS = 190, BACK_MS = 150;
const HEAD_C = 0.285, HEAD_LIFT = 0.640, RISE_TRAVEL = 0.95;
const LEAD_MS = 460, SETTLE_MS = 70;
const W_PERFECT = 85, W_GOOD = 165, MISS_PENALTY = 4, START_SKIP_MS = 1200;
const DIFF_NAMES = ['简单', '普通', '困难'];
const DIFF_MUL = [0.85, 1.0, 1.25];
const DIFF_SPAWN = [1.14, 1.0, 0.88];
const DIFF_ALIVE = [2, 3, 4];
const DIFF_BLUE = [0.60, 0.50, 0.35];
const DIFF_GREEN = [0.85, 0.80, 0.65];

const C_DEEP = 'rgb(11,47,73)';
const C_POOL = 'rgb(18,58,86)';
const C_INK = 'rgb(15,62,94)';
const C_INK_SOFT = 'rgb(74,122,150)';
const C_GOLD = 'rgb(255,201,60)';
const C_RED = 'rgb(242,96,84)';
const C_GREEN = 'rgb(66,196,134)';
const FONT = '"PingFang SC","Noto Sans SC","Microsoft YaHei",system-ui,sans-serif';

/* ---------------- 存储 ---------------- */
const store = {
  get(k, d) { try { const v = localStorage.getItem('mole_' + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem('mole_' + k, JSON.stringify(v)); } catch (e) { } }
};

/* ---------------- 状态 ---------------- */
function newCell() {
  return {
    active: false, state: 0, p: 0, hit0: 0, hitsTotal: 1, basinHp: 0, basinColor: 0,
    basinShakeAt: 0, face: 0, dieAt: 0, dizzySfx: false, retreatAt: 0, bornAt: 0,
    liveUntil: 0
  };
}

const S = {
  phase: 'READY',           // READY / PLAYING / OVER
  mode: 'RHYTHM',           // RHYTHM / CLASSIC
  cells: Array.from({ length: NC }, newCell),
  fx: [], basins: [],
  score: 0, combo: 0, maxCombo: 0,
  nowMs: 0, wall: 0, lastFrame: 0, startAt: 0, nextSpawnAt: 0,
  best: store.get('best', 0), bestClassic: store.get('bestC', 0),
  diff: store.get('diff', 1),
  best_local: store.get('best', 0),
  // 节奏
  map: null, analyzing: false, mapErr: '', chart: [], nextBeat: 0,
  judgeOffset: store.get('offset', 0),
  perfectCount: 0, goodCount: 0, missCount: 0, notesTotal: 0,
  judgeSum: 0, judgeCnt: 0,
  // 声音
  sfxVol: store.get('sfxVol', 0.20), bgmVol: store.get('bgmVol', 0.50),
  vibOn: store.get('vib', true), musicOn: store.get('musicOn', false), soundOn: store.get('soundOn', true),
  // 面板
  showSettings: false, showRank: false, paused: false,
  overNote: '', dragSlider: -1, customTrack: '', customMap: null,
};

/* ---------------- 资源 ---------------- */
const IMG = {};
const SFX = {};
const SFX_NAMES = ['hit1', 'hit2', 'hit3', 'knock', 'dizzy', 'miss', 'combo', 'start', 'over'];
const IMG_NAMES = ['char_happy', 'char_angry', 'char_cry', 'char_dizzy', 'basin_grey', 'basin_blue', 'basin_green'];
const FACES = () => [IMG.char_happy, IMG.char_angry, IMG.char_cry, IMG.char_dizzy];
const BASINS = () => [IMG.basin_grey, IMG.basin_blue, IMG.basin_green];

const bgm = new Audio();
bgm.loop = false;
bgm.preload = 'auto';
bgm.src = 'assets/bgm.mp3';

function loadImg(src) {
  return new Promise((res) => {
    const im = new Image();
    im.onload = () => res(im);
    im.onerror = () => res(null);
    im.src = src;
  });
}

async function loadAll() {
  await Promise.all(IMG_NAMES.map(async (n) => { IMG[n] = await loadImg('assets/' + n + '.png'); }));
  SFX_NAMES.forEach((n) => { const a = new Audio('assets/sfx_' + n + '.wav'); a.preload = 'auto'; SFX[n] = a; });
  layout();               // 图片到位后重新算一遍几何（角色高宽比要用到真实尺寸）
  document.getElementById('boot').style.display = 'none';
  prepareRhythm();
}

/* ---------------- 画布与布局 ---------------- */
const cv = document.getElementById('cv');
const ctx = cv.getContext('2d');
let W = 0, H = 0, DPR = 1;
const L = {};          // 布局常量
const bubbles = [];
for (let i = 0; i < 22; i++) bubbles.push([Math.random(), Math.random(), 0.4 + Math.random() * 0.9, 4 + Math.random() * 10]);
const gearR = { x: 0, y: 0, w: 0, h: 0 };
const btnMain = { x: 0, y: 0, w: 0, h: 0 }, btnSecond = { x: 0, y: 0, w: 0, h: 0 };
const btnBoard = { x: 0, y: 0, w: 0, h: 0 }, btnResume = { x: 0, y: 0, w: 0, h: 0 }, btnMenu = { x: 0, y: 0, w: 0, h: 0 };
const diffChip = [{}, {}, {}];
const pauseBtn = { x: 0, y: 0, w: 0, h: 0, on: false };
const sliders = [{}, {}, {}];   // 音效 / 音乐 / 偏移
const toggles = [{}, {}];       // 震动 / 经典音乐
const btnPick = {}, btnBuiltin = {}, btnCloseS = {}, btnCloseR = {}, rankTab = [{}, {}];

function resize() {
  DPR = Math.min(3, window.devicePixelRatio || 1);
  W = cv.clientWidth; H = cv.clientHeight;
  cv.width = Math.round(W * DPR); cv.height = Math.round(H * DPR);
  ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
  layout();
}
function layout() {
  L.hudH = H * 0.115;
  L.skyH = H * 0.235;
  L.cellW = W / COLS;
  const gTop = H * 0.315;
  const avail = H - gTop - H * 0.02;
  L.cellH = Math.min(avail / ROWS, L.cellW * 1.38);
  L.gridTop = gTop + (avail - L.cellH * ROWS) / 2;
  L.rx = L.cellW * 0.47;
  L.ry = L.cellW * 0.17;
  L.charW = L.cellW * 0.90;
  const im = IMG.char_happy;
  L.charH = im ? L.charW * im.height / im.width : L.charW * 0.94;
  L.cutOff = L.ry * 0.05;
  // 设置按钮（右上角）
  const gs = L.hudH * 0.30;
  gearR.w = gs; gearR.h = gs; gearR.x = W - 8 - gs; gearR.y = L.hudH * 0.04;
  layoutSettings();
}
const holeX = (i) => L.cellW * ((i % COLS) + 0.5);
const holeY = (i) => L.gridTop + L.cellH * (Math.floor(i / COLS) + 0.58);
const cutY = (i) => holeY(i) + L.cutOff;
const dLevel = () => Math.max(0, Math.min(2, S.diff));
const diffName = () => DIFF_NAMES[dLevel()];

/* ---------------- 小工具 ---------------- */
function easeOutBack(x) {
  const c1 = 1.70158, c3 = c1 + 1;
  const t = x - 1;
  return 1 + c3 * t * t * t + c1 * t * t;
}
function hash(k) {
  let x = Math.imul(k | 0, -1640531535) | 0;
  x ^= (x >>> 13);
  x = Math.imul(x, -2048144789) | 0;
  x ^= (x >>> 16);
  return ((x >>> 8) & 0xFFFF) / 65536;
}
function rr(x, y, w, h, r) {
  const rad = Math.max(0, Math.min(r, Math.min(w, h) / 2));
  ctx.beginPath();
  ctx.moveTo(x + rad, y);
  ctx.arcTo(x + w, y, x + w, y + h, rad);
  ctx.arcTo(x + w, y + h, x, y + h, rad);
  ctx.arcTo(x, y + h, x, y, rad);
  ctx.arcTo(x, y, x + w, y, rad);
  ctx.closePath();
}
function oval(cx, cy, rx, ry) { ctx.beginPath(); ctx.ellipse(cx, cy, Math.max(0.1, rx), Math.max(0.1, ry), 0, 0, Math.PI * 2); }
function text(str, x, y, size, color, align) {
  ctx.font = 'bold ' + size.toFixed(1) + 'px ' + FONT;
  ctx.fillStyle = color;
  ctx.textAlign = align || 'center';
  ctx.textBaseline = 'alphabetic';
  ctx.fillText(str, x, y);
}
function measure(str, size) { ctx.font = 'bold ' + size + 'px ' + FONT; return ctx.measureText(str).width; }

/* ---------------- 音效 ---------------- */
function playSfx(name, rate, vol) {
  if (!S.soundOn) return;
  if (S.mode === 'RHYTHM' && S.phase === 'PLAYING') return;   // 音游不放音效，会糊
  const base = SFX[name];
  if (!base) return;
  try {
    const a = base.cloneNode();
    a.volume = Math.max(0, Math.min(1, S.sfxVol * (vol == null ? 1 : vol)));
    a.playbackRate = rate || 1;
    a.play().catch(() => { });
  } catch (e) { }
}
function buzz(ms) { if (S.vibOn && navigator.vibrate) { try { navigator.vibrate(ms); } catch (e) { } } }

/* ---------------- 节拍分析（全本地，不上传） ---------------- */
async function analyzeBeat(url) {
  const res = await fetch(url);
  const buf = await res.arrayBuffer();
  const AC = window.AudioContext || window.webkitAudioContext;
  const ac = new AC();
  const audio = await ac.decodeAudioData(buf);
  await ac.close();
  const sr = audio.sampleRate;
  const ch0 = audio.getChannelData(0);
  const hop = 512;
  const n = Math.floor(ch0.length / hop);
  const env = new Float32Array(n);
  for (let t = 0; t < n; t++) {
    let s = 0;
    const st = t * hop;
    for (let k = 0; k < hop; k++) { const v = ch0[st + k] || 0; s += v * v; }
    env[t] = Math.sqrt(s / hop);
  }
  const nov = new Float32Array(n);
  for (let t = 1; t < n; t++) nov[t] = Math.max(0, Math.log(1 + env[t]) - Math.log(1 + env[t - 1]));
  let mean = 0;
  for (let t = 0; t < n; t++) mean += nov[t];
  mean /= Math.max(1, n);
  for (let t = 0; t < n; t++) nov[t] = Math.max(0, nov[t] - mean * 0.5);

  const fps = sr / hop;
  const lagMin = Math.max(8, Math.round(fps * 0.26));
  const lagMax = Math.min(Math.floor(n / 3), Math.round(fps * 1.50));
  let bestLag = lagMin, bestScore = -1;
  const scores = new Float64Array(lagMax + 2);
  for (let lag = lagMin; lag <= lagMax; lag++) {
    let s = 0;
    for (let t = 0; t + lag < n; t++) s += nov[t] * nov[t + lag];
    scores[lag] = s;
    if (s > bestScore) { bestScore = s; bestLag = lag; }
  }
  let lag = bestLag;
  const a1 = scores[lag - 1], b1 = scores[lag], c1 = scores[lag + 1];
  if (isFinite(a1) && isFinite(c1) && b1 > 0) {
    const den = (a1 - 2 * b1 + c1);
    if (Math.abs(den) > 1e-9) lag = lag + 0.5 * (a1 - c1) / den;
  }
  let bpm = 60 * fps / lag;
  while (bpm < 75) bpm *= 2;
  while (bpm > 180) bpm /= 2;
  const periodMs = 60000 / bpm;

  // 相位：在 0..lag 内找让拍点处 novelty 之和最大的偏移
  let bestPhase = 0, bestSum = -1;
  const step = Math.max(1, Math.round(lag / 64));
  for (let ph = 0; ph < lag; ph += step) {
    let s = 0, c = 0;
    for (let t = ph; t < n; t += lag) { s += nov[Math.round(t)]; c++; }
    s /= Math.max(1, c);
    if (s > bestSum) { bestSum = s; bestPhase = ph; }
  }
  const phaseMs = bestPhase / fps * 1000;
  const durationMs = audio.duration * 1000;
  const count = Math.max(0, Math.floor((durationMs - phaseMs) / periodMs));
  const energies = new Array(count).fill(128);
  const half = Math.max(1, Math.round(lag * 0.25));
  let mx = 1;
  for (let k = 0; k < count; k++) {
    const c = Math.round((phaseMs + k * periodMs) / 1000 * fps);
    let s = 0, m = 0;
    for (let j = Math.max(0, c - half); j < Math.min(n, c + half); j++) { s += nov[j]; m++; }
    energies[k] = m ? s / m : 0;
    if (energies[k] > mx) mx = energies[k];
  }
  for (let k = 0; k < count; k++) energies[k] = Math.min(255, Math.round(energies[k] / mx * 255));
  return { bpm, periodMs, phaseMs, durationMs, energies, count };
}

function fallbackMap(reason) {
  const durationMs = (bgm.duration && isFinite(bgm.duration)) ? bgm.duration * 1000 : 180000;
  const periodMs = 500, phaseMs = 0;
  const count = Math.floor(durationMs / periodMs);
  return { bpm: 120, periodMs, phaseMs, durationMs, energies: new Array(count).fill(128), count, fallback: true, err: reason };
}

async function prepareRhythm() {
  if (S.analyzing) return;
  S.analyzing = true;
  S.mapErr = '';
  const cached = store.get('beat_builtin', null);
  if (cached && cached.periodMs) {
    S.map = cached;
    S.analyzing = false;
    buildChart();
    return;
  }
  try {
    const m = await analyzeBeat('assets/bgm.mp3');
    S.map = m;
    store.set('beat_builtin', { bpm: m.bpm, periodMs: m.periodMs, phaseMs: m.phaseMs, durationMs: m.durationMs, energies: m.energies, count: m.count });
  } catch (e) {
    S.map = fallbackMap('分析失败');
    S.mapErr = '节拍分析失败，已按 120 BPM 兜底';
  }
  S.analyzing = false;
  buildChart();
}

/* ---------------- 谱面 ---------------- */
function buildChart() {
  const m = S.map;
  S.chart = [];
  if (!m) return;
  const busy = new Array(NC).fill(0);
  let k = 0;
  while (k < m.count && m.phaseMs + k * m.periodMs + S.judgeOffset < START_SKIP_MS) k++;
  let lastCol = -1;
  const lv = dLevel();
  const eArr = [];
  for (let i = 0; i < m.count; i++) eArr.push(m.energies[i] == null ? 128 : m.energies[i]);
  const sorted = eArr.slice().sort((a, b) => a - b);
  const med = sorted.length ? sorted[Math.floor(sorted.length / 2)] : 128;
  const cut = (lv === 0 ? 0.55 : lv === 1 ? 0.26 : 0.14) * med;
  while (k < m.count) {
    const e = eArr[k] == null ? 128 : eArr[k];
    const strong = e > 165, mid = e > 95;
    if (e < cut) { k++; continue; }
    let hits = 1;
    const hh = hash(k + 991);
    if (strong) {
      if (hh > (lv === 2 ? 0.80 : 0.88)) hits = 3;
      else if (hh > (lv === 2 ? 0.46 : 0.55)) hits = 2;
    } else if (mid) {
      if (hh > 0.80) hits = 2;
    } else if (hh > 0.93) hits = 2;
    const pc = S.chart.length;
    if (pc % 9 === 4) hits = 3;
    else if (pc % 4 === 2 && hits < 2) hits = 2;
    if (k + hits > m.count) hits = 1;
    if (hits > 1) {
      let run = 1;
      for (let j = 1; j < hits; j++) {
        if (k + j < m.count && eArr[k + j] >= cut) run++; else break;
      }
      hits = run;
    }
    const t = Math.round(m.phaseMs + k * m.periodMs + S.judgeOffset);
    const off = Math.min(NC - 1, Math.floor(hash(k + 37) * NC));
    let idx = -1;
    for (let j = 0; j < NC; j++) {
      const i = (off + j) % NC;
      if (busy[i] > t - LEAD_MS) continue;
      if (i % COLS === lastCol) continue;
      idx = i; break;
    }
    if (idx < 0) {
      for (let j = 0; j < NC; j++) {
        const i = (off + j) % NC;
        if (busy[i] > t - LEAD_MS) continue;
        idx = i; break;
      }
    }
    if (idx < 0) { k++; continue; }
    busy[idx] = t + ((hits - 1) * m.periodMs) + 1260;
    lastCol = idx % COLS;
    S.chart.push({ t, cell: idx, hits, color: hits === 3 ? 1 : hits === 2 ? 2 : 0 });
    k += hits;
  }
}

/* ---------------- 流程 ---------------- */
function start(mode) {
  S.mode = mode;
  S.phase = 'PLAYING';
  S.score = 0; S.combo = 0; S.maxCombo = 0;
  S.perfectCount = 0; S.goodCount = 0; S.missCount = 0; S.notesTotal = 0;
  S.judgeSum = 0; S.judgeCnt = 0;
  S.nextBeat = 0;
  S.fx = []; S.basins = [];
  S.cells = Array.from({ length: NC }, newCell);
  S.overNote = '';
  S.showSettings = false; S.showRank = false; S.paused = false;
  const now = performance.now();
  S.startAt = now; S.wall = now; S.startWall = now;
  S.nextSpawnAt = now + 420;
  if (mode === 'RHYTHM') {
    buildChart();
    S.nowMs = 0;
    try { bgm.currentTime = 0; } catch (e) { }
    bgm.volume = S.soundOn ? 1 : 0;
    bgm.play().catch(() => { });
    playSfx('start', 1, 0.8);
  } else {
    S.nowMs = now;
    bgm.pause();
    playSfx('start', 1, 0.9);
  }
  requestAnimationFrame(loop);
}

function gameOver() {
  if (S.phase === 'OVER') return;
  S.phase = 'OVER';
  if (S.mode === 'RHYTHM') {
    if (S.score > S.best) { S.best = S.score; store.set('best', S.best); }
    S.overNote = '本机最高 ' + S.best + ' 分';
  } else {
    if (S.score > S.bestClassic) { S.bestClassic = S.score; store.set('bestC', S.bestClassic); }
    S.overNote = '本机最高 ' + S.bestClassic + ' 分';
  }
  bgm.pause();
  playSfx('over', 1, 0.9);
}

function toMenu() {
  S.phase = 'READY';
  S.paused = false; S.showSettings = false; S.showRank = false;
  S.fx = []; S.basins = [];
  S.cells = Array.from({ length: NC }, newCell);
  bgm.pause();
  startMenuBgm();
}

function startMenuBgm() {
  if (!S.soundOn) return;
  if (S.phase !== 'READY') return;
  try {
    bgm.currentTime = 0;
    bgm.volume = Math.max(0, Math.min(1, S.bgmVol));
    bgm.play().catch(() => { });
  } catch (e) { }
}

function pauseGame() {
  if (S.paused) return;
  S.paused = true;
  bgm.pause();
}
function resumeGame() {
  S.paused = false;
  if (S.mode === 'RHYTHM') { bgm.volume = S.soundOn ? 1 : 0; bgm.play().catch(() => { }); }
  else if (S.musicOn && S.soundOn) bgm.play().catch(() => { });
  S.lastFrame = 0;
}

/* ---------------- 每帧推进 ---------------- */
function updateChartClock() {
  if (!bgm.paused && isFinite(bgm.currentTime)) {
    const t = bgm.currentTime * 1000;
    if (Math.abs(t - S.mapPos || 0) < 400) S.mapPos = t;
  }
}
function songPos() {
  if (CLOCK_WALL) return performance.now() - (S.startWall || 0);   // 自测用：不依赖音频自动播放
  return (bgm.currentTime || 0) * 1000;
}

function tickRhythm() {
  const m = S.map;
  if (!m) return;
  const now = S.nowMs;
  while (S.nextBeat < S.chart.length) {
    const n = S.chart[S.nextBeat];
    if (n.t - LEAD_MS > now) break;
    spawnNote(n);
    S.nextBeat++;
  }
  for (let i = 0; i < NC; i++) {
    const c = S.cells[i];
    if (!c.active) continue;
    if (c.retreatAt === 0) {
      const t0 = c.hit0 - LEAD_MS, t1 = c.hit0 - SETTLE_MS;
      if (now >= t1) c.p = 1;
      else if (now <= t0) c.p = 0;
      else c.p = easeOutBack(Math.max(0, Math.min(1, (now - t0) / (t1 - t0))));
      if (c.state === 0 && c.basinHp > 0 && now > nextJudge(c) + W_GOOD) { missNote(i); continue; }
      if (c.state === 3) {
        if (!c.dizzySfx && now - c.dieAt >= DEATH_SHOW_MS) { c.dizzySfx = true; playSfx('dizzy', 1, 0.8); }
        if (now - c.dieAt >= DEATH_SHOW_MS + DEATH_DIZZY_MS) c.retreatAt = now;
      }
    } else {
      const k = Math.max(0, Math.min(1, (now - c.retreatAt) / BACK_MS));
      c.p = 1 - k * k;
      if (now - c.retreatAt >= BACK_MS) {
        c.active = false; c.p = 0; c.retreatAt = 0; c.state = 0; c.dieAt = 0; c.dizzySfx = false; c.basinHp = 0;
      }
    }
  }
}

function tickClassic() {
  const now = S.nowMs;
  for (let i = 0; i < NC; i++) {
    const c = S.cells[i];
    if (!c.active) continue;
    if (c.state === 3 && !c.dizzySfx && now - c.dieAt >= DEATH_SHOW_MS) { c.dizzySfx = true; playSfx('dizzy', 1, 0.8); }
    if (c.retreatAt === 0 && now >= c.liveUntil) {
      c.retreatAt = now;
      if (c.state < 3) S.combo = 0;
    }
    if (c.retreatAt > 0) {
      const k = Math.max(0, Math.min(1, (now - c.retreatAt) / BACK_MS));
      c.p = 1 - k * k;
    } else {
      const k = Math.max(0, Math.min(1, (now - c.bornAt) / RISE_MS));
      c.p = easeOutBack(k);
    }
    if (c.retreatAt > 0 && now - c.retreatAt >= BACK_MS) {
      c.active = false; c.p = 0; c.retreatAt = 0; c.state = 0; c.dieAt = 0; c.dizzySfx = false; c.basinHp = 0;
    }
  }
  if (now >= S.nextSpawnAt) {
    spawnClassic();
    const iv = Math.max(420, 830 - S.score * 11) * DIFF_SPAWN[dLevel()];
    S.nextSpawnAt = now + iv + Math.random() * 180;
  }
}

function nextJudge(c) { return c.hit0 + (c.hitsTotal - c.basinHp) * (S.map ? S.map.periodMs : 500); }

function spawnNote(n) {
  const c = S.cells[n.cell];
  c.active = true; c.state = 0; c.face = 0; c.dieAt = 0; c.dizzySfx = false;
  c.basinShakeAt = 0; c.retreatAt = 0;
  c.basinHp = n.hits; c.hitsTotal = n.hits; c.hit0 = n.t; c.basinColor = n.color;
  c.bornAt = S.nowMs;
  c.liveUntil = n.t + (n.hits - 1) * (S.map ? S.map.periodMs : 500);
  c.p = 0;
  S.fx.push({ kind: 1, x: holeX(n.cell), y: holeY(n.cell), vx: 0, vy: 0, life: 480, t0: S.nowMs, text: null, color: 0, size: 0 });
}

function spawnClassic() {
  let count = 0;
  for (const c of S.cells) if (c.active) count++;
  if (count >= DIFF_ALIVE[dLevel()]) return;
  const free = [];
  for (let i = 0; i < NC; i++) if (!S.cells[i].active) free.push(i);
  if (!free.length) return;
  const i = free[Math.floor(Math.random() * free.length)];
  const c = S.cells[i];
  c.active = true; c.state = 0; c.bornAt = S.nowMs; c.retreatAt = 0;
  const roll = Math.random();
  const tb = DIFF_BLUE[dLevel()], tg = DIFF_GREEN[dLevel()];
  c.basinColor = roll < tb ? 0 : roll < tg ? 1 : 2;
  c.basinHp = c.basinColor === 1 ? 3 : c.basinColor === 2 ? 2 : 1;
  c.hitsTotal = c.basinHp;
  c.face = 0; c.dieAt = 0; c.dizzySfx = false; c.basinShakeAt = 0;
  c.liveUntil = S.nowMs + Math.max(900, 1500 - Math.min(S.score * 8, 520)) + (c.basinHp - 1) * 460;
  c.p = 0;
  S.fx.push({ kind: 1, x: holeX(i), y: holeY(i), vx: 0, vy: 0, life: 520, t0: S.nowMs, text: null, color: 0, size: 0 });
}

/* ---------------- 敲击 ---------------- */
function hitCell(i, tapAt) {
  const c = S.cells[i];
  if (c.state !== 0 || c.basinHp <= 0) return;
  const rawErr = tapAt - nextJudge(c);
  const perfect = Math.abs(rawErr) <= W_PERFECT;
  const mult = perfect ? 1.0 : 0.62;
  const base = c.basinColor === 1 ? SCORE_BLUE : c.basinColor === 2 ? SCORE_GREEN : SCORE_SILVER;
  const bonus = Math.min(Math.floor(S.combo / 10), 5);
  const g = Math.round(base * mult * DIFF_MUL[dLevel()]) + bonus;
  S.score += g; S.combo++;
  if (S.combo > S.maxCombo) S.maxCombo = S.combo;
  S.notesTotal++;
  if (perfect) S.perfectCount++; else S.goodCount++;
  S.judgeSum += rawErr; S.judgeCnt++;
  c.basinHp--; c.basinShakeAt = S.nowMs;
  c.face = Math.min(c.face + 1, 2);
  S.fx.push({
    kind: 0, x: holeX(i), y: cutY(i) - L.charH * 0.34, vx: 0, vy: 0, life: 560, t0: S.nowMs,
    text: (perfect ? 'Perfect +' : 'Good +') + g, color: perfect ? 'rgb(255,222,120)' : 'rgb(200,232,255)', size: L.charW * 0.17
  });
  S.fx.push({ kind: 1, x: holeX(i), y: cutY(i), vx: 0, vy: 0, life: 300, t0: S.nowMs, text: null, color: 0, size: 0 });
  const rate = perfect ? 1.0 : 0.84;
  if (c.basinHp > 0) {
    playSfx(c.basinHp >= 2 ? 'hit1' : 'hit2', rate);
    buzz(9);
  } else {
    playSfx('hit3', rate);
    knockOff(i);
  }
}

function knockOff(i) {
  const c = S.cells[i];
  const cx = holeX(i);
  const bw = L.charW * 0.78;
  const bmp = BASINS()[c.basinColor];
  const bh = bmp ? bw * bmp.height / bmp.width : bw * 0.4;
  const baseTop = cutY(i) - L.charH * (HEAD_LIFT + HEAD_C);
  const by = baseTop + L.charH * 0.37 - bh / 2;
  const dir = hash(i * 7 + 3) > 0.5 ? 1 : -1;
  S.basins.push({
    x: cx, y: by, vx: dir * (90 + hash(i + 11) * 130), vy: -(420 + hash(i + 17) * 160),
    rot: 0, rotv: dir * (300 + hash(i + 23) * 260), size: bw, color: c.basinColor
  });
  for (let j = 0; j < 3; j++) {
    S.fx.push({
      kind: 2, x: cx + (hash(i * 13 + j) - 0.5) * L.charW * 0.5, y: by,
      vx: (hash(i * 31 + j) - 0.5) * 80, vy: -(40 + hash(i * 41 + j) * 60),
      life: 380, t0: S.nowMs, text: null, color: 0, size: 3 + hash(i * 53 + j) * 3
    });
  }
  c.basinHp = 0; c.state = 3; c.dieAt = S.nowMs;
  playSfx('knock', 1, 0.7);
  buzz(22);
}

function missNote(i) {
  const c = S.cells[i];
  S.combo = 0; S.missCount++; S.notesTotal++;
  S.score = Math.max(0, S.score - MISS_PENALTY);
  S.fx.push({ kind: 0, x: holeX(i), y: cutY(i) - L.charH * 0.30, vx: 0, vy: 0, life: 620, t0: S.nowMs, text: 'Miss', color: C_RED, size: L.charW * 0.18 });
  c.state = 2; c.face = 2; c.basinHp = 0; c.retreatAt = S.nowMs + 130;
  playSfx('miss', 0.9, 0.8);
  buzz(6);
}

function missTap(x, y) {
  S.combo = 0;
  S.score = Math.max(0, S.score - MISS_PENALTY);
  S.fx.push({ kind: 0, x, y, vx: 0, vy: 0, life: 460, t0: S.nowMs, text: '-' + MISS_PENALTY, color: C_RED, size: L.charW * 0.17 });
  S.fx.push({ kind: 1, x, y, vx: 0, vy: 0, life: 300, t0: S.nowMs, text: null, color: 0, size: 0 });
  playSfx('miss', 0.85, 0.9);
  buzz(8);
}

function hitClassic(i) {
  const c = S.cells[i];
  const cx = holeX(i), cy = cutY(i);
  if (c.state >= 3) return;
  if (c.basinHp > 1) {
    c.basinHp--; c.face = Math.min(c.face + 1, 2); c.basinShakeAt = S.nowMs;
    c.liveUntil = Math.max(c.liveUntil, S.nowMs + 520);
    const g = gainClassic(c);
    S.fx.push({ kind: 0, x: cx, y: cy - L.charH * 0.36, vx: 0, vy: 0, life: 520, t0: S.nowMs, text: '+' + g, color: 'rgb(214,236,250)', size: L.charW * 0.20 });
    S.fx.push({ kind: 1, x: cx, y: cy - L.charH * 0.10, vx: 0, vy: 0, life: 320, t0: S.nowMs, text: null, color: 0, size: 0 });
    buzz(12);
    playSfx(c.basinHp >= 2 ? 'hit1' : 'hit2');
    return;
  }
  const bw = L.charW * 0.78;
  const bmp = BASINS()[c.basinColor];
  const bh = bmp ? bw * bmp.height / bmp.width : bw * 0.4;
  const baseTop = cutY(i) - L.charH * (HEAD_LIFT + HEAD_C) + (1 - c.p) * L.charH * RISE_TRAVEL;
  const by = baseTop + L.charH * 0.37 - bh / 2;
  const dir = Math.random() < 0.5 ? 1 : -1;
  S.basins.push({
    x: cx, y: by, vx: dir * (90 + Math.random() * 130), vy: -(420 + Math.random() * 160),
    rot: 0, rotv: dir * (300 + Math.random() * 260), size: bw, color: c.basinColor
  });
  for (let k = 0; k < 3; k++) {
    S.fx.push({
      kind: 2, x: cx + (Math.random() - 0.5) * L.charW * 0.5, y: by,
      vx: (Math.random() - 0.5) * 80, vy: -(40 + Math.random() * 60),
      life: 380, t0: S.nowMs, text: null, color: 0, size: 3 + Math.random() * 3
    });
  }
  const g = gainClassic(c);
  S.fx.push({ kind: 0, x: cx, y: cy - L.charH * 0.30, vx: 0, vy: 0, life: 700, t0: S.nowMs, text: '+' + g, color: 'rgb(255,214,102)', size: L.charW * 0.26 });
  S.fx.push({ kind: 1, x: cx, y: cy, vx: 0, vy: 0, life: 380, t0: S.nowMs, text: null, color: 0, size: 0 });
  c.basinHp = 0; c.state = 3; c.dieAt = S.nowMs;
  c.liveUntil = S.nowMs + DEATH_SHOW_MS + DEATH_DIZZY_MS;
  playSfx('hit3');
  playSfx('knock', 1, 0.7);
  buzz(26);
}

function gainClassic(c) {
  S.combo++;
  if (S.combo > S.maxCombo) S.maxCombo = S.combo;
  const base = c.basinColor === 1 ? 3 : c.basinColor === 2 ? 2 : 1;
  const g = base + Math.min(Math.floor(S.combo / 3), 3);
  S.score += g;
  return g;
}

function missClassic(x, y) {
  S.combo = 0;
  if (S.score > 0) {
    S.score--;
    S.fx.push({ kind: 0, x, y, vx: 0, vy: 0, life: 460, t0: S.nowMs, text: '-1', color: C_RED, size: L.charW * 0.20 });
  }
  S.fx.push({ kind: 1, x, y, vx: 0, vy: 0, life: 300, t0: S.nowMs, text: null, color: 0, size: 0 });
  playSfx('miss', 0.85, 0.9);
  buzz(8);
}

/* ---------------- 绘制 ---------------- */
function drawSea() {
  const g1 = ctx.createLinearGradient(0, 0, 0, Math.max(1, L.skyH));
  g1.addColorStop(0, 'rgb(233,247,255)');
  g1.addColorStop(1, 'rgb(191,230,248)');
  ctx.fillStyle = g1;
  ctx.fillRect(0, 0, W, L.skyH);

  const sun = ctx.createRadialGradient(W * 0.78, L.skyH * 0.42, 1, W * 0.78, L.skyH * 0.42, Math.max(2, W * 0.30));
  sun.addColorStop(0, 'rgba(255,243,201,0.40)');
  sun.addColorStop(1, 'rgba(255,243,201,0)');
  ctx.fillStyle = sun;
  ctx.fillRect(0, 0, W, L.skyH + 2);
  ctx.fillStyle = 'rgba(255,246,216,0.33)';
  oval(W * 0.78, L.skyH * 0.42, W * 0.085, W * 0.085); ctx.fill();

  ctx.fillStyle = 'rgba(255,255,255,0.47)';
  oval(W * 0.26, L.skyH * 0.43, W * 0.18, L.skyH * 0.11); ctx.fill();
  ctx.fillStyle = 'rgba(255,255,255,0.33)';
  oval(W * 0.27, L.skyH * 0.34, W * 0.11, L.skyH * 0.10); ctx.fill();
  oval(W * 0.75, L.skyH * 0.67, W * 0.13, L.skyH * 0.07); ctx.fill();

  const g2 = ctx.createLinearGradient(0, L.skyH, 0, Math.max(L.skyH + 1, H));
  g2.addColorStop(0, 'rgb(143,211,239)');
  g2.addColorStop(0.35, 'rgb(90,170,216)');
  g2.addColorStop(1, 'rgb(38,100,146)');
  ctx.fillStyle = g2;
  ctx.fillRect(0, L.skyH, W, H - L.skyH);

  ctx.fillStyle = 'rgba(234,249,255,0.8)';
  ctx.fillRect(0, L.skyH - 1.5, W, 3);

  const t = S.nowMs / 1000;
  for (let k = 0; k < 4; k++) {
    const y = L.skyH + (H - L.skyH) * (0.08 + k * 0.24);
    const a = Math.max(8, 26 - k * 4) / 255;
    ctx.strokeStyle = 'rgba(255,255,255,' + a.toFixed(3) + ')';
    ctx.lineWidth = 2 + k * 0.6;
    let x = -L.cellW + ((t * (14 + k * 8)) % L.cellW);
    ctx.beginPath();
    while (x < W + L.cellW) { ctx.moveTo(x, y); ctx.lineTo(x + L.cellW * 0.34, y); x += L.cellW * 0.86; }
    ctx.stroke();
  }
  const span = H - L.skyH;
  for (const b of bubbles) {
    const s2 = b[2];
    const yy = L.skyH + (((b[1] * span - t * 12 * s2) % span + span) % span);
    ctx.fillStyle = 'rgba(255,255,255,0.20)';
    oval(b[0] * W, yy, b[3] * s2 * 0.5, b[3] * s2 * 0.5); ctx.fill();
  }
}

function drawHoles() {
  for (let i = 0; i < NC; i++) {
    const cx = holeX(i), cy = holeY(i);
    ctx.fillStyle = 'rgba(0,26,46,0.13)';
    oval(cx, cy + 4, L.rx * 1.02, L.ry + 3); ctx.fill();
    ctx.fillStyle = C_POOL;
    oval(cx, cy, L.rx, L.ry); ctx.fill();
    ctx.fillStyle = C_DEEP;
    oval(cx, cy - L.ry * 0.03, L.rx * 0.94, L.ry * 0.83); ctx.fill();
    ctx.fillStyle = 'rgba(207,239,255,0.25)';
    oval(cx, cy + L.ry * 0.51, L.rx * 0.92, L.ry * 0.41); ctx.fill();
    ctx.strokeStyle = 'rgba(207,240,255,0.6)';
    ctx.lineWidth = 2.5;
    oval(cx, cy, L.rx - 1, L.ry - 1); ctx.stroke();
  }
  for (let i = 0; i < NC; i++) {
    const c = S.cells[i];
    if (c.active && c.p > 0.005) drawMole(i, c);
  }
  // 水面前沿
  for (let i = 0; i < NC; i++) {
    const cx = holeX(i), cy = holeY(i);
    ctx.save();
    ctx.beginPath();
    ctx.rect(cx - L.rx, cy, L.rx * 2, L.ry + 2);
    ctx.clip();
    ctx.fillStyle = C_POOL;
    oval(cx, cy, L.rx, L.ry); ctx.fill();
    ctx.fillStyle = 'rgba(207,239,255,0.25)';
    oval(cx, cy + L.ry * 0.51, L.rx * 0.92, L.ry * 0.41); ctx.fill();
    ctx.strokeStyle = 'rgba(207,240,255,0.6)';
    ctx.lineWidth = 2.5;
    oval(cx, cy, L.rx - 1, L.ry - 1); ctx.stroke();
    ctx.restore();
  }
}

function drawMole(i, c) {
  const cx = holeX(i), cut = cutY(i);
  const e = c.state === 3 ? S.nowMs - c.dieAt : 0;
  const dizzy = c.state === 3 && e >= DEATH_SHOW_MS;
  const bmp = FACES()[dizzy ? 3 : Math.max(0, Math.min(2, c.face))];
  if (!bmp) return;
  const w = L.charW;
  const h = w * bmp.height / bmp.width;
  const topY = cut - h * (HEAD_LIFT + HEAD_C) + (1 - c.p) * h * RISE_TRAVEL;

  ctx.save();
  if (dizzy) {
    const k = Math.max(0, Math.min(1, (e - DEATH_SHOW_MS) / DEATH_DIZZY_MS));
    ctx.translate(cx, cut - h * 0.30);
    ctx.rotate(Math.sin(k * 12.566) * 7 * Math.PI / 180);
    ctx.translate(-cx, -(cut - h * 0.30));
  }
  ctx.beginPath();
  ctx.rect(cx - w, 0, w * 2, cut);
  ctx.clip();
  ctx.drawImage(bmp, cx - w / 2, topY, w, h);

  if (c.state === 0) {
    const bb = BASINS()[c.basinColor];
    if (bb) {
      const bw = w * 0.78;
      const bh = bw * bb.height / bb.width;
      const by = topY + h * 0.37;
      const sk = (S.nowMs - c.basinShakeAt) / 260;
      if (sk < 1) {
        const amp = 1 - sk;
        ctx.save();
        ctx.translate(cx + Math.sin(S.nowMs / 21) * 6 * amp, by - bh / 2);
        ctx.rotate(Math.sin(S.nowMs / 15) * 7 * amp * Math.PI / 180);
        ctx.drawImage(bb, -bw / 2, -bh / 2, bw, bh);
        ctx.restore();
      } else {
        ctx.drawImage(bb, cx - bw / 2, by - bh, bw, bh);
      }
    }
  }
  ctx.restore();

  if (c.state === 0 && c.basinHp > 1) {
    const bb = BASINS()[c.basinColor];
    const bw = w * 0.78;
    const bh = bb ? bw * bb.height / bb.width : bw * 0.4;
    const by = topY + h * 0.37;
    const r = w * 0.115;
    const bx2 = cx + bw * 0.40, by2 = by - bh * 0.90;
    ctx.fillStyle = c.basinColor === 1 ? 'rgb(47,111,208)' : 'rgb(46,158,82)';
    oval(bx2, by2, r, r); ctx.fill();
    text(String(c.basinHp), bx2, by2 + r * 0.45, r * 1.30, '#fff', 'center');
  }
  ctx.fillStyle = 'rgba(220,244,255,0.33)';
  oval(cx, cut + L.ry * 0.03, w * 0.44, L.ry * 0.13); ctx.fill();
}

function drawFlyBasins() {
  for (const b of S.basins) {
    const bmp = BASINS()[b.color];
    if (!bmp) continue;
    const w = b.size, h = w * bmp.height / bmp.width;
    ctx.save();
    ctx.translate(b.x, b.y);
    ctx.rotate(b.rot * Math.PI / 180);
    ctx.drawImage(bmp, -w / 2, -h / 2, w, h);
    ctx.restore();
  }
}

function drawFx() {
  for (const f of S.fx) {
    const k = (S.nowMs - f.t0) / f.life;
    if (k < 0 || k > 1) continue;
    if (f.kind === 0) {
      const al = Math.max(0, Math.min(1, 1 - k));
      ctx.globalAlpha = al;
      text(f.text || '', f.x, f.y - 10 - k * 46, f.size, f.color, 'center');
      ctx.globalAlpha = 1;
    } else if (f.kind === 1) {
      const al = Math.max(0, Math.min(1, (1 - k) * 0.63));
      ctx.strokeStyle = 'rgba(220,245,255,' + al.toFixed(3) + ')';
      ctx.lineWidth = 3 * (1 - k) + 0.8;
      const r = L.rx * (0.5 + k * 0.9);
      oval(f.x, f.y, r, r * 0.36); ctx.stroke();
    } else if (f.kind === 2) {
      const al = Math.max(0, Math.min(1, (1 - k) * 0.86));
      ctx.fillStyle = 'rgba(226,247,255,' + al.toFixed(3) + ')';
      const r = f.size * (1 - k * 0.6);
      oval(f.x + f.vx * k, f.y + f.vy * k - 20 * k, r, r); ctx.fill();
    }
  }
}

const QS = new URLSearchParams(location.search);
const DEBUG = QS.get('debug') === '1';
const CLOCK_WALL = QS.get('clock') === 'wall';
function drawDebug() {
  const lines = [
    'now=' + Math.round(S.nowMs) + ' audio=' + (bgm.currentTime * 1000).toFixed(0) + (bgm.paused ? ' PAUSED' : ''),
    'chart=' + S.chart.length + ' nextBeat=' + S.nextBeat + ' count=' + (S.map ? S.map.count : -1),
    'bpm=' + (S.map ? S.map.bpm.toFixed(1) : '-') + ' per=' + (S.map ? S.map.periodMs.toFixed(0) : '-') + ' phase=' + (S.map ? S.map.phaseMs.toFixed(0) : '-'),
    'dur=' + (S.map ? Math.round(S.map.durationMs) : -1) + ' off=' + S.judgeOffset,
    't0=' + (S.chart[0] ? S.chart[0].t : -1) + ' tN=' + (S.chart[S.nextBeat] ? S.chart[S.nextBeat].t : -1),
  ];
  const act = S.cells.map((c, i) => c.active ? (i + ':' + c.state + ',' + c.basinHp + ',' + c.p.toFixed(2) + ',' + Math.round(c.hit0)) : '').filter(Boolean);
  lines.push('active=' + (act.length ? act.join(' ') : '-'));
  let y = 300;
  for (const l of lines) {
    ctx.font = 'bold 22px monospace';
    ctx.textAlign = 'left';
    ctx.fillStyle = 'rgba(0,0,0,0.65)';
    ctx.fillRect(10, y - 20, ctx.measureText(l).width + 12, 26);
    ctx.fillStyle = '#7CFC9A';
    ctx.fillText(l, 16, y);
    y += 28;
  }
}

function drawHud() {
  ctx.fillStyle = 'rgba(255,255,255,0.24)';
  rr(0, 0, W, L.hudH * 0.96, L.hudH * 0.28); ctx.fill();

  const scoreStr = String(S.score);
  const big = L.hudH * 0.44;
  text(scoreStr, W * 0.075, L.hudH * 0.58, big, C_INK, 'left');
  const sw = measure(scoreStr, big);
  text('分', W * 0.075 + sw + 5, L.hudH * 0.58, L.hudH * 0.18, C_INK_SOFT, 'left');

  const b0 = S.mode === 'RHYTHM' ? S.best : S.bestClassic;
  text('最高 ' + b0, W * 0.5, L.hudH * 0.31, L.hudH * 0.17, C_INK_SOFT, 'center');

  if (S.phase === 'PLAYING' && S.combo >= 2) {
    text('连击 ×' + S.combo, W * 0.5, L.hudH * 0.64, L.hudH * 0.23, C_GOLD, 'center');
  }

  if (S.phase === 'PLAYING') {
    if (S.mode === 'RHYTHM') {
      const acc = S.notesTotal > 0 ? Math.round(S.perfectCount * 100 / S.notesTotal) + '%' : '--';
      text(acc, W * 0.845, L.hudH * 0.44, L.hudH * 0.20, C_INK_SOFT, 'right');
    } else {
      const rem = Math.max(0, (ROUND_MS - (S.nowMs - S.startAt)) / 1000);
      text(rem.toFixed(1), W * 0.845, L.hudH * 0.44, L.hudH * 0.20, rem <= 5 ? C_RED : C_INK, 'right');
    }
  }

  const left = W * 0.075, right = W * 0.925;
  const ty = L.hudH * 0.76, th = L.hudH * 0.13;
  ctx.fillStyle = 'rgba(255,255,255,0.33)';
  rr(left, ty, right - left, th, th / 2); ctx.fill();
  let ratio = 0;
  if (S.phase === 'PLAYING') {
    ratio = S.mode === 'RHYTHM'
      ? Math.max(0, Math.min(1, S.nowMs / Math.max(1, S.map ? S.map.durationMs : 1)))
      : Math.max(0, Math.min(1, 1 - (S.nowMs - S.startAt) / ROUND_MS));
  } else if (S.phase === 'OVER') ratio = 1;
  if (ratio > 0) {
    ctx.fillStyle = (S.mode === 'CLASSIC' && ratio < 0.22) ? C_RED : C_GREEN;
    rr(left, ty, (right - left) * ratio, th, th / 2); ctx.fill();
  }

  // 暂停键
  pauseBtn.on = S.phase === 'PLAYING';
  if (pauseBtn.on) {
    const ps = L.hudH * 0.30, gap = 8;
    pauseBtn.w = ps; pauseBtn.h = ps;
    pauseBtn.x = gearR.x - gap - ps; pauseBtn.y = gearR.y;
    ctx.fillStyle = 'rgba(255,255,255,0.24)';
    rr(pauseBtn.x, pauseBtn.y, ps, ps, ps * 0.32); ctx.fill();
    ctx.fillStyle = C_INK;
    const bw = ps * 0.085, bh = ps * 0.36;
    const cx2 = pauseBtn.x + ps / 2, cy2 = pauseBtn.y + ps / 2;
    rr(cx2 - ps * 0.17, cy2 - bh, bw, bh * 2, bw / 2); ctx.fill();
    rr(cx2 + ps * 0.10, cy2 - bh, bw, bh * 2, bw / 2); ctx.fill();
  }
}

function drawChart() {
  const m = S.map;
  if (!m) return;
  const l = W * 0.055, r = W * 0.945;
  const top = L.hudH + 5;
  const h = Math.min((L.skyH - L.hudH) * 0.56, L.hudH * 0.74);
  if (h < 24) return;
  const laneH = h / 3;
  ctx.fillStyle = 'rgba(255,255,255,0.20)';
  rr(l, top, r - l, h, h * 0.26); ctx.fill();
  ctx.strokeStyle = 'rgba(255,255,255,0.18)';
  ctx.lineWidth = 1;
  for (let i = 1; i <= 2; i++) {
    const y = top + laneH * i;
    ctx.beginPath(); ctx.moveTo(l + 5, y); ctx.lineTo(r - 5, y); ctx.stroke();
  }
  const phx = l + (r - l) * 0.17;
  ctx.fillStyle = 'rgba(255,224,138,0.6)';
  ctx.fillRect(phx - 1.5, top + 3, 3, h - 6);

  const look = 2600;
  const pxms = (r - phx) / look;
  const period = m.periodMs;
  for (const n of S.chart) {
    const x0 = phx + (n.t - S.nowMs) * pxms;
    if (x0 < l - 40 || x0 > r + 26) continue;
    const x1 = phx + (n.t + (n.hits - 1) * period - S.nowMs) * pxms;
    const y = top + laneH * (n.cell + 0.5);
    const nh = laneH * 0.52;
    ctx.fillStyle = n.color === 1 ? 'rgb(96,170,240)' : n.color === 2 ? 'rgb(80,214,150)' : 'rgb(206,220,232)';
    const left = x0 - nh * 0.42;
    const right = Math.max(x1 + nh * 0.42, x0 + nh * 0.42);
    rr(left, y - nh / 2, right - left, nh, nh * 0.42); ctx.fill();
  }
  const nxt = S.nextBeat < S.chart.length ? S.chart[S.nextBeat].t : -1;
  if (S.nextBeat > 0 && nxt > 0 && nxt - S.nowMs > 1400) {
    text('间奏', W * 0.5, top + h * 0.66, h * 0.30, 'rgba(30,76,110,0.4)', 'center');
  }
}

function drawGear() {
  const gs = gearR.w;
  if (gs <= 1) return;
  ctx.fillStyle = 'rgba(9,30,46,0.40)';
  rr(gearR.x, gearR.y + gs * 0.07, gs, gs, gs * 0.30); ctx.fill();
  ctx.fillStyle = '#fff';
  rr(gearR.x, gearR.y, gs, gs, gs * 0.30); ctx.fill();
  ctx.strokeStyle = C_INK;
  ctx.lineWidth = Math.max(1.4, gs * 0.040);
  rr(gearR.x, gearR.y, gs, gs, gs * 0.30); ctx.stroke();

  const gx = gearR.x + gs / 2, gy = gearR.y + gs / 2 - gs * 0.04;
  const lw = gs * 0.36, step = gs * 0.145;
  ctx.strokeStyle = C_INK;
  ctx.lineWidth = Math.max(2, gs * 0.075);
  ctx.beginPath();
  for (let k = -1; k <= 1; k++) {
    const ly = gy + step * k;
    ctx.moveTo(gx - lw, ly); ctx.lineTo(gx + lw, ly);
  }
  ctx.stroke();
  ctx.fillStyle = C_INK;
  const knobs = [[-lw * 0.50, -step], [lw * 0.30, 0], [-lw * 0.05, step]];
  for (const kb of knobs) { oval(gx + kb[0], gy + kb[1], gs * 0.068, gs * 0.068); ctx.fill(); }

  const tw = gs * 1.10, th2 = gs * 0.42;
  ctx.fillStyle = '#fff';
  rr(gx - tw / 2, gearR.y + gs + gs * 0.10, tw, th2, th2 / 2); ctx.fill();
  text('设置', gx, gearR.y + gs + gs * 0.10 + th2 / 2 + th2 * 0.62 * 0.36, th2 * 0.62, C_INK, 'center');
}

function drawOverlay() {
  ctx.fillStyle = 'rgba(9,30,46,0.70)';
  ctx.fillRect(0, 0, W, H);

  const pw = W * 0.84;
  const ph = Math.min(H * 0.68, pw * 1.52);
  const pl = (W - pw) / 2, pt = (H - ph) / 2;

  ctx.fillStyle = 'rgba(0,0,0,0.40)';
  rr(pl, pt + 6, pw, ph, 28); ctx.fill();
  ctx.fillStyle = 'rgba(255,255,255,0.95)';
  rr(pl, pt, pw, ph, 28); ctx.fill();

  if (S.phase === 'READY') {
    text('打鲸鲸', W * 0.5, pt + ph * 0.145, pw * 0.105, C_INK, 'center');
    const m = S.map;
    if (m) {
      text('本曲 ' + Math.round(m.bpm) + ' BPM · ' + Math.round(m.durationMs / 1000) + ' 秒 · ' + m.count + ' 个拍点',
        W * 0.5, pt + ph * 0.235, pw * 0.040, C_INK_SOFT, 'center');
    } else {
      text(S.analyzing ? '正在分析音乐节拍…' : '音乐分析失败：' + S.mapErr, W * 0.5, pt + ph * 0.235, pw * 0.040, C_RED, 'center');
    }

    btnMain.x = W * 0.5 - pw * 0.36; btnMain.w = pw * 0.72;
    btnMain.y = pt + ph * 0.28; btnMain.h = ph * 0.10;
    ctx.fillStyle = S.map ? C_GOLD : 'rgb(191,203,214)';
    rr(btnMain.x, btnMain.y, btnMain.w, btnMain.h, btnMain.h / 2); ctx.fill();
    text(S.map ? '音游模式' : '分析中…', btnMain.x + btnMain.w / 2, btnMain.y + btnMain.h * 0.44, btnMain.h * 0.34, 'rgb(92,60,5)', 'center');
    text('看顶部谱面，鼓点到了就敲', btnMain.x + btnMain.w / 2, btnMain.y + btnMain.h * 0.80, btnMain.h * 0.21, 'rgb(92,60,5)', 'center');

    btnSecond.x = W * 0.5 - pw * 0.36; btnSecond.w = pw * 0.72;
    btnSecond.y = pt + ph * 0.405; btnSecond.h = ph * 0.10;
    ctx.fillStyle = 'rgba(30,76,110,0.09)';
    rr(btnSecond.x, btnSecond.y, btnSecond.w, btnSecond.h, btnSecond.h / 2); ctx.fill();
    text('经典模式', btnSecond.x + btnSecond.w / 2, btnSecond.y + btnSecond.h * 0.44, btnSecond.h * 0.34, C_INK, 'center');
    text('30 秒随便敲，没有音乐', btnSecond.x + btnSecond.w / 2, btnSecond.y + btnSecond.h * 0.80, btnSecond.h * 0.21, C_INK_SOFT, 'center');

    text('难度', W * 0.5, pt + ph * 0.556, pw * 0.040, C_INK_SOFT, 'center');
    const cw = pw * 0.245, cgap = pw * 0.030;
    const c0 = W * 0.5 - (cw * 3 + cgap * 2) / 2;
    for (let k = 0; k < 3; k++) {
      const ch = diffChip[k];
      ch.x = c0 + k * (cw + cgap); ch.y = pt + ph * 0.578; ch.w = cw; ch.h = ph * 0.06;
      const on = dLevel() === k;
      ctx.fillStyle = on ? C_INK : 'rgba(30,76,110,0.08)';
      rr(ch.x, ch.y, ch.w, ch.h, ch.h / 2); ctx.fill();
      text(DIFF_NAMES[k], ch.x + ch.w / 2, ch.y + ch.h / 2 + ch.h * 0.42 * 0.35, ch.h * 0.42, on ? '#fff' : C_INK, 'center');
    }

    btnBoard.x = W * 0.5 - pw * 0.30; btnBoard.w = pw * 0.60;
    btnBoard.y = pt + ph * 0.668; btnBoard.h = ph * 0.064;
    ctx.fillStyle = 'rgba(30,76,110,0.09)';
    rr(btnBoard.x, btnBoard.y, btnBoard.w, btnBoard.h, btnBoard.h / 2); ctx.fill();
    text('排行榜', btnBoard.x + btnBoard.w / 2, btnBoard.y + btnBoard.h / 2 + btnBoard.h * 0.40 * 0.35, btnBoard.h * 0.40, C_INK, 'center');

    text('银盆敲 1 下 · 绿盆连敲 2 下 · 蓝盆连敲 3 下', W * 0.5, pt + ph * 0.792, pw * 0.034, C_INK_SOFT, 'center');
    text('音游最高 ' + S.best + ' · 经典最高 ' + S.bestClassic, W * 0.5, pt + ph * 0.848, pw * 0.038, C_INK_SOFT, 'center');
    text('难度 ' + diffName() + ' · 网页版', W * 0.5, pt + ph * 0.905, pw * 0.032, C_INK_SOFT, 'center');
  } else {
    text(S.mode === 'RHYTHM' ? '完 成' : '时间到', W * 0.5, pt + ph * 0.14, pw * 0.090, C_INK, 'center');
    text(String(S.score), W * 0.5, pt + ph * 0.35, pw * 0.185, 'rgb(28,122,190)', 'center');
    if (S.mode === 'RHYTHM') {
      const acc = S.notesTotal > 0 ? Math.round(S.perfectCount * 100 / S.notesTotal) : 0;
      text('精准 ' + S.perfectCount + ' · 良好 ' + S.goodCount + ' · 漏 ' + S.missCount + ' · 准确率 ' + acc + '%',
        W * 0.5, pt + ph * 0.44, pw * 0.042, C_INK_SOFT, 'center');
      text('最大连击 ×' + S.maxCombo, W * 0.5, pt + ph * 0.50, pw * 0.042, C_INK_SOFT, 'center');
      if (S.judgeCnt >= 8) {
        const avg = Math.round(S.judgeSum / S.judgeCnt);
        text('判定平均偏差 ' + (avg >= 0 ? '+' : '') + avg + 'ms' + (Math.abs(avg) > 25 ? '（设置里可调偏移）' : ''),
          W * 0.5, pt + ph * 0.545, pw * 0.032, Math.abs(avg) > 25 ? C_RED : C_INK_SOFT, 'center');
      }
    } else {
      text('最大连击 ×' + S.maxCombo, W * 0.5, pt + ph * 0.47, pw * 0.042, C_INK_SOFT, 'center');
    }
    const b0 = S.mode === 'RHYTHM' ? S.best : S.bestClassic;
    text(S.score >= b0 && S.score > 0 ? '新纪录！' : '最高 ' + b0, W * 0.5, pt + ph * 0.58, pw * 0.046, C_INK, 'center');
    if (S.overNote) text(S.overNote, W * 0.5, pt + ph * 0.632, pw * 0.036, C_INK_SOFT, 'center');

    btnMain.x = W * 0.5 - pw * 0.36; btnMain.w = pw * 0.72;
    btnMain.y = pt + ph * 0.66; btnMain.h = ph * 0.15;
    ctx.fillStyle = C_GOLD;
    rr(btnMain.x, btnMain.y, btnMain.w, btnMain.h, btnMain.h / 2); ctx.fill();
    text('再来一局', btnMain.x + btnMain.w / 2, btnMain.y + btnMain.h * 0.65, btnMain.h * 0.42, 'rgb(92,60,5)', 'center');

    btnSecond.x = W * 0.5 - pw * 0.20; btnSecond.w = pw * 0.40;
    btnSecond.y = pt + ph * 0.86; btnSecond.h = ph * 0.08;
    text('回主界面', btnSecond.x + btnSecond.w / 2, btnSecond.y + btnSecond.h * 0.72, btnSecond.h * 0.62, C_INK_SOFT, 'center');
  }
}

function drawPause() {
  ctx.fillStyle = 'rgba(9,30,46,0.70)';
  ctx.fillRect(0, 0, W, H);
  const pw = W * 0.84, ph = H * 0.34;
  const pl = (W - pw) / 2, pt = (H - ph) / 2;
  ctx.fillStyle = 'rgba(0,0,0,0.40)';
  rr(pl, pt + 6, pw, ph, 26); ctx.fill();
  ctx.fillStyle = 'rgba(255,255,255,0.95)';
  rr(pl, pt, pw, ph, 26); ctx.fill();
  text('已暂停', W * 0.5, pt + ph * 0.22, pw * 0.095, C_INK, 'center');

  btnResume.x = pl + pw * 0.08; btnResume.w = pw * 0.84;
  btnResume.y = pt + ph * 0.31; btnResume.h = ph * 0.26;
  ctx.fillStyle = C_GOLD;
  rr(btnResume.x, btnResume.y, btnResume.w, btnResume.h, btnResume.h / 2); ctx.fill();
  text('继续', btnResume.x + btnResume.w / 2, btnResume.y + btnResume.h * 0.64, btnResume.h * 0.40, 'rgb(92,60,5)', 'center');

  btnMenu.x = pl + pw * 0.08; btnMenu.w = pw * 0.84;
  btnMenu.y = pt + ph * 0.63; btnMenu.h = ph * 0.26;
  ctx.fillStyle = 'rgba(30,76,110,0.09)';
  rr(btnMenu.x, btnMenu.y, btnMenu.w, btnMenu.h, btnMenu.h / 2); ctx.fill();
  text('回主界面', btnMenu.x + btnMenu.w / 2, btnMenu.y + btnMenu.h * 0.64, btnMenu.h * 0.40, C_INK, 'center');
}

/* ---------------- 设置面板 ---------------- */
function layoutSettings() {
  const pw = W * 0.86, ph = Math.min(H * 0.76, pw * 1.5);
  S.pw = pw; S.ph = ph; S.pl = (W - pw) / 2; S.pt = (H - ph) / 2;
  const x = S.pl + pw * 0.10, w = pw * 0.80;
  for (let i = 0; i < 3; i++) {
    sliders[i].x = x; sliders[i].w = w;
    sliders[i].y = S.pt + ph * (0.255 + i * 0.115); sliders[i].h = 8;
    sliders[i].knob = sliders[i].y + 4;
  }
  toggles[0].x = x; toggles[0].w = w * 0.46; toggles[0].y = S.pt + ph * 0.585; toggles[0].h = ph * 0.075;
  toggles[1].x = x + w * 0.54; toggles[1].w = w * 0.46; toggles[1].y = toggles[0].y; toggles[1].h = toggles[0].h;
  btnPick.x = x; btnPick.w = w * 0.62; btnPick.y = S.pt + ph * 0.685; btnPick.h = ph * 0.075;
  btnBuiltin.x = x + w * 0.66; btnBuiltin.w = w * 0.34; btnBuiltin.y = btnPick.y; btnBuiltin.h = btnPick.h;
  btnCloseS.x = W * 0.5 - pw * 0.30; btnCloseS.w = pw * 0.60;
  btnCloseS.y = S.pt + ph * 0.830; btnCloseS.h = ph * 0.10;
}

function drawSettings() {
  ctx.fillStyle = 'rgba(9,30,46,0.70)';
  ctx.fillRect(0, 0, W, H);
  const pw = S.pw, ph = S.ph, pl = S.pl, pt = S.pt;
  ctx.fillStyle = 'rgba(0,0,0,0.40)';
  rr(pl, pt + 6, pw, ph, 26); ctx.fill();
  ctx.fillStyle = 'rgba(255,255,255,0.96)';
  rr(pl, pt, pw, ph, 26); ctx.fill();

  text('设置', W * 0.5, pt + ph * 0.075, pw * 0.085, C_INK, 'center');
  text('判定偏移：敲早了往正调，敲晚了往负调', W * 0.5, pt + ph * 0.135, pw * 0.032, C_INK_SOFT, 'center');

  const labels = ['音效', '音乐', '判定偏移'];
  const vals = [Math.round(S.sfxVol * 100) + '%', Math.round(S.bgmVol * 100) + '%', (S.judgeOffset >= 0 ? '+' : '') + S.judgeOffset + 'ms'];
  for (let i = 0; i < 3; i++) {
    const s = sliders[i];
    text(labels[i], s.x, s.y - 12, pw * 0.040, C_INK, 'left');
    text(vals[i], s.x + s.w, s.y - 12, pw * 0.040, C_INK_SOFT, 'right');
    ctx.fillStyle = 'rgba(30,76,110,0.13)';
    rr(s.x, s.y, s.w, s.h, s.h / 2); ctx.fill();
    let ratio;
    if (i === 0) ratio = S.sfxVol;
    else if (i === 1) ratio = S.bgmVol;
    else ratio = (S.judgeOffset + 200) / 400;
    ctx.fillStyle = C_INK;
    rr(s.x, s.y, s.w * ratio, s.h, s.h / 2); ctx.fill();
    ctx.fillStyle = '#fff';
    ctx.strokeStyle = C_INK; ctx.lineWidth = 2;
    oval(s.x + s.w * ratio, s.y + s.h / 2, 10, 10); ctx.fill(); ctx.stroke();
  }

  const tg = [['震动', S.vibOn], ['经典放音乐', S.musicOn]];
  for (let i = 0; i < 2; i++) {
    const t = toggles[i];
    ctx.fillStyle = tg[i][1] ? C_INK : 'rgba(30,76,110,0.10)';
    rr(t.x, t.y, t.w, t.h, t.h / 2); ctx.fill();
    text(tg[i][0], t.x + t.w / 2, t.y + t.h / 2 + t.h * 0.32, t.h * 0.42, tg[i][1] ? '#fff' : C_INK, 'center');
  }

  ctx.fillStyle = 'rgba(30,76,110,0.10)';
  rr(btnPick.x, btnPick.y, btnPick.w, btnPick.h, btnPick.h / 2); ctx.fill();
  text('换一首音乐', btnPick.x + btnPick.w / 2, btnPick.y + btnPick.h / 2 + btnPick.h * 0.32, btnPick.h * 0.40, C_INK, 'center');
  rr(btnBuiltin.x, btnBuiltin.y, btnBuiltin.w, btnBuiltin.h, btnBuiltin.h / 2); ctx.fill();
  text('内置', btnBuiltin.x + btnBuiltin.w / 2, btnBuiltin.y + btnBuiltin.h / 2 + btnBuiltin.h * 0.32, btnBuiltin.h * 0.40, C_INK, 'center');

  ctx.fillStyle = C_GOLD;
  rr(btnCloseS.x, btnCloseS.y, btnCloseS.w, btnCloseS.h, btnCloseS.h / 2); ctx.fill();
  text('完成', btnCloseS.x + btnCloseS.w / 2, btnCloseS.y + btnCloseS.h * 0.66, btnCloseS.h * 0.40, 'rgb(92,60,5)', 'center');

  text('角色原型 ZipZipPipe · 美术/音乐 AI 生成 · 非商用', W * 0.5, pt + ph * 0.955, pw * 0.028, C_INK_SOFT, 'center');
}

/* ---------------- 排行榜（网页版 = 本机记录） ---------------- */
function drawRank() {
  ctx.fillStyle = 'rgba(9,30,46,0.70)';
  ctx.fillRect(0, 0, W, H);
  const pw = W * 0.86, ph = Math.min(H * 0.70, pw * 1.5);
  const pl = (W - pw) / 2, pt = (H - ph) / 2;
  ctx.fillStyle = 'rgba(0,0,0,0.40)';
  rr(pl, pt + 6, pw, ph, 26); ctx.fill();
  ctx.fillStyle = 'rgba(255,255,255,0.96)';
  rr(pl, pt, pw, ph, 26); ctx.fill();

  text('排行榜', W * 0.5, pt + ph * 0.10, pw * 0.08, C_INK, 'center');
  text('网页版：分数只存这台设备的浏览器里', W * 0.5, pt + ph * 0.165, pw * 0.033, C_INK_SOFT, 'center');

  const tabs = ['音游', '经典'];
  for (let i = 0; i < 2; i++) {
    const t = rankTab[i];
    t.x = W * 0.5 - pw * 0.32 + i * pw * 0.33; t.y = pt + ph * 0.21; t.w = pw * 0.31; t.h = ph * 0.07;
    const on = (i === 0) === (S.mode === 'RHYTHM') && S.rankTab === i;
    ctx.fillStyle = on ? C_INK : 'rgba(30,76,110,0.10)';
    rr(t.x, t.y, t.w, t.h, t.h / 2); ctx.fill();
    text(tabs[i], t.x + t.w / 2, t.y + t.h / 2 + t.h * 0.34, t.h * 0.42, on ? '#fff' : C_INK, 'center');
  }

  const rows = [
    ['1', '本机最高（音游）', String(S.best)],
    ['2', '本机最高（经典）', String(S.bestClassic)],
    ['3', '难度', diffName()],
  ];
  let y = pt + ph * 0.36;
  for (const row of rows) {
    ctx.fillStyle = 'rgba(30,76,110,0.06)';
    rr(pl + pw * 0.08, y - ph * 0.035, pw * 0.84, ph * 0.075, 12); ctx.fill();
    text(row[0], pl + pw * 0.14, y + ph * 0.012, pw * 0.042, C_INK_SOFT, 'center');
    text(row[1], pl + pw * 0.22, y + ph * 0.012, pw * 0.040, C_INK, 'left');
    text(row[2], pl + pw * 0.86, y + ph * 0.012, pw * 0.045, C_INK, 'right');
    y += ph * 0.10;
  }

  btnCloseR.x = W * 0.5 - pw * 0.30; btnCloseR.w = pw * 0.60;
  btnCloseR.y = pt + ph * 0.82; btnCloseR.h = ph * 0.10;
  ctx.fillStyle = C_GOLD;
  rr(btnCloseR.x, btnCloseR.y, btnCloseR.w, btnCloseR.h, btnCloseR.h / 2); ctx.fill();
  text('关闭', btnCloseR.x + btnCloseR.w / 2, btnCloseR.y + btnCloseR.h * 0.66, btnCloseR.h * 0.40, 'rgb(92,60,5)', 'center');
}

/* ---------------- 主循环 ---------------- */
function updateFlyBasins(dt) {
  const d = dt / 1000;
  for (let k = S.basins.length - 1; k >= 0; k--) {
    const b = S.basins[k];
    b.vy += 1500 * d;
    b.x += b.vx * d;
    b.y += b.vy * d;
    b.rot += b.rotv * d;
    if (b.y > H + b.size || b.x < -b.size * 2 || b.x > W + b.size * 2) S.basins.splice(k, 1);
  }
}
function updateFx() {
  for (let k = S.fx.length - 1; k >= 0; k--) {
    if (S.nowMs - S.fx[k].t0 > S.fx[k].life) S.fx.splice(k, 1);
  }
}

let running = false;
function loop(ts) {
  if (!running) { running = true; }
  const wall = performance.now();
  const dt = S.lastFrame === 0 ? 0 : Math.min(48, wall - S.lastFrame);
  S.lastFrame = wall;

  if (S.phase === 'PLAYING' && !S.showSettings && !S.paused) {
    if (S.mode === 'RHYTHM') {
      S.nowMs = songPos();
      const m = S.map;
      if (m && (S.nowMs >= m.durationMs || (bgm.ended && S.nextBeat >= S.chart.length))) gameOver();
      else tickRhythm();
    } else {
      S.nowMs = performance.now();
      tickClassic();
      if (S.nowMs - S.startAt >= ROUND_MS) gameOver();
    }
  } else {
    S.wall = performance.now();
    if (S.phase !== 'PLAYING') S.nowMs = S.wall;
  }

  updateFx();
  if (!S.showSettings && !S.paused) updateFlyBasins(dt);

  ctx.clearRect(0, 0, W, H);
  drawSea();
  if (S.mode === 'RHYTHM' && S.phase === 'PLAYING') drawChart();
  drawHoles();
  drawFx();
  drawFlyBasins();
  drawHud();
  if (S.phase !== 'PLAYING') drawOverlay();
  if (S.paused) drawPause();
  if (S.phase === 'PLAYING' && !S.showSettings && !S.paused) drawGear();
  if (S.showSettings) drawSettings();
  if (S.showRank) drawRank();
  if (DEBUG) drawDebug();

  requestAnimationFrame(loop);
}

/* ---------------- 输入 ---------------- */
function inRect(r, x, y) { return x >= r.x && x <= r.x + r.w && y >= r.y && y <= r.y + r.h; }

function hitBox(i, x, y) {
  const c = S.cells[i];
  if (!c.active || c.state !== 0 || c.basinHp <= 0 || c.p < 0.35) return false;
  const cx = holeX(i), cut = cutY(i);
  const topY = cut - L.charH * (HEAD_LIFT + HEAD_C);
  if (x < cx - L.charW * 0.47 || x > cx + L.charW * 0.47) return false;
  if (y < topY || y > cut + L.ry * 0.4) return false;
  return true;
}

function touchRhythm(x, y) {
  const t = S.nowMs;
  let bestIdx = -1, bestD = Infinity;
  for (let i = 0; i < NC; i++) {
    if (!hitBox(i, x, y)) continue;
    const d = Math.abs(t - nextJudge(S.cells[i]));
    if (d < bestD) { bestD = d; bestIdx = i; }
  }
  if (bestIdx >= 0 && bestD <= W_GOOD) hitCell(bestIdx, t); else missTap(x, y);
}
function touchClassic(x, y) {
  for (let i = 0; i < NC; i++) {
    if (hitBox(i, x, y)) { hitClassic(i); return; }
  }
  missClassic(x, y);
}

function onDown(x, y) {
  if (S.showRank) {
    const tabs = [0, 1];
    for (let i = 0; i < 2; i++) { if (inRect(rankTab[i], x, y)) { S.rankTab = i; return; } }
    if (inRect(btnCloseR, x, y)) S.showRank = false;
    return;
  }
  if (S.showSettings) { onSettingsDown(x, y); return; }
  if (S.paused) {
    if (inRect(btnResume, x, y)) resumeGame();
    else if (inRect(btnMenu, x, y)) toMenu();
    return;
  }
  if (S.phase === 'PLAYING' && S.mode === 'RHYTHM' && bgm.paused && !S.paused) bgm.play().catch(() => { });
  if (inRect(gearR, x, y)) {
    S.showSettings = true;
    layoutSettings();
    if (S.phase === 'PLAYING' && S.mode === 'RHYTHM') bgm.pause();
    return;
  }
  if (S.phase === 'PLAYING' && pauseBtn.on && inRect(pauseBtn, x, y)) { pauseGame(); return; }

  if (S.phase === 'READY') {
    for (let k = 0; k < 3; k++) {
      if (inRect(diffChip[k], x, y)) { S.diff = k; store.set('diff', k); buildChart(); return; }
    }
    if (inRect(btnBoard, x, y)) { S.showRank = true; S.rankTab = 0; return; }
    if (inRect(btnMain, x, y) && S.map) { start('RHYTHM'); return; }
    if (inRect(btnSecond, x, y)) { start('CLASSIC'); return; }
    return;
  }
  if (S.phase === 'OVER') {
    if (inRect(btnMain, x, y)) start(S.mode);
    else if (inRect(btnSecond, x, y)) toMenu();
    return;
  }
  if (S.mode === 'RHYTHM') touchRhythm(x, y); else touchClassic(x, y);
}

function onSettingsDown(x, y) {
  for (let i = 0; i < 3; i++) {
    const s = sliders[i];
    if (Math.abs(y - s.y - s.h / 2) < 22 && x >= s.x - 12 && x <= s.x + s.w + 12) {
      S.dragSlider = i; setSlider(i, x); return;
    }
  }
  if (inRect(toggles[0], x, y)) { S.vibOn = !S.vibOn; store.set('vib', S.vibOn); return; }
  if (inRect(toggles[1], x, y)) { S.musicOn = !S.musicOn; store.set('musicOn', S.musicOn); return; }
  if (inRect(btnBuiltin, x, y)) { S.customTrack = ''; bgm.src = 'assets/bgm.mp3'; S.map = null; prepareRhythm(); return; }
  if (inRect(btnPick, x, y)) { pickFile(); return; }
  if (inRect(btnCloseS, x, y)) { S.showSettings = false; S.dragSlider = -1; if (S.paused) { } return; }
}

function setSlider(i, x) {
  const s = sliders[i];
  const r = Math.max(0, Math.min(1, (x - s.x) / s.w));
  if (i === 0) { S.sfxVol = r; store.set('sfxVol', r); }
  else if (i === 1) { S.bgmVol = r; store.set('bgmVol', r); if (S.phase !== 'PLAYING') bgm.volume = r; }
  else { S.judgeOffset = Math.round(-200 + r * 400); store.set('offset', S.judgeOffset); if (S.map) buildChart(); }
}

function pickFile() {
  const inp = document.createElement('input');
  inp.type = 'file';
  inp.accept = 'audio/*';
  inp.onchange = async () => {
    const f = inp.files && inp.files[0];
    if (!f) return;
    S.customTrack = f.name;
    const url = URL.createObjectURL(f);
    bgm.src = url;
    S.map = null;
    S.analyzing = true;
    S.mapErr = '';
    try {
      S.map = await analyzeBeat(url);
      store.set('beat_custom', null);
      buildChart();
    } catch (e) {
      S.map = fallbackMap(String(e));
      S.mapErr = '分析失败，按 120 BPM 兜底';
      buildChart();
    }
    S.analyzing = false;
  };
  inp.click();
}

cv.addEventListener('pointerdown', (e) => {
  e.preventDefault();
  const r = cv.getBoundingClientRect();
  const x = e.clientX - r.left, y = e.clientY - r.top;
  if (!window.__fs && document.documentElement.requestFullscreen) {
    window.__fs = true;
    document.documentElement.requestFullscreen().catch(() => { });
  }
  if (S.phase === 'READY' && S.soundOn) startMenuBgm();
  onDown(x, y);
}, { passive: false });

cv.addEventListener('pointermove', (e) => {
  if (S.dragSlider < 0) return;
  const r = cv.getBoundingClientRect();
  setSlider(S.dragSlider, e.clientX - r.left);
});
cv.addEventListener('pointerup', () => { S.dragSlider = -1; });
cv.addEventListener('pointercancel', () => { S.dragSlider = -1; });

document.addEventListener('visibilitychange', () => {
  if (document.hidden && S.phase === 'PLAYING' && !S.paused) pauseGame();
});

window.addEventListener('resize', resize);
window.addEventListener('orientationchange', () => setTimeout(resize, 200));

resize();
loadAll();

/* 调试：?m=rhythm / ?m=classic 自动开局，?screen=settings|rank 直接开面板（自测用） */
(function bootParams() {
  const q = new URLSearchParams(location.search);
  const scr = q.get('screen');
  const m = q.get('m');
  if (!m && !scr) return;
  const wait = setInterval(() => {
    if (S.analyzing) return;
    if (!document.getElementById('boot') || document.getElementById('boot').style.display === 'none') {
      clearInterval(wait);
      if (scr === 'settings') { S.showSettings = true; layoutSettings(); }
      else if (scr === 'rank') { S.showRank = true; }
      else if (m === 'classic') start('CLASSIC');
      else if (m === 'rhythm' && S.map) start('RHYTHM');
    }
  }, 300);
})();
requestAnimationFrame(loop);
