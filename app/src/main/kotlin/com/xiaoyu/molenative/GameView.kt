package com.xiaoyu.molenative

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 打地鼠 · 原生版
 * 蓝发鲸鱼少女从海面冒头，头顶扣着铁饭盆；敲掉铁盆她会生气。
 * 画面全部实时绘制 + 三张精灵图，安装包仍只有 1 MB 上下。
 */
class GameView(context: Context) : View(context) {

    private enum class Phase { READY, PLAYING, OVER }

    private class Cell {
        var target = 0f
        var p = 0f
        var hideAt = 0L
        var hit = false
        var hitAt = 0L
        // 被打飞出去的铁饭盆
        var basinActive = false
        var bx = 0f
        var by = 0f
        var bvx = 0f
        var bvy = 0f
        var brot = 0f
        var brotv = 0f
        var balpha = 1f
        var bsize = 0f
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
        val kind: Int,
        val size: Float = 1f
    )

    private companion object {
        const val COLS = 3
        const val ROWS = 3
        const val ROUND_MS = 30_000L

        val C_WHITE = 0xFFF2FBFF.toInt()
        val C_MUTED = 0xFFA6CEE2.toInt()
        val C_GOLD = 0xFFFFD75E.toInt()
        val C_SPOT = 0xFF155F8C.toInt()
        val C_SPOT_DEEP = 0xFF0A3A5A.toInt()
        val C_FOAM = 0xFFBDE9FF.toInt()
        val C_BTN_A = 0xFF37B3F2.toInt()
        val C_BTN_B = 0xFF1668AE.toInt()
        val C_CARD = 0xFF0B3D5E.toInt()
        val C_ANGRY = 0xFFFF6B6B.toInt()
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

    private val happyBmp: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.char_happy)
    private val angryBmp: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.char_angry)
    private val basinBmp: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.basin)

    private var cellSize = 0f
    private var gridLeft = 0f
    private var gridTop = 0f
    private var spotRx = 0f
    private var spotRy = 0f

    private val startBtn = RectF()
    private val againBtn = RectF()
    private val soundChip = RectF()

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val tmp = RectF()
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val charRect = RectF()
    private val basinRect = RectF()
    private val btnPath = Path()
    private val lipPath = Path()

    init {
        best = prefs.getInt("best", 0)
        pShadow.color = 0x66000000
        isFocusable = true
        isClickable = true
    }

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
        spotRx = cellSize * 0.42f
        spotRy = cellSize * 0.17f

        pBg.shader = LinearGradient(
            0f, 0f, w * 0.2f, h.toFloat(),
            0xFF1B85BC.toInt(), 0xFF04203A.toInt(), Shader.TileMode.CLAMP
        )

        val bw = min(w * 0.58f, dp * 240f)
        val bh = dp * 56f
        startBtn.set(w / 2f - bw / 2f, h * 0.655f, w / 2f + bw / 2f, h * 0.655f + bh)
        againBtn.set(startBtn)
        soundChip.set(w / 2f - dp * 46f, h - dp * 46f, w / 2f + dp * 46f, h - dp * 20f)
    }

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
                        val c = cells[idx]
                        c.target = 1f
                        c.hit = false
                        c.hideAt = now +
                            (lerp(1250f, 700f, t) * (0.86f + Random.nextFloat() * 0.28f)).toLong()
                        splash(idx)
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
            if (c.basinActive) {
                c.bx += c.bvx * dt
                c.by += c.bvy * dt
                c.bvy += dp * 1500f * dt
                c.brot += c.brotv * dt
                c.balpha -= dt * 0.85f
                if (c.balpha <= 0f) c.basinActive = false
            }
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

    private fun splash(idx: Int) {
        val cx = holeCx(idx)
        val cy = holeCy(idx)
        repeat(7) {
            val a = -0.2f + Random.nextFloat() * 3.4f
            val sp = dp * (90f + Random.nextFloat() * 130f)
            fx.add(
                Fx(
                    cx + (Random.nextFloat() - 0.5f) * spotRx,
                    cy,
                    kotlin.math.cos(a) * sp * 0.7f,
                    -kotlin.math.abs(sin(a)) * sp,
                    0.5f, 0.5f, null, 0xFFE6F8FF.toInt(), 2, 0.7f
                )
            )
        }
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pBg)

        // 光束
        pFill.color = 0x14FFFFFF
        val rayPath = Path()
        for (i in 0 until 3) {
            val bx = width * (0.1f + i * 0.34f)
            rayPath.reset()
            rayPath.moveTo(bx, 0f)
            rayPath.lineTo(bx + width * 0.16f, 0f)
            rayPath.lineTo(bx + width * 0.34f, height * 0.95f)
            rayPath.lineTo(bx + width * 0.10f, height * 0.95f)
            rayPath.close()
            canvas.drawPath(rayPath, pFill)
        }

        pFill.color = 0x1A6FD0F5
        canvas.drawCircle(width * 0.85f, height * 0.12f, width * 0.3f, pFill)
    }

    private fun drawHud(canvas: Canvas) {
        val pad = width * 0.055f
        val topY = height * 0.055f

        text(canvas, "分数", pad, topY, 12f, C_MUTED, Paint.Align.LEFT, true)
        text(canvas, score.toString(), pad, topY + dp * 34f, 32f, C_WHITE, Paint.Align.LEFT, true)

        text(canvas, "最高", width - pad, topY, 12f, C_MUTED, Paint.Align.RIGHT, true)
        text(canvas, best.toString(), width - pad, topY + dp * 30f, 20f, C_GOLD, Paint.Align.RIGHT, true)

        if (phase == Phase.PLAYING) {
            val left = nowMs - startAt
            val ratio = ((ROUND_MS - left).coerceAtLeast(0L)).toFloat() / ROUND_MS
            val y = height * 0.152f
            val hgt = dp * 8f
            tmp.set(pad, y, width - pad, y + hgt)
            pFill.color = 0x33FFFFFF
            canvas.drawRoundRect(tmp, hgt / 2f, hgt / 2f, pFill)
            pFill.color = when {
                ratio > 0.5f -> 0xFF5FE3C0.toInt()
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

    private fun drawBoard(canvas: Canvas) {
        for (i in 0 until COLS * ROWS) {
            val cx = holeCx(i)
            val cy = holeCy(i)
            val c = cells[i]

            // 水面
            tmp.set(cx - spotRx * 1.12f, cy - spotRy * 1.16f, cx + spotRx * 1.12f, cy + spotRy * 1.16f)
            pFill.color = 0x332FA8DC
            canvas.drawOval(tmp, pFill)

            tmp.set(cx - spotRx, cy - spotRy, cx + spotRx, cy + spotRy)
            pFill.color = C_SPOT_DEEP
            canvas.drawOval(tmp, pFill)

            // 涟漪
            val rip = ((time * 0.45f + i * 0.17f) % 1f)
            outerRect.set(
                cx - spotRx * (1f + rip * 0.22f), cy - spotRy * (1f + rip * 0.3f),
                cx + spotRx * (1f + rip * 0.22f), cy + spotRy * (1f + rip * 0.3f)
            )
            pStroke.color = 0x40E8FBFF
            pStroke.strokeWidth = dp * 1.6f
            pStroke.alpha = ((1f - rip) * 150).toInt()
            canvas.drawOval(outerRect, pStroke)
            pStroke.alpha = 255

            if (c.p > 0.01f) drawChar(canvas, c, cx, cy + spotRy * 0.5f, cellSize * 0.80f, i)

            // 水面盖住她的下半身
            val lip = lipPath
            lip.reset()
            outerRect.set(cx - spotRx * 1.04f, cy - spotRy * 1.06f, cx + spotRx * 1.04f, cy + spotRy * 1.06f)
            innerRect.set(cx - spotRx * 0.86f, cy - spotRy * 0.80f, cx + spotRx * 0.86f, cy + spotRy * 0.80f)
            lip.addArc(outerRect, 0f, 180f)
            lip.addArc(innerRect, 180f, -180f)
            lip.close()
            pFill.color = 0x552FA8DC
            canvas.drawPath(lip, pFill)
            pStroke.color = C_FOAM
            pStroke.strokeWidth = dp * 2f
            pStroke.alpha = 90
            canvas.drawPath(lip, pStroke)
            pStroke.alpha = 255

            if (c.basinActive) drawFlyingBasin(canvas, c)
        }
    }

    private fun drawChar(canvas: Canvas, c: Cell, cx: Float, baseY: Float, w: Float, idx: Int) {
        val s = easeOutBack(c.p).coerceIn(0f, 1.12f)
        if (s <= 0.02f) return
        val h = w * 0.8203f
        val by = baseY + (1f - s) * h * 0.95f
        val left = cx - w * s / 2f
        val topY = by - h * s
        charRect.set(left, topY, cx + w * s / 2f, by)
        val bmp = if (c.hit) angryBmp else happyBmp
        canvas.drawBitmap(bmp, null, charRect, pBmp)

        if (!c.hit) {
            val bw = w * 0.60f * s
            val bh = bw * 0.625f
            val tilt = sin((time + idx * 0.7f) * 2.0f) * 3.5f
            canvas.save()
            canvas.rotate(tilt, cx, topY + bh * 0.35f)
            basinRect.set(cx - bw / 2f, topY - bh * 0.42f, cx + bw / 2f, topY + bh * 0.58f)
            canvas.drawBitmap(basinBmp, null, basinRect, pBmp)
            canvas.restore()
        }
    }

    private fun drawFlyingBasin(canvas: Canvas, c: Cell) {
        val sz = c.bsize
        pBmp.alpha = (255 * c.balpha).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.rotate(c.brot, c.bx, c.by)
        basinRect.set(c.bx - sz, c.by - sz * 0.62f, c.bx + sz, c.by + sz * 0.62f)
        canvas.drawBitmap(basinBmp, null, basinRect, pBmp)
        canvas.restore()
        pBmp.alpha = 255
    }

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
                    canvas.drawCircle(f.x, f.y, spotRx * 1.4f * (1f - k), pStroke)
                    pStroke.alpha = 255
                }
                2 -> {
                    pFill.color = f.color
                    pFill.alpha = (230 * k).toInt()
                    canvas.drawCircle(f.x, f.y, dp * 2.4f * f.size * k + dp * 0.7f, pFill)
                    pFill.alpha = 255
                }
                3 -> {
                    val pop = 0.7f + 0.3f * min(1f, (1f - k) * 4f)
                    text(
                        canvas, f.text ?: "", f.x, f.y, 22f * pop, f.color,
                        Paint.Align.CENTER, true, (255 * k).toInt()
                    )
                }
                5 -> {
                    // 生气符号（十字青筋）
                    val r = dp * 6f
                    pStroke.color = f.color
                    pStroke.strokeWidth = dp * 2.6f
                    pStroke.alpha = (255 * k).toInt()
                    canvas.drawLine(f.x - r, f.y - r, f.x + r, f.y + r, pStroke)
                    canvas.drawLine(f.x - r, f.y + r, f.x + r, f.y - r, pStroke)
                    pStroke.alpha = 255
                }
            }
        }
    }

    private fun drawReady(canvas: Canvas) {
        pFill.color = 0xB3041C30.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)

        val bob = sin(time * 2.0f) * dp * 3f
        demoCell.p = 1f
        demoCell.hit = false
        drawChar(canvas, demoCell, width / 2f, height * 0.255f + bob, width * 0.46f, 0)

        text(canvas, "打地鼠", width / 2f, height * 0.375f, 44f, C_WHITE, Paint.Align.CENTER, true)
        text(
            canvas, "敲掉她头上的铁饭盆", width / 2f, height * 0.375f + dp * 32f,
            14f, C_MUTED, Paint.Align.CENTER
        )
        text(
            canvas, "最高分  $best", width / 2f, height * 0.375f + dp * 68f,
            15f, C_GOLD, Paint.Align.CENTER, true
        )

        drawButton(canvas, startBtn, "开 始")
        text(
            canvas, "30 秒 · 打得越快分越高", width / 2f, startBtn.bottom + dp * 30f,
            12f, C_MUTED, Paint.Align.CENTER
        )
        drawSoundChip(canvas)
    }

    private val demoCell = Cell()

    private fun drawOver(canvas: Canvas) {
        pFill.color = 0xB3041C30.toInt()
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
            if (c.p < 0.45f || c.hit) continue
            val dx = (x - holeCx(i)) / (spotRx * 1.15f)
            val dy = (y - (holeCy(i) - spotRy * 2.2f)) / (spotRy * 4.4f)
            val d = dx * dx + dy * dy
            if (d < 1f && d < bestD) {
                bestD = d
                idx = i
            }
        }

        if (idx >= 0) {
            val c = cells[idx]
            val pts = 1 + min(combo / 5, 3)
            score += pts
            combo += 1
            lastHitAt = nowMs
            c.hit = true
            c.hitAt = nowMs
            c.hideAt = nowMs + 750

            // 铁盆飞出去
            val w = cellSize * 0.80f
            c.bsize = w * 0.60f / 2f
            c.bx = holeCx(idx)
            c.by = holeCy(idx) - w * 0.8203f + dp * 4f
            c.bvx = (if (Random.nextBoolean()) 1 else -1) * dp * (80f + Random.nextFloat() * 120f)
            c.bvy = -dp * (430f + Random.nextFloat() * 130f)
            c.brot = 0f
            c.brotv = (if (c.bvx > 0) 1 else -1) * (260f + Random.nextFloat() * 240f)
            c.balpha = 1f
            c.basinActive = true

            val cx = holeCx(idx)
            val cy = holeCy(idx) - spotRy * 2.6f
            fx.add(Fx(cx, cy, 0f, -dp * 90f, 0.7f, 0.7f, "+$pts", C_GOLD, 0))
            fx.add(Fx(cx, holeCy(idx), 0f, 0f, 0.4f, 0.4f, null, 0xFFFFE082.toInt(), 1))
            fx.add(Fx(cx, cy, 0f, -dp * 40f, 0.85f, 0.85f, "angry", C_ANGRY, 5))
            if (combo >= 3) {
                fx.add(
                    Fx(
                        width / 2f, gridTop + cellSize * 0.30f, 0f, -dp * 24f,
                        0.9f, 0.9f, "连击 ×$combo", 0xFFFFB74D.toInt(), 3
                    )
                )
            }
            buzz(18)
            beep(true)
        } else {
            if (combo > 0) combo = 0
            buzz(8)
            beep(false)
        }
    }

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
            c.hit = false
            c.basinActive = false
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

    private fun holeCx(i: Int): Float = gridLeft + (i % COLS) * cellSize + cellSize / 2f

    private fun holeCy(i: Int): Float = gridTop + (i / COLS) * cellSize + cellSize * 0.64f

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
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            tone?.release()
        } catch (e: Exception) {
        }
        tone = null
    }
}
