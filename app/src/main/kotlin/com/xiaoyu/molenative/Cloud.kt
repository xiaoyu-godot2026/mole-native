package com.xiaoyu.molenative

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 云榜单客户端：腾讯云开发 HTTP 函数，零第三方依赖（只用 org.json）。
 * 所有回调都在主线程触发，方便直接刷新界面。
 */
object Cloud {

    const val BASE = "https://mole-lb-d0g7v7rwd066f7da8-1517019879.ap-shanghai.app.tcloudbase.com/lb"

    class Row(val rank: Int, val name: String, val score: Int, val diff: String)

    private val main = Handler(Looper.getMainLooper())

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun request(urlStr: String, body: String?): JSONObject? {
        var c: HttpURLConnection? = null
        try {
            c = URL(urlStr).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 9000
            c.requestMethod = if (body == null) "GET" else "POST"
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "mole-native/3.7")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val w = OutputStreamWriter(c.outputStream, "UTF-8")
                w.write(body)
                w.flush()
                w.close()
            }
            val code = c.responseCode
            val st = if (code in 200..299) c.inputStream else c.errorStream
            val sb = StringBuilder()
            if (st != null) {
                val r = BufferedReader(InputStreamReader(st, "UTF-8"))
                var line = r.readLine()
                while (line != null) {
                    sb.append(line)
                    line = r.readLine()
                }
                r.close()
            }
            val txt = sb.toString()
            if (code !in 200..299) {
                Diag.line("cloud", "http " + code + " " + txt.take(70))
                return null
            }
            return JSONObject(txt)
        } catch (t: Throwable) {
            Diag.line("cloud", "net " + (t.message ?: t.toString()).take(70))
            return null
        } finally {
            try {
                c?.disconnect()
            } catch (t: Throwable) {
            }
        }
    }

    /** 交成绩：回调 (成功?, 名次) */
    fun submit(mode: String, uid: String, name: String, score: Int, diff: String, cb: (Boolean, Int) -> Unit) {
        Thread {
            var ok = false
            var rank = 0
            try {
                val o = JSONObject()
                o.put("action", "submit")
                o.put("mode", mode)
                o.put("uid", uid)
                o.put("name", name)
                o.put("score", score)
                o.put("diff", diff)
                val j = request(BASE, o.toString())
                if (j != null) {
                    ok = j.optBoolean("ok")
                    rank = j.optInt("rank", 0)
                    if (!ok) Diag.line("cloud", "submit " + j.optString("error", "?"))
                }
            } catch (t: Throwable) {
            }
            val fOk = ok
            val fRank = rank
            main.post { cb(fOk, fRank) }
        }.start()
    }

    /** 前 N 名：回调 (行, 总人数, 错误) */
    fun top(mode: String, limit: Int, cb: (List<Row>?, Int, String) -> Unit) {
        Thread {
            var rows: List<Row>? = null
            var total = 0
            var err = ""
            val j = request(BASE + "?action=top&mode=" + enc(mode) + "&limit=" + limit, null)
            if (j != null && j.optBoolean("ok")) {
                total = j.optInt("total", 0)
                val list = ArrayList<Row>()
                val arr = j.optJSONArray("top")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(
                            Row(
                                o.optInt("rank", i + 1),
                                o.optString("name", "匿名玩家"),
                                o.optInt("score", 0),
                                o.optString("diff", "normal")
                            )
                        )
                    }
                }
                rows = list
            } else {
                err = "连不上，稍后再试"
            }
            val fRows = rows
            val fTotal = total
            val fErr = err
            main.post { cb(fRows, fTotal, fErr) }
        }.start()
    }

    /** 我的名次：回调 (名次, 最好成绩, 是否存在 0/1) */
    fun rank(mode: String, uid: String, cb: (Int, Int, Int) -> Unit) {
        Thread {
            var rk = -1
            var sc = 0
            var found = 0
            val j = request(BASE + "?action=rank&mode=" + enc(mode) + "&uid=" + enc(uid), null)
            if (j != null && j.optBoolean("ok") && j.optBoolean("found")) {
                found = 1
                rk = j.optInt("rank", -1)
                sc = j.optInt("score", 0)
            }
            val fRk = rk
            val fSc = sc
            val fFound = found
            main.post { cb(fRk, fSc, fFound) }
        }.start()
    }
}
