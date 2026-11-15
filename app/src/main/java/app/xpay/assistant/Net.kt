package app.xpay.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Net {
    /** Спрашивает твой Worker и возвращает полный текст ответа (потоковый ответ собирается целиком). */
    fun chat(ctx: Context, text: String, history: List<Pair<String, String>>): String {
        val base = Prefs.url(ctx)
        if (base.isEmpty()) return ""

        val msgs = JSONArray()
        for ((role, content) in history.takeLast(6)) {
            msgs.put(JSONObject().put("role", role).put("content", content))
        }
        msgs.put(JSONObject().put("role", "user").put("content", text))

        val body = JSONObject()
            .put("messages", msgs)
            .put("voice", true)
            .put("model", "erko")
            .put("lang", Prefs.lang(ctx))
            .put("search", false)
            .put("aname", Prefs.name(ctx))
            .put("now", java.util.Date().toString())

        val conn = URL("$base/api/chat").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode !in 200..299) return ""

        val sb = StringBuilder()
        conn.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (l in lines) {
                if (!l.startsWith("data:")) continue
                val d = l.substring(5).trim()
                if (d.isEmpty() || d == "[DONE]") continue
                try {
                    val j = JSONObject(d)
                    if (j.has("response")) {
                        sb.append(j.getString("response"))
                    } else {
                        val ch = j.optJSONArray("choices")
                        if (ch != null && ch.length() > 0) {
                            sb.append(ch.getJSONObject(0).optJSONObject("delta")?.optString("content", "") ?: "")
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }
        return sb.toString()
    }
}
