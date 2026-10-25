package com.xiaoyu.molenative

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri

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
                    if (mp?.isPlaying != true) mp?.start()
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
                p.isLooping = true
                p.setVolume(bgmVol, bgmVol)
                p.setDataSource(ctx, Uri.parse(uriStr))
                p.setOnPreparedListener { r ->
                    mp = r
                    mpReady = true
                    try {
                        r.setVolume(bgmVol, bgmVol)
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
            p.isLooping = true
            p.setVolume(bgmVol, bgmVol)
            p.start()
            mp = p
            mpReady = true
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
