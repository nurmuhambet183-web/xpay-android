package app.xpay.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.telephony.SmsManager

/** Выполнение команд ассистента на телефоне. Возвращает пустую строку при успехе или текст ошибки. */
object Actions {
    /** Эти действия всегда требуют подтверждения. */
    val CONFIRM = setOf("call", "sms")

    private val ALIASES = mapOf(
        "телеграм" to "telegram", "телега" to "telegram", "ватсап" to "whatsapp", "вотсап" to "whatsapp",
        "ютуб" to "youtube", "хром" to "chrome", "карты" to "maps", "камера" to "camera",
        "инстаграм" to "instagram", "тикток" to "tiktok", "настройки" to "settings", "часы" to "clock",
        "почта" to "gmail", "музыка" to "music", "галерея" to "gallery", "калькулятор" to "calculator"
    )

    private fun num(s: String) = s.filter { it.isDigit() || it == '+' }
    private fun isNumber(s: String) = Regex("^[+\\d][\\d\\s()\\-]{5,}$").matches(s.trim())

    fun findNumber(ctx: Context, who: String): String? {
        val w = who.trim()
        if (w.isEmpty()) return null
        if (isNumber(w)) return num(w)
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val q = w.lowercase()
        val stem = if (q.length > 3) q.dropLast(1) else q
        var best: String? = null
        var loose: String? = null
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return null
        c.use {
            while (it.moveToNext()) {
                val name = (it.getString(0) ?: "").lowercase()
                val n = it.getString(1) ?: continue
                if (name == q) return num(n)
                if (best == null && name.contains(q)) best = n
                if (loose == null && name.contains(stem)) loose = n
            }
        }
        val r = best ?: loose
        return if (r == null) null else num(r)
    }

    fun describe(cmd: String, arg: String): String {
        val p = arg.split("|", limit = 2)
        return if (cmd == "call") "Позвонить: " + p[0] + "?" else "Отправить СМС: " + p[0] + "\n" + p.getOrElse(1) { "" }
    }

    fun run(ctx: Context, cmd: String, arg: String): String {
        return try {
            exec(ctx, cmd, arg)
        } catch (e: Exception) {
            "Не получилось: " + (e.message ?: "ошибка")
        }
    }

    private fun start(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(ctx: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    private fun exec(ctx: Context, cmd: String, arg: String): String {
        val p = arg.split("|")
        return when (cmd) {
            "call" -> {
                val n = findNumber(ctx, p[0]) ?: return "Не нашёл номер для " + p[0]
                val act = if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
                    Intent.ACTION_CALL else Intent.ACTION_DIAL
                start(ctx, Intent(act, Uri.parse("tel:$n")))
                ""
            }
            "sms" -> {
                val n = findNumber(ctx, p[0]) ?: return "Не нашёл номер для " + p[0]
                val text = p.drop(1).joinToString("|")
                if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED && text.isNotBlank()) {
                    val sm = smsManager(ctx)
                    sm.sendMultipartTextMessage(n, null, sm.divideMessage(text), null, null)
                    "Отправил"
                } else {
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$n"))
                    i.putExtra("sms_body", text)
                    start(ctx, i)
                    ""
                }
            }
            "wa" -> {
                val n = findNumber(ctx, p[0]) ?: return "Не нашёл номер для " + p[0]
                val t = p.drop(1).joinToString("|")
                val digits = n.filter { it.isDigit() }
                start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + digits + "?text=" + Uri.encode(t))))
                ""
            }
            "mail" -> {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + p[0].trim()))
                i.putExtra(Intent.EXTRA_SUBJECT, p.getOrElse(1) { "" })
                i.putExtra(Intent.EXTRA_TEXT, p.drop(2).joinToString("|"))
                start(ctx, i)
                ""
            }
            "open" -> {
                val u = arg.trim()
                if (u.startsWith("http")) {
                    start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                    ""
                } else openApp(ctx, u)
            }
            "app" -> openApp(ctx, arg)
            "map" -> {
                start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(arg))))
                ""
            }
            "timer" -> {
                val sec = p[0].trim().toIntOrNull() ?: return "Не понял время"
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, sec)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, p.getOrElse(1) { "" })
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                start(ctx, i)
                ""
            }
            "alarm" -> {
                val hm = p[0].trim().split(":")
                val h = hm.getOrNull(0)?.trim()?.toIntOrNull() ?: return "Не понял время"
                val m = hm.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h)
                    .putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, p.getOrElse(1) { "" })
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                start(ctx, i)
                ""
            }
            "torch" -> {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return "Нет фонарика"
                cm.setTorchMode(id, arg.trim() != "off")
                ""
            }
            "vibrate" -> {
                val pat = arg.split(",").mapNotNull { it.trim().toLongOrNull() }.ifEmpty { listOf(200L) }
                @Suppress("DEPRECATION")
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0L) + pat.toLongArray(), -1))
                ""
            }
            else -> "Команда не поддерживается: $cmd"
        }
    }

    fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val n = name.trim().lowercase()
        if (n.isEmpty()) return "Не понял, какое приложение открыть"
        val alias = ALIASES[n]
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(main, 0)
        fun label(i: Int) = list[i].loadLabel(pm).toString().lowercase()
        var hit = -1
        for (i in list.indices) {
            val l = label(i)
            if (l == n || (alias != null && l == alias)) { hit = i; break }
        }
        if (hit < 0) {
            for (i in list.indices) {
                val l = label(i)
                if (l.contains(n) || (alias != null && l.contains(alias))) { hit = i; break }
            }
        }
        if (hit < 0) return "Не нашёл приложение $name"
        val launch = pm.getLaunchIntentForPackage(list[hit].activityInfo.packageName) ?: return "Не удалось открыть $name"
        start(ctx, launch)
        return ""
    }
}
