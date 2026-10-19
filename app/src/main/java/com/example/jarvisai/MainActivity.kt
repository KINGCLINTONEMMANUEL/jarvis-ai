
package com.example.jarvisai

import android.app.Activity
import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import android.graphics.Color
import android.view.ViewGroup
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var web: WebView
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private val aiExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(5, 11, 20)
        window.navigationBarColor = Color.rgb(5, 11, 20)

        tts = TextToSpeech(this, this)

        web = WebView(this)
        web.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Jarvis")

        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    private fun tellPage(functionCall: String) {
        runOnUiThread {
            if (::web.isInitialized) {
                web.evaluateJavascript(functionCall, null)
            }
        }
    }

    private fun startRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tellPage("showMessage('Speech recognition is unavailable on this device.')")
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                tellPage("setStatus('Listening...')")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                tellPage("setStatus('Thinking...')")
            }

            override fun onError(error: Int) {
                tellPage("setStatus('Ready')")
                tellPage("showMessage('Could not recognize speech. Please try again.')")
            }

            override fun onResults(results: Bundle?) {
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return

                tellPage("handleVoiceResult(${JSONObject.quote(spoken)})")
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer?.startListening(intent)
    }

    private fun askLocalAI(prompt: String) {
        aiExecutor.execute {
            var connection: HttpURLConnection? = null

            try {
                connection = URL(
                    "http://127.0.0.1:8080/v1/chat/completions"
                ).openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 120000
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type", "application/json"
                )

                val messages = JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            "You are JARVIS, a helpful personal AI assistant. " +
                            "Answer clearly and honestly. Do not invent " +
                            "personal history or facts. User: $prompt"
                        )
                )

                val request = JSONObject()
                    .put("messages", messages)
                    .put("max_tokens", 160)
                    .put("temperature", 0.7)

                OutputStreamWriter(
                    connection.outputStream, Charsets.UTF_8
                ).use {
                    it.write(request.toString())
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val responseText = BufferedReader(
                    InputStreamReader(stream, Charsets.UTF_8)
                ).use { it.readText() }

                if (code !in 200..299) {
                    throw Exception("Local server returned HTTP $code")
                }

                val json = JSONObject(responseText)
                val reply = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                tellPage(
                    "handleAIResult(true, ${JSONObject.quote(reply)})"
                )
            } catch (e: Exception) {
                tellPage(
                    "handleAIResult(false, " +
                    "${JSONObject.quote("Local AI unavailable. Start the " +
                    "llama-server in Termux and try again.")})"
                )
            } finally {
                connection?.disconnect()
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun askAI(prompt: String) {
            if (prompt.isBlank()) return
            askLocalAI(prompt.take(2000))
        }

        @JavascriptInterface
        fun listen() {
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        arrayOf(Manifest.permission.RECORD_AUDIO), 101
                    )
                } else {
                    startRecognition()
                }
            }
        }

        @JavascriptInterface
        fun speak(text: String) {
            runOnUiThread {
                if (::tts.isInitialized) {
                    tts.speak(
                        text.take(1500),
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "jarvis-reply"
                    )
                }
            }
        }

        @JavascriptInterface
        fun searchWeb(query: String) {
            runOnUiThread {
                val url = Uri.parse(
                    "https://www.google.com/search?q=" + Uri.encode(query)
                )
                startActivity(Intent(Intent.ACTION_VIEW, url))
            }
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread {
                startActivity(
                    Intent(android.provider.Settings.ACTION_SETTINGS)
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        )

        if (requestCode == 101) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecognition()
            } else {
                tellPage(
                    "showMessage('Microphone permission is required for voice input.')"
                )
            }
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        aiExecutor.shutdownNow()
        web.destroy()
        super.onDestroy()
    }
}
