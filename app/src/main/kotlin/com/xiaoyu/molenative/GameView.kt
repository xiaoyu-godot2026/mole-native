package com.xiaoyu.molenative

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : View(context) {

    private val ctx: Context = context
    private val audio = GameAudio(context)

    private enum class Phase { READY, PLAYING, OVER }
    private enum class Mode { RHYTHM, CLASSIC }

    private class Cell {
        var active = false
        var state = 0          // 0 在场(有盆) / 2 漏了正在沉 / 3 被打晕
        var p = 0f             // 露出进度 0..1
        var hit0 = 0L          // 第一拍判定时刻（歌曲时间）
        var hitsTotal = 1      // 总共要敲几拍
        var basinHp = 0        // 还剩几拍没敲
        var basinColor = 0     // 0 银 / 1 蓝 / 2 绿
        var basinShakeAt = 0L
        var face = 0           // 0 开心 1 生气 2 哭 3 晕
        var dieAt = 0L
        var dizzySfx = false
        var retreatAt = 0L
        var bornAt = 0L
        var stateAt = 0L
        var lastHitAt = 0L
        var liveUntil = 0L
    }

    private class Fx(
        val kind: Int, var x: Float, var y: Float,
        val vx: Float, val vy: Float, val life: Long,
        var t0: Long, val text: String?, val color: Int, val size: Float
    )

    private class FlyBasin(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var rot: Float, val rotv: Float, val size: Float, val color: Int
    )

    private class Note(val t: Long, val cell: Int, val hits: Int, val color: Int)

    companion object {
        private const val COLS = 3
        private const val ROWS = 3
        private const val SCORE_SILVER = 10     // 银盆基础分（敲 1 拍）
        private const val SCORE_BLUE = 30       // 蓝盆基础分（连敲 3 拍）
        private const val SCORE_GREEN = 50      // 绿盆基础分（连敲 2 拍）
        private const val DEATH_SHOW_MS = 340L  // 盆飞走后先露出原表情
        private const val DEATH_DIZZY_MS = 620L // 晕 + 左右晃两下
        private const val NC = COLS * ROWS
        private const val ROUND_MS = 30_000L
        private const val PREFS = "mole"
        private const val RISE_MS = 190f
        private const val BACK_MS = 150f
        private const val HEAD_C = 0.285f      // 头心在画布中的位置
        private const val HEAD_LIFT = 0.640f   // 头心到水面
        private const val RISE_TRAVEL = 0.95f

        private const val LEAD_MS = 460L        // 提前多久冒头
        private const val SETTLE_MS = 70L       // 提前到位
        private const val W_PERFECT = 85L       // Perfect 窗口
        private const val W_GOOD = 165L         // Good 窗口
        private const val MISS_PENALTY = 4
        private const val START_SKIP_MS = 1200L
    }

    private val sp = resources.displayMetrics.density
    private fun dp(v: Float) = v * sp

    private val cDeep = Color.rgb(11, 47, 73)
    private val cPool = Color.rgb(18, 58, 86)
    private val cInk = Color.rgb(15, 62, 94)
    private val cInkSoft = Color.rgb(74, 122, 150)
    private val cGold = Color.rgb(255, 201, 60)
    private val cRed = Color.rgb(242, 96, 84)
    private val cGreen = Color.rgb(66, 196, 134)

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val pImg = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33002440 }

    private val tmp = RectF()
    private val tmp2 = RectF()
    private val clipPath = Path()
    private val mat = Matrix()

    private val bmpHappy = load(R.drawable.char_happy)
    private val bmpAngry = load(R.drawable.char_angry)
    private val bmpCry = load(R.drawable.char_cry)
    private val bmpDizzy = load(R.drawable.char_dizzy)
    // 0 银（1 拍） / 1 蓝（连打 3 拍） / 2 绿（连打 2 拍）
    private val bmpBasins = arrayOf(
        load(R.drawable.basin_grey),
        load(R.drawable.basin_blue),
        load(R.drawable.basin_green)
    )
    private val faces = arrayOf(bmpHappy, bmpAngry, bmpCry, bmpDizzy)

    private fun load(id: Int): Bitmap {
        val o = BitmapFactory.Options()
        o.inScaled = false
        return BitmapFactory.decodeResource(resources, id, o)
    }

    private var vw = 0f
    private var vh = 0f
    private var hudH = 0f
    private var skyH = 0f
    private var cellW = 0f
    private var cellH = 0f
    private var gridTop = 0f
    private var rx = 0f
    private var ry = 0f
    private var charW = 0f
    private var charH = 0f
    private var cutOff = 0f
    private var insetTop = 0f
    private var shadowShader: Shader? = null
    private var skyShader: Shader? = null
    private var seaShader: Shader? = null
    private var sunShader: Shader? = null

    private var phase = Phase.READY
    private val cells = Array(NC) { Cell() }
    private val fx = ArrayList<Fx>()
    private val basins = ArrayList<FlyBasin>()
    private var score = 0
    private var combo = 0
    private var best = 0
    private var startAt = 0L
    private var nextSpawnAt = 0L
    private var nowMs = 0L
    private var lastFrame = 0L
    private var btnRect = RectF()

    // ---------------- 节奏 ----------------
    private var map: BeatMap? = null
    private var analyzing = false
    private var mapErr = ""
    private var nextBeat = 0
    private var lastCell = -1
    private var judgeOffset = 0
    private var maxCombo = 0
    private var perfectCount = 0
    private var goodCount = 0
    private var missCount = 0
    private var notesTotal = 0
    private var lastSongPos = -1L
    private var gameStartAt = 0L
    private var mode = Mode.RHYTHM
    private var bestClassic = 0
    private val chart = ArrayList<Note>()
    private var btnRect2 = RectF()

    private val bubbles = Array(26) {
        floatArrayOf(
            Random.nextFloat(), Random.nextFloat(), 0.4f + Random.nextFloat() * 0.9f, 6f + Random.nextFloat() * 16f
        )
    }

    // 设置面板
    private var showSettings = false
    private var dragSld = -1
    private val gearR = RectF()
    private val panel = RectF()
    private val sldSfxTrack = RectF()
    private val sldBgmTrack = RectF()
    private val sldSfxHit = RectF()
    private val sldBgmHit = RectF()
    private val btnPickBgm = RectF()
    private val btnResetBgm = RectF()
    private val btnVib = RectF()
    private val btnMusic = RectF()
    private val btnCloseSet = RectF()
    private val sldOffTrack = RectF()
    private val sldOffHit = RectF()

    init {
        Diag.boot(ctx)
        best = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("best", 0)
        judgeOffset = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("judgeOffset", 0)
        bestClassic = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("bestC", 0)
        isClickable = true
        ensureMap()
        setOnApplyWindowInsetsListener { _, insets ->
            val t = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets.displayCutout?.safeInsetTop ?: 0
            } else {
                0
            }
            if (t != insetTop.toInt()) {
                insetTop = t.toFloat()
                layoutNow()
                invalidate()
            }
            insets
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutNow()
    }

    private fun layoutNow() {
        if (width < 8 || height < 8) return
        vw = width.toFloat(); vh = height.toFloat() - insetTop
        if (vw < 8f || vh < 8f) return
        hudH = vh * 0.115f
        skyH = vh * 0.235f
        cellW = vw / COLS
        val gTop = vh * 0.315f
        val avail = vh - gTop - vh * 0.02f
        cellH = min(avail / ROWS, cellW * 1.38f)
        gridTop = gTop + (avail - cellH * ROWS) / 2f
        rx = cellW * 0.47f
        ry = cellW * 0.17f
        charW = cellW * 0.90f
        charH = charW * bmpHappy.height / bmpHappy.width
        cutOff = ry * 0.05f

        try {
            skyShader = LinearGradient(
                0f, 0f, 0f, max(1f, skyH),
                intArrayOf(Color.rgb(233, 247, 255), Color.rgb(191, 230, 248)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            seaShader = LinearGradient(
                0f, skyH, 0f, max(skyH + 1f, vh),
                intArrayOf(Color.rgb(143, 211, 239), Color.rgb(90, 170, 216), Color.rgb(38, 100, 146)),
                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
            )
            sunShader = RadialGradient(
                vw * 0.78f, skyH * 0.42f, max(2f, vw * 0.30f),
                intArrayOf(0x66FFF3C9, 0x00FFF3C9), null, Shader.TileMode.CLAMP
            )
            shadowShader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(0x8C08253C.toInt(), 0x5508253C, 0x0008253C),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        } catch (t: Throwable) {
        }
    }

    private fun holeX(i: Int) = cellW * ((i % COLS) + 0.5f)
    private fun holeY(i: Int) = gridTop + cellH * ((i / COLS) + 0.58f)
    private fun cutY(i: Int) = holeY(i) + cutOff

    override fun onDraw(canvas: Canvas) {
        try {
            Diag.frame(System.nanoTime())
            drawFrame(canvas)
        } catch (t: Throwable) {
            try {
                pFill.shader = null
                pFill.color = Color.BLACK
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
                pText.color = Color.WHITE
                pText.textAlign = Paint.Align.LEFT
                pText.textSize = 22f
                var y = 60f
                for (line in android.util.Log.getStackTraceString(t).split("\n").take(22)) {
                    canvas.drawText(line.take(74), 16f, y, pText)
                    y += 28f
                }
            } catch (e: Throwable) {
            }
        }
    }

    private fun drawFrame(canvas: Canvas) {
        if (phase == Phase.PLAYING) {
            if (!showSettings) {
                if (mode == Mode.RHYTHM) {
                    nowMs = songPos()
                    val m = map
                    if (m != null && nowMs >= m.durationMs) gameOver() else tickRhythm()
                } else {
                    nowMs = System.currentTimeMillis()
                    tickClassic()
                    if (nowMs - startAt >= ROUND_MS) gameOver()
                }
            }
        } else {
            nowMs = System.currentTimeMillis()
        }
        val wall = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 0f else min(48L, wall - lastFrame).toFloat()
        lastFrame = wall

        updateFx()
        if (!showSettings) updateBasins(dt)

        canvas.save()
        canvas.translate(0f, insetTop)
        drawSea(canvas)
        if (mode == Mode.RHYTHM && phase == Phase.PLAYING) drawChart(canvas)
        drawBoard(canvas)
        drawFx(canvas)
        drawFlyBasins(canvas)
        drawHud(canvas)
        if (phase != Phase.PLAYING) drawOverlay(canvas)
        if (showSettings) drawSettings(canvas)
        canvas.restore()

        if (phase != Phase.READY) postInvalidateOnAnimation()
    }

    private fun drawSea(canvas: Canvas) {
        pFill.shader = skyShader
        canvas.drawRect(0f, -insetTop, vw, skyH, pFill)
        pFill.shader = sunShader
        canvas.drawCircle(vw * 0.78f, skyH * 0.42f, vw * 0.30f, pFill)
        pFill.shader = null

        pFill.color = 0x55FFF6D8
        canvas.drawCircle(vw * 0.78f, skyH * 0.42f, vw * 0.085f, pFill)

        pFill.color = 0x77FFFFFF
        canvas.drawOval(vw * 0.08f, skyH * 0.32f, vw * 0.44f, skyH * 0.54f, pFill)
        pFill.color = 0x55FFFFFF
        canvas.drawOval(vw * 0.16f, skyH * 0.24f, vw * 0.38f, skyH * 0.44f, pFill)
        canvas.drawOval(vw * 0.62f, skyH * 0.60f, vw * 0.88f, skyH * 0.74f, pFill)

        pFill.shader = seaShader
        canvas.drawRect(0f, skyH, vw, vh, pFill)
        pFill.shader = null

        pFill.color = 0xCCEAF9FF.toInt()
        canvas.drawRect(0f, skyH - dp(1.5f), vw, skyH + dp(1.5f), pFill)

        val t = nowMs / 1000f
        pStroke.style = Paint.Style.STROKE
        for (k in 0 until 4) {
            val y = skyH + (vh - skyH) * (0.08f + k * 0.24f)
            val a = (26 - k * 4).coerceAtLeast(8)
            pStroke.color = Color.argb(a, 255, 255, 255)
            pStroke.strokeWidth = dp(2f) + k * dp(0.6f)
            var x = -cellW + ((t * (14f + k * 8f)) % cellW)
            while (x < vw + cellW) {
                canvas.drawLine(x, y, x + cellW * 0.34f, y, pStroke)
                x += cellW * 0.86f
            }
        }
        pStroke.style = Paint.Style.FILL

        for (b in bubbles) {
            val s2 = b[2]
            val span = vh - skyH
            val yy = skyH + (((b[1] * span - t * 12f * s2) % span + span) % span)
            pFill.color = 0x33FFFFFF
            canvas.drawCircle(b[0] * vw, yy, b[3] * s2 * 0.5f, pFill)
        }
    }

    private fun drawBoard(canvas: Canvas) {
        for (i in 0 until NC) {
            val cx = holeX(i)
            val cy = holeY(i)

            pShadow.color = 0x22001A2E
            canvas.drawOval(cx - rx * 1.02f, cy - ry + dp(4f), cx + rx * 1.02f, cy + ry + dp(10f), pShadow)

            pFill.color = cPool
            canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, pFill)
            tmp.set(cx - rx * 0.94f, cy - ry * 0.86f, cx + rx * 0.94f, cy + ry * 0.80f)
            pFill.color = cDeep
            canvas.drawOval(tmp, pFill)

            pFill.color = 0x40CFEFFF
            tmp.set(cx - rx * 0.92f, cy + ry * 0.10f, cx + rx * 0.92f, cy + ry * 0.92f)
            canvas.drawOval(tmp, pFill)

            pStroke.style = Paint.Style.STROKE
            pStroke.color = 0x99CFF0FF.toInt()
            pStroke.strokeWidth = dp(2.5f)
            canvas.drawOval(cx - rx + dp(1f), cy - ry + dp(1f), cx + rx - dp(1f), cy + ry - dp(1f), pStroke)
            pStroke.style = Paint.Style.FILL
        }
        for (i in 0 until NC) {
            val c = cells[i]
            if (c.active && c.p > 0.005f) drawMole(canvas, i, c)
        }
        // 水面前沿：盖住人物的下半身，免得身体被直线切断、和洞口阴影叠得怪
        for (i in 0 until NC) {
            val cx = holeX(i)
            val cy = holeY(i)
            canvas.save()
            clipPath.reset()
            clipPath.addOval(cx - rx, cy - ry, cx + rx, cy + ry, Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.clipRect(cx - rx, cy, cx + rx, cy + ry + dp(2f))
            pFill.color = cPool
            canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, pFill)
            pFill.color = 0x40CFEFFF
            tmp.set(cx - rx * 0.92f, cy + ry * 0.10f, cx + rx * 0.92f, cy + ry * 0.92f)
            canvas.drawOval(tmp, pFill)
            pStroke.style = Paint.Style.STROKE
            pStroke.color = 0x99CFF0FF.toInt()
            pStroke.strokeWidth = dp(2.5f)
            canvas.drawOval(cx - rx + dp(1f), cy - ry + dp(1f), cx + rx - dp(1f), cy + ry - dp(1f), pStroke)
            pStroke.style = Paint.Style.FILL
            canvas.restore()
        }
    }

    private fun drawMole(canvas: Canvas, i: Int, c: Cell) {
        val cx = holeX(i)
        val cut = cutY(i)
        // 死亡三段：先露原表情 -> 到点才换晕并左右晃两下 -> 下沉
        val e = if (c.state == 3) nowMs - c.dieAt else 0L
        val dizzy = c.state == 3 && e >= DEATH_SHOW_MS
        val bmp = faces[if (dizzy) 3 else c.face.coerceIn(0, 2)]
        val w = charW
        val h = w * bmp.height / bmp.width
        val topY = cut - h * (HEAD_LIFT + HEAD_C) + (1f - c.p) * h * RISE_TRAVEL

        canvas.save()
        if (dizzy) {
            val k = ((e - DEATH_SHOW_MS).toFloat() / DEATH_DIZZY_MS).coerceIn(0f, 1f)
            canvas.rotate(sin(k * 12.566f) * 7f, cx, cut - h * 0.30f)
        }
        clipPath.reset()
        clipPath.addRect(cx - w, 0f, cx + w, cut, Path.Direction.CW)
        canvas.clipPath(clipPath)
        tmp2.set(cx - w / 2f, topY, cx + w / 2f, topY + h)
        canvas.drawBitmap(bmp, null, tmp2, pImg)

        if (c.state == 0) {
            val bb = bmpBasins[c.basinColor]
            val bw = w * 0.78f
            val bh = bw * bb.height / bb.width
            val by = topY + h * 0.37f
            val sk = (nowMs - c.basinShakeAt) / 260f
            if (sk < 1f) {
                val amp = 1f - sk
                canvas.save()
                canvas.translate(cx + sin(nowMs / 21f) * dp(6f) * amp, by - bh / 2f)
                canvas.rotate(sin(nowMs / 15f) * 7f * amp)
                tmp2.set(-bw / 2f, -bh / 2f, bw / 2f, bh / 2f)
                canvas.drawBitmap(bb, null, tmp2, pImg)
                canvas.restore()
            } else {
                tmp2.set(cx - bw / 2f, by - bh, cx + bw / 2f, by)
                canvas.drawBitmap(bb, null, tmp2, pImg)
            }
        }
        canvas.restore()

        pFill.color = 0x55DCF4FF
        canvas.drawOval(cx - w * 0.44f, cut - ry * 0.10f, cx + w * 0.44f, cut + ry * 0.16f, pFill)
    }

    private fun drawFlyBasins(canvas: Canvas) {
        for (b in basins) {
            val bmp = bmpBasins[b.color]
            val w = b.size
            val h = w * bmp.height / bmp.width
            canvas.save()
            canvas.rotate(b.rot, b.x, b.y)
            tmp2.set(b.x - w / 2f, b.y - h / 2f, b.x + w / 2f, b.y + h / 2f)
            canvas.drawBitmap(bmp, null, tmp2, pImg)
            canvas.restore()
        }
    }

    private fun drawFx(canvas: Canvas) {
        for (f in fx) {
            val k = (nowMs - f.t0).toFloat() / f.life
            if (k < 0f || k > 1f) continue
            when (f.kind) {
                0 -> {
                    pText.textAlign = Paint.Align.CENTER
                    pText.textSize = f.size
                    pText.style = Paint.Style.FILL
                    val al = ((1f - k) * 255).toInt().coerceIn(0, 255)
                    pText.color = Color.argb(al, Color.red(f.color), Color.green(f.color), Color.blue(f.color))
                    canvas.drawText(f.text ?: "", f.x, f.y - dp(10f) - k * dp(46f), pText)
                }
                1 -> {
                    pStroke.style = Paint.Style.STROKE
                    pStroke.color = Color.argb(((1f - k) * 160).toInt().coerceIn(0, 255), 220, 245, 255)
                    pStroke.strokeWidth = dp(3f) * (1f - k) + dp(0.8f)
                    val r = rx * (0.5f + k * 0.9f)
                    canvas.drawOval(f.x - r, f.y - r * 0.36f, f.x + r, f.y + r * 0.36f, pStroke)
                    pStroke.style = Paint.Style.FILL
                }
                2 -> {
                    pFill.color = Color.argb(((1f - k) * 220).toInt().coerceIn(0, 255), 226, 247, 255)
                    val r = f.size * (1f - k * 0.6f)
                    canvas.drawCircle(f.x + f.vx * k, f.y + f.vy * k - dp(20f) * k, r, pFill)
                }
                3 -> {
                    pText.textAlign = Paint.Align.CENTER
                    pText.textSize = f.size * (1f + k * 0.25f)
                    pText.style = Paint.Style.FILL
                    pText.color = Color.argb(((1f - k) * 255).toInt().coerceIn(0, 255), 255, 214, 102)
                    canvas.drawText(f.text ?: "", f.x, f.y - k * dp(30f), pText)
                }
            }
        }
    }

    private fun drawHud(canvas: Canvas) {
        pFill.shader = null
        pFill.color = 0x3DFFFFFF
        tmp.set(0f, 0f, vw, hudH * 0.96f)
        canvas.drawRoundRect(tmp, hudH * 0.28f, hudH * 0.28f, pFill)

        // 分数
        pText.textAlign = Paint.Align.LEFT
        pText.textSize = hudH * 0.44f
        pText.color = cInk
        pText.style = Paint.Style.FILL
        canvas.drawText(score.toString(), vw * 0.075f, hudH * 0.58f, pText)
        val sw = pText.measureText(score.toString())
        pText.textSize = hudH * 0.18f
        pText.color = cInkSoft
        canvas.drawText("分", vw * 0.075f + sw + dp(5f), hudH * 0.58f, pText)

        // 最高分
        pText.textAlign = Paint.Align.CENTER
        pText.textSize = hudH * 0.17f
        pText.color = cInkSoft
        val bx = if (mode == Mode.RHYTHM) best else bestClassic
        canvas.drawText("最高 $bx", vw * 0.5f, hudH * 0.31f, pText)

        // 连击
        if (phase == Phase.PLAYING && combo >= 2) {
            pText.textAlign = Paint.Align.CENTER
            pText.textSize = hudH * 0.23f
            pText.color = cGold
            canvas.drawText("连击 ×$combo", vw * 0.5f, hudH * 0.64f, pText)
        }

        // 右侧
        if (phase == Phase.PLAYING) {
            pText.textAlign = Paint.Align.RIGHT
            pText.textSize = hudH * 0.20f
            if (mode == Mode.RHYTHM) {
                pText.color = cInkSoft
                val acc = if (notesTotal > 0) (perfectCount * 100f / notesTotal).roundToInt().toString() + "%" else "--"
                canvas.drawText(acc, vw * 0.845f, hudH * 0.44f, pText)
            } else {
                val rem = ((ROUND_MS - (nowMs - startAt)) / 1000f).coerceAtLeast(0f)
                pText.color = if (rem <= 5f) cRed else cInk
                canvas.drawText(String.format("%.1f", rem), vw * 0.845f, hudH * 0.44f, pText)
            }
        }

        // 进度条
        val left = vw * 0.075f
        val right = vw * 0.925f
        val ty2 = hudH * 0.76f
        val th2 = hudH * 0.13f
        tmp.set(left, ty2, right, ty2 + th2)
        pFill.color = 0x55FFFFFF
        canvas.drawRoundRect(tmp, th2 / 2f, th2 / 2f, pFill)
        val ratio = when (phase) {
            Phase.PLAYING -> if (mode == Mode.RHYTHM) {
                val dur = (map?.durationMs ?: 1L).coerceAtLeast(1L)
                (nowMs.toFloat() / dur).coerceIn(0f, 1f)
            } else {
                (1f - (nowMs - startAt).toFloat() / ROUND_MS).coerceIn(0f, 1f)
            }
            Phase.OVER -> 1f
            else -> 0f
        }
        if (ratio > 0f) {
            pFill.color = if (mode == Mode.CLASSIC && ratio < 0.22f) cRed else cGreen
            tmp.set(left, ty2, left + (right - left) * ratio, ty2 + th2)
            canvas.drawRoundRect(tmp, th2 / 2f, th2 / 2f, pFill)
        }

        // 声音设置入口（右上角）
        val gs = hudH * 0.215f
        gearR.set(vw - dp(10f) - gs, hudH * 0.05f, vw - dp(10f), hudH * 0.05f + gs)
        pFill.color = 0x3DFFFFFF
        canvas.drawRoundRect(gearR, gs * 0.32f, gs * 0.32f, pFill)
        val gc = 0xD91E4C6E.toInt()
        pStroke.color = gc
        pStroke.strokeWidth = max(dp(1.8f), gs * 0.075f)
        pFill.color = gc
        val gx = gearR.centerX()
        val gy = gearR.centerY()
        val lw = gs * 0.40f
        val step = gs * 0.155f
        for (k in -1..1) {
            val ly = gy + step * k
            canvas.drawLine(gx - lw, ly, gx + lw, ly, pStroke)
        }
        canvas.drawCircle(gx - lw * 0.50f, gy - step, gs * 0.072f, pFill)
        canvas.drawCircle(gx + lw * 0.30f, gy, gs * 0.072f, pFill)
        canvas.drawCircle(gx - lw * 0.05f, gy + step, gs * 0.072f, pFill)
    }

    private fun layoutSettings() {
        val pw = vw * 0.86f
        val ph = min(pw * 1.32f, vh * 0.74f)
        panel.set((vw - pw) / 2f, (vh - ph) / 2f, (vw + pw) / 2f, (vh + ph) / 2f)

        val l = panel.left + pw * 0.07f
        val r = panel.right - pw * 0.07f
        val trackL = l + pw * 0.22f
        val trackR = r - pw * 0.22f

        val y1 = panel.top + ph * 0.215f
        val y2 = panel.top + ph * 0.305f
        val y3 = panel.top + ph * 0.395f
        sldSfxTrack.set(trackL, y1, trackR, y1)
        sldBgmTrack.set(trackL, y2, trackR, y2)
        sldOffTrack.set(trackL, y3, trackR, y3)
        val ht = ph * 0.032f
        sldSfxHit.set(trackL - ht, y1 - ht * 1.5f, trackR + ht, y1 + ht * 1.5f)
        sldBgmHit.set(trackL - ht, y2 - ht * 1.5f, trackR + ht, y2 + ht * 1.5f)
        sldOffHit.set(trackL - ht, y3 - ht * 1.5f, trackR + ht, y3 + ht * 1.5f)

        val bh = ph * 0.108f
        btnPickBgm.set(l, panel.top + ph * 0.535f, r, panel.top + ph * 0.535f + bh)
        val gap = pw * 0.04f
        val third = (r - l - gap * 2f) / 3f
        val by = panel.top + ph * 0.725f
        btnResetBgm.set(l, by, l + third, by + bh)
        btnVib.set(l + third + gap, by, l + third * 2f + gap, by + bh)
        btnMusic.set(l + (third + gap) * 2f, by, r, by + bh)
        btnCloseSet.set(l, panel.bottom - ph * 0.135f, r, panel.bottom - ph * 0.045f)
    }

    private fun drawSettings(canvas: Canvas) {
        layoutSettings()
        pFill.shader = null
        pFill.style = Paint.Style.FILL
        pFill.color = 0xB3000A14.toInt()
        canvas.drawRect(0f, -insetTop, vw, vh, pFill)

        pFill.color = 0xF4F6FAFD.toInt()
        canvas.drawRoundRect(panel, dp(20f), dp(20f), pFill)

        val ph = panel.height()
        val l = panel.left + panel.width() * 0.07f
        val r = panel.right - panel.width() * 0.07f

        pText.style = Paint.Style.FILL
        pText.textAlign = Paint.Align.CENTER
        pText.textSize = ph * 0.066f
        pText.color = cInk
        canvas.drawText("声音与判定", panel.centerX(), panel.top + ph * 0.105f, pText)

        val th = ph * 0.019f
        val knob = ph * 0.026f
        val labels = arrayOf("音效", "音乐", "判定偏移")
        val tracks = arrayOf(sldSfxTrack, sldBgmTrack, sldOffTrack)
        val vals = floatArrayOf(audio.sfxVol, audio.bgmVol, (judgeOffset + 200) / 400f)
        val txts = arrayOf(
            (audio.sfxVol * 100).roundToInt().toString() + "%",
            (audio.bgmVol * 100).roundToInt().toString() + "%",
            (if (judgeOffset >= 0) "+" else "") + judgeOffset + "ms"
        )
        for (k in 0..2) {
            val tr = tracks[k]
            val v = vals[k].coerceIn(0f, 1f)
            pText.textAlign = Paint.Align.LEFT
            pText.textSize = ph * 0.040f
            pText.color = cInk
            canvas.drawText(labels[k], l, tr.centerY() + ph * 0.014f, pText)

            pText.textAlign = Paint.Align.RIGHT
            pText.textSize = ph * 0.036f
            pText.color = cInkSoft
            canvas.drawText(txts[k], r, tr.centerY() + ph * 0.013f, pText)

            tmp.set(tr.left, tr.centerY() - th / 2f, tr.right, tr.centerY() + th / 2f)
            pFill.color = 0x241E4C6E
            canvas.drawRoundRect(tmp, th / 2f, th / 2f, pFill)
            val kx = tr.left + tr.width() * v
            if (kx > tr.left + 1f) {
                tmp.set(tr.left, tr.centerY() - th / 2f, kx, tr.centerY() + th / 2f)
                pFill.color = cInk
                canvas.drawRoundRect(tmp, th / 2f, th / 2f, pFill)
            }
            pFill.color = Color.WHITE
            canvas.drawCircle(kx, tr.centerY(), knob, pFill)
            pStroke.color = cInk
            pStroke.strokeWidth = max(dp(1.7f), ph * 0.006f)
            canvas.drawCircle(kx, tr.centerY(), knob, pStroke)
        }

        button(canvas, btnPickBgm, "更换背景音乐", false)
        pText.textAlign = Paint.Align.CENTER
        pText.textSize = ph * 0.036f
        pText.color = cInkSoft
        val nm = audio.bgmName
        canvas.drawText("当前：" + if (nm.length > 16) nm.substring(0, 16) + "…" else nm,
            panel.centerX(), panel.top + ph * 0.672f, pText)
        pText.textSize = ph * 0.030f
        canvas.drawText("判定偏移：敲早了往正调，敲晚了往负调", panel.centerX(), panel.top + ph * 0.468f, pText)

        button(canvas, btnResetBgm, "内置", !audio.bgmCustom, 0.40f)
        button(canvas, btnVib, if (audio.vibOn) "震动 开" else "震动 关", audio.vibOn, 0.36f)
        button(canvas, btnMusic, if (audio.musicOn) "音乐 开" else "音乐 关", audio.musicOn, 0.36f)
        button(canvas, btnCloseSet, "完成", true)
    }

    private fun button(canvas: Canvas, rect: RectF, label: String, solid: Boolean, ts: Float = 0.46f) {
        pFill.style = Paint.Style.FILL
        pFill.color = if (solid) cInk else 0x181E4C6E
        canvas.drawRoundRect(rect, rect.height() * 0.32f, rect.height() * 0.32f, pFill)
        pText.textAlign = Paint.Align.CENTER
        pText.style = Paint.Style.FILL
        pText.textSize = rect.height() * ts
        pText.color = if (solid) Color.WHITE else cInk
        canvas.drawText(label, rect.centerX(), rect.centerY() + pText.textSize * 0.35f, pText)
    }

    private fun touchSettings(e: MotionEvent, x: Float, y: Float) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                when {
                    sldSfxHit.contains(x, y) -> {
                        dragSld = 0
                        setSld(0, x)
                    }
                    sldBgmHit.contains(x, y) -> {
                        dragSld = 1
                        setSld(1, x)
                    }
                    sldOffHit.contains(x, y) -> {
                        dragSld = 2
                        setSld(2, x)
                    }
                    btnPickBgm.contains(x, y) -> {
                        dragSld = -1
                        pickBgm()
                    }
                    btnResetBgm.contains(x, y) -> {
                        dragSld = -1
                        audio.clearBgm()
                        refreshMap()
                    }
                    btnVib.contains(x, y) -> {
                        dragSld = -1
                        audio.vibOn = !audio.vibOn
                        audio.saveVib()
                        if (audio.vibOn) buzz(20)
                    }
                    btnMusic.contains(x, y) -> {
                        dragSld = -1
                        audio.musicOn = !audio.musicOn
                        if (phase == Phase.PLAYING && mode == Mode.RHYTHM) {
                            // 音游里曲子是游戏本体，不让开关插手
                        } else if (audio.musicOn && phase != Phase.OVER) {
                            audio.startBgm()
                        } else {
                            audio.pauseBgm()
                        }
                    }
                    btnCloseSet.contains(x, y) -> closeSettings()
                    !panel.contains(x, y) -> closeSettings()
                    else -> dragSld = -1
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragSld >= 0) {
                    setSld(dragSld, x)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragSld == 0) audio.play(GameAudio.HIT2, 1f, 0.75f)
                if (dragSld >= 0) audio.saveVolumes()
                if (dragSld == 2) saveOffset()
                dragSld = -1
                invalidate()
            }
        }
    }

    private fun setSld(which: Int, x: Float) {
        val t = when (which) {
            0 -> sldSfxTrack
            1 -> sldBgmTrack
            else -> sldOffTrack
        }
        val v = ((x - t.left) / max(1f, t.width())).coerceIn(0f, 1f)
        when (which) {
            0 -> audio.sfxVol = v
            1 -> audio.bgmVol = v
            else -> judgeOffset = (v * 400f - 200f).roundToInt()
        }
    }

    private fun saveOffset() {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("judgeOffset", judgeOffset).apply()
        } catch (t: Throwable) {
        }
    }

    private fun closeSettings() {
        showSettings = false
        dragSld = -1
        audio.saveVolumes()
        saveOffset()
        bgmPolicy()
        lastFrame = 0L
    }

    /** 背景音乐策略：开始界面与音游永远有音乐；经典对局看开关；结算静音 */
    private fun bgmPolicy() {
        val on = when {
            phase == Phase.READY -> true
            phase == Phase.OVER -> false
            mode == Mode.RHYTHM -> true
            else -> audio.musicOn
        }
        if (on) audio.resumeBgm() else audio.pauseBgm()
    }

    private fun pickBgm() {
        val act = ctx as? Activity ?: return
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            act.startActivityForResult(i, MainActivity.REQ_BGM)
        } catch (t: Throwable) {
        }
    }

    private fun drawChart(canvas: Canvas) {
        val m = map ?: return
        val l = vw * 0.055f
        val r = vw * 0.945f
        val top = hudH + dp(5f)
        val h = min((skyH - hudH) * 0.56f, hudH * 0.74f)
        if (h < dp(24f)) return
        val laneH = h / 3f

        pFill.shader = null
        pFill.style = Paint.Style.FILL
        pFill.color = 0x33FFFFFF
        tmp.set(l, top, r, top + h)
        canvas.drawRoundRect(tmp, h * 0.26f, h * 0.26f, pFill)

        pStroke.style = Paint.Style.STROKE
        pStroke.color = 0x2EFFFFFF
        pStroke.strokeWidth = max(1f, dp(1f))
        for (i in 1..2) {
            val y = top + laneH * i
            canvas.drawLine(l + dp(5f), y, r - dp(5f), y, pStroke)
        }

        val phx = l + (r - l) * 0.17f
        pFill.color = 0x99FFE08A.toInt()
        canvas.drawRect(phx - dp(1.5f), top + dp(3f), phx + dp(1.5f), top + h - dp(3f), pFill)

        val look = 2600f
        val pxms = (r - phx) / look
        val period = m.periodMs
        for (n in chart) {
            val x0 = phx + (n.t - nowMs) * pxms
            if (x0 < l - dp(40f) || x0 > r + dp(26f)) continue
            val x1 = phx + (n.t + ((n.hits - 1) * period).toLong() - nowMs) * pxms
            val y = top + laneH * (n.cell + 0.5f)
            val nh = laneH * 0.52f
            pFill.color = when (n.color) {
                1 -> Color.rgb(96, 170, 240)
                2 -> Color.rgb(80, 214, 150)
                else -> Color.rgb(206, 220, 232)
            }
            tmp.set(
                x0 - nh * 0.42f, y - nh / 2f,
                max(x1 + nh * 0.42f, x0 + nh * 0.42f), y + nh / 2f
            )
            canvas.drawRoundRect(tmp, nh * 0.42f, nh * 0.42f, pFill)
        }
    }

    private fun drawOverlay(canvas: Canvas) {
        pFill.color = 0xB3091E2E.toInt()
        canvas.drawRect(0f, -insetTop, vw, vh, pFill)

        val pw = vw * 0.82f
        val ph = vh * 0.52f
        val pl = (vw - pw) / 2f
        val pt = (vh - ph) / 2f

        pShadow.color = 0x66000000
        tmp.set(pl, pt + dp(6f), pl + pw, pt + ph + dp(6f))
        canvas.drawRoundRect(tmp, dp(28f), dp(28f), pShadow)
        pFill.color = 0xF2FFFFFF.toInt()
        tmp.set(pl, pt, pl + pw, pt + ph)
        canvas.drawRoundRect(tmp, dp(28f), dp(28f), pFill)

        pText.textAlign = Paint.Align.CENTER
        pText.style = Paint.Style.FILL
        if (phase == Phase.READY) {
            pText.textSize = pw * 0.105f
            pText.color = cInk
            canvas.drawText("打鲸鲸", vw * 0.5f, pt + ph * 0.145f, pText)

            val m = map
            pText.textSize = pw * 0.040f
            pText.color = cInkSoft
            if (m != null) {
                canvas.drawText(
                    "本曲 " + (m.bpm + 0.5).toInt() + " BPM · " + (m.durationMs / 1000) + " 秒 · " + m.count + " 个拍点",
                    vw * 0.5f, pt + ph * 0.235f, pText
                )
            } else {
                pText.color = cRed
                canvas.drawText(if (analyzing) "正在分析音乐节拍…" else "音乐分析失败：" + mapErr, vw * 0.5f, pt + ph * 0.235f, pText)
            }
            if (m != null && mapErr.isNotEmpty()) {
                pText.color = cRed
                pText.textSize = pw * 0.032f
                canvas.drawText(mapErr, vw * 0.5f, pt + ph * 0.277f, pText)
            }

            btnRect.set(vw * 0.5f - pw * 0.36f, pt + ph * 0.33f, vw * 0.5f + pw * 0.36f, pt + ph * 0.49f)
            pFill.color = if (map == null) 0xFFBFCBD6.toInt() else cGold
            canvas.drawRoundRect(btnRect, btnRect.height() / 2f, btnRect.height() / 2f, pFill)
            pText.textSize = btnRect.height() * 0.34f
            pText.color = Color.rgb(92, 60, 5)
            canvas.drawText(if (map == null) "分析中…" else "音游模式", btnRect.centerX(), btnRect.centerY() - btnRect.height() * 0.09f, pText)
            pText.textSize = btnRect.height() * 0.21f
            canvas.drawText("看顶部谱面，鼓点到了就敲", btnRect.centerX(), btnRect.centerY() + btnRect.height() * 0.27f, pText)

            btnRect2.set(vw * 0.5f - pw * 0.36f, pt + ph * 0.55f, vw * 0.5f + pw * 0.36f, pt + ph * 0.71f)
            pFill.color = 0x181E4C6E
            canvas.drawRoundRect(btnRect2, btnRect2.height() / 2f, btnRect2.height() / 2f, pFill)
            pText.textSize = btnRect2.height() * 0.34f
            pText.color = cInk
            canvas.drawText("经典模式", btnRect2.centerX(), btnRect2.centerY() - btnRect2.height() * 0.09f, pText)
            pText.textSize = btnRect2.height() * 0.21f
            pText.color = cInkSoft
            canvas.drawText("30 秒随便敲，没有音乐", btnRect2.centerX(), btnRect2.centerY() + btnRect2.height() * 0.27f, pText)

            pText.textSize = pw * 0.040f
            pText.color = cInkSoft
            canvas.drawText("音游最高 $best · 经典最高 $bestClassic", vw * 0.5f, pt + ph * 0.88f, pText)
        } else {
            pText.textSize = pw * 0.090f
            pText.color = cInk
            canvas.drawText(if (mode == Mode.RHYTHM) "完 成" else "时间到", vw * 0.5f, pt + ph * 0.14f, pText)

            pText.textSize = pw * 0.185f
            pText.color = Color.rgb(28, 122, 190)
            canvas.drawText(score.toString(), vw * 0.5f, pt + ph * 0.35f, pText)

            pText.textSize = pw * 0.042f
            pText.color = cInkSoft
            if (mode == Mode.RHYTHM) {
                val acc = if (notesTotal > 0) (perfectCount * 100f / notesTotal).roundToInt() else 0
                canvas.drawText("精准 $perfectCount · 良好 $goodCount · 漏 $missCount · 准确率 $acc%", vw * 0.5f, pt + ph * 0.44f, pText)
                canvas.drawText("最大连击 ×$maxCombo", vw * 0.5f, pt + ph * 0.50f, pText)
            } else {
                canvas.drawText("最大连击 ×$maxCombo", vw * 0.5f, pt + ph * 0.47f, pText)
            }
            pText.textSize = pw * 0.046f
            val b0 = if (mode == Mode.RHYTHM) best else bestClassic
            canvas.drawText(if (score >= b0 && score > 0) "新纪录！" else "最高 $b0", vw * 0.5f, pt + ph * 0.58f, pText)

            btnRect.set(vw * 0.5f - pw * 0.36f, pt + ph * 0.66f, vw * 0.5f + pw * 0.36f, pt + ph * 0.81f)
            pFill.color = cGold
            canvas.drawRoundRect(btnRect, btnRect.height() / 2f, btnRect.height() / 2f, pFill)
            pText.textSize = btnRect.height() * 0.42f
            pText.color = Color.rgb(92, 60, 5)
            canvas.drawText("再来一局", btnRect.centerX(), btnRect.centerY() + btnRect.height() * 0.15f, pText)

            btnRect2.set(vw * 0.5f - pw * 0.20f, pt + ph * 0.86f, vw * 0.5f + pw * 0.20f, pt + ph * 0.94f)
            pText.textSize = btnRect2.height() * 0.62f
            pText.color = cInkSoft
            canvas.drawText("换模式", btnRect2.centerX(), btnRect2.centerY() + btnRect2.height() * 0.22f, pText)
        }
    }

    private fun tickRhythm() {
        val m = map ?: return
        val now = nowMs

        while (nextBeat < chart.size) {
            val n = chart[nextBeat]
            if (n.t - LEAD_MS > now) break
            spawnNote(n)
            nextBeat++
        }

        for (i in 0 until NC) {
            val c = cells[i]
            if (!c.active) continue
            if (c.retreatAt == 0L) {
                val t0 = c.hit0 - LEAD_MS
                val t1 = c.hit0 - SETTLE_MS
                c.p = when {
                    now >= t1 -> 1f
                    now <= t0 -> 0f
                    else -> easeOutBack(((now - t0).toFloat() / (t1 - t0).toFloat()).coerceIn(0f, 1f))
                }
                if (c.state == 0 && c.basinHp > 0 && now > nextJudge(c) + W_GOOD) {
                    missNote(i)
                    continue
                }
                if (c.state == 3) {
                    if (!c.dizzySfx && now - c.dieAt >= DEATH_SHOW_MS) {
                        c.dizzySfx = true
                        sfx(GameAudio.DIZZY, 1f, 0.8f)
                    }
                    if (now - c.dieAt >= DEATH_SHOW_MS + DEATH_DIZZY_MS) c.retreatAt = now
                }
            } else {
                val k = ((now - c.retreatAt).toFloat() / BACK_MS).coerceIn(0f, 1f)
                c.p = 1f - k * k
                if (now - c.retreatAt >= BACK_MS) {
                    c.active = false
                    c.p = 0f
                    c.retreatAt = 0L
                    c.state = 0
                    c.dieAt = 0L
                    c.dizzySfx = false
                    c.basinHp = 0
                }
            }
        }
    }

    private fun tickClassic() {
        for (i in 0 until NC) {
            val c = cells[i]
            if (!c.active) continue
            if (c.state == 3 && !c.dizzySfx && nowMs - c.dieAt >= DEATH_SHOW_MS) {
                c.dizzySfx = true
                sfx(GameAudio.DIZZY, 1f, 0.8f)
            }
            if (c.retreatAt == 0L && nowMs >= c.liveUntil) {
                c.retreatAt = nowMs
                if (c.state < 3) combo = 0
            }
            c.p = if (c.retreatAt > 0L) {
                val k = ((nowMs - c.retreatAt) / BACK_MS).coerceIn(0f, 1f)
                1f - k * k
            } else {
                val k = ((nowMs - c.bornAt) / RISE_MS).coerceIn(0f, 1f)
                easeOutBack(k)
            }
            if (c.retreatAt > 0L && nowMs - c.retreatAt >= BACK_MS) {
                c.active = false
                c.p = 0f
                c.retreatAt = 0L
                c.state = 0
                c.dieAt = 0L
                c.dizzySfx = false
                c.basinHp = 0
            }
        }
        if (nowMs >= nextSpawnAt) {
            spawnClassic()
            val iv = max(360f, 830f - score * 11f)
            nextSpawnAt = nowMs + iv.toLong() + (Random.nextFloat() * 180f).toLong()
        }
    }

    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val t = x - 1f
        return 1f + c3 * t * t * t + c1 * t * t
    }

    private fun sfx(id: Int, rate: Float = 1f, vol: Float = 1f) {
        // 音游模式不放音效：鼓点会糊
        if (mode == Mode.CLASSIC) audio.play(id, rate, vol)
    }

    private fun hash(k: Int): Float {
        var x = k * -1640531535
        x = x xor (x ushr 13)
        x *= -2048144789
        x = x xor (x ushr 16)
        return ((x ushr 8) and 0xFFFF) / 65536f
    }

    private fun nextJudge(c: Cell): Long {
        val per = map?.periodMs ?: 500.0
        return c.hit0 + ((c.hitsTotal - c.basinHp) * per).toLong()
    }

    // 开局前把整首谱面排好：哪一拍、哪一格、敲几下
    private fun buildChart(m: BeatMap) {
        chart.clear()
        val busy = LongArray(NC)
        var k = 0
        while (k < m.count && m.time(k) + judgeOffset < START_SKIP_MS) k++
        var lastCol = -1
        while (k < m.count) {
            val e = m.energyAt(k)
            val strong = e > 165
            val mid = e > 95
            var place = if (mid) true else (k % 2 == 0)
            if (hash(k) < 0.10f) place = false
            if (!place) {
                k++
                continue
            }
            var hits = 1
            val hh = hash(k + 991)
            if (strong) {
                if (hh > 0.90f) hits = 3 else if (hh > 0.72f) hits = 2
            } else if (mid && hh > 0.92f) {
                hits = 2
            }
            if (k + hits > m.count) hits = 1
            val t = (m.time(k) + judgeOffset).toLong()
            val off = (hash(k + 37) * NC).toInt().coerceIn(0, NC - 1)
            var idx = -1
            for (j in 0 until NC) {
                val i = (off + j) % NC
                if (busy[i] > t - LEAD_MS) continue
                if (i % COLS == lastCol) continue
                idx = i
                break
            }
            if (idx < 0) {
                for (j in 0 until NC) {
                    val i = (off + j) % NC
                    if (busy[i] > t - LEAD_MS) continue
                    idx = i
                    break
                }
            }
            if (idx < 0) {
                k++
                continue
            }
            busy[idx] = t + ((hits - 1) * m.periodMs).toLong() + 1260L
            lastCol = idx % COLS
            chart.add(Note(t, idx, hits, when (hits) {
                3 -> 1
                2 -> 2
                else -> 0
            }))
            k += hits
        }
    }

    private fun spawnNote(n: Note) {
        val c = cells[n.cell]
        c.active = true
        c.state = 0
        c.face = 0
        c.dieAt = 0L
        c.dizzySfx = false
        c.basinShakeAt = 0L
        c.retreatAt = 0L
        c.basinHp = n.hits
        c.hitsTotal = n.hits
        c.hit0 = n.t
        c.basinColor = n.color
        c.bornAt = nowMs
        c.stateAt = nowMs
        c.liveUntil = n.t + ((n.hits - 1) * (map?.periodMs ?: 500.0)).toLong()
        c.p = 0f
        fx.add(Fx(1, holeX(n.cell), holeY(n.cell), 0f, 0f, 480L, nowMs, null, 0, 0f))
    }

    private fun hitCell(i: Int, tapAt: Long) {
        val c = cells[i]
        if (c.state != 0 || c.basinHp <= 0) return
        val rawErr = tapAt - nextJudge(c)
        val d = abs(rawErr)
        val perfect = d <= W_PERFECT
        val mult = if (perfect) 1.0f else 0.62f
        val base = when (c.basinColor) {
            1 -> SCORE_BLUE
            2 -> SCORE_GREEN
            else -> SCORE_SILVER
        }
        val bonus = min(combo / 10, 5)
        val g = (base * mult).roundToInt() + bonus
        score += g
        combo += 1
        if (combo > maxCombo) maxCombo = combo
        notesTotal += 1
        if (perfect) perfectCount += 1 else goodCount += 1
        Diag.judge(rawErr.toInt())

        c.basinHp -= 1
        c.basinShakeAt = nowMs
        c.face = min(c.face + 1, 2)

        val col = if (perfect) Color.rgb(255, 222, 120) else Color.rgb(200, 232, 255)
        fx.add(Fx(0, holeX(i), cutY(i) - charH * 0.34f, 0f, 0f, 560L, nowMs,
            (if (perfect) "Perfect +" else "Good +") + g, col, charW * 0.17f))
        fx.add(Fx(1, holeX(i), cutY(i), 0f, 0f, 300L, nowMs, null, 0, 0f))

        val rate = if (perfect) 1.0f else 0.84f
        if (c.basinHp > 0) {
            sfx(if (c.basinHp >= 2) GameAudio.HIT1 else GameAudio.HIT2, rate)
            buzz(9)
        } else {
            sfx(GameAudio.HIT3, rate)
            knockOff(i)
        }
    }

    private fun knockOff(i: Int) {
        val c = cells[i]
        val cx = holeX(i)
        val bw = charW * 0.78f
        val bh = bw * bmpBasins[c.basinColor].height / bmpBasins[c.basinColor].width
        val baseTop = cutY(i) - charH * (HEAD_LIFT + HEAD_C)
        val by = baseTop + charH * 0.37f - bh / 2f
        val dir = if (hash(i * 7 + 3) > 0.5f) 1f else -1f
        basins.add(
            FlyBasin(
                cx, by,
                dir * dp(90f + hash(i + 11) * 130f),
                -dp(420f + hash(i + 17) * 160f),
                0f,
                dir * (300f + hash(i + 23) * 260f),
                bw,
                c.basinColor
            )
        )
        for (j in 0 until 3) {
            fx.add(
                Fx(
                    2, cx + (hash(i * 13 + j) - 0.5f) * charW * 0.5f, by,
                    (hash(i * 31 + j) - 0.5f) * dp(80f), -dp(40f + hash(i * 41 + j) * 60f),
                    380L, nowMs, null, 0, dp(3f + hash(i * 53 + j) * 3f)
                )
            )
        }
        c.basinHp = 0
        c.state = 3
        c.stateAt = nowMs
        c.dieAt = nowMs
        sfx(GameAudio.KNOCK, 1f, 0.7f)
        buzz(22)
    }

    private fun missNote(i: Int) {
        val c = cells[i]
        combo = 0
        missCount += 1
        notesTotal += 1
        score = max(0, score - MISS_PENALTY)
        fx.add(Fx(0, holeX(i), cutY(i) - charH * 0.30f, 0f, 0f, 620L, nowMs, "Miss", cRed, charW * 0.18f))
        c.state = 2
        c.face = 2
        c.basinHp = 0
        c.retreatAt = nowMs + 130L
        sfx(GameAudio.MISS, 0.9f, 0.8f)
        buzz(6)
    }

    private fun missTap(x: Float, y: Float) {
        combo = 0
        score = max(0, score - MISS_PENALTY)
        fx.add(Fx(0, x, y, 0f, 0f, 460L, nowMs, "-" + MISS_PENALTY, cRed, charW * 0.17f))
        fx.add(Fx(1, x, y, 0f, 0f, 300L, nowMs, null, 0, 0f))
        sfx(GameAudio.MISS, 0.85f, 0.9f)
        buzz(8)
    }

    // ---------------- 经典模式 ----------------

    private fun spawnClassic() {
        var count = 0
        for (c in cells) if (c.active) count++
        if (count >= 3) return
        val free = ArrayList<Int>()
        for (i in 0 until NC) if (!cells[i].active) free.add(i)
        if (free.isEmpty()) return
        val i = free[Random.nextInt(free.size)]
        val c = cells[i]
        c.active = true
        c.state = 0
        c.bornAt = nowMs
        c.stateAt = nowMs
        c.retreatAt = 0L
        val roll = Random.nextFloat()
        c.basinColor = when {
            roll < 0.50f -> 0
            roll < 0.80f -> 1
            else -> 2
        }
        c.basinHp = when (c.basinColor) {
            1 -> 3
            2 -> 2
            else -> 1
        }
        c.hitsTotal = c.basinHp
        c.face = 0
        c.dieAt = 0L
        c.dizzySfx = false
        c.basinShakeAt = 0L
        c.lastHitAt = 0L
        c.liveUntil = nowMs + (1500L - min(score * 8L, 520L)).coerceAtLeast(900L) + (c.basinHp - 1) * 460L
        c.p = 0f
        fx.add(Fx(1, holeX(i), holeY(i), 0f, 0f, 520L, nowMs, null, 0, 0f))
    }

    private fun hitClassic(i: Int) {
        val c = cells[i]
        val cx = holeX(i)
        val cy = cutY(i)
        if (c.state >= 3) return

        if (c.basinHp > 1) {
            c.basinHp -= 1
            c.face = min(c.face + 1, 2)
            c.basinShakeAt = nowMs
            c.lastHitAt = nowMs
            c.liveUntil = max(c.liveUntil, nowMs + 520L)
            val g = gainClassic(c)
            fx.add(Fx(0, cx, cy - charH * 0.36f, 0f, 0f, 520L, nowMs, "+$g", Color.rgb(214, 236, 250), charW * 0.20f))
            fx.add(Fx(1, cx, cy - charH * 0.10f, 0f, 0f, 320L, nowMs, null, 0, 0f))
            buzz(12)
            sfx(if (c.basinHp >= 2) GameAudio.HIT1 else GameAudio.HIT2)
            return
        }

        val bw = charW * 0.78f
        val bh = bw * bmpBasins[c.basinColor].height / bmpBasins[c.basinColor].width
        val baseTop = cutY(i) - charH * (HEAD_LIFT + HEAD_C) + (1f - c.p) * charH * RISE_TRAVEL
        val by = baseTop + charH * 0.37f - bh / 2f
        val dir = if (Random.nextBoolean()) 1f else -1f
        basins.add(
            FlyBasin(
                cx, by,
                dir * dp(90f + Random.nextFloat() * 130f),
                -dp(420f + Random.nextFloat() * 160f),
                0f,
                dir * (300f + Random.nextFloat() * 260f),
                bw,
                c.basinColor
            )
        )
        for (k in 0 until 3) {
            fx.add(
                Fx(
                    2, cx + (Random.nextFloat() - 0.5f) * charW * 0.5f, by,
                    (Random.nextFloat() - 0.5f) * dp(80f), -dp(40f + Random.nextFloat() * 60f),
                    380L, nowMs, null, 0, dp(3f + Random.nextFloat() * 3f)
                )
            )
        }
        val g = gainClassic(c)
        fx.add(Fx(0, cx, cy - charH * 0.30f, 0f, 0f, 700L, nowMs, "+$g", Color.rgb(255, 214, 102), charW * 0.26f))
        fx.add(Fx(1, cx, cy, 0f, 0f, 380L, nowMs, null, 0, 0f))
        c.basinHp = 0
        c.state = 3
        c.stateAt = nowMs
        c.dieAt = nowMs
        c.liveUntil = nowMs + DEATH_SHOW_MS + DEATH_DIZZY_MS
        sfx(GameAudio.HIT3)
        sfx(GameAudio.KNOCK, 1f, 0.7f)
        buzz(26)
    }

    private fun gainClassic(c: Cell): Int {
        combo += 1
        if (combo > maxCombo) maxCombo = combo
        val base = when (c.basinColor) {
            1 -> 3
            2 -> 5
            else -> 1
        }
        val g = base + min(combo / 3, 3)
        score += g
        return g
    }

    private fun missClassic(x: Float, y: Float) {
        combo = 0
        if (score > 0) {
            score -= 1
            fx.add(Fx(0, x, y, 0f, 0f, 460L, nowMs, "-1", cRed, charW * 0.20f))
        }
        fx.add(Fx(1, x, y, 0f, 0f, 300L, nowMs, null, 0, 0f))
        sfx(GameAudio.MISS, 0.85f, 0.9f)
        buzz(8)
    }

    private fun updateFx() {
        var k = fx.size - 1
        while (k >= 0) {
            if (nowMs - fx[k].t0 > fx[k].life) fx.removeAt(k)
            k--
        }
    }

    private fun updateBasins(dt: Float) {
        val d = dt / 1000f
        var k = basins.size - 1
        while (k >= 0) {
            val b = basins[k]
            b.vy += dp(1500f) * d
            b.x += b.vx * d
            b.y += b.vy * d
            b.rot += b.rotv * d
            if (b.y > vh + b.size || b.x < -b.size * 2 || b.x > vw + b.size * 2) basins.removeAt(k)
            k--
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y - insetTop

        if (showSettings) {
            touchSettings(e, x, y)
            return true
        }

        if (e.action != MotionEvent.ACTION_DOWN) return true

        if (gearR.contains(x, y)) {
            showSettings = true
            dragSld = -1
            // 只有音游对局必须暂停（游戏暂停而歌继续会失同步）；其余让音乐继续，方便边拖滑条边听
            if (phase == Phase.PLAYING && mode == Mode.RHYTHM) audio.pauseBgm()
            invalidate()
            return true
        }

        if (phase == Phase.READY) {
            if (btnRect.contains(x, y) && map != null) start(Mode.RHYTHM)
            else if (btnRect2.contains(x, y)) start(Mode.CLASSIC)
            return true
        }
        if (phase == Phase.OVER) {
            if (btnRect.contains(x, y)) {
                start(mode)
            } else if (btnRect2.contains(x, y)) {
                phase = Phase.READY
                audio.restartBgm()
                invalidate()
            }
            return true
        }

        if (mode == Mode.RHYTHM) touchRhythm(x, y) else touchClassic(x, y)
        return true
    }

    private fun hitBox(i: Int, x: Float, y: Float): Boolean {
        val c = cells[i]
        if (!c.active || c.state != 0 || c.basinHp <= 0 || c.p < 0.35f) return false
        val cx = holeX(i)
        val cut = cutY(i)
        val topY = cut - charH * (HEAD_LIFT + HEAD_C)
        if (x < cx - charW * 0.47f || x > cx + charW * 0.47f) return false
        if (y < topY || y > cut + ry * 0.4f) return false
        return true
    }

    private fun touchRhythm(x: Float, y: Float) {
        val t = nowMs
        var bestIdx = -1
        var bestD = Long.MAX_VALUE
        for (i in 0 until NC) {
            if (!hitBox(i, x, y)) continue
            val d = abs(t - nextJudge(cells[i]))
            if (d < bestD) {
                bestD = d
                bestIdx = i
            }
        }
        if (bestIdx >= 0 && bestD <= W_GOOD) hitCell(bestIdx, t) else missTap(x, y)
    }

    private fun touchClassic(x: Float, y: Float) {
        var bestIdx = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until NC) {
            if (!hitBox(i, x, y)) continue
            val cut = cutY(i)
            val topY = cut - charH * (HEAD_LIFT + HEAD_C)
            val d = abs(y - (topY + cut) / 2f)
            if (d < bestD) {
                bestD = d
                bestIdx = i
            }
        }
        if (bestIdx >= 0) hitClassic(bestIdx) else missClassic(x, y)
    }

    private fun songPos(): Long {
        val raw = if (audio.bgmPlaying) audio.positionMs() else (System.currentTimeMillis() - gameStartAt)
        if (raw < lastSongPos) return lastSongPos
        lastSongPos = raw
        return raw
    }

    private fun ensureMap() {
        if (map != null || analyzing) return
        analyzing = true
        val uriStr = audio.bgmUriString
        Thread {
            var got: BeatMap? = null
            var err = ""
            var fromCache = false
            val t0 = System.currentTimeMillis()
            try {
                got = BeatDetector.loadCache(ctx, uriStr)
                fromCache = got != null
                if (got == null) {
                    val u = if (uriStr != null) Uri.parse(uriStr) else null
                    got = BeatDetector.analyze(ctx, u, R.raw.bgm)
                    if (got != null) BeatDetector.saveCache(ctx, uriStr, got)
                }
            } catch (t: Throwable) {
                err = t.toString()
            }
            val m2 = got
            val e2 = err
            post {
                analyzing = false
                map = if (m2 != null) m2 else {
                    val dur = if (audio.durationMs > 0L) audio.durationMs else 120000L
                    BeatMap(120.0, 500.0, 0.0, dur, ByteArray(0))
                }
                mapErr = if (m2 == null) "识别失败，已按 120 BPM 兜底" else ""
                val src = if (fromCache) "cache" else "analyzed " + (System.currentTimeMillis() - t0) + "ms"
                Diag.run("src", src)
                Diag.line("map", src)
                val mm = map
                if (mm != null) buildChart(mm)
                invalidate()
            }
        }.start()
    }

    private fun refreshMap() {
        map = null
        analyzing = false
        lastSongPos = -1L
        ensureMap()
        invalidate()
    }

    private fun start(m0: Mode) {
        mode = m0
        Diag.clearRun()
        Diag.run("mode", if (m0 == Mode.RHYTHM) "rhythm" else "classic")
        score = 0
        combo = 0
        maxCombo = 0
        perfectCount = 0
        goodCount = 0
        missCount = 0
        notesTotal = 0
        fx.clear()
        basins.clear()
        for (c in cells) {
            c.active = false
            c.p = 0f
            c.state = 0
            c.retreatAt = 0L
            c.dizzySfx = false
            c.basinHp = 0
            c.hit0 = 0L
            c.hitsTotal = 1
        }
        lastCell = -1
        lastFrame = 0L
        if (m0 == Mode.RHYTHM) {
            val m = map ?: return
            buildChart(m)
            nextBeat = 0
            lastSongPos = -1L
            gameStartAt = System.currentTimeMillis()
            phase = Phase.PLAYING
            audio.restartBgm()
            nowMs = 0L
        } else {
            startAt = System.currentTimeMillis()
            nowMs = startAt
            nextSpawnAt = startAt + 300L
            phase = Phase.PLAYING
            bgmPolicy()
        }
        sfx(GameAudio.START, 1f, 0.5f)
        Diag.line("start", if (m0 == Mode.RHYTHM) "rhythm" else "classic")
        postInvalidateOnAnimation()
    }

    private fun gameOver() {
        phase = Phase.OVER
        for (c in cells) {
            c.active = false
            c.p = 0f
            c.retreatAt = 0L
            c.state = 0
            c.basinHp = 0
        }
        combo = 0
        audio.pauseBgm()
        sfx(GameAudio.OVER, 1f, 0.9f)
        val sp2 = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (mode == Mode.RHYTHM) {
            if (score > best) {
                best = score
                sp2.edit().putInt("best", best).apply()
            }
        } else {
            if (score > bestClassic) {
                bestClassic = score
                sp2.edit().putInt("bestC", bestClassic).apply()
            }
        }
        diagDump("over")
        postInvalidateOnAnimation()
    }

    /** 一局结束时把关键数字写进剪贴板（助手在 DSH 里读走） */
    private fun diagDump(tag: String) {
        val m = map
        val bpm = ((m?.bpm ?: 0.0) * 10.0).roundToInt() / 10.0
        Diag.run("bpm", bpm)
        Diag.run("beats", m?.count ?: 0)
        Diag.run("chart", chart.size)
        Diag.run("dur", (audio.durationMs / 100.0).roundToInt() / 10.0)
        Diag.run("score", score)
        Diag.run("best", if (mode == Mode.RHYTHM) best else bestClassic)
        Diag.run("p", perfectCount)
        Diag.run("g", goodCount)
        Diag.run("miss", missCount)
        Diag.run("combo", maxCombo)
        Diag.run("vol", "sfx" + (audio.sfxVol * 100).roundToInt() + "/bgm" + (audio.bgmVol * 100).roundToInt())
        Diag.run("off", (if (judgeOffset >= 0) "+" else "") + judgeOffset)
        Diag.run("vib", if (audio.vibOn) 1 else 0)
        Diag.run("music", if (audio.musicOn) 1 else 0)
        Diag.run("mapErr", if (mapErr.isEmpty()) "-" else mapErr)
        Diag.publish(tag)
    }

    private fun buzz(ms: Int) {
        if (!audio.vibOn) return
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms.toLong())
            }
        } catch (t: Throwable) {
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try { audio.release() } catch (t: Throwable) { }
    }

    // 由 MainActivity 在 onResume / onPause 调用
    fun onResumeAudio() {
        try {
            if (phase == Phase.READY) audio.startBgm() else bgmPolicy()
        } catch (t: Throwable) {
        }
    }

    fun onPauseAudio() {
        try { audio.pauseBgm() } catch (t: Throwable) { }
    }

    // 用户在系统选择器里挑好本地音乐
    fun onPickedBgm(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (t: Throwable) {
        }
        var name: String? = null
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        } catch (t: Throwable) {
        }
        audio.setBgm(uri, name)
        Diag.line("bgm", (name ?: uri.toString()).take(60))
        refreshMap()
        invalidate()
    }
}
