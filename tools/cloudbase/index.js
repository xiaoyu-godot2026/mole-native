// 打鲸鲸 云榜单 - CloudBase HTTP 函数
// 入口: https://<env>.service.tcloudbase.com/lb?action=top&mode=rhythm
let cloudbase = null;
let loadErr = '';
try {
  cloudbase = require('@cloudbase/node-sdk');
} catch (e) {
  loadErr = String((e && e.message) || e);
}

const MODES = ['rhythm', 'classic'];
const DIFFS = ['easy', 'normal', 'hard'];
const MAX_SCORE = 300000;
const VERSION = '1.1';
// 省资源点 + 防刷：一局游戏不可能 6 秒内打完，同一 uid 6 秒内重复提交直接丢弃
const SUBMIT_GAP_MS = 6000;
const TOP_TTL_MS = 20000;    // 榜单缓存 20 秒
const TOTAL_TTL_MS = 60000;  // 参与人数缓存 60 秒
const cacheTop = {};    // mode|limit -> {t,total,list}
const cacheTotal = {};  // mode -> {t,n}
const lastHit = {};     // mode|uid -> {t,rank,best,total}

function dropTopCache(mode) {
  const pre = mode + '|';
  Object.keys(cacheTop).forEach(function (k) { if (k.indexOf(pre) === 0) delete cacheTop[k]; });
}

let db = null;
let cmd = null;
try {
  if (!cloudbase) throw new Error('sdk_missing: ' + loadErr);
  const app = cloudbase.init({ env: cloudbase.SYMBOL_CURRENT_ENV });
  db = app.database();
  cmd = db.command;
} catch (e) {
  db = null;
  loadErr = 'init_failed: ' + String((e && e.message) || e);
}

function resp(obj, code) {
  return {
    statusCode: code || 200,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'no-store'
    },
    body: JSON.stringify(obj)
  };
}

function bad(msg) {
  return resp({ ok: false, error: msg, v: VERSION }, 200);
}

function cleanName(n) {
  let s = String(n == null ? '' : n).replace(/[\u0000-\u001f\u007f]/g, '').trim();
  if (s.length === 0) s = '匿名玩家';
  if (s.length > 12) s = s.slice(0, 12);
  return s;
}

function toInt(v) {
  const n = Number(v);
  if (!isFinite(n)) return NaN;
  return Math.floor(n);
}

function parseParams(event) {
  const out = {};
  const q = event.queryStringParameters || event.queryString || {};
  if (q && typeof q === 'object') {
    Object.keys(q).forEach(function (k) { out[k] = q[k]; });
  }
  if (event.body) {
    let raw = event.body;
    try {
      if (event.isBase64Encoded) raw = Buffer.from(raw, 'base64').toString('utf8');
      const j = JSON.parse(raw);
      if (j && typeof j === 'object') {
        Object.keys(j).forEach(function (k) { out[k] = j[k]; });
      }
    } catch (e) { /* ignore */ }
  }
  return out;
}

exports.main = async (event) => {
  if (!db) return bad('db_unavailable | ' + loadErr);

  const p = parseParams(event || {});
  const action = String(p.action || 'top').toLowerCase();
  const mode = String(p.mode || 'rhythm').toLowerCase();
  const col = db.collection('scores');

  try {
    if (action === 'health') {
      const cnt = await col.count();
      return resp({ ok: true, v: VERSION, docs: cnt.total });
    }

    if (MODES.indexOf(mode) < 0) return bad('bad_mode');

    if (action === 'top') {
      let limit = toInt(p.limit);
      if (!isFinite(limit) || limit < 1) limit = 20;
      if (limit > 50) limit = 50;
      const key = mode + '|' + limit;
      const hit = cacheTop[key];
      if (hit && Date.now() - hit.t < TOP_TTL_MS) {
        return resp({ ok: true, v: VERSION, mode: mode, total: hit.total, top: hit.list, cached: 1 });
      }
      const tn = cacheTotal[mode];
      const total = (tn && Date.now() - tn.t < TOTAL_TTL_MS)
        ? tn.n
        : (await col.where({ mode: mode }).count()).total;
      cacheTotal[mode] = { t: Date.now(), n: total };
      const r = await col.where({ mode: mode }).orderBy('score', 'desc').limit(limit).get();
      const top = (r.data || []).map(function (d, i) {
        return { rank: i + 1, name: d.name || '匿名玩家', score: d.score || 0, diff: d.diff || 'normal' };
      });
      cacheTop[key] = { t: Date.now(), total: total, list: top };
      return resp({ ok: true, v: VERSION, mode: mode, total: total, top: top });
    }

    if (action === 'rank') {
      const uid = String(p.uid || '');
      if (!/^[A-Za-z0-9_-]{4,24}$/.test(uid)) return bad('bad_uid');
      const r = await col.where({ mode: mode, uid: uid }).limit(1).get();
      if (!r.data || r.data.length === 0) {
        const tn0 = cacheTotal[mode];
        const t0 = (tn0 && Date.now() - tn0.t < TOTAL_TTL_MS)
          ? tn0.n : (await col.where({ mode: mode }).count()).total;
        cacheTotal[mode] = { t: Date.now(), n: t0 };
        return resp({ ok: true, v: VERSION, mode: mode, found: false, total: t0 });
      }
      const mine = r.data[0];
      const better = (await col.where({ mode: mode, score: cmd.gt(mine.score || 0) }).count()).total;
      const tn = cacheTotal[mode];
      const total = (tn && Date.now() - tn.t < TOTAL_TTL_MS)
        ? tn.n : (await col.where({ mode: mode }).count()).total;
      cacheTotal[mode] = { t: Date.now(), n: total };
      return resp({
        ok: true, v: VERSION, mode: mode, found: true,
        rank: better + 1, score: mine.score || 0, name: mine.name || '匿名玩家',
        diff: mine.diff || 'normal', total: total
      });
    }

    if (action === 'submit') {
      const uid = String(p.uid || '');
      if (!/^[A-Za-z0-9_-]{4,24}$/.test(uid)) return bad('bad_uid');
      const score = toInt(p.score);
      if (!isFinite(score) || score < 0 || score > MAX_SCORE) return bad('bad_score');
      const diff = DIFFS.indexOf(String(p.diff || '').toLowerCase()) >= 0
        ? String(p.diff).toLowerCase() : 'normal';
      const name = cleanName(p.name);
      const now = Date.now();
      const hk = mode + '|' + uid;
      if (Object.keys(lastHit).length > 3000) {
        Object.keys(lastHit).forEach(function (k) { delete lastHit[k]; });
      }
      const prev = lastHit[hk];
      if (prev && now - prev.t < SUBMIT_GAP_MS && score <= prev.best) {
        // 6 秒内重复（或更差）的提交：直接丢弃，一次数据库都不碰
        return resp({
          ok: true, v: VERSION, mode: mode, cooldown: 1,
          rank: prev.rank, score: prev.best, isBest: false, total: prev.total || 0
        });
      }

      const r = await col.where({ mode: mode, uid: uid }).limit(1).get();
      let best = score;
      if (r.data && r.data.length > 0) {
        const old = r.data[0];
        const oldScore = old.score || 0;
        best = Math.max(oldScore, score);
        if (score > oldScore || name !== (old.name || '') || diff !== (old.diff || '')) {
          await col.doc(old._id).update({ name: name, score: best, diff: diff, ts: now });
          dropTopCache(mode);
        }
      } else {
        await col.add({ mode: mode, uid: uid, name: name, score: score, diff: diff, ts: now });
        dropTopCache(mode);
        delete cacheTotal[mode];
      }
      const rank = (await col.where({ mode: mode, score: cmd.gt(best) }).count()).total + 1;
      const tn = cacheTotal[mode];
      const total = (tn && now - tn.t < TOTAL_TTL_MS)
        ? tn.n : (await col.where({ mode: mode }).count()).total;
      cacheTotal[mode] = { t: now, n: total };
      lastHit[hk] = { t: now, rank: rank, best: best, total: total };
      return resp({
        ok: true, v: VERSION, mode: mode, rank: rank,
        score: best, isBest: score >= best, total: total
      });
    }

    return bad('bad_action');
  } catch (e) {
    return resp({ ok: false, error: 'server_error', detail: String((e && e.message) || e), v: VERSION }, 200);
  }
};
