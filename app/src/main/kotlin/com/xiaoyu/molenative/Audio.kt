package com.xiaoyu.molenative

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.SystemClock

/**
 * 音频：SoundPool 播音效，MediaPlayer 循环背景音乐。
 * 音量/选择都存 SharedPreferences(名字 "mole")。
 */
class GameAudio(private val ctx: Context) {

    companion object {
        const val HIT1 = 0      // 敲第一下（盆剩 2）
        const val HIT2 = 1      // 敲第二下（盆剩 1）
        const val HIT3 = 2      // 敲碎
        const val KNOCK = 3     // 盆飞
        const val DIZZY = 4     // 晕
        const val MISS = 5      // 漏敲
        const val COMBO = 6     // 连击
        const val START = 7
        const val OVER = 8
        private const val N = 9
        const val PREFS = "mole"

        private val RAWS = intArrayOf(
            R.raw.sfx_hit1, R.raw.sfx_hit2, R.raw.sfx_hit3,
            R.raw.sfx_knock, R.raw.sfx_dizzy, R.raw.sfx_miss,
            R.raw.sfx_combo, R.raw.sfx_start, R.raw.sfx_over
        )
    }

    private val sp: SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val sfxAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    var sfxVol: Float = sp.getFloat("sfxVol", 0.85f).coerceIn(0f, 1f)
    var bgmVol: Float = sp.getFloat("bgmVol", 0.32f).coerceIn(0f, 1f)
        set(v) {
            field = v.coerceIn(0f, 1f)
            try {
                mp?.setVolume(field, field)
            } catch (t: Throwable) {
            }
        }

    var vibOn: Boolean = sp.getBoolean("vibOn", true)

    /**
     * 声音总开关（v3.8）：关掉 = 主界面、经典对局、结算既不放音乐也不出音效。
     * 唯一例外是音游对局：那首曲子就是游戏本体，静音了就没法跟着鼓点敲，所以照常播。
     */
    var soundOn: Boolean = sp.getBoolean("soundOn", true)
        set(v) {
            field = v
            try {
                sp.edit().putBoolean("soundOn", v).apply()
            } catch (t: Throwable) {
            }
            if (!v) {
                try {
                    mp?.pause()
                } catch (t: Throwable) {
                }
            }
        }

    init {
        // v3.2：默认音量下调（音效 20%，音乐 50%），老用户也强制刷一次
        try {
            if (sp.getInt("volVer", 0) < 2) {
                sfxVol = 0.20f
                bgmVol = 0.50f
                sp.edit().putFloat("sfxVol", 0.20f).putFloat("bgmVol", 0.50f).putInt("volVer", 2).apply()
            }
        } catch (t: Throwable) {
        }
    }

    /** 当前背景音乐名字（给设置面板显示） */
    val bgmName: String
        get() = sp.getString("bgmName", null) ?: "内置轻音乐"

    val bgmCustom: Boolean
        get() = sp.getString("bgmUri", null) != null

    private var pool: SoundPool? = null
    private val sids = IntArray(N)
    private val sidOk = BooleanArray(N)
    private var mp: MediaPlayer? = null
    private var mpReady = false
    private var wantBgm = false
    private var lastRawPos = -1L
    private var lastRawAt = 0L

    /** 对局里必须关循环，菜单里才循环（循环会让游戏时钟卡在曲尾不动） */
    private var loopBgm = true

    /** 本曲是否已经播完（对局据此结算，不靠时钟猜） */
    var completed = false
        private set

    fun setLooping(on: Boolean) {
        loopBgm = on
        try {
            mp?.isLooping = on
        } catch (t: Throwable) {
        }
    }

    /** 分析/播放音乐长度（毫秒） */
    var durationMs = 0L
        private set

    val bgmReady: Boolean get() = mpReady

    val bgmPlaying: Boolean
        get() = try {
            mpReady && mp?.isPlaying == true
        } catch (t: Throwable) {
            false
        }

    val bgmUriString: String?
        get() = try {
            sp.getString("bgmUri", null)
        } catch (t: Throwable) {
            null
        }

    /** 平滑后的播放进度，供节奏调度当全局时钟 */
    fun positionMs(): Long {
        if (!mpReady) return 0L
        val p = try {
            (mp?.currentPosition ?: 0).toLong()
        } catch (t: Throwable) {
            return 0L
        }
        val now = SystemClock.elapsedRealtime()
        if (p != lastRawPos) {
            lastRawPos = p
            lastRawAt = now
            return p
        }
        val est = lastRawPos + (now - lastRawAt)
        val cap = if (durationMs > 0L) durationMs else est
        return if (est > cap) cap else est
    }

    /** 暂停恢复后重置平滑基线，避免用暂停时长把进度估算顶飞 */
    fun rebaseClock() {
        lastRawPos = -1L
        lastRawAt = SystemClock.elapsedRealtime()
    }

    /** 一局开始：从头播 */
    fun restartBgm() {
        wantBgm = true
        lastRawPos = -1L
        completed = false
        val p = mp
        if (p == null) {
            startBgm()
            return
        }
        try {
            if (!mpReady) return
            p.isLooping = loopBgm
            p.seekTo(0)
            p.start()
        } catch (t: Throwable) {
            releaseBgm()
            startBgm()
        }
    }

    init {
        try {
            val p = SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(sfxAttrs)
                .build()
            p.setOnLoadCompleteListener { _, sid, status ->
                for (i in 0 until N) if (sids[i] == sid && status == 0) sidOk[i] = true
            }
            for (i in 0 until N) {
                try {
                    sids[i] = p.load(ctx, RAWS[i], 1)
                } catch (t: Throwable) {
                    sids[i] = 0
                }
            }
            pool = p
        } catch (t: Throwable) {
            pool = null
        }
    }

    fun play(c: Int, rate: Float = 1f, volScale: Float = 1f) {
        if (!soundOn) return
        if (sfxVol <= 0.01f) return
        val p = pool ?: return
        if (c < 0 || c >= N || sids[c] == 0) return
        val v = (sfxVol * volScale).coerceIn(0f, 1f)
        try {
            p.play(sids[c], v, v, 1, 0, rate.coerceIn(0.5f, 2f))
        } catch (t: Throwable) {
        }
    }

    fun saveVolumes() {
        try {
            sp.edit().putFloat("sfxVol", sfxVol).putFloat("bgmVol", bgmVol).apply()
        } catch (t: Throwable) {
        }
    }

    fun saveVib() {
        try {
            sp.edit().putBoolean("vibOn", vibOn).apply()
        } catch (t: Throwable) {
        }
    }

    // ---------------- 背景音乐 ----------------

    fun startBgm() {
        wantBgm = true
        if (mp != null) {
            if (mpReady) {
                try {
                    if (mp?.isPlaying != true) {
                        mp?.isLooping = loopBgm
                        completed = false
                        mp?.start()
                    }
                } catch (t: Throwable) {
                }
            }
            return
        }
        val uriStr = sp.getString("bgmUri", null)
        if (uriStr != null) {
            val p = MediaPlayer()
            try {
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                p.isLooping = loopBgm
                p.setVolume(bgmVol, bgmVol)
                p.setDataSource(ctx, Uri.parse(uriStr))
                p.setOnPreparedListener { r ->
                    mp = r
                    mpReady = true
                    try {
                        r.isLooping = loopBgm
                        r.setOnCompletionListener { completed = true }
                        durationMs = r.duration.toLong()
                        r.setVolume(bgmVol, bgmVol)
                        completed = false
                        if (wantBgm) r.start()
                    } catch (t: Throwable) {
                    }
                }
                p.setOnErrorListener { r, _, _ ->
                    try {
                        r.release()
                    } catch (t: Throwable) {
                    }
                    mp = null
                    mpReady = false
                    builtinBgm()
                    true
                }
                mp = p
                mpReady = false
                p.prepareAsync()
                return
            } catch (t: Throwable) {
                try {
                    p.release()
                } catch (e: Throwable) {
                }
                mp = null
                mpReady = false
            }
        }
        builtinBgm()
    }

    private fun builtinBgm() {
        try {
            val p = MediaPlayer.create(ctx, R.raw.bgm)
            if (p == null) return
            p.isLooping = loopBgm
            p.setVolume(bgmVol, bgmVol)
            p.setOnCompletionListener { completed = true }
            completed = false
            p.start()
            mp = p
            mpReady = true
            try {
                durationMs = p.duration.toLong()
            } catch (t: Throwable) {
            }
        } catch (t: Throwable) {
            mp = null
            mpReady = false
        }
    }

    fun pauseBgm() {
        try {
            if (mpReady && mp?.isPlaying == true) mp?.pause()
        } catch (t: Throwable) {
        }
    }

    fun resumeBgm() {
        if (wantBgm) startBgm()
    }

    private fun releaseBgm() {
        val p = mp
        mp = null
        mpReady = false
        try {
            if (p != null && p.isPlaying) p.pause()
        } catch (t: Throwable) {
        }
        try {
            p?.release()
        } catch (t: Throwable) {
        }
    }

    /** 换成手机里的音频文件 */
    fun setBgm(uri: Uri, name: String?) {
        try {
            sp.edit()
                .putString("bgmUri", uri.toString())
                .putString("bgmName", name ?: "本地音乐")
                .apply()
        } catch (t: Throwable) {
        }
        releaseBgm()
        startBgm()
    }

    /** 恢复内置音乐 */
    fun clearBgm() {
        try {
            sp.edit().remove("bgmUri").remove("bgmName").apply()
        } catch (t: Throwable) {
        }
        releaseBgm()
        startBgm()
    }

    fun release() {
        wantBgm = false
        releaseBgm()
        try {
            pool?.release()
        } catch (t: Throwable) {
        }
        pool = null
    }
}
