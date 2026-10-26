package com.xiaoyu.molenative

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Base64
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 拍点表：BPM + 第一拍相位 + 每拍能量强度。
 * 整个玩法由它驱动（哪一拍出怪、出几只）。
 */
class BeatMap(
    val bpm: Double,
    val periodMs: Double,
    val phaseMs: Double,
    val durationMs: Long,
    private val energy: ByteArray
) {
    val count: Int = if (periodMs < 1.0) 0 else max(0, ((durationMs - phaseMs) / periodMs).toInt())

    fun time(k: Int): Double = phaseMs + k * periodMs

    /** 0..255，越大越"炸" */
    fun energyAt(k: Int): Int = if (k >= 0 && k < energy.size) energy[k].toInt() and 0xFF else 128

    fun encode(): String = bpm.toString() + "|" + periodMs + "|" + phaseMs + "|" +
            durationMs + "|" + Base64.encodeToString(energy, Base64.NO_WRAP)

    companion object {
        fun decode(s: String?): BeatMap? {
            if (s == null || s.isEmpty()) return null
            return try {
                val p = s.split("|")
                if (p.size < 5) return null
                BeatMap(
                    p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toLong(),
                    Base64.decode(p[4], Base64.NO_WRAP)
                )
            } catch (t: Throwable) {
                null
            }
        }
    }
}

/**
 * 解码音频 -> 能量包络 -> 自相关求主周期(BPM)与相位 -> 每拍强度。
 * 全程本地计算，不上传、不需要网络。
 */
object BeatDetector {

    private const val HOP = 512
    private const val CACHE = "beat_v3_"
    private const val MAX_SECONDS = 330

    fun cacheKey(uriStr: String?): String = CACHE + (uriStr ?: "builtin")

    fun loadCache(ctx: Context, uriStr: String?): BeatMap? {
        return try {
            val sp = ctx.getSharedPreferences(GameAudio.PREFS, Context.MODE_PRIVATE)
            BeatMap.decode(sp.getString(cacheKey(uriStr), null))
        } catch (t: Throwable) {
            null
        }
    }

    fun saveCache(ctx: Context, uriStr: String?, m: BeatMap) {
        try {
            val sp = ctx.getSharedPreferences(GameAudio.PREFS, Context.MODE_PRIVATE)
            sp.edit().putString(cacheKey(uriStr), m.encode()).apply()
        } catch (t: Throwable) {
        }
    }

    fun analyze(ctx: Context, uri: Uri?, resId: Int): BeatMap? {
        val env = envelope(ctx, uri, resId) ?: return null
        val e = env.env
        val n = e.size
        if (n < 600) return null

        val nov = FloatArray(n)
        for (t in 1 until n) {
            val d = ln(1.0 + e[t]).toFloat() - ln(1.0 + e[t - 1]).toFloat()
            nov[t] = if (d > 0f) d else 0f
        }
        var mean = 0.0
        for (v in nov) mean += v
        mean /= n
        for (t in 0 until n) {
            val v = nov[t] - (mean * 0.5).toFloat()
            nov[t] = if (v > 0f) v else 0f
        }

        val fps = env.sampleRate.toDouble() / HOP
        val lagMin = max(8, (fps * 0.26).toInt())
        val lagMax = min(n / 3, (fps * 1.50).toInt())
        if (lagMax <= lagMin + 2) return null

        val scores = DoubleArray(lagMax + 2)
        var bestLag = lagMin
        var bestScore = -1.0
        for (lag in lagMin..lagMax) {
            var s = 0.0
            var t = lag
            while (t < n) {
                s += nov[t] * nov[t - lag]
                t++
            }
            s /= (n - lag).toDouble()
            scores[lag] = s
            if (s > bestScore) {
                bestScore = s
                bestLag = lag
            }
        }
        if (bestScore <= 0.0) return null

        var lagF = bestLag.toDouble()
        if (bestLag > lagMin && bestLag < lagMax) {
            val a = scores[bestLag - 1]
            val b = scores[bestLag]
            val c = scores[bestLag + 1]
            val den = a - 2 * b + c
            if (abs(den) > 1e-12) lagF += 0.5 * (a - c) / den
        }
        if (lagF < 1.0) lagF = bestLag.toDouble()

        var periodMs = lagF / fps * 1000.0
        while (periodMs > 1000.0) periodMs /= 2.0
        while (periodMs < 330.0) periodMs *= 2.0
        val bpm = 60000.0 / periodMs

        // 相位：把拍点打在该周期上，看哪个偏移能量最集中
        var bestPhase = 0
        var bestPhaseScore = -1.0
        for (o in 0 until bestLag) {
            var s = 0.0
            var t = o
            while (t < n) {
                s += nov[t]
                t += bestLag
            }
            if (s > bestPhaseScore) {
                bestPhaseScore = s
                bestPhase = o
            }
        }
        var phaseMs = bestPhase / fps * 1000.0
        while (phaseMs > periodMs) phaseMs -= periodMs

        // 半拍纠偏：如果错开半拍后的格点能量明显更高，说明拍点落在了反拍上，整体挪半拍
        var halfScore = 0.0
        var ht = bestPhase + bestLag / 2
        while (ht < n) {
            halfScore += nov[ht]
            ht += bestLag
        }
        if (halfScore > bestPhaseScore * 1.15) {
            phaseMs += periodMs / 2.0
            while (phaseMs > periodMs) phaseMs -= periodMs
        }

        val durationMs = (n.toDouble() / fps * 1000.0).toLong()
        val count = ((durationMs - phaseMs) / periodMs).toInt()
        if (count < 8) return null

        // 每拍强度
        val avg = FloatArray(count)
        var maxAvg = 0f
        for (k in 0 until count) {
            val t0 = ((phaseMs + k * periodMs) / 1000.0 * fps).toInt()
            val t1 = ((phaseMs + k * periodMs + periodMs * 0.9) / 1000.0 * fps).toInt()
            var s = 0f
            var c = 0
            var t = max(0, t0)
            val te = min(n, t1)
            while (t < te) {
                s += nov[t]
                c++
                t++
            }
            avg[k] = if (c > 0) s / c else 0f
            if (avg[k] > maxAvg) maxAvg = avg[k]
        }
        val energy = ByteArray(count)
        if (maxAvg > 0f) {
            for (k in 0 until count) {
                val v = (avg[k] / maxAvg * 255f * 0.9f).toInt().coerceIn(0, 255)
                energy[k] = v.toByte()
            }
        }
        return BeatMap(bpm, periodMs, phaseMs, durationMs, energy)
    }

    private class Env(val env: FloatArray, val sampleRate: Int)

    private fun envelope(ctx: Context, uri: Uri?, resId: Int): Env? {
        val ex = MediaExtractor()
        try {
            if (uri != null) {
                ex.setDataSource(ctx, uri, null)
            } else {
                val afd = ctx.resources.openRawResourceFd(resId) ?: return null
                ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
            }
        } catch (t: Throwable) {
            try { ex.release() } catch (e: Throwable) {}
            return null
        }

        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = if (f.containsKey(MediaFormat.KEY_MIME)) f.getString(MediaFormat.KEY_MIME) else null
            if (mime != null && mime.startsWith("audio/")) {
                track = i
                fmt = f
                break
            }
        }
        if (track < 0 || fmt == null) {
            try { ex.release() } catch (e: Throwable) {}
            return null
        }
        ex.selectTrack(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: run {
            try { ex.release() } catch (e: Throwable) {}
            return null
        }

        val out = ArrayList<Float>(1 shl 16)
        var sampleRate = 44100
        var channels = 1
        var isFloat = false
        var acc = 0.0
        var accN = 0
        val limit = MAX_SECONDS * 44100

        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inEos = false
            var outEos = false
            if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            while (!outEos && accN < limit) {
                if (!inEos) {
                    val inIdx = codec.dequeueInputBuffer(3000)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)
                        val sz = if (ib != null) ex.readSampleData(ib, 0) else -1
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 3000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        isFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                } else if (outIdx >= 0) {
                    val ob = codec.getOutputBuffer(outIdx)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        if (isFloat) {
                            val fb = ob.asFloatBuffer()
                            val frames = fb.remaining() / channels
                            for (i in 0 until frames) {
                                val v = fb.get(i * channels).toDouble()
                                acc += v * v
                                accN++
                                if (accN >= HOP) {
                                    out.add((acc / HOP).toFloat())
                                    acc = 0.0
                                    accN = 0
                                }
                            }
                        } else {
                            val sb = ob.asShortBuffer()
                            val frames = sb.remaining() / channels
                            for (i in 0 until frames) {
                                val v = sb.get(i * channels) / 32768.0
                                acc += v * v
                                accN++
                                if (accN >= HOP) {
                                    out.add((acc / HOP).toFloat())
                                    acc = 0.0
                                    accN = 0
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inEos) {
                        // 等最后几帧
                    }
                }
            }
        } catch (t: Throwable) {
            return null
        } finally {
            try { codec?.stop() } catch (t: Throwable) {}
            try { codec?.release() } catch (t: Throwable) {}
            try { ex.release() } catch (t: Throwable) {}
        }

        if (out.size < 600) return null
        val arr = FloatArray(out.size)
        for (i in out.indices) arr[i] = out[i]
        return Env(arr, sampleRate)
    }
}
