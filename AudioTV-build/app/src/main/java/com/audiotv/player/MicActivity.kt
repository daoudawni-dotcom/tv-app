package com.audiotv.player

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.abs

class MicActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var meter: ProgressBar
    private lateinit var mode: Spinner
    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var generation = 0
    private var lastSpeech = ""
    private var testingKeys = false

    private val keys = arrayOf(
        "system", "direct", "offline", "mic",
        "recognition", "communication", "default", "raw", "keys"
    )

    private val labels = arrayOf(
        "نافذة التعرف الصوتي للنظام",
        "التعرف المباشر",
        "التعرف المحلي على الجهاز",
        "MIC – الميكروفون",
        "VOICE_RECOGNITION",
        "VOICE_COMMUNICATION",
        "DEFAULT",
        "UNPROCESSED (Android 7+)",
        "فحص أزرار الريموت"
    )

    private val prefs by lazy { getSharedPreferences("mic", MODE_PRIVATE) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        root.addView(TextView(this).apply {
            text = "اختبار توافق الميكروفون"
            textSize = 24f
        })

        mode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MicActivity,
                android.R.layout.simple_spinner_dropdown_item,
                labels
            )
            val saved = prefs.getString("mode", "direct") ?: "direct"
            setSelection(keys.indexOf(saved).coerceAtLeast(0))
        }
        root.addView(mode)

        val row = LinearLayout(this)
        root.addView(row)

        fun btn(title: String, action: () -> Unit) {
            row.addView(
                Button(this).apply {
                    text = title
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
        }

        btn("ابدأ الفحص") { startTest() }
        btn("إيقاف") {
            stopTest()
            report("تم إيقاف الفحص")
        }
        btn("اعتماد الوضع") {
            val k = keys[mode.selectedItemPosition]
            if (k in arrayOf("system", "direct", "offline")) {
                prefs.edit().putString("mode", k).apply()
                report("تم حفظ وضع الأوامر: $k")
            } else {
                report("هذا وضع تشخيص فقط وليس محرك تعرف على الكلام")
            }
        }
        btn("استخدم النص") {
            if (lastSpeech.isNotBlank()) deliver(lastSpeech)
            else report("لا توجد نتيجة صوتية بعد")
        }

        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(meter)

        val scroll = ScrollView(this)
        log = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(false)
        }
        scroll.addView(log)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)

        val manager = getSystemService(AUDIO_SERVICE) as AudioManager
        report("Android SDK: ${Build.VERSION.SDK_INT}\nخدمة التعرف: ${SpeechRecognizer.isRecognitionAvailable(this)}")
        if (Build.VERSION.SDK_INT >= 31) {
            report("التعرف المحلي: ${SpeechRecognizer.isOnDeviceRecognitionAvailable(this)}")
        }
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        report("مصادر الإدخال: ${inputs.size}")
        inputs.forEach { report("${it.productName} | type=${it.type} | id=${it.id}") }

        row.getChildAt(0).requestFocus()
        if (intent.getBooleanExtra("auto", false)) startTest()
    }

    private fun report(s: String) {
        log.append("\n$s")
        if (log.length() > 8000) log.text = log.text.takeLast(6000)
        (log.parent as? ScrollView)?.post {
            (log.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun speechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-JO")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث الآن")
    }

    private fun startTest() {
        if (worker?.isAlive == true) {
            report("أوقف الفحص السابق وانتظر انتهاءه")
            return
        }
        stopTest()
        lastSpeech = ""
        val k = keys[mode.selectedItemPosition]
        report("بدء: $k")
        if (k == "keys") {
            testingKeys = true
            report("اضغط زر المايك وأزرار الريموت؛ زر الرجوع للخروج")
            return
        }
        if (k == "system") {
            try {
                startActivityForResult(speechIntent(), 21)
            } catch (e: Exception) {
                report("تعذر فتح خدمة النظام: ${e.message}")
            }
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 11)
            return
        }
        when (k) {
            "direct", "offline" -> recognize(k == "offline")
            else -> record(k)
        }
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (r == 11 && g.firstOrNull() == PackageManager.PERMISSION_GRANTED) startTest()
        else report("لم يتم منح إذن الميكروفون")
    }

    private fun recognize(local: Boolean) {
        try {
            if (local && (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this))) {
                report("التعرف المحلي غير متوفر")
                return
            }
            if (!local && !SpeechRecognizer.isRecognitionAvailable(this)) {
                report("خدمة التعرف المباشر غير متوفرة")
                return
            }
            val r = if (local && Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {
                    report("جاهز: اضغط المايك وتحدث")
                }

                override fun onBeginningOfSpeech() {
                    report("تم رصد بداية الكلام")
                }

                override fun onRmsChanged(v: Float) {
                    meter.progress = ((v + 2) * 8).toInt().coerceIn(0, 100)
                }

                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {
                    report("انتهى الكلام")
                }

                override fun onError(e: Int) {
                    report("خطأ التعرف: $e (6 مهلة، 7 لا تطابق، 9 إذن، 12 لغة غير مدعومة، 13 لغة غير متاحة)")
                }

                override fun onResults(b: Bundle?) {
                    result(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                }

                override fun onPartialResults(b: Bundle?) {
                    b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                        report("جزئي: $it")
                    }
                }

                override fun onEvent(t: Int, b: Bundle?) {}
            })
            r.startListening(speechIntent().apply {
                if (local) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (e: Exception) {
            report("تعذر بدء التعرف: ${e.message}")
        }
    }

    private fun result(s: String?) {
        if (s.isNullOrBlank()) {
            report("لم يتم التعرف على نص")
            return
        }
        lastSpeech = s
        report("النص: $s")
        if (intent.getBooleanExtra("auto", false)) deliver(s)
    }

    private fun deliver(s: String) {
        setResult(RESULT_OK, Intent().putExtra("speech", s))
        finish()
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        if (r == 21) {
            if (c == RESULT_OK) result(d?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull())
            else report("ألغيت أو فشلت نافذة التعرف")
        }
    }

    private fun record(k: String) {
        val source = when (k) {
            "mic" -> MediaRecorder.AudioSource.MIC
            "recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            "communication" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "raw" -> if (Build.VERSION.SDK_INT >= 24) MediaRecorder.AudioSource.UNPROCESSED else {
                report("غير مدعوم")
                return
            }
            else -> MediaRecorder.AudioSource.DEFAULT
        }
        val token = ++generation
        running = true
        worker = Thread {
            var a: AudioRecord? = null
            try {
                val rate = listOf(16000, 48000, 44100, 8000).firstOrNull {
                    AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
                } ?: error("لا يوجد معدل صوت مناسب")
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(
                    source,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min * 2, 4096)
                )
                a = rec
                check(rec.state == AudioRecord.STATE_INITIALIZED) { "فشل تهيئة AudioRecord" }
                rec.startRecording()
                check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                val data = ShortArray(1024)
                var max = 0
                var count = 0
                val until = SystemClock.elapsedRealtime() + 12000
                runOnUiThread {
                    if (token == generation) report("تسجيل 12 ثانية؛ اضغط زر المايك وتحدث. معدل $rate Hz")
                }
                while (running && SystemClock.elapsedRealtime() < until) {
                    val n = rec.read(data, 0, data.size)
                    if (n < 0) error("AudioRecord read=$n")
                    var peak = 0
                    for (i in 0 until n) peak = maxOf(peak, abs(data[i].toInt()))
                    max = maxOf(max, peak)
                    if (++count % 4 == 0) {
                        val level = (peak * 100 / 32768).coerceIn(0, 100)
                        runOnUiThread { if (token == generation) meter.progress = level }
                    }
                }
                val route = if (Build.VERSION.SDK_INT >= 23) rec.routedDevice?.productName?.toString() else null
                runOnUiThread {
                    if (token == generation) {
                        report("انتهى: أعلى سعة $max/32768 | المسار: ${route ?: "غير معروف"}\nوجود إشارة لا يثبت أنها من الريموت؛ قارن الصمت بالكلام")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { if (token == generation) report("فشل التقاط الصوت: ${e.message}") }
            } finally {
                try {
                    a?.stop()
                } catch (_: Exception) {
                }
                a?.release()
                running = false
            }
        }.also { it.start() }
    }

    private fun stopTest() {
        testingKeys = false
        generation++
        running = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        if (::meter.isInitialized) meter.progress = 0
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && ::mode.isInitialized) {
            report("زر: ${KeyEvent.keyCodeToString(e.keyCode)} (${e.keyCode}) scan=${e.scanCode} device=${e.deviceId}")
            if (testingKeys && e.keyCode != KeyEvent.KEYCODE_BACK) return true
            if (testingKeys && e.keyCode == KeyEvent.KEYCODE_BACK) {
                testingKeys = false
                report("انتهى فحص الأزرار")
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onStop() {
        stopTest()
        super.onStop()
    }

    override fun onDestroy() {
        stopTest()
        super.onDestroy()
    }
}
