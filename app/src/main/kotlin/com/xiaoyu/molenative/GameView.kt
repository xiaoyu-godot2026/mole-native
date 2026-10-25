package com.xiaoyu.molenative

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 打地鼠 · 原生版
 * 全部画面由 Canvas 实时绘制，不依赖任何图片资源，安装包只有几十 KB。
 */
class GameView(context: Context) : View(context) {

    private enum class Phase { READY, PLAYING, OVER }

    private class Cell {
        var target = 0f      // 目标位置：0 藏起来，1 探出来
        var p = 0f           // 当前动画进度
        var hideAt = 0L      // 该缩回去的时间
        var flash = 0f       // 命中反馈
    }

    private class Fx(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        val maxLife: Float,
        val text: String?,
        val color: Int,
        val kind: Int
    )

    private companion object {
        const val COLS = 3
        const val ROWS = 3
        const val ROUND_MS = 30_000L

        val C_WHITE = 0xFFF7FFF9.toInt()
        val C_MUTED = 0xFF9FC7AE.toInt()
        val C_HOLE = 0xFF150C06.toInt()
        val C_HOLE_RIM = 0xFF5A3A22.toInt()
        val C_HOLE_LIP = 0xFF8A6444.toInt()
        val C_FUR = 0xFF9A6739.toInt()
        val C_FUR_LIGHT = 0xFFC08A5A.toInt()
        val C_EAR = 0xFF7A4F2A.toInt()
        val C_EAR_IN = 0xFFD89E76.toInt()
        val C_MUZZLE = 0xFFF5DDBC.toInt()
        val C_NOSE = 0xFFEC7C92.toInt()
        val C_CHEEK = 0xFFF093A1.toInt()
        val C_INK = 0xFF22130A.toInt()
        val C_GOLD = 0xFFFFD75E.toInt()
        val C_BTN_A = 0xFFFFA24F.toInt()
        val C_BTN_B = 0xFFF2612B.toInt()
        val C_CARD = 0xFF123A2A.toInt()
        val C_GRASS = 0xFF2E8B58.toInt()
    }

    private val cells = Array(COLS * ROWS) { Cell() }
    private val fx = ArrayList<Fx>()

    private var phase = Phase.READY
    private var score = 0
    private var combo = 0
    private var best = 0
    private var newRecord = false
    private var startAt = 0L
    private var nextSpawnAt = 0L
    private var lastHitAt = 0L
    private var lastFrame = 0L
    private var nowMs = 0L
    private var muted = false
    private var time = 0f

    private val prefs by lazy { context.getSharedPreferences("mole", Context.MODE_PRIVATE) }
    private val vib by lazy { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    private var tone: ToneGenerator? = null

    private val dp = resources.displayMetrics.density

    // ---- 布局 ----
    private var cellSize = 0f
    private var gridLeft = 0f
    private var gridTop = 0f
    private var holeRx = 0f
    private var holeRy = 0f

    private val startBtn = RectF()
    private val againBtn = RectF()
    private val soundChip = RectF()

    // ---- 画笔 ----
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmp = RectF()
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val btnPath = Path()
    private val rimPath = Path()
    private val bodyPath = Path()
    private val mouthPath = Path()

    init {
        best = prefs.getInt("best", 0)
        pShadow.color = 0x66000000
        isFocusable = true
        isClickable = true
    }

    // ------------------------------------------------------------------ 尺寸
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = w * 0.055f
        val top = h * 0.22f
        val bot = h * 0.885f
        val cw = (w - pad * 2f) / COLS
        val ch = (bot - top) / ROWS
        cellSize = min(cw, ch)
        gridLeft = (w - cellSize * COLS) / 2f
        gridTop = top + ((bot - top) - cellSize * ROWS) / 2f
        holeRx = cellSize * 0.40f
        holeRy = cellSize * 0.185f

        pBg.shader = LinearGradient(
            0f, 0f, w * 0.25f, h.toFloat(),
            0xFF145034.toInt(), 0xFF061A12.toInt(), Shader.TileMode.CLAMP
        )

        val bw = min(w * 0.58f, dp * 240f)
        val bh = dp * 56f
        startBtn.set(w / 2f - bw / 2f, h * 0.615f, w / 2f + bw / 2f, h * 0.615f + bh)
        againBtn.set(startBtn)
        soundChip.set(w / 2f - dp * 46f, h - dp * 46f, w / 2f + dp * 46f, h - dp * 20f)

    }

    // ------------------------------------------------------------------ 帧循环
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val now = System.currentTimeMillis()
        nowMs = now
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame).coerceIn(0L, 60L)) / 1000f
        lastFrame = now
        time += dt

        update(dt, now)
        drawBackground(canvas)
        drawHud(canvas)
        drawBoard(canvas)
        drawFx(canvas)
        when (phase) {
            Phase.READY -> drawReady(canvas)
            Phase.OVER -> drawOver(canvas)
            Phase.PLAYING -> {}
        }
        postInvalidateOnAnimation()
    }

    private fun update(dt: Float, now: Long) {
        if (phase == Phase.PLAYING) {
            val elapsed = now - startAt
            if (elapsed >= ROUND_MS) {
                endGame()
            } else {
                val t = elapsed.toFloat() / ROUND_MS
                val maxActive = when {
                    t < 0.30f -> 1
                    t < 0.65f -> 2
                    else -> 3
                }
                val active = cells.count { it.target > 0f }
                if (now >= nextSpawnAt && active < maxActive) {
                    val free = ArrayList<Int>(cells.size)
                    for (i in cells.indices) {
                        if (cells[i].target == 0f && cells[i].p < 0.10f) free.add(i)
                    }
                    if (free.isNotEmpty()) {
                        val idx = free[Random.nextInt(free.size)]
                        cells[idx].target = 1f
                        cells[idx].hideAt = now +
                            (lerp(1150f, 620f, t) * (0.86f + Random.nextFloat() * 0.28f)).toLong()
                    }
                    nextSpawnAt = now +
                        (lerp(790f, 330f, t) * (0.85f + Random.nextFloat() * 0.3f)).toLong()
                }
            }
        }

        for (c in cells) {
            c.p += (c.target - c.p) * min(1f, dt * 15f)
            if (c.p < 0.003f && c.target == 0f) c.p = 0f
            if (c.target > 0f && now >= c.hideAt) c.target = 0f
            if (c.flash > 0f) c.flash = max(0f, c.flash - dt * 2.6f)
        }

        if (combo > 0 && now - lastHitAt > 1700) combo = 0

        val it = fx.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.life -= dt
            f.x += f.vx * dt
            f.y += f.vy * dt
            if (f.kind == 2) f.vy += dp * 900f * dt
            if (f.life <= 0f) it.remove()
        }
    }

    // ------------------------------------------------------------------ 背景
    private fun drawBackground(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pBg)

        pFill.color = 0x2233C97E
        canvas.drawCircle(width * 0.15f, height * 0.10f, width * 0.42f, pFill)
        pFill.color = 0x1A1FB26C
        canvas.drawCircle(width * 0.92f, height * 0.30f, width * 0.36f, pFill)

        // 底部草地
        val grassTop = height * 0.945f
        pFill.color = 0x3316B36B
        canvas.drawRect(0f, grassTop, width.toFloat(), height.toFloat(), pFill)
    }

    // ------------------------------------------------------------------ 顶部信息
    private fun drawHud(canvas: Canvas) {
        val pad = width * 0.055f
        val topY = height * 0.055f

        text(canvas, "分数", pad, topY, 12f, C_MUTED, Paint.Align.LEFT, true)
        text(canvas, score.toString(), pad, topY + dp * 34f, 32f, C_WHITE, Paint.Align.LEFT, true)

        text(canvas, "最高", width - pad, topY, 12f, C_MUTED, Paint.Align.RIGHT, true)
        text(
            canvas, best.toString(), width - pad, topY + dp * 30f, 20f,
            C_GOLD, Paint.Align.RIGHT, true
        )

        if (phase == Phase.PLAYING) {
            val left = nowMs - startAt
            val ratio = ((ROUND_MS - left).coerceAtLeast(0L)).toFloat() / ROUND_MS
            val y = height * 0.152f
            val hgt = dp * 8f
            tmp.set(pad, y, width - pad, y + hgt)
            pFill.color = 0x33FFFFFF
            canvas.drawRoundRect(tmp, hgt / 2f, hgt / 2f, pFill)
            pFill.color = when {
                ratio > 0.5f -> 0xFF4CD787.toInt()
                ratio > 0.22f -> 0xFFFFC044.toInt()
                else -> 0xFFFF6B5E.toInt()
            }
            tmp.set(pad, y, pad + (width - pad * 2f) * ratio, y + hgt)
            canvas.drawRoundRect(tmp, hgt / 2f, hgt / 2f, pFill)

            val secs = ((ROUND_MS - left) / 1000).toInt() + 1
            text(canvas, "${secs}s", width - pad, y + dp * 26f, 12f, C_MUTED, Paint.Align.RIGHT)
        }

        if (combo >= 2) {
            val pop = min(1f, (nowMs - lastHitAt) / 120f)
            text(
                canvas, "连击 ×$combo", width / 2f, height * 0.205f,
                15f + 4f * pop, C_GOLD, Paint.Align.CENTER, true
            )
        }
    }

    // ------------------------------------------------------------------ 棋盘
    private fun drawBoard(canvas: Canvas) {
        for (i in 0 until COLS * ROWS) {
            val cx = holeCx(i)
            val cy = holeCy(i)

            tmp.set(cx - holeRx * 1.06f, cy - holeRy * 1.15f, cx + holeRx * 1.06f, cy + holeRy * 1.15f)
            pFill.color = C_HOLE_RIM
            canvas.drawOval(tmp, pFill)
            tmp.set(cx - holeRx, cy - holeRy, cx + holeRx, cy + holeRy)
            pFill.color = C_HOLE
            canvas.drawOval(tmp, pFill)
            pStroke.color = 0x1AFFFFFF
            pStroke.strokeWidth = dp * 1.2f
            canvas.drawOval(tmp, pStroke)

            val c = cells[i]
            if (c.p > 0.01f) {
                drawMole(canvas, cx, cy + holeRy * 0.95f, holeRx * 0.80f, holeRy * 1.95f, c.p, c.flash, i)
            }

            // 前沿：遮住鼹鼠下半身，做出“从洞里钻出来”的感觉
            // 前沿：外圈下半沿 + 内圈下半沿反向 = 一圈月牙形的土沿
            val rim = rimPath
            rim.reset()
            outerRect.set(cx - holeRx * 1.07f, cy - holeRy * 1.14f, cx + holeRx * 1.07f, cy + holeRy * 1.14f)
            innerRect.set(cx - holeRx * 0.90f, cy - holeRy * 0.92f, cx + holeRx * 0.90f, cy + holeRy * 0.92f)
            rim.addArc(outerRect, 0f, 180f)
            rim.addArc(innerRect, 180f, -180f)
            rim.close()
            pFill.color = C_HOLE_RIM
            canvas.drawPath(rim, pFill)
            pStroke.color = C_HOLE_LIP
            pStroke.strokeWidth = dp * 1.2f
            pStroke.alpha = 110
            canvas.drawPath(rim, pStroke)
            pStroke.alpha = 255
        }
    }

    private fun drawMole(
        canvas: Canvas,
        cx: Float,
        baseY: Float,
        mw: Float,
        mh: Float,
        progress: Float,
        flash: Float,
        idx: Int
    ) {
        val sRaw = easeOutBack(progress)
        val s = sRaw.coerceIn(0f, 1.15f)
        if (s <= 0.02f) return

        val by = baseY + (1f - s) * mh * 2.3f

        canvas.save()
        canvas.scale(s, s, cx, by)

        // 耳朵
        pFill.color = C_EAR
        canvas.drawCircle(cx - mw * 0.88f, by - mh * 1.16f, mw * 0.27f, pFill)
        canvas.drawCircle(cx + mw * 0.88f, by - mh * 1.16f, mw * 0.27f, pFill)
        pFill.color = C_EAR_IN
        canvas.drawCircle(cx - mw * 0.88f, by - mh * 1.16f, mw * 0.13f, pFill)
        canvas.drawCircle(cx + mw * 0.88f, by - mh * 1.16f, mw * 0.13f, pFill)

        // 身体
        val body = bodyPath
        body.reset()
        body.moveTo(cx - mw, by)
        body.cubicTo(cx - mw, by - mh * 1.95f, cx + mw, by - mh * 1.95f, cx + mw, by)
        body.close()
        pFill.color = C_FUR
        canvas.drawPath(body, pFill)

        // 头顶高光
        pFill.color = C_FUR_LIGHT
        pFill.alpha = 70
        tmp.set(cx - mw * 0.50f, by - mh * 1.30f, cx + mw * 0.50f, by - mh * 0.88f)
        canvas.drawOval(tmp, pFill)
        pFill.alpha = 255

        // 眼睛
        val eyeY = by - mh * 1.02f
        val eyeDx = mw * 0.36f
        val blink = flash <= 0.01f && ((nowMs + idx * 640L) % 3700L) > 3560L
        if (flash > 0.01f) {
            pStroke.color = C_INK
            pStroke.strokeWidth = mw * 0.10f
            for (sgn in intArrayOf(-1, 1)) {
                val ex = cx + sgn * eyeDx
                val r = mw * 0.20f
                canvas.drawLine(ex - r, eyeY - r, ex + r, eyeY + r, pStroke)
                canvas.drawLine(ex - r, eyeY + r, ex + r, eyeY - r, pStroke)
            }
        } else if (blink) {
            pStroke.color = C_INK
            pStroke.strokeWidth = mw * 0.075f
            canvas.drawLine(cx - eyeDx - mw * 0.17f, eyeY, cx - eyeDx + mw * 0.17f, eyeY, pStroke)
            canvas.drawLine(cx + eyeDx - mw * 0.17f, eyeY, cx + eyeDx + mw * 0.17f, eyeY, pStroke)
        } else {
            pFill.color = 0xFFFFFFFF.toInt()
            tmp.set(cx - eyeDx - mw * 0.21f, eyeY - mw * 0.25f, cx - eyeDx + mw * 0.21f, eyeY + mw * 0.25f)
            canvas.drawOval(tmp, pFill)
            tmp.set(cx + eyeDx - mw * 0.21f, eyeY - mw * 0.25f, cx + eyeDx + mw * 0.21f, eyeY + mw * 0.25f)
            canvas.drawOval(tmp, pFill)

            pFill.color = C_INK
            canvas.drawCircle(cx - eyeDx + mw * 0.03f, eyeY + mw * 0.02f, mw * 0.105f, pFill)
            canvas.drawCircle(cx + eyeDx + mw * 0.03f, eyeY + mw * 0.02f, mw * 0.105f, pFill)

            pFill.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(cx - eyeDx - mw * 0.02f, eyeY - mw * 0.06f, mw * 0.042f, pFill)
            canvas.drawCircle(cx + eyeDx - mw * 0.02f, eyeY - mw * 0.06f, mw * 0.042f, pFill)
        }

        // 口鼻
        tmp.set(cx - mw * 0.58f, by - mh * 0.96f, cx + mw * 0.58f, by - mh * 0.16f)
        pFill.color = C_MUZZLE
        canvas.drawOval(tmp, pFill)

        tmp.set(cx - mw * 0.17f, by - mh * 0.93f, cx + mw * 0.17f, by - mh * 0.71f)
        pFill.color = C_NOSE
        canvas.drawOval(tmp, pFill)

        pStroke.color = 0xFF8B5636.toInt()
        pStroke.strokeWidth = mw * 0.05f
        val mouth = mouthPath
        mouth.reset()
        mouth.moveTo(cx, by - mh * 0.72f)
        mouth.lineTo(cx, by - mh * 0.60f)
        mouth.moveTo(cx, by - mh * 0.60f)
        mouth.quadTo(cx - mw * 0.15f, by - mh * 0.45f, cx - mw * 0.25f, by - mh * 0.56f)
        mouth.moveTo(cx, by - mh * 0.60f)
        mouth.quadTo(cx + mw * 0.15f, by - mh * 0.45f, cx + mw * 0.25f, by - mh * 0.56f)
        canvas.drawPath(mouth, pStroke)

        // 腮红
        pFill.color = C_CHEEK
        pFill.alpha = 145
        tmp.set(cx - mw * 0.88f, by - mh * 0.78f, cx - mw * 0.56f, by - mh * 0.56f)
        canvas.drawOval(tmp, pFill)
        tmp.set(cx + mw * 0.56f, by - mh * 0.78f, cx + mw * 0.88f, by - mh * 0.56f)
        canvas.drawOval(tmp, pFill)
        pFill.alpha = 255

        canvas.restore()
    }

    // ------------------------------------------------------------------ 特效
    private fun drawFx(canvas: Canvas) {
        for (f in fx) {
            val k = (f.life / f.maxLife).coerceIn(0f, 1f)
            when (f.kind) {
                0 -> text(
                    canvas, f.text ?: "", f.x, f.y, 20f, f.color,
                    Paint.Align.CENTER, true, (255 * k).toInt()
                )
                1 -> {
                    pStroke.color = f.color
                    pStroke.strokeWidth = dp * 3f * k
                    pStroke.alpha = (200 * k).toInt()
                    canvas.drawCircle(f.x, f.y, holeRx * 1.5f * (1f - k), pStroke)
                    pStroke.alpha = 255
                }
                2 -> {
                    pFill.color = f.color
                    pFill.alpha = (230 * k).toInt()
                    canvas.drawCircle(f.x, f.y, dp * 2.6f * k + dp * 0.8f, pFill)
                    pFill.alpha = 255
                }
                3 -> {
                    val pop = 0.7f + 0.3f * min(1f, (1f - k) * 4f)
                    text(
                        canvas, f.text ?: "", f.x, f.y, 22f * pop, f.color,
                        Paint.Align.CENTER, true, (255 * k).toInt()
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ 开始页
    private fun drawReady(canvas: Canvas) {
        pFill.color = 0xB307241A.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)

        val bob = sin(time * 2.2f) * dp * 3f
        drawMole(canvas, width / 2f, height * 0.235f + bob, width * 0.105f, width * 0.14f, 1f, 0f, 0)

        text(canvas, "打地鼠", width / 2f, height * 0.345f, 44f, C_WHITE, Paint.Align.CENTER, true)
        text(
            canvas, "30 秒 · 打得越快分越高", width / 2f, height * 0.345f + dp * 34f,
            14f, C_MUTED, Paint.Align.CENTER
        )
        text(
            canvas, "最高分  $best", width / 2f, height * 0.345f + dp * 72f,
            15f, C_GOLD, Paint.Align.CENTER, true
        )

        drawButton(canvas, startBtn, "开 始")
        text(
            canvas, "点击冒头的地鼠", width / 2f, startBtn.bottom + dp * 30f,
            12f, C_MUTED, Paint.Align.CENTER
        )
        drawSoundChip(canvas)
    }

    // ------------------------------------------------------------------ 结算页
    private fun drawOver(canvas: Canvas) {
        pFill.color = 0xB307241A.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)

        val cw = width * 0.84f
        val chh = dp * 336f
        val card = RectF(width / 2f - cw / 2f, height / 2f - chh / 2f, width / 2f + cw / 2f, height / 2f + chh / 2f)
        pFill.color = C_CARD
        canvas.drawRoundRect(card, dp * 26f, dp * 26f, pFill)
        pStroke.color = 0x33FFFFFF
        pStroke.strokeWidth = dp * 1.4f
        canvas.drawRoundRect(card, dp * 26f, dp * 26f, pStroke)

        text(canvas, "本局得分", width / 2f, card.top + dp * 46f, 13f, C_MUTED, Paint.Align.CENTER, true)
        text(canvas, score.toString(), width / 2f, card.top + dp * 118f, 60f, C_WHITE, Paint.Align.CENTER, true)

        if (newRecord) {
            tmp.set(width / 2f - dp * 52f, card.top + dp * 136f, width / 2f + dp * 52f, card.top + dp * 164f)
            pFill.color = 0x33FFD75E
            canvas.drawRoundRect(tmp, dp * 14f, dp * 14f, pFill)
            text(canvas, "新纪录！", width / 2f, card.top + dp * 158f, 12f, C_GOLD, Paint.Align.CENTER, true)
        }

        text(
            canvas, "最高分  $best", width / 2f, card.top + dp * 200f,
            14f, C_MUTED, Paint.Align.CENTER
        )

        againBtn.set(
            width / 2f - dp * 96f, card.bottom - dp * 96f,
            width / 2f + dp * 96f, card.bottom - dp * 40f
        )
        drawButton(canvas, againBtn, "再来一局")
        drawSoundChip(canvas)
    }

    // ------------------------------------------------------------------ 组件
    private fun drawButton(canvas: Canvas, r: RectF, label: String) {
        val rr = r.height() / 2f
        tmp.set(r.left, r.top + dp * 5f, r.right, r.bottom + dp * 5f)
        canvas.drawRoundRect(tmp, rr, rr, pShadow)

        btnPath.reset()
        btnPath.addRoundRect(r, rr, rr, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(btnPath)
        val bands = 12
        val bh = r.height() / bands
        for (i in 0 until bands) {
            pFill.color = blend(C_BTN_A, C_BTN_B, i / (bands - 1f))
            canvas.drawRect(r.left, r.top + i * bh, r.right, r.top + (i + 1) * bh + 1f, pFill)
        }
        canvas.restore()

        text(canvas, label, r.centerX(), r.centerY() + dp * 8f, 19f, 0xFFFFFFFF.toInt(), Paint.Align.CENTER, true)
    }

    private fun blend(c1: Int, c2: Int, t: Float): Int {
        val a = ((c1 ushr 24 and 0xFF) + (((c2 ushr 24 and 0xFF) - (c1 ushr 24 and 0xFF)) * t)).toInt()
        val r = ((c1 ushr 16 and 0xFF) + (((c2 ushr 16 and 0xFF) - (c1 ushr 16 and 0xFF)) * t)).toInt()
        val g = ((c1 ushr 8 and 0xFF) + (((c2 ushr 8 and 0xFF) - (c1 ushr 8 and 0xFF)) * t)).toInt()
        val b = ((c1 and 0xFF) + (((c2 and 0xFF) - (c1 and 0xFF)) * t)).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun drawSoundChip(canvas: Canvas) {
        pFill.color = 0x22FFFFFF
        canvas.drawRoundRect(soundChip, soundChip.height() / 2f, soundChip.height() / 2f, pFill)
        text(
            canvas, if (muted) "音效 关" else "音效 开",
            soundChip.centerX(), soundChip.centerY() + dp * 5f,
            12f, C_MUTED, Paint.Align.CENTER
        )
    }

    private fun text(
        canvas: Canvas,
        s: String,
        x: Float,
        y: Float,
        sizeSp: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
        bold: Boolean = false,
        alpha: Int = 255
    ) {
        pText.textSize = sizeSp * dp
        pText.color = color
        pText.alpha = alpha
        pText.textAlign = align
        pText.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        canvas.drawText(s, x, y, pText)
    }

    // ------------------------------------------------------------------ 输入
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN) return true
        if (cellSize == 0f) return true
        val x = e.x
        val y = e.y

        if (soundChip.contains(x, y)) {
            muted = !muted
            if (!muted) beep(true)
            return true
        }

        when (phase) {
            Phase.READY -> if (startBtn.contains(x, y)) start()
            Phase.OVER -> if (againBtn.contains(x, y)) start()
            Phase.PLAYING -> tryHit(x, y)
        }
        return true
    }

    private fun tryHit(x: Float, y: Float) {
        var idx = -1
        var bestD = 1.6f
        for (i in cells.indices) {
            val c = cells[i]
            if (c.p < 0.45f) continue
            val dx = (x - holeCx(i)) / (holeRx * 1.25f)
            val dy = (y - (holeCy(i) - holeRy * 1.5f)) / (holeRy * 3.0f)
            val d = dx * dx + dy * dy
            if (d < 1f && d < bestD) {
                bestD = d
                idx = i
            }
        }

        if (idx >= 0) {
            val pts = 1 + min(combo / 5, 3)
            score += pts
            combo += 1
            lastHitAt = nowMs
            cells[idx].target = 0f
            cells[idx].flash = 1f

            val cx = holeCx(idx)
            val cy = holeCy(idx) - holeRy * 1.9f
            fx.add(Fx(cx, cy, 0f, -dp * 90f, 0.7f, 0.7f, "+$pts", C_GOLD, 0))
            fx.add(Fx(cx, holeCy(idx), 0f, 0f, 0.4f, 0.4f, null, 0xFFFFD54F.toInt(), 1))
            repeat(5) {
                val a = Random.nextFloat() * 6.283f
                fx.add(
                    Fx(
                        cx, cy,
                        cos(a) * dp * 130f,
                        sin(a) * dp * 130f - dp * 40f,
                        0.42f, 0.42f, null, 0xFFFFF3B0.toInt(), 2
                    )
                )
            }
            if (combo >= 3) {
                fx.add(
                    Fx(
                        width / 2f, gridTop + cellSize * 0.30f, 0f, -dp * 24f,
                        0.9f, 0.9f, "连击 ×$combo", 0xFFFFB74D.toInt(), 3
                    )
                )
            }
            buzz(16)
            beep(true)
        } else {
            if (combo > 0) combo = 0
            buzz(8)
            beep(false)
        }
    }

    // ------------------------------------------------------------------ 流程
    private fun start() {
        val now = System.currentTimeMillis()
        phase = Phase.PLAYING
        score = 0
        combo = 0
        newRecord = false
        startAt = now
        nowMs = now
        nextSpawnAt = now + 420
        lastFrame = 0L
        for (c in cells) {
            c.target = 0f
            c.p = 0f
            c.flash = 0f
        }
        fx.clear()
    }

    private fun endGame() {
        phase = Phase.OVER
        for (c in cells) c.target = 0f
        if (score > best) {
            best = score
            newRecord = true
            prefs.edit().putInt("best", best).apply()
        }
    }

    // ------------------------------------------------------------------ 工具
    private fun holeCx(i: Int): Float = gridLeft + (i % COLS) * cellSize + cellSize / 2f

    private fun holeCy(i: Int): Float = gridTop + (i / COLS) * cellSize + cellSize * 0.62f

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val p = x.coerceIn(0f, 1f) - 1f
        return 1f + c3 * p * p * p + c1 * p * p
    }

    private fun buzz(ms: Long) {
        try {
            vib?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // 设备无振动器时忽略
        }
    }

    private fun beep(hit: Boolean) {
        if (muted) return
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
            tone?.startTone(
                if (hit) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK,
                if (hit) 45 else 30
            )
        } catch (e: Exception) {
            // 部分设备限制音频，忽略
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            tone?.release()
        } catch (e: Exception) {
            // ignore
        }
        tone = null
    }
}
