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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : View(context) {

    private val ctx: Context = context
    private val audio = GameAudio(context)

    private enum class Phase { READY, PLAYING, OVER }

    private class Cell {
        var active = false
        var state = 0
        var bornAt = 0L
        var stateAt = 0L
        var liveUntil = 0L
        var retreatAt = 0L
        var basinHp = 1
        var basinColor = 0
        var basinShakeAt = 0L
        var lastHitAt = 0L
        var face = 0        // 0开心 1生气 2哭 3晕（锁定）
        var dieAt = 0L      // 被打飞的时间点
        var dizzySfx = false
        var p = 0f
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

    companion object {
        private const val COLS = 3
        private const val ROWS = 3
        private const val SCORE_SILVER = 1      // 银盆基础分
        private const val SCORE_BLUE = 3        // 蓝盆基础分
        private const val SCORE_GREEN = 5       // 绿盆基础分
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
    private val bmpBasins = arrayOf(
        load(R.drawable.basin_grey),
        load(R.drawable.basin_blue),
        load(R.drawable.basin_green),
        load(R.drawable.basin_red)
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
    private val btnCloseSet = RectF()

    init {
        best = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("best", 0)
        isClickable = true
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
        nowMs = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 0f else min(48L, nowMs - lastFrame).toFloat()
        lastFrame = nowMs

        if (phase == Phase.PLAYING) {
            if (showSettings) {
                // 打开设置时把整局游戏的时间戳一起推后 = 暂停
                val d = dt.toLong()
                if (d > 0L) {
                    startAt += d
                    nextSpawnAt += d
                    for (c in cells) {
                        if (!c.active) continue
                        c.bornAt += d
                        c.stateAt += d
                        c.liveUntil += d
                        if (c.retreatAt != 0L) c.retreatAt += d
                        if (c.dieAt != 0L) c.dieAt += d
                        if (c.basinShakeAt != 0L) c.basinShakeAt += d
                        if (c.lastHitAt != 0L) c.lastHitAt += d
                    }
                    for (f in fx) f.t0 += d
                }
            } else {
                tick()
                if (nowMs - startAt >= ROUND_MS) gameOver()
            }
        }
        updateFx()
        if (!showSettings) updateBasins(dt)

        canvas.save()
        canvas.translate(0f, insetTop)
        drawSea(canvas)
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
        canvas.drawText("最高 $best", vw * 0.5f, hudH * 0.33f, pText)

        // 连击
        if (phase == Phase.PLAYING && combo >= 3) {
            pText.textAlign = Paint.Align.CENTER
            pText.textSize = hudH * 0.21f
            pText.color = cGold
            canvas.drawText("连击 ×$combo", vw * 0.5f, hudH * 0.62f, pText)
        }

        // 倒计时
        pText.textAlign = Paint.Align.RIGHT
        if (phase == Phase.PLAYING) {
            val rem = ((ROUND_MS - (nowMs - startAt)) / 1000f).coerceAtLeast(0f)
            pText.textSize = hudH * 0.40f
            pText.color = if (rem <= 5f) cRed else cInk
            canvas.drawText(String.format("%.1f", rem), vw * 0.925f, hudH * 0.58f, pText)
        }

        val left = vw * 0.075f
        val right = vw * 0.925f
        val ty = hudH * 0.76f
        val th = hudH * 0.13f
        tmp.set(left, ty, right, ty + th)
        pFill.color = 0x55FFFFFF
        canvas.drawRoundRect(tmp, th / 2f, th / 2f, pFill)

        val ratio = when (phase) {
            Phase.PLAYING -> (1f - (nowMs - startAt).toFloat() / ROUND_MS).coerceIn(0f, 1f)
            Phase.OVER -> 0f
            else -> 1f
        }
        if (ratio > 0f) {
            pFill.color = when {
                ratio > 0.5f -> cGreen
                ratio > 0.22f -> cGold
                else -> cRed
            }
            tmp.set(left, ty, left + (right - left) * ratio, ty + th)
            canvas.drawRoundRect(tmp, th / 2f, th / 2f, pFill)
        }

        // 声音设置入口（右上角）
        val gs = hudH * 0.17f
        gearR.set(vw - dp(9f) - gs, hudH * 0.055f, vw - dp(9f), hudH * 0.055f + gs)
        pFill.color = 0x3DFFFFFF
        canvas.drawRoundRect(gearR, gs * 0.32f, gs * 0.32f, pFill)
        val gc = 0xD91E4C6E.toInt()
        pStroke.color = gc
        pStroke.strokeWidth = max(dp(1.6f), gs * 0.075f)
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

    // ---------------- 声音设置面板 ----------------

    private fun layoutSettings() {
        val pw = vw * 0.86f
        val ph = min(pw * 1.26f, vh * 0.66f)
        panel.set((vw - pw) / 2f, (vh - ph) / 2f, (vw + pw) / 2f, (vh + ph) / 2f)

        val l = panel.left + pw * 0.07f
        val r = panel.right - pw * 0.07f
        val trackL = l + pw * 0.15f
        val trackR = r - pw * 0.17f

        val y1 = panel.top + ph * 0.24f
        val y2 = panel.top + ph * 0.35f
        sldSfxTrack.set(trackL, y1, trackR, y1)
        sldBgmTrack.set(trackL, y2, trackR, y2)
        val ht = ph * 0.034f
        sldSfxHit.set(trackL - ht, y1 - ht * 1.6f, trackR + ht, y1 + ht * 1.6f)
        sldBgmHit.set(trackL - ht, y2 - ht * 1.6f, trackR + ht, y2 + ht * 1.6f)

        val bh = ph * 0.098f
        btnPickBgm.set(l, panel.top + ph * 0.455f, r, panel.top + ph * 0.455f + bh)
        val gap = pw * 0.045f
        val half = (r - l - gap) / 2f
        btnResetBgm.set(l, panel.top + ph * 0.655f, l + half, panel.top + ph * 0.655f + bh)
        btnVib.set(l + half + gap, panel.top + ph * 0.655f, r, panel.top + ph * 0.655f + bh)
        btnCloseSet.set(l, panel.bottom - ph * 0.145f, r, panel.bottom - ph * 0.045f)
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
        pText.textSize = ph * 0.072f
        pText.color = cInk
        canvas.drawText("声音设置", panel.centerX(), panel.top + ph * 0.115f, pText)

        val th = ph * 0.021f
        val knob = ph * 0.029f
        val labels = arrayOf("音效", "音乐")
        val tracks = arrayOf(sldSfxTrack, sldBgmTrack)
        val vols = arrayOf(audio.sfxVol, audio.bgmVol)
        for (k in 0..1) {
            val tr = tracks[k]
            val v = vols[k]
            pText.textAlign = Paint.Align.LEFT
            pText.textSize = ph * 0.050f
            pText.color = cInk
            canvas.drawText(labels[k], l, tr.centerY() + ph * 0.018f, pText)

            pText.textAlign = Paint.Align.RIGHT
            pText.textSize = ph * 0.044f
            pText.color = cInkSoft
            canvas.drawText("${(v * 100).roundToInt()}%", r, tr.centerY() + ph * 0.016f, pText)

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
            pStroke.strokeWidth = max(dp(1.7f), ph * 0.007f)
            canvas.drawCircle(kx, tr.centerY(), knob, pStroke)
        }

        button(canvas, btnPickBgm, "更换背景音乐", false)
        pText.textAlign = Paint.Align.CENTER
        pText.textSize = ph * 0.040f
        pText.color = cInkSoft
        val nm = audio.bgmName
        canvas.drawText("当前：" + if (nm.length > 18) nm.substring(0, 18) + "…" else nm,
            panel.centerX(), panel.top + ph * 0.615f, pText)

        button(canvas, btnResetBgm, "恢复内置", !audio.bgmCustom)
        button(canvas, btnVib, if (audio.vibOn) "震动 开" else "震动 关", audio.vibOn)
        button(canvas, btnCloseSet, "完成", true)
    }

    private fun button(canvas: Canvas, rect: RectF, label: String, solid: Boolean) {
        pFill.style = Paint.Style.FILL
        pFill.color = if (solid) cInk else 0x181E4C6E
        canvas.drawRoundRect(rect, rect.height() * 0.32f, rect.height() * 0.32f, pFill)
        pText.textAlign = Paint.Align.CENTER
        pText.style = Paint.Style.FILL
        pText.textSize = rect.height() * 0.40f
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
                    btnPickBgm.contains(x, y) -> {
                        dragSld = -1
                        pickBgm()
                    }
                    btnResetBgm.contains(x, y) -> {
                        dragSld = -1
                        audio.clearBgm()
                        audio.startBgm()
                    }
                    btnVib.contains(x, y) -> {
                        dragSld = -1
                        audio.vibOn = !audio.vibOn
                        audio.saveVib()
                        if (audio.vibOn) buzz(20)
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
                if (dragSld == 1) audio.startBgm()
                if (dragSld >= 0) audio.saveVolumes()
                dragSld = -1
                invalidate()
            }
        }
    }

    private fun setSld(which: Int, x: Float) {
        val t = if (which == 0) sldSfxTrack else sldBgmTrack
        val v = ((x - t.left) / max(1f, t.width())).coerceIn(0f, 1f)
        if (which == 0) audio.sfxVol = v else audio.bgmVol = v
    }

    private fun closeSettings() {
        showSettings = false
        dragSld = -1
        audio.saveVolumes()
        lastFrame = 0L
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

    private fun drawOverlay(canvas: Canvas) {
        pFill.color = 0xB3091E2E.toInt()
        canvas.drawRect(0f, -insetTop, vw, vh, pFill)

        val pw = vw * 0.78f
        val ph = vh * 0.40f
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
            pText.textSize = pw * 0.115f
            pText.color = cInk
            canvas.drawText("打鲸鲸", vw * 0.5f, pt + ph * 0.30f, pText)

            pText.textSize = pw * 0.058f
            pText.color = cInkSoft
            canvas.drawText("有的敲一下就掉盆，有的要敲好几下", vw * 0.5f, pt + ph * 0.44f, pText)
            pText.textSize = pw * 0.050f
            canvas.drawText("盆掉了：生气 → 哭 → 晕", vw * 0.5f, pt + ph * 0.53f, pText)

            btnRect.set(vw * 0.5f - pw * 0.32f, pt + ph * 0.62f, vw * 0.5f + pw * 0.32f, pt + ph * 0.82f)
            pFill.color = cGold
            canvas.drawRoundRect(btnRect, btnRect.height() / 2f, btnRect.height() / 2f, pFill)
            pText.textSize = btnRect.height() * 0.46f
            pText.color = Color.rgb(92, 60, 5)
            canvas.drawText("开 始", btnRect.centerX(), btnRect.centerY() + btnRect.height() * 0.17f, pText)

            pText.textSize = pw * 0.048f
            pText.color = cInkSoft
            canvas.drawText("最高 $best 分", vw * 0.5f, pt + ph * 0.93f, pText)
        } else {
            pText.textSize = pw * 0.105f
            pText.color = cInk
            canvas.drawText("时间到", vw * 0.5f, pt + ph * 0.24f, pText)

            pText.textSize = pw * 0.22f
            pText.color = Color.rgb(28, 122, 190)
            canvas.drawText(score.toString(), vw * 0.5f, pt + ph * 0.50f, pText)

            pText.textSize = pw * 0.052f
            pText.color = cInkSoft
            val tip = if (score >= best && score > 0) "新纪录！" else "最高 $best"
            canvas.drawText(tip, vw * 0.5f, pt + ph * 0.59f, pText)

            btnRect.set(vw * 0.5f - pw * 0.34f, pt + ph * 0.66f, vw * 0.5f + pw * 0.34f, pt + ph * 0.86f)
            pFill.color = cGold
            canvas.drawRoundRect(btnRect, btnRect.height() / 2f, btnRect.height() / 2f, pFill)
            pText.textSize = btnRect.height() * 0.44f
            pText.color = Color.rgb(92, 60, 5)
            canvas.drawText("再来一局", btnRect.centerX(), btnRect.centerY() + btnRect.height() * 0.16f, pText)
        }
    }

    private fun tick() {
        for (i in 0 until NC) {
            val c = cells[i]
            if (!c.active) continue
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
            } else if (c.state == 3 && !c.dizzySfx && nowMs - c.dieAt >= DEATH_SHOW_MS) {
                c.dizzySfx = true
                audio.play(GameAudio.DIZZY, 1f, 0.85f)
            }
        }

        if (nowMs >= nextSpawnAt) {
            spawn()
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

    private fun spawn() {
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
        // 只有三档：银(1下) / 蓝(2下) / 绿(3下)
        val roll = Random.nextFloat()
        c.basinColor = when {
            roll < 0.50f -> 0
            roll < 0.80f -> 1
            else -> 2
        }
        c.basinHp = when (c.basinColor) {
            1 -> 3      // 蓝：敲 3 下
            2 -> 2      // 绿：敲 2 下
            else -> 1   // 银：敲 1 下
        }
        c.face = 0
        c.dieAt = 0L
        c.dizzySfx = false
        c.basinShakeAt = 0L
        c.lastHitAt = 0L
        c.liveUntil = nowMs + (1500L - min(score * 8L, 520L)).coerceAtLeast(900L) + (c.basinHp - 1) * 460L
        c.p = 0f
        fx.add(Fx(1, holeX(i), holeY(i), 0f, 0f, 520L, nowMs, null, 0, 0f))
    }

    private fun hit(i: Int) {
        val c = cells[i]
        val cx = holeX(i)
        val cy = cutY(i)

        // 已经晕倒：状态锁死，再按既不换脸也不加分
        if (c.state >= 3) return

        // 铁盆还有血：砸一下不掉，表情凶一层
        if (c.basinHp > 1) {
            c.basinHp -= 1
            c.face = min(c.face + 1, 2)
            c.basinShakeAt = nowMs
            c.lastHitAt = nowMs
            c.liveUntil = max(c.liveUntil, nowMs + 520L)
            val g = gain(c)
            fx.add(Fx(0, cx, cy - charH * 0.36f, 0f, 0f, 520L, nowMs, "+$g", Color.rgb(214, 236, 250), charW * 0.20f))
            fx.add(Fx(1, cx, cy - charH * 0.10f, 0f, 0f, 320L, nowMs, null, 0, 0f))
            buzz(12)
            audio.play(if (c.basinHp >= 2) GameAudio.HIT1 else GameAudio.HIT2)
            return
        }

        // 最后一下：盆被打飞
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

        val g = gain(c)
        fx.add(Fx(0, cx, cy - charH * 0.30f, 0f, 0f, 700L, nowMs, "+$g", Color.rgb(255, 214, 102), charW * 0.26f))
        fx.add(Fx(1, cx, cy, 0f, 0f, 380L, nowMs, null, 0, 0f))

        // 死亡三段：先露原表情 -> 晕+晃两下 -> 沉下去
        c.basinHp = 0
        c.state = 3
        c.stateAt = nowMs
        c.dieAt = nowMs
        c.liveUntil = nowMs + DEATH_SHOW_MS + DEATH_DIZZY_MS
        buzz(26)
        audio.play(GameAudio.HIT3)
        audio.play(GameAudio.KNOCK, 1f, 0.75f)
    }

    // 命中一次：基础分（银1/蓝3/绿5）+ 连击加成（每满3连 +1，封顶 +3）
    private fun gain(c: Cell): Int {
        combo += 1
        val base = when (c.basinColor) {
            1 -> SCORE_BLUE
            2 -> SCORE_GREEN
            else -> SCORE_SILVER
        }
        val g = base + min(combo / 3, 3)
        score += g
        if (combo % 3 == 0) audio.play(GameAudio.COMBO, 1f, 0.5f)
        return g
    }

    private fun miss(x: Float, y: Float) {
        combo = 0
        if (score > 0) {
            score -= 1
            fx.add(Fx(0, x, y, 0f, 0f, 460L, nowMs, "-1", Color.rgb(255, 120, 120), charW * 0.20f))
        }
        fx.add(Fx(1, x, y, 0f, 0f, 300L, nowMs, null, 0, 0f))
        audio.play(GameAudio.MISS, 0.85f, 0.9f)
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
            invalidate()
            return true
        }

        if (phase != Phase.PLAYING) {
            if (btnRect.contains(x, y)) start()
            return true
        }

        var bestIdx = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until NC) {
            val c = cells[i]
            if (!c.active || c.p < 0.35f) continue
            val cx = holeX(i)
            val cut = cutY(i)
            val topY = cut - charH * (HEAD_LIFT + HEAD_C)
            if (x < cx - charW * 0.47f || x > cx + charW * 0.47f) continue
            if (y < topY || y > cut + ry * 0.4f) continue
            val d = kotlin.math.abs(y - (topY + cut) / 2f)
            if (d < bestD) { bestD = d; bestIdx = i }
        }

        if (bestIdx >= 0) hit(bestIdx) else miss(x, y)
        return true
    }

    private fun start() {
        score = 0
        combo = 0
        fx.clear()
        basins.clear()
        for (c in cells) {
            c.active = false
            c.p = 0f
            c.state = 0
            c.retreatAt = 0L
            c.dizzySfx = false
        }
        phase = Phase.PLAYING
        audio.play(GameAudio.START, 1f, 0.9f)
        audio.startBgm()
        startAt = System.currentTimeMillis()
        nowMs = startAt
        nextSpawnAt = startAt + 300L
        lastFrame = 0L
        postInvalidateOnAnimation()
    }

    private fun gameOver() {
        phase = Phase.OVER
        for (c in cells) if (c.active) c.retreatAt = nowMs
        combo = 0
        audio.play(GameAudio.OVER, 1f, 0.9f)
        if (score > best) {
            best = score
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("best", best).apply()
        }
        postInvalidateOnAnimation()
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
        try { audio.startBgm() } catch (t: Throwable) { }
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
        invalidate()
    }
}
