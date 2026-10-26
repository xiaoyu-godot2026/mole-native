package com.xiaoyu.molenative

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 免费曲库（Jamendo）：搜索 + 下载到本地。
 * 曲目多为 CC 授权（署名即可非商用），界面上要标注作者与来源。
 */
object MusicLib {

    private const val CLIENT_ID = "d50689af"
    private const val API = "https://api.jamendo.com/v3.0/tracks/"
    private val main = Handler(Looper.getMainLooper())

    class Track(
        val id: String,
        val name: String,
        val artist: String,
        val durSec: Int,
        val audioUrl: String,
        val license: String
    ) {
        fun durText(): String {
            val m = durSec / 60
            val s = durSec % 60
            return m.toString() + ":" + (if (s < 10) "0" else "") + s
        }
    }

    /** 搜索：回调 (结果, 错误) */
    fun search(q: String, cb: (List<Track>?, String) -> Unit) {
        Thread {
            var list: List<Track>? = null
            var err = ""
            var c: HttpURLConnection? = null
            try {
                val url = API + "?client_id=" + CLIENT_ID + "&format=json&limit=24" +
                        "&audioformat=mp32&include=licenses&search=" + URLEncoder.encode(q, "UTF-8")
                c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 13000
                c.setRequestProperty("Accept", "application/json")
                val r = BufferedReader(InputStreamReader(c.inputStream, "UTF-8"))
                val sb = StringBuilder()
                var line = r.readLine()
                while (line != null) {
                    sb.append(line)
                    line = r.readLine()
                }
                r.close()
                val j = JSONObject(sb.toString())
                val out = ArrayList<Track>()
                val arr = j.optJSONArray("results")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val au = o.optString("audio", "")
                        if (au.isEmpty() || !au.startsWith("http")) continue
                        out.add(
                            Track(
                                o.optString("id", i.toString()),
                                o.optString("name", "无题"),
                                o.optString("artist_name", "未知作者"),
                                o.optInt("duration", 0),
                                au,
                                o.optString("license_ccurl", "")
                            )
                        )
                    }
                }
                if (out.isEmpty()) err = "没搜到，换个词试试" else list = out
            } catch (t: Throwable) {
                err = "连不上曲库，检查网络后重试"
                Diag.line("music", "search " + (t.message ?: t.toString()).take(60))
            } finally {
                try {
                    c?.disconnect()
                } catch (t: Throwable) {
                }
            }
            val fList = list
            val fErr = err
            main.post { cb(fList, fErr) }
        }.start()
    }

    /** 下载到 filesDir/music/<id>.mp3，回调 (文件, 错误) */
    fun download(ctx: Context, t: Track, cb: (File?, String) -> Unit) {
        Thread {
            var file: File? = null
            var err = ""
            var c: HttpURLConnection? = null
            try {
                val dir = File(ctx.filesDir, "music")
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, "jm" + t.id + ".mp3")
                if (!f.exists() || f.length() < 20000L) {
                    c = URL(t.audioUrl).openConnection() as HttpURLConnection
                    c.connectTimeout = 10000
                    c.readTimeout = 25000
                    c.instanceFollowRedirects = true
                    c.setRequestProperty("User-Agent", "mole-native/3.7")
                    val ins = c.inputStream
                    val out = f.outputStream()
                    val buf = ByteArray(64 * 1024)
                    var n = ins.read(buf)
                    while (n > 0) {
                        out.write(buf, 0, n)
                        n = ins.read(buf)
                    }
                    out.flush()
                    out.close()
                    ins.close()
                }
                if (f.length() > 20000L) file = f else err = "下载失败"
            } catch (t2: Throwable) {
                err = "下载失败，检查网络"
                Diag.line("music", "dl " + (t2.message ?: t2.toString()).take(60))
            } finally {
                try {
                    c?.disconnect()
                } catch (t2: Throwable) {
                }
            }
            val fFile = file
            val fErr = err
            main.post { cb(fFile, fErr) }
        }.start()
    }
}
