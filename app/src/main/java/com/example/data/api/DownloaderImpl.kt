package com.example.data.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloaderImpl private constructor(val client: OkHttpClient) : Downloader() {

    companion object {
        private var instance: DownloaderImpl? = null

        private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

        private val cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val host = url.host
                val existing = cookieStore.getOrPut(host) { mutableListOf() }
                synchronized(existing) {
                    cookies.forEach { cookie ->
                        existing.removeAll { it.name == cookie.name }
                        existing.add(cookie)
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val host = url.host
                val cookies = mutableListOf<Cookie>()
                
                // Add confirmed GDPR consent cookies for youtube.com and google.com
                if (host.contains("youtube.com") || host.contains("youtubei") || host.contains("google.com")) {
                    cookies.add(Cookie.Builder().domain("youtube.com").path("/").name("SOCS").value("CAISEwgDEgk1NDI5NTgyOTYaAmVuIAEaBgiA_LqmBg").build())
                    cookies.add(Cookie.Builder().domain("youtube.com").path("/").name("CONSENT").value("YES+cb.20210328-17-p0.en+FX+478").build())
                    cookies.add(Cookie.Builder().domain("youtube.com").path("/").name("PREF").value("hl=en&gl=US&tz=UTC&f6=40000000").build())
                    cookies.add(Cookie.Builder().domain("youtube.com").path("/").name("GPS").value("1").build())
                }

                cookieStore[host]?.let {
                    synchronized(it) {
                        cookies.addAll(it)
                    }
                }
                return cookies
            }
        }

        fun init(builder: OkHttpClient.Builder? = null): DownloaderImpl {
            val okHttpClient = (builder ?: OkHttpClient.Builder())
                .cookieJar(cookieJar)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(32, 10, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .build()
            return DownloaderImpl(okHttpClient).also { instance = it }
        }

        fun getInstance(): DownloaderImpl {
            return instance ?: init()
        }
    }

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = okhttp3.Request.Builder()
            .url(url)

        var hasUserAgent = false
        var hasAcceptLanguage = false

        headers.forEach { (name, values) ->
            if (values.isNotEmpty()) {
                if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                if (name.equals("Accept-Language", ignoreCase = true)) hasAcceptLanguage = true
                requestBuilder.removeHeader(name)
                values.forEach { value ->
                    requestBuilder.addHeader(name, value)
                }
            }
        }

        if (!hasUserAgent) {
            requestBuilder.header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            )
        }
        if (!hasAcceptLanguage) {
            requestBuilder.header("Accept-Language", "en-US,en;q=0.9")
        }

        val body = if (dataToSend != null) {
            dataToSend.toRequestBody(null)
        } else if (httpMethod.equals("POST", ignoreCase = true)) {
            ByteArray(0).toRequestBody(null)
        } else {
            null
        }

        requestBuilder.method(httpMethod, body)

        return client.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            val responseHeaders = mutableMapOf<String, List<String>>()
            for (name in response.headers.names()) {
                responseHeaders[name] = response.headers.values(name)
            }

            Response(
                response.code,
                response.message,
                responseHeaders,
                responseBody,
                response.request.url.toString()
            )
        }
    }
}
