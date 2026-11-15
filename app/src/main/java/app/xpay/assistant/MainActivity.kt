package app.xpay.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var rec: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.userAgentString = s.userAgentString + " XpayApp/1.0"
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme ?: ""
                if (scheme == "http" || scheme == "https") {
                    val own = Uri.parse(Prefs.url(this@MainActivity)).host
                    if (u.host == own) return false
                    openExternal(Intent(Intent.ACTION_VIEW, u))
                    return true
                }
                try {
                    val i = if (scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)
                    i.component = null
                    i.selector = null
                    openExternal(i)
                } catch (e: Exception) {
                }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.resources)
                    } else {
                        request.deny()
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, true, false)
            }
        }

        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)

        val url = Prefs.url(this)
        if (url.isEmpty()) askUrl() else web.loadUrl(url)
    }

    override fun onResume() {
        super.onResume()
        WakeService.instance?.pause(true)
    }

    override fun onPause() {
        WakeService.instance?.pause(false)
        super.onPause()
    }

    override fun onDestroy() {
        stopRec()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun openExternal(i: Intent) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            report("Не удалось открыть")
        }
    }

    private fun report(msg: String) {
        if (msg.isNotEmpty()) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun js(code: String) {
        runOnUiThread { web.evaluateJavascript(code, null) }
    }

    // ---------- действия по просьбе ассистента ----------
    private fun execute(cmd: String, arg: String) {
        if (Actions.CONFIRM.contains(cmd)) {
            AlertDialog.Builder(this)
                .setMessage(Actions.describe(cmd, arg))
                .setPositiveButton("Да") { _, _ -> report(Actions.run(this, cmd, arg)) }
                .setNegativeButton("Отмена", null)
                .show()
        } else {
            report(Actions.run(this, cmd, arg))
        }
    }

    // ---------- распознавание речи для страницы (в WebView своего нет) ----------
    private fun startRec(lang: String) {
        stopRec()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            js("window.__xpSRError&&window.__xpSRError('not-allowed')")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("window.__xpSRError&&window.__xpSRError('service-not-allowed')")
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
                val e = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                    SpeechRecognizer.ERROR_CLIENT -> "aborted"
                    else -> "network"
                }
                js("window.__xpSRError&&window.__xpSRError('$e')")
            }

            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (t.isEmpty()) {
                    js("window.__xpSRError&&window.__xpSRError('no-speech')")
                } else {
                    js("window.__xpSRResult&&window.__xpSRResult(" + JSONObject.quote(t) + ")")
                }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        r.startListening(i)
    }

    private fun stopRec() {
        try { rec?.destroy() } catch (e: Exception) {}
        rec = null
    }

    // ---------- настройки ----------
    private fun askUrl() {
        val et = EditText(this)
        et.hint = "https://твой-воркер.workers.dev"
        et.setText(Prefs.url(this))
        AlertDialog.Builder(this)
            .setTitle("Адрес твоего Xpay")
            .setView(et)
            .setCancelable(Prefs.url(this).isNotEmpty())
            .setPositiveButton("Открыть") { _, _ ->
                var u = et.text.toString().trim()
                if (u.isNotEmpty() && !u.startsWith("http")) u = "https://$u"
                if (u.isEmpty()) {
                    askUrl()
                } else {
                    Prefs.setUrl(this, u)
                    web.loadUrl(Prefs.url(this))
                }
            }
            .show()
    }

    private fun askName() {
        val et = EditText(this)
        et.setText(Prefs.name(this))
        AlertDialog.Builder(this)
            .setTitle("Имя ассистента")
            .setMessage("Пиши так, как это услышит распознавание речи")
            .setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) {
                    Prefs.setName(this, n)
                    js("try{localStorage.setItem('xp_name'," + JSONObject.quote(n) + ")}catch(e){}")
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun askAll() {
        requestPermissions(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION
            ), 2
        )
    }

    private fun toggleWake() {
        if (Prefs.wake(this)) {
            stopService(Intent(this, WakeService::class.java))
            Prefs.setWake(this, false)
            report("Фоновая активация выключена")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            report("Дай доступ к микрофону и повтори")
            return
        }
        Prefs.setWake(this, true)
        startForegroundService(Intent(this, WakeService::class.java))
        report("Скажи: Привет, " + Prefs.name(this))
    }

    private fun settings() {
        val items = arrayOf(
            "Дать разрешения (звонки, контакты, СМС, место)",
            "Показ поверх окон (чтобы работать из фона)",
            "Не ограничивать батарею",
            if (Prefs.wake(this)) "Фоновая активация: ВКЛ (выключить)" else "Фоновая активация: ВЫКЛ (включить)",
            "Имя ассистента: " + Prefs.name(this),
            "Адрес сервера: " + Prefs.url(this)
        )
        AlertDialog.Builder(this)
            .setTitle("Xpay: настройки")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askAll()
                    1 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    2 -> startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    3 -> toggleWake()
                    4 -> askName()
                    5 -> askUrl()
                    else -> {}
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** Мост для страницы: window.Android.* */
    inner class Bridge {
        @JavascriptInterface
        fun run(cmd: String, arg: String) {
            runOnUiThread { execute(cmd, arg) }
        }

        @JavascriptInterface
        fun startListening(lang: String) {
            runOnUiThread { startRec(lang) }
        }

        @JavascriptInterface
        fun stopListening() {
            runOnUiThread { stopRec() }
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread { settings() }
        }
    }
}
