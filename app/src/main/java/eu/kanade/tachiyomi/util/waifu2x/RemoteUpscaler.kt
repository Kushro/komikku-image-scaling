package eu.kanade.tachiyomi.util.waifu2x

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    /**
     * Send a bitmap to the remote upscaler server and receive the upscaled bitmap.
     *
     * @param input Original decoded bitmap (ARGB_8888)
     * @param host Server IP address (e.g. "192.168.1.42")
     * @param port Server port (e.g. 8282)
     * @return Upscaled bitmap, or null on any failure
     */
    suspend fun process(
        input: Bitmap,
        host: String,
        port: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (host.isBlank()) {
            Log.w(TAG, "Remote upscaler host is blank — skipping")
            return@withContext null
        }

        try {
            // 1. Encode bitmap to PNG bytes
            val baos = ByteArrayOutputStream()
            input.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val imageBytes = baos.toByteArray()

            // 2. POST to remote server
            val body = imageBytes.toRequestBody("image/png".toMediaType())
            val request = Request.Builder()
                .url("http://$host:$port/upscale")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Remote upscaler returned HTTP ${response.code}")
                return@withContext null
            }

            // 3. Decode response as bitmap
            val responseBody = response.body!!.bytes()
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(responseBody, 0, responseBody.size, options)
        } catch (e: ConnectException) {
            Log.e(TAG, "Cannot connect to remote upscaler at $host:$port — is the server running?")
            null
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Remote upscaler timed out for $host:$port")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Remote upscaler failed: ${e.message}", e)
            null
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
