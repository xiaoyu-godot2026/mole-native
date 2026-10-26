# art/ 资源管线

生成游戏用到的美术与音频。所有脚本都是**幂等**的：重跑会覆盖 `app/src/main/res/` 下的产物。

## 美术

| 文件 | 作用 |
|---|---|
| `canon.json` | 归一化基准：画布 613×617，头部质心 (305.35, 176.01) |
| `norm2.js` | 把绿幕原图抠图 + 按头部质心对齐，输出 613×617 的角色 PNG |
| `gen_assets.js` | 批量处理四张表情（happy/angry/cry/dizzy） |
| `trim_basin.js` | 铁盆抠图 + 裁边，输出 516×277 |
| `gen_icon.js` | 生成各密度启动图标 |

绿幕抠图参数（写死在脚本里，勿随手改）：

```
gn   = g - (r + b) / 2        绿度
alpha: gn < 26 → 0，gn > 62 → 1，中间线性
反预乘: ad = max(alpha, 0.34) → RGB /= ad
去绿边: fg = min(fg, (fr + fb) / 2 + 8)
```

角色图对齐规则：头部质心固定在同一位置，四种表情共用一份 `canon.json`，
保证切换表情时头不动（这是把表情贴在同一个身体上的关键）。

运行：

```bash
NODE_PATH=/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules node art/gen_assets.js
```

## 音频

`gen_audio.py` 是纯 Python 合成器，**没有任何第三方依赖**，直接跑：

```bash
python3 art/gen_audio.py                 # 只生成 9 个音效 + 试听文件
python3 art/gen_audio.py --synth-bgm     # 额外合成一个占位背景音乐 bgm.wav
```

- 采样率 22050 Hz、单声道、16 bit
- 频率 22.05k 下上限 11025 Hz，够用且体积小
- 音效设计目标：**尾巴 120ms 内收干净**。128 BPM 一拍只有 466ms，
  尾巴长了快速连打会糊成一团
- 用户提供的背景音乐不归这个脚本管：`res/raw/bgm.mp3` 是外部导入的

导出试听文件（不装 APK 也能听）：脚本会在仓库上层目录生成 `audio_preview.wav`。

## 推送

`push_files.py` 用 GitHub Git Data API 推送文件（本机 `git push` 走 HTTPS 会失败）：

```bash
python3 art/push_files.py <文件清单.txt> "提交信息"
```

清单文件里一行一个路径，相对仓库根目录。二进制文件走 base64，能推 png/mp3/wav。

## 目录结构（2026-10-26 整理）

| 路径 | 内容 | 能不能删 |
|---|---|---|
| `chibi/raw/` | AI 原始图 5 张 jpg | **不能**，唯一原始件 |
| `chibi/cut/` | 最终采用的一轮切图（01_pot ~ 05_happy） | 不能 |
| `chibi/bbox.json` | 切图包围盒 | 不能 |
| `ref/` | 参考图（face.png、deepseek-chan.jpg） | 不能 |
| `canon.json` | 归一化基准（画布 613×617、头部质心） | 不能 |
| `norm2.js` `gen_assets.js` `gen_icon.js` `trim_basin.js` `gen_audio.py` `push_files.py` | 可复现的生成/推送脚本，**幂等** | 不能 |
| `attempts/` | 历史中间产物：另两轮切图（`cut_v2/` `cut_v3/`）、预览图、mock、一次性脚本（`scripts/`） | 可以 |

`attempts/` 全是"当时试出来的东西"，留着当过程记录；删掉不影响任何成品，
因为成品由上面的脚本从 `chibi/raw/` 重新生成。
