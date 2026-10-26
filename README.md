# 打鲸鲸（mole-native）

用「鲸鱼娘」角色做的打地鼠 + 音游混合小游戏。原生 Android，Kotlin + Canvas 自绘，**零第三方依赖**。

- 包名：`com.xiaoyu.molenative`
- 当前版本：**3.5.2**（versionCode 20）
- 最低支持：Android 8.0（minSdk 26）
- 安装包：约 5.6 MB（CI 产物 5.63 MB）

## 两种模式

| | 音游模式 | 经典模式 |
|---|---|---|
| 时长 | 整首歌（当前内置曲 2:05） | 30 秒 |
| 出怪 | 由音乐节拍自动生成谱面 | 随机 |
| 判定 | Perfect ±85ms / Good ±165ms | 无判定，敲到就算 |
| 音效 | 关闭（避免干扰鼓点） | 开启 |
| 背景音乐 | 全程播放，就是本体 | 只在开始界面响 |

游戏开始时会自动分析当前背景音乐的节拍（BPM / 首拍位置 / 每拍强弱），生成贯穿全曲的谱面。
换任意本地音乐文件都能重新识别；识别失败会按 120 BPM 兜底。

## 目录结构

```
app/src/main/kotlin/com/xiaoyu/molenative/
├── MainActivity.kt      全屏沉浸、生命周期、选歌回调
├── GameView.kt          全部玩法与绘制（约 1965 行）
├── Audio.kt             SoundPool 音效 + MediaPlayer 背景音乐
├── Beat.kt              音频解码 → 节拍识别 → 拍点表
└── Diag.kt              每局诊断 / 崩溃记录 / 写系统剪贴板
app/src/main/res/
├── drawable-nodpi/      角色四表情 + 三种铁盆（无损 PNG）
└── raw/                 背景音乐 mp3 + 9 个合成音效 wav
art/                     美术/音频生成脚本与管线说明
docs/项目档案.md          状态、数值、角色设定、资产规格、代码地图（完整档案）
keystore/                固定签名（保证覆盖安装不丢存档）
```

## 构建

推 `main` 分支自动触发 GitHub Actions（`.github/workflows/build.yml`），约 1~2 分钟产出
`app-debug.apk`，从 Actions 的 artifact 下载即可。

本地构建需要 JDK 17 + Android SDK 34。

## 详细资料

角色设定、表情规格、抠图参数、全部玩法数值、代码地图、踩过的坑 —— 全部在
[docs/项目档案.md](docs/项目档案.md)。
