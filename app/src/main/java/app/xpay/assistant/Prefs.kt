package app.xpay.assistant

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("xpay", Context.MODE_PRIVATE)

    fun url(c: Context): String = sp(c).getString("url", "") ?: ""
    fun setUrl(c: Context, v: String) {
        sp(c).edit().putString("url", v.trim().trimEnd('/')).apply()
    }

    fun name(c: Context): String = sp(c).getString("name", "Xpay") ?: "Xpay"
    fun setName(c: Context, v: String) {
        sp(c).edit().putString("name", v.trim()).apply()
    }

    fun lang(c: Context): String = sp(c).getString("lang", "ru-RU") ?: "ru-RU"

    fun wake(c: Context): Boolean = sp(c).getBoolean("wake", false)
    fun setWake(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("wake", v).apply()
    }
}
