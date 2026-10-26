package com.xiaoyu.molenative

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备侧诊断（v3.6 起走 logcat，不再往剪贴板写）。
 *
 * 为什么改：设备 shell 已通，助手一条命令就能读到结构化数据
 *   /root/dsh-bin/dev "logcat -d -s MoleDiag"
 * 剪贴板那条老路（每局结束覆盖用户自己复制的内容）属于冗余，删掉。
 *
 * 目标不变：一次游玩后能拿到可调参的数字 ——
 * bpm / 谱面拍数 / 判定误差分布 / 帧率 / 音量 / 偏移量 / 崩溃栈。
 * 另外仍落一份文件在 App 外部私有目录 files/diag.log 备用。
 */
object Diag {

    private const val TAG = "MoleDiag"
    private const val PRE = "diag"
    private const val MAX_FILE = 120 * 1024
    private const val TAIL_MAX = 24
    private const val CHUNK = 3000

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
    private var prevCrash = ""

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
                    prevCrash = lc
                    sp.edit().putString("lastCrash", "").commit()
                }
            } catch (t: Throwable) {
            }
            Log.i(TAG, "boot " + headLine())
            if (prevCrash.isNotEmpty()) logText("prevcrash", prevCrash)
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

    private fun headLine(): String {
        val sb = StringBuilder()
        for ((k, v) in head) sb.append(k).append('=').append(v).append(' ')
        return sb.toString().trim()
    }

    private fun installCrash() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val stack = Log.getStackTraceString(e)
                val block = summary()
                appendFile("=== CRASH ===\n" + block + "\nSTACK " + t.name + "\n" + stack)
                Log.e(TAG, "CRASH " + t.name + "\n" + stack)
                logText("crash", block)
                val c = ctx
                if (c != null) {
                    c.getSharedPreferences(PRE, Context.MODE_PRIVATE).edit()
                        .putString("lastCrash", block.take(6000)).commit()
                }
            } catch (x: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** 记一条事件（内存环形缓冲 + 实时进 logcat） */
    fun line(tag: String, msg: String) {
        val s = msg.take(160)
        synchronized(lock) {
            tail.add(fmt.format(Date()) + " " + tag + " " + s)
            while (tail.size > TAIL_MAX) tail.removeAt(0)
        }
        Log.i(TAG, "evt " + tag + " " + s)
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
            if (prevCrash.isNotEmpty()) {
                sb.append("prevCrash:\n").append(prevCrash.take(4000)).append('\n')
            }
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

    /** 结算/退出/启动失败时调用：摘要落文件 + 整段写进 logcat（TAG=MoleDiag） */
    fun publish(reason: String) {
        val text = summary()
        appendFile("=== " + reason + " ===\n" + text)
        logText(reason, text)
    }

    /** 长文本按行切成 <=3000 字符的块写 logcat，避免被单行上限截断 */
    private fun logText(prefix: String, text: String) {
        try {
            val s = text.replace("\r", "")
            var i = 0
            while (i < s.length) {
                var end = minOf(i + CHUNK, s.length)
                if (end < s.length) {
                    val nl = s.lastIndexOf('\n', end - 1)
                    if (nl > i + 200) end = nl + 1
                }
                Log.i(TAG, "[" + prefix + "] " + s.substring(i, end))
                i = end
            }
        } catch (t: Throwable) {
        }
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
