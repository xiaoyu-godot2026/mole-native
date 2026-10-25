package com.xiaoyu.molenative

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备侧诊断。
 *
 * 存在的理由：这个容器读不到手机的 /sdcard（/app/readfile 被桥拒绝），
 * 唯一能把设备上的文字送出来的通道是系统剪贴板（助手侧用 /app/clip 读）。
 * 所以诊断结果统一压成一个短文本块塞进剪贴板；同时落一份文件备用。
 *
 * 目标不是"能打日志"，而是让助手在一次游玩后拿到可调参的数字：
 * bpm / 谱面拍数 / 判定误差分布 / 帧率 / 音量 / 偏移量 / 崩溃栈。
 */
object Diag {

    private const val PRE = "diag"
    private const val MAX_FILE = 120 * 1024
    private const val TAIL_MAX = 24

    // 判定误差直方图：-250..+250ms，20ms 一格，25 格
    private const val HIST_N = 25
    private const val HIST_W = 20
    private const val HIST_LO = -250

    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private var ctx: Context? = null
    private var inited = false

    private val head = LinkedHashMap<String, String>()
    private val runMap = LinkedHashMap<String, String>()
    private val tail = ArrayList<String>()

    // 帧率（按 1 秒窗口统计）
    private var fWinStart = 0L
    private var fCount = 0L
    private var fMin = 0
    private var fSum = 0L
    private var fWin = 0

    // 判定误差
    private val hist = IntArray(HIST_N)
    private var histN = 0
    private var histSum = 0L
    private var histBig = 0

    fun boot(c: Context) {
        synchronized(lock) {
            if (inited) return
            inited = true
            ctx = c.applicationContext
            val dm = c.resources.displayMetrics
            head["dev"] = Build.MODEL + "/sdk" + Build.VERSION.SDK_INT
            head["scr"] = dm.widthPixels.toString() + "x" + dm.heightPixels + "/" + dm.densityDpi + "dpi"
            head["ver"] = appVer(c)
            try {
                val sp = c.getSharedPreferences(PRE, Context.MODE_PRIVATE)
                val lc = sp.getString("lastCrash", "") ?: ""
                if (lc.isNotEmpty()) {
                    line("CRASH-PREV", lc)
                    sp.edit().putString("lastCrash", "").commit()
                }
            } catch (t: Throwable) {
            }
        }
        installCrash()
    }

    @Suppress("DEPRECATION")
    private fun appVer(c: Context): String = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        val vc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
        pi.versionName + "(" + vc + ")"
    } catch (t: Throwable) {
        "?"
    }

    private fun installCrash() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val block = summary() + "\nSTACK " + t.name + "\n" +
                    android.util.Log.getStackTraceString(e)
                appendFile("=== CRASH ===\n" + block)
                val c = ctx
                if (c != null) {
                    c.getSharedPreferences(PRE, Context.MODE_PRIVATE).edit()
                        .putString("lastCrash", block.take(6000)).commit()
                    copy(c, block.take(8000))
                }
            } catch (x: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** 记一条事件（只进内存环形缓冲，summary 时带出去） */
    fun line(tag: String, msg: String) {
        synchronized(lock) {
            tail.add(fmt.format(Date()) + " " + tag + " " + msg.take(160))
            while (tail.size > TAIL_MAX) tail.removeAt(0)
        }
    }

    /** 记一个本局指标；同名覆盖，顺序即输出顺序 */
    fun run(k: String, v: Any?) {
        synchronized(lock) { runMap[k] = v?.toString() ?: "-" }
    }

    fun clearRun() {
        synchronized(lock) {
            runMap.clear()
            hist.fill(0)
            histN = 0
            histSum = 0L
            histBig = 0
            fWinStart = 0L
            fCount = 0L
            fMin = 0
            fSum = 0L
            fWin = 0
        }
    }

    /** 每帧调一次；1 秒窗口算一次 fps */
    fun frame(nanos: Long) {
        synchronized(lock) {
            if (fWinStart == 0L) {
                fWinStart = nanos
                fCount = 0L
                return
            }
            fCount++
            val dt = nanos - fWinStart
            if (dt >= 1_000_000_000L) {
                val fps = (fCount * 1_000_000_000L / dt).toInt()
                if (fps > 0) {
                    if (fMin == 0 || fps < fMin) fMin = fps
                    fSum += fps
                    fWin++
                }
                fWinStart = nanos
                fCount = 0L
            }
        }
    }

    /** 记一次判定误差（正 = 敲晚了，负 = 敲早了） */
    fun judge(errMs: Int) {
        synchronized(lock) {
            val e = if (errMs > 400) 400 else if (errMs < -400) -400 else errMs
            var idx = (e - HIST_LO) / HIST_W
            if (idx < 0) idx = 0
            if (idx >= HIST_N) idx = HIST_N - 1
            hist[idx]++
            histN++
            histSum += e
            if (e < -165 || e > 165) histBig++
        }
    }

    fun summary(): String {
        synchronized(lock) {
            val sb = StringBuilder()
            sb.append("#打鲸鲸")
            for ((k, v) in head) sb.append(' ').append(k).append('=').append(v)
            sb.append('\n')
            if (runMap.isNotEmpty()) {
                sb.append("run:")
                for ((k, v) in runMap) sb.append(' ').append(k).append('=').append(v)
                sb.append('\n')
            }
            sb.append(judgeLine()).append('\n')
            sb.append("fps: avg=")
            sb.append(if (fWin > 0) (fSum / fWin).toString() else "-")
            sb.append(" min=").append(if (fMin > 0) fMin.toString() else "-")
            sb.append('\n')
            if (tail.isNotEmpty()) {
                sb.append("evt:")
                for (s in tail) sb.append("\n  ").append(s)
            }
            return sb.toString()
        }
    }

    private fun judgeLine(): String {
        if (histN == 0) return "judge: n=0"
        val mean = histSum.toDouble() / histN
        var acc = 0
        var med = 0
        val half = histN / 2
        for (i in 0 until HIST_N) {
            acc += hist[i]
            if (acc >= half) {
                med = HIST_LO + i * HIST_W + HIST_W / 2
                break
            }
        }
        var inW = 0
        for (i in 0 until HIST_N) {
            val lo = HIST_LO + i * HIST_W
            if (lo >= -85 && lo < 85) inW += hist[i]
        }
        val pct = inW * 100 / histN
        return "judge: n=" + histN + " mean=" + Math.round(mean) + " med=" + med +
            " in85%=" + pct + " big=" + histBig
    }

    /** 把当前摘要写进剪贴板（返回是否成功），并落文件 */
    fun publish(reason: String): Boolean {
        val c = ctx ?: return false
        val text = summary()
        appendFile("=== " + reason + " ===\n" + text)
        return copy(c, text)
    }

    private fun copy(c: Context, text: String): Boolean = try {
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("打鲸鲸诊断", text))
        true
    } catch (t: Throwable) {
        false
    }

    private fun appendFile(text: String) {
        val c = ctx ?: return
        try {
            val dir = c.getExternalFilesDir(null) ?: c.filesDir
            val f = File(dir, "diag.log")
            if (f.length() > MAX_FILE) {
                val old = File(dir, "diag.old.log")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(text + "\n")
        } catch (t: Throwable) {
        }
    }
}
