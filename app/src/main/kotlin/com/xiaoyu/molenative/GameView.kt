package com.xiaoyu.molenative

import android.content.Context
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
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : View(context) {

    private val ctx: Context = context

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
        var reward = 0      // 这个角色总共还能给多少分
        var p = 0f
    }

    private class Fx(
        val kind: Int, var x: Float, var y: Float,
        val vx: Float, val vy: Float, val life: Long,
        val t0: Long, val text: String?, val color: Int, val size: Float
    )

    private class FlyBasin(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var rot: Float, val rotv: Float, val size: Float, val color: Int
    )

    companion object {
        private const val COLS = 3
        private const val ROWS = 3
        private const val HIT_SCORE = 10        // 每敲一下固定分
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

    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 55)
    } catch (e: Throwable) {
        null
    }

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
        rx = cellW * 0.45f
        ry = cellW * 0.17f
        charW = cellW * 0.94f
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
            tick()
            if (nowMs - startAt >= ROUND_MS) gameOver()
        }
        updateFx()
        updateBasins(dt)

        canvas.save()
        canvas.translate(0f, insetTop)
        drawSea(canvas)
        drawBoard(canvas)
        drawFx(canvas)
        drawFlyBasins(canvas)
        drawHud(canvas)
        if (phase != Phase.PLAYING) drawOverlay(canvas)
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
    }

    private fun drawMole(canvas: Canvas, i: Int, c: Cell) {
        val cx = holeX(i)
        val cut = cutY(i)
        val bmp = faces[c.face.coerceIn(0, 3)]
        val w = charW
        val h = w * bmp.height / bmp.width
        val topY = cut - h * (HEAD_LIFT + HEAD_C) + (1f - c.p) * h * RISE_TRAVEL

        canvas.save()
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
        c.basinHp = c.basinColor + 1
        c.face = 0
        c.reward = HIT_SCORE * c.basinHp
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
            c.face = min(c.face + 1, 3)
            c.basinShakeAt = nowMs
            c.lastHitAt = nowMs
            c.liveUntil = max(c.liveUntil, nowMs + 520L)
            award(c, cx, cy - charH * 0.36f, Color.rgb(214, 236, 250))
            fx.add(Fx(1, cx, cy - charH * 0.10f, 0f, 0f, 320L, nowMs, null, 0, 0f))
            buzz(12)
            beep(760)
            return
        }

        // 最后一下：盆被打飞，人物当场晕住，很快沉下去
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

        c.basinHp = 0
        c.face = 3
        c.state = 3
        c.stateAt = nowMs
        c.liveUntil = nowMs + 300L
        award(c, cx, cy - charH * 0.30f, Color.rgb(255, 214, 102))
        beep(1600)
        buzz(26)
    }

    // 每个角色总共只有一笔固定分，敲几下都在这笔里分，敲完就是 0
    private fun award(c: Cell, cx: Float, cy: Float, color: Int) {
        val g = min(HIT_SCORE, c.reward)
        if (g <= 0) return
        c.reward -= g
        score += g
        combo += 1
        fx.add(Fx(0, cx, cy, 0f, 0f, 620L, nowMs, "+$g", color, charW * 0.24f))
        fx.add(Fx(1, cx, cy, 0f, 0f, 380L, nowMs, null, 0, 0f))
    }

    private fun miss() {
        combo = 0
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
        if (e.action != MotionEvent.ACTION_DOWN) return true
        val x = e.x
        val y = e.y - insetTop

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

        if (bestIdx >= 0) hit(bestIdx) else miss()
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
        }
        phase = Phase.PLAYING
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
        if (score > best) {
            best = score
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("best", best).apply()
        }
        postInvalidateOnAnimation()
    }

    private fun beep(tag: Int) {
        try {
            tone?.startTone(
                when {
                    tag < 950 -> ToneGenerator.TONE_PROP_BEEP
                    tag < 1200 -> ToneGenerator.TONE_PROP_BEEP2
                    else -> ToneGenerator.TONE_PROP_ACK
                }, 60
            )
        } catch (t: Throwable) {
        }
    }

    private fun buzz(ms: Int) {
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
        try { tone?.release() } catch (t: Throwable) { }
    }
}
