package app.xpay.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Фоновая голосовая активация: слушает имя ассистента, спрашивает твой Worker,
 * отвечает голосом и выполняет команды. Звонки и СМС только после голосового "да".
 */
class WakeService : Service(), TextToSpeech.OnInitListener {

    companion object {
        var instance: WakeService? = null
        const val CHANNEL = "xpay_wake"
        const val ACTION_STOP = "app.xpay.assistant.STOP"
    }

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var busy = false
    private var paused = false
    private var armedUntil = 0L
    private var pending: Pair<String, String>? = null
    private var pendingUntil = 0L
    private val history = ArrayList<Pair<String, String>>()
    private var utter = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale("ru", "RU")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    main.post { speaking = false; listen() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { speaking = false; listen() }
                }
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setWake(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()
        main.post { listen() }
        return START_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Голосовая активация", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Xpay слушает")
            .setContentText("Скажи: Привет, " + Prefs.name(this))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Выключить", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        instance = null
        release()
        try { tts?.shutdown() } catch (e: Exception) {}
        super.onDestroy()
    }

    /** Пока открыто окно приложения, фоновая служба молчит, чтобы не мешать микрофону. */
    fun pause(p: Boolean) {
        main.post {
            paused = p
            if (p) release() else listen()
        }
    }

    private fun release() {
        try { rec?.destroy() } catch (e: Exception) {}
        rec = null
    }

    private fun listen() {
        if (instance == null || paused || speaking || busy || rec != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf()
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                release()
                val d = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500L else 400L
                main.postDelayed({ listen() }, d)
            }

            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                release()
                handle(t)
                if (!busy && !speaking) main.postDelayed({ listen() }, 200)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.lang(this))
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(i)
    }

    private fun handle(t: String) {
        if (t.isBlank()) return
        val now = System.currentTimeMillis()

        // ждём голосовое подтверждение звонка или СМС
        val pd = pending
        if (pd != null) {
            pending = null
            if (now <= pendingUntil) {
                val words = t.lowercase().split(Regex("[^\\p{L}]+"))
                val yes = words.any { it in setOf("да", "давай", "звони", "отправляй", "подтверждаю", "ага", "yes") }
                if (yes) {
                    val r = Actions.run(this, pd.first, pd.second)
                    say(if (r.isEmpty()) "Готово" else r)
                } else {
                    say("Отменил")
                }
                return
            }
        }

        // после имени ждали команду
        if (armedUntil > now) {
            armedUntil = 0
            ask(t)
            return
        }

        val c = Wake.parse(t, Prefs.name(this)) ?: return
        if (c.isNotEmpty()) {
            ask(c)
        } else {
            beep()
            armedUntil = now + 9000
        }
    }

    private fun beep() {
        try {
            val g = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            g.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {}
    }

    private fun ask(text: String) {
        busy = true
        Thread {
            val reply = try { Net.chat(this, text, history) } catch (e: Exception) { "" }
            main.post { onReply(text, reply) }
        }.start()
    }

    private fun onReply(user: String, reply: String) {
        busy = false
        if (reply.isBlank()) {
            say("Не получилось связаться с сервером")
            return
        }
        val tag = Regex("\\[\\[do\\|([a-z]+)(?:\\|([\\s\\S]*?))?\\]\\]")
        val acts = tag.findAll(reply).map { Pair(it.groupValues[1], it.groupValues[2]) }.toList()
        var spoken = tag.replace(reply, "").trim()
        history.add(Pair("user", user))
        history.add(Pair("assistant", spoken))
        while (history.size > 8) history.removeAt(0)

        for ((cmd, arg) in acts) {
            if (Actions.CONFIRM.contains(cmd)) {
                if (pending == null) {
                    pending = Pair(cmd, arg)
                    pendingUntil = System.currentTimeMillis() + 15000
                    spoken += " " + Actions.describe(cmd, arg).replace("\n", ". ") + " Скажи да, чтобы подтвердить."
                }
            } else {
                val r = Actions.run(this, cmd, arg)
                if (r.isNotEmpty()) spoken += " $r"
            }
        }
        say(spoken)
    }

    private fun say(text: String) {
        val clean = text.replace(Regex("[*#`_>]"), "").trim().take(3500)
        if (!ttsReady || clean.isEmpty()) {
            speaking = false
            main.postDelayed({ listen() }, 200)
            return
        }
        speaking = true
        utter++
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "u$utter")
    }
}
