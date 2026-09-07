package com.netproxy.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import fi.iki.elonen.NanoHTTPD
import java.io.StringReader
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalProxyServer(port: Int, private val secretKey: String, private val allowedHostsConfig: String, private val context: Context) : NanoHTTPD("127.0.0.1", port) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun isHostAllowed(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val allowedList = allowedHostsConfig.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        val targetHost = host.lowercase()
        return allowedList.any { allowed ->
            targetHost == allowed || targetHost.endsWith(".$allowed")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val remoteIp = session.remoteIpAddress
        if (remoteIp != "127.0.0.1" && remoteIp != "0:0:0:0:0:0:0:1" && remoteIp != "localhost") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        }

        val parms = session.parms
        val headers = session.headers
        val token = parms["key"] ?: headers["key"] ?: headers["x-proxy-key"]
        val targetRaw = parms["url"]
        
        val apiMode = parms.containsKey("api")
        val htmlMode = parms.containsKey("html")

        if (token.isNullOrEmpty() || token != secretKey) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        }

        if (targetRaw.isNullOrEmpty()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        }

        try {
            val targetURL = URL(targetRaw)
            if (targetURL.protocol != "https") {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
            }
            if (targetURL.port != -1 && targetURL.port != 443) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
            }

            // Stroga provera dozvoljenih hostova
            if (!isHostAllowed(targetURL.host)) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
            }

            val method = session.method.name
            if (method != "GET" && method != "HEAD") {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
            }
            if (targetRaw.length > 2000) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
            }

            if ((apiMode && htmlMode) || (!apiMode && !htmlMode)) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
            }

            val uaStr = parms["ua"]
            val defaultUa = if (htmlMode) {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
            } else {
                "LFM_MusicApp/1.0 (contact: moj@gmail.com)"
            }
            val finalUa = if (!uaStr.isNullOrBlank() && uaStr.length < 300) uaStr else defaultUa

            val waitTime = parms["wait"]?.toLongOrNull() ?: 0L

            var htmlResult = ""
            val latch = CountDownLatch(1)

            mainHandler.post {
                try {
                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = finalUa
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            
                            mainHandler.postDelayed({
                                val jsCode = if (htmlMode) {
                                    "(function() { return document.documentElement.outerHTML; })();"
                                } else {
                                    "(function() { return document.body.innerText || document.documentElement.innerText; })();"
                                }

                                view?.evaluateJavascript(jsCode) { result ->
                                    htmlResult = result ?: ""
                                    view?.destroy()
                                    latch.countDown()
                                }
                            }, waitTime)
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                view?.destroy()
                                latch.countDown()
                            }
                        }
                    }

                    webView.loadUrl(targetRaw)
                } catch (e: Exception) {
                    latch.countDown()
                }
            }

            latch.await(15, TimeUnit.SECONDS)

            val cleanResult = if (htmlResult.startsWith("\"") && htmlResult.endsWith("\"")) {
                try {
                    JsonReader(StringReader(htmlResult)).use { reader ->
                        reader.isLenient = true
                        if (reader.peek() == android.util.JsonToken.STRING) {
                            reader.nextString()
                        } else {
                            htmlResult
                        }
                    }
                } catch (e: Exception) {
                    htmlResult
                }
            } else {
                htmlResult
            }

            val responseBytes = cleanResult.toByteArray(Charsets.UTF_8)
            val mimeType = if (htmlMode) "text/html; charset=UTF-8" else "application/json; charset=UTF-8"

            val nanoResponse = newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                responseBytes.inputStream(),
                responseBytes.size.toLong()
            )

            nanoResponse.addHeader("Access-Control-Allow-Origin", "*")
            return nanoResponse

        } catch (e: Exception) {
            Log.e("ProxyServer", "Error handling request", e)
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        }
    }
}
