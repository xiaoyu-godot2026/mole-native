# 云榜单后端（腾讯云开发 CloudBase）

2026-10 从 0 搭好并跑通。LeanCloud 国内版停止注册、Cloudflare workers.dev 在国内被墙，最终选腾讯云开发体验版（免费 6 个月，用超只停服不扣钱）。

## 线上信息
- 环境 ID：`mole-lb-d0g7v7rwd066f7da8`（上海，体验版，到期 2027-04-26）
- 云函数：`molenb`（Nodejs18.15）
- HTTP 访问服务路径：`/lb`
- 对外地址：
  `https://mole-lb-d0g7v7rwd066f7da8-1517019879.ap-shanghai.app.tcloudbase.com/lb`
- 数据库：文档型（NoSQL），集合 `scores`，字段 `mode/uid/name/score/diff/ts`
- 密钥：本机 `/root/.secrets/tcb.env`（SecretId/SecretKey，0600，不进仓库）

## 接口
| 用途 | 请求 |
|---|---|
| 健康检查 | `GET /lb?action=health` |
| 取榜 | `GET /lb?action=top&mode=rhythm&limit=20` |
| 查我的名次 | `GET /lb?action=rank&mode=rhythm&uid=xxxx` |
| 交成绩 | `POST /lb` body `{"action":"submit","mode":"rhythm","uid":"...","name":"...","score":123,"diff":"normal"}` |

`mode` 只有 `rhythm`（音游）/ `classic`（经典）；同一 uid 同一榜只保留最高分。

## 注意
- 浏览器直接打开会看到腾讯云「测试域名风险提醒」页，那只对浏览器导航生效；App 用 HttpURLConnection 请求直接拿到 JSON。
- 想彻底去掉得绑一个已备案域名，个人用没必要。
- 分数由客户端上报，可伪造，仅供娱乐。

## 重新部署
```bash
cd /root/mole-lb
npm install --no-audit --no-fund
. /root/.secrets/tcb.env
tcb login --apiKeyId "$TC_SECRET_ID" --apiKey "$TC_SECRET_KEY"
tcb fn deploy molenb --dir /root/mole-lb -e "$TC_ENV_ID" --runtime Nodejs18.15 --install-dependency true --force
tcb service create -p lb -f molenb -e "$TC_ENV_ID"
```
