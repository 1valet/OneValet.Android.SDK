package com.onevalet.onevaletsdk.demo.network

import android.util.Log
import com.onevalet.onevaletsdk.demo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * A small, dependency-light HTTP client — the Kotlin counterpart of the iOS
 * sample's `NetworkClient`. Uses `HttpURLConnection` on the IO dispatcher and
 * `kotlinx.serialization` for the JSON (de)coding, so the demo pulls in no
 * Retrofit/OkHttp stack. Besides request/response calls it can hold a
 * Server-Sent Events stream open (see [readEventStream]).
 */
class NetworkClient {

    /** Public so the `inline` helpers below can reference it. */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** POST to [urlString] with no body and decode the JSON response into [R]. */
    suspend inline fun <reified R> post(urlString: String, bearerToken: String? = null): R =
        json.decodeFromString(request(urlString, "POST", "", bearerToken))

    /** POST [payload] as JSON to [urlString] and decode the response into [R]. */
    suspend inline fun <reified P, reified R> post(
        urlString: String,
        payload: P,
        bearerToken: String? = null,
    ): R =
        json.decodeFromString(request(urlString, "POST", json.encodeToString(payload), bearerToken))

    /** POST [payload] as JSON; returns true on a 2xx (response body ignored). */
    suspend inline fun <reified P> postForResult(
        urlString: String,
        payload: P,
        bearerToken: String? = null,
    ): Boolean {
        request(urlString, "POST", json.encodeToString(payload), bearerToken)
        return true
    }

    suspend fun request(
        urlString: String,
        method: String,
        body: String?,
        bearerToken: String? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Accept", "application/json")
                bearerToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                connectTimeout = 30_000
                readTimeout = 30_000
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
            }
            try {
                if (body != null) {
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (BuildConfig.DEBUG) {
                    // Debug builds only: bodies can contain access tokens.
                    Log.d(TAG, "$method $urlString -> $code\n$text")
                }
                if (code !in 200..299) throw NetworkError.HttpError(code, text)
                text
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Opens [urlString] as a Server-Sent Events stream and invokes [onEvent] for every
     * event until [onEvent] returns false (stop listening), the server closes the
     * connection, the coroutine is cancelled, or the connection drops (an [Exception] —
     * callers reconnect with backoff). Heartbeat comments (`: keep-alive`) are consumed
     * silently, but they keep the read timeout from firing: a stream that goes silent for
     * longer than three heartbeat intervals throws SocketTimeoutException.
     */
    suspend fun readEventStream(
        urlString: String,
        bearerToken: String,
        onEvent: suspend (event: String, data: String) -> Boolean,
    ): Unit = coroutineScope {
        val connection = withContext(Dispatchers.IO) {
            (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "text/event-stream")
                // HttpURLConnection's transparent gzip buffers whole responses, which would
                // hold events back indefinitely.
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Authorization", "Bearer $bearerToken")
                connectTimeout = 30_000
                // The stream is idle between events, but the portal writes a heartbeat after
                // every quiet 20 s. Three missed heartbeats means the connection is dead
                // (Wi-Fi → cellular, NAT dropped the session): the read throws
                // SocketTimeoutException and the caller's reconnect loop takes over.
                // Keep this in step with SseStream._heartbeatInterval on the portal.
                readTimeout = 60_000
            }
        }

        // Blocking reads don't observe coroutine cancellation — disconnecting the
        // connection from a watcher coroutine is what breaks the read loop out.
        val cancellationWatcher = launch {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }

        try {
            withContext(Dispatchers.IO) {
                val code = connection.responseCode
                if (code !in 200..299) {
                    val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw NetworkError.HttpError(code, text)
                }

                connection.inputStream.bufferedReader().use { reader ->
                    var eventName = "message"
                    val data = StringBuilder()

                    while (true) {
                        val line = reader.readLine() ?: break

                        when {
                            line.isEmpty() -> {
                                if (data.isNotEmpty() && !onEvent(eventName, data.toString())) {
                                    return@use
                                }
                                eventName = "message"
                                data.clear()
                            }

                            line.startsWith(":") -> Unit // heartbeat comment

                            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()

                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").trim())
                            }
                        }
                    }
                }
            }
        } finally {
            cancellationWatcher.cancel()
            connection.disconnect()
        }
    }

    companion object {
        const val TAG = "OneValetDemoNet"
    }
}

/** Networking failures, mirroring the iOS sample's `NetworkError`. */
sealed class NetworkError(message: String) : Exception(message) {
    class HttpError(val statusCode: Int, val bodyText: String) :
        NetworkError("HTTP error $statusCode")
}
