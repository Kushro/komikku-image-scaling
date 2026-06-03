package eu.kanade.tachiyomi.util.waifu2x

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Sends images to a remote upscaler server for GPU-accelerated processing.
 *
 * When the remote upscaler is enabled in settings, this replaces the local NCNN
 * pipeline. The server decides which model to use — no local model configuration
 * is needed on the device.
 */
object RemoteUpscaler {

    private const val TAG = "RemoteUpscaler"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // Batches are processed sequentially server-side, so a whole batch can take much
    // longer than a single image. Give batch requests a generous read/write timeout.
    private val batchClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Send a bitmap to the remote upscaler server and receive the upscaled bitmap.
     *
     * @param input Original decoded bitmap (ARGB_8888)
     * @param host Server IP address (e.g. "192.168.1.42")
     * @param port Server port (e.g. 8282)
     * @param onStatus Optional callback invoked with human-readable progress messages
     * @return Upscaled bitmap, or null on any failure
     */
    suspend fun process(
        input: Bitmap,
        host: String,
        port: Int,
        onStatus: suspend (String) -> Unit = {},
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (host.isBlank()) {
            Log.w(TAG, "Remote upscaler host is blank — skipping")
            return@withContext null
        }

        try {
            onStatus("Connecting to $host:$port…")

            // 1. Encode bitmap to PNG bytes
            val baos = ByteArrayOutputStream()
            input.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val imageBytes = baos.toByteArray()

            onStatus("Uploading image (${imageBytes.size / 1024} KB)…")

            // 2. POST to remote server
            val body = imageBytes.toRequestBody("image/png".toMediaType())
            val request = Request.Builder()
                .url("http://$host:$port/upscale")
                .post(body)
                .build()

            val response = client.newCall(request).execute()

            onStatus("Processing on server…")

            if (!response.isSuccessful) {
                Log.e(TAG, "Remote upscaler returned HTTP ${response.code}")
                onStatus("Server error: HTTP ${response.code}")
                return@withContext null
            }

            onStatus("Downloading result…")

            // 3. Decode response as bitmap
            val responseBody = response.body!!.bytes()
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val result = BitmapFactory.decodeByteArray(responseBody, 0, responseBody.size, options)
            if (result != null) onStatus("Enhancement complete")
            result
        } catch (e: ConnectException) {
            Log.e(TAG, "Cannot connect to remote upscaler at $host:$port — is the server running?")
            onStatus("Connection failed — is the server running?")
            null
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Remote upscaler timed out for $host:$port")
            onStatus("Server timed out")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Remote upscaler failed: ${e.message}", e)
            onStatus("Remote enhancement failed")
            null
        }
    }

    /**
     * Ask the server to download a single image by URL and return the upscaled bitmap.
     *
     * Corresponds to `POST /upscale/url`. The server fetches the image itself (using a
     * generic browser User-Agent), so this offloads the download from the device. Returns
     * null on any failure — callers should fall back to [process] with the decoded bitmap
     * when this happens (e.g. the source is behind Cloudflare or needs auth headers).
     *
     * @param imageUrl Remote source image URL the server should download
     */
    suspend fun processUrl(
        imageUrl: String,
        host: String,
        port: Int,
        onStatus: suspend (String) -> Unit = {},
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (host.isBlank() || imageUrl.isBlank()) return@withContext null

        try {
            onStatus("Requesting server-side download…")
            val payload = JSONObject().put("url", imageUrl).toString()
            val request = Request.Builder()
                .url("http://$host:$port/upscale/url")
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Remote /upscale/url returned HTTP ${response.code}")
                onStatus("Server error: HTTP ${response.code}")
                return@withContext null
            }

            onStatus("Downloading result…")
            val responseBody = response.body!!.bytes()
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val result = BitmapFactory.decodeByteArray(responseBody, 0, responseBody.size, options)
            if (result != null) onStatus("Enhancement complete")
            result
        } catch (e: ConnectException) {
            Log.e(TAG, "Cannot connect to remote upscaler at $host:$port — is the server running?")
            onStatus("Connection failed — is the server running?")
            null
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Remote upscaler timed out for $host:$port")
            onStatus("Server timed out")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Remote /upscale/url failed: ${e.message}", e)
            onStatus("Remote enhancement failed")
            null
        }
    }

    /**
     * Send several images in a single request and receive the upscaled bitmaps.
     *
     * Corresponds to `POST /upscale/batch` (JSON `{images:[base64…]}`). The returned list
     * is aligned to the input order; entries the server failed to upscale are null.
     *
     * @param images Raw encoded image bytes (PNG/JPEG) — sent base64-encoded
     */
    suspend fun processBatch(
        images: List<ByteArray>,
        host: String,
        port: Int,
        onStatus: suspend (String) -> Unit = {},
    ): List<Bitmap?> = withContext(Dispatchers.IO) {
        if (host.isBlank() || images.isEmpty()) return@withContext emptyList()

        try {
            onStatus("Uploading batch of ${images.size} image(s)…")
            val arr = JSONArray()
            images.forEach { arr.put(Base64.encodeToString(it, Base64.NO_WRAP)) }
            val payload = JSONObject().put("images", arr).toString()
            val request = Request.Builder()
                .url("http://$host:$port/upscale/batch")
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            val response = batchClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Remote /upscale/batch returned HTTP ${response.code}")
                onStatus("Server error: HTTP ${response.code}")
                return@withContext List(images.size) { null }
            }
            onStatus("Decoding batch result…")
            parseBatchResponse(response.body!!.string(), images.size)
        } catch (e: Exception) {
            Log.e(TAG, "Remote /upscale/batch failed: ${e.message}", e)
            onStatus("Remote batch enhancement failed")
            List(images.size) { null }
        }
    }

    /**
     * Ask the server to download several images by URL and upscale them in one request.
     *
     * Corresponds to `POST /upscale/batch/url` (JSON `{urls:[…]}`). The server downloads
     * in parallel. The returned list is aligned to the input order; failures are null.
     */
    suspend fun processBatchUrl(
        urls: List<String>,
        host: String,
        port: Int,
        onStatus: suspend (String) -> Unit = {},
    ): List<Bitmap?> = withContext(Dispatchers.IO) {
        if (host.isBlank() || urls.isEmpty()) return@withContext emptyList()

        try {
            onStatus("Requesting server-side download of ${urls.size} image(s)…")
            val arr = JSONArray()
            urls.forEach { arr.put(it) }
            val payload = JSONObject().put("urls", arr).toString()
            val request = Request.Builder()
                .url("http://$host:$port/upscale/batch/url")
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            val response = batchClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Remote /upscale/batch/url returned HTTP ${response.code}")
                onStatus("Server error: HTTP ${response.code}")
                return@withContext List(urls.size) { null }
            }
            onStatus("Decoding batch result…")
            parseBatchResponse(response.body!!.string(), urls.size)
        } catch (e: Exception) {
            Log.e(TAG, "Remote /upscale/batch/url failed: ${e.message}", e)
            onStatus("Remote batch enhancement failed")
            List(urls.size) { null }
        }
    }

    /**
     * Parse a batch response body into bitmaps aligned to the request order.
     * Shape: {"batch_id":N,"results":[{"index":i,"success":bool,"image":"b64"|null,"error":"…"}]}.
     */
    private fun parseBatchResponse(body: String, expectedSize: Int): List<Bitmap?> {
        val out = arrayOfNulls<Bitmap>(expectedSize)
        try {
            val results = JSONObject(body).getJSONArray("results")
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val index = item.optInt("index", i)
                if (index !in 0 until expectedSize) continue
                if (item.optBoolean("success", false)) {
                    val b64 = item.optString("image", "")
                    if (b64.isNotEmpty()) {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        out[index] = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                } else {
                    Log.w(TAG, "Batch item $index failed: ${item.optString("error")}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse batch response: ${e.message}", e)
        }
        return out.toList()
    }

    /**
     * Check if the remote upscaler server is alive.
     *
     * @return true if server responds with 200 OK, false otherwise
     */
    suspend fun checkHealth(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext false
        try {
            val request = Request.Builder()
                .url("http://$host:$port/health")
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) return@withContext true
            // Fall back to /status if /health is not found (older server)
            if (response.code == 404) {
                val statusRequest = Request.Builder()
                    .url("http://$host:$port/status")
                    .get()
                    .build()
                return@withContext client.newCall(statusRequest).execute().isSuccessful
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed for $host:$port — ${e.message}")
            false
        }
    }

    /**
     * Check if the remote upscaler server is reachable.
     *
     * @return Status response map, or null if unreachable
     */
    suspend fun checkStatus(host: String, port: Int): Map<String, Any?>? = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext null

        try {
            val request = Request.Builder()
                .url("http://$host:$port/status")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body!!.string()
            // Parse simple JSON manually to avoid adding a JSON dependency
            parseStatusJson(body)
        } catch (e: Exception) {
            Log.w(TAG, "Remote upscaler status check failed: ${e.message}")
            null
        }
    }

    /**
     * Minimal JSON parser for the /status response.
     * Avoids pulling in kotlinx-serialization or Moshi for a single use case.
     */
    private fun parseStatusJson(json: String): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        // Strip outer braces and split by commas, handling quoted strings
        val content = json.trim().removeSurrounding("{", "}")
        val regex = """"(\w+)":\s*("(?:[^"\\]|\\.)*"|null|\d+)""".toRegex()
        regex.findAll(content).forEach { match ->
            val key = match.groupValues[1]
            val rawValue = match.groupValues[2]
            result[key] = when {
                rawValue == "null" -> null
                rawValue.startsWith("\"") -> rawValue.removeSurrounding("\"")
                rawValue.contains(".") -> rawValue.toDouble()
                else -> rawValue.toIntOrNull() ?: rawValue.toLongOrNull()
            }
        }
        return result
    }
}
