package com.rimesync.android.core

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** JSON 导航辅助函数。 */
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.lng(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
fun JsonObject.ints(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** Rime-server API 客户端，行为与 CLI 的 APIClient 对齐。 */
class ApiClient(private val config: RimeSyncConfig) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client: OkHttpClient = buildClient()

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(config.timeout.toLong(), TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
        if (!config.verifySsl) {
            val trustAll = TrustAllCerts
            builder.sslSocketFactory(trustAll.sslContext.socketFactory, trustAll.trustManager)
                .hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    private fun baseRequest(method: String, endpoint: String): Request.Builder {
        val builder = Request.Builder()
            .url(config.serverUrl + endpoint)
            .method(method, null)
        if (config.apiToken.isNotBlank()) {
            builder.header("X-Api-Token", config.apiToken)
        }
        return builder
    }

    private suspend fun <T> withRetry(
        action: String,
        retries: Int,
        onError: (suspend () -> Unit)? = null,
        block: suspend () -> Response,
        parse: (Response) -> T,
    ): T {
        val maxAttempts = maxOf(1, retries)
        var attempt = 0
        while (true) {
            attempt++
            var response: Response? = null
            try {
                response = block()
                val code = response.code
                if (response.isSuccessful) return parse(response)
                val errorMsg = parseError(response, code)
                if (code >= 500 && attempt < maxAttempts) {
                    onError?.invoke()
                    delay((1L shl attempt) * 1000L)
                    continue
                }
                onError?.invoke()
                throw ApiErrorException(errorMsg)
            } catch (e: IOException) {
                onError?.invoke()
                if (attempt >= maxAttempts) {
                    throw ApiErrorException("${action}失败，已达最大重试次数: ${e.message}", e)
                }
                delay((1L shl attempt) * 1000L)
            } finally {
                response?.close()
            }
        }
    }

    private fun parseError(response: Response, code: Int): String {
        return try {
            val body = response.body?.string() ?: return "HTTP错误 $code"
            val obj = json.parseToJsonElement(body).jsonObject
            obj.str("error") ?: "HTTP错误 $code"
        } catch (e: Exception) {
            "HTTP错误 $code"
        }
    }

    private suspend fun requestJson(
        method: String,
        endpoint: String,
        body: RequestBody? = null,
        params: Map<String, String>? = null,
        timeout: Long? = null,
        retries: Int = config.retryCount,
    ): JsonObject {
        val request = baseRequest(method, endpoint)
        if (body != null) {
            request.method(method, body)
        }
        var url = request.build().url.toString()
        if (params != null && params.isNotEmpty()) {
            url = url + "?" + params.map { "${it.key}=${it.value}" }.joinToString("&")
        }
        val finalRequest = request.url(url).build()
        return withRetry("请求", retries, block = {
            (if (timeout != null) client.newBuilder().callTimeout(timeout, TimeUnit.SECONDS).build() else client)
                .newCall(finalRequest).execute()
        }, parse = { response ->
            val body = response.body?.string() ?: "{}"
            json.parseToJsonElement(body).jsonObject
        })
    }

    private suspend fun requestBytes(
        endpoint: String,
        params: Map<String, String>? = null,
        retries: Int = config.retryCount,
    ): ByteArray {
        val request = baseRequest("GET", endpoint)
        var url = request.build().url.toString()
        if (params != null && params.isNotEmpty()) {
            url = url + "?" + params.map { "${it.key}=${it.value}" }.joinToString("&")
        }
        return withRetry("下载", retries, block = {
            client.newCall(request.url(url).build()).execute()
        }, parse = { response ->
            response.body?.bytes() ?: ByteArray(0)
        })
    }

    /** 流式下载到文件（用于大 tar 包），失败时清理残留文件。 */
    private suspend fun downloadToFile(
        endpoint: String,
        target: File,
        params: Map<String, String>? = null,
    ): File {
        val request = baseRequest("GET", endpoint)
        var url = request.build().url.toString()
        if (params != null && params.isNotEmpty()) {
            url = url + "?" + params.map { "${it.key}=${it.value}" }.joinToString("&")
        }
        target.parentFile?.mkdirs()
        return withRetry("下载", config.retryCount, onError = {
            target.delete()
        }, block = {
            client.newCall(request.url(url).build()).execute()
        }, parse = { response ->
            response.body?.byteStream()?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            target
        })
    }

    private fun multipart(
        endpoint: String,
        fileName: String,
        file: File,
        mime: String,
        fields: Map<String, String>,
    ): RequestBody {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        for ((k, v) in fields) {
            body.addFormDataPart(k, v)
        }
        body.addFormDataPart("file", fileName, file.asRequestBody(mime.toMediaType()))
        return body.build()
    }

    // ---- 端点 ----

    suspend fun getDeviceList(): JsonObject = requestJson("GET", "/api/device/list")

    suspend fun getSyncInfo(device: String? = null, since: String? = null): JsonObject {
        val params = HashMap<String, String>()
        if (device != null) params["device"] = device
        if (since != null) params["since"] = since
        return requestJson("GET", "/api/sync/info", params = params.ifEmpty { null })
    }

    suspend fun getFullSyncInfo(exclude: String? = null, since: String? = null): JsonObject {
        val params = HashMap<String, String>()
        if (exclude != null) params["exclude"] = exclude
        if (since != null) params["since"] = since
        return requestJson("GET", "/api/full_sync/info", params = params.ifEmpty { null })
    }

    suspend fun uploadSyncTar(tar: File, device: String): JsonObject =
        requestJson("POST", "/api/sync/upload/tar", body = multipart(
            "/api/sync/upload/tar", "sync_$device.tar", tar, "application/x-tar",
            mapOf("device" to device),
        ))

    suspend fun uploadSyncFile(file: File, filename: String, device: String): JsonObject =
        requestJson("POST", "/api/sync/upload/file", body = multipart(
            "/api/sync/upload/file", filename, file, "application/octet-stream",
            mapOf("device" to device, "filename" to filename),
        ))

    suspend fun uploadFullSync(file: File, overwrite: Boolean): JsonObject =
        requestJson("POST", "/api/full_sync/upload", body = multipart(
            "/api/full_sync/upload", file.name, file, "application/x-tar",
            mapOf("overwrite" to overwrite.toString()),
        ))

    suspend fun downloadSyncFile(filename: String, device: String): ByteArray =
        requestBytes("/api/sync/get/$device/file/$filename")

    suspend fun downloadFullSyncTar(exclude: String? = null, since: String? = null, target: File): File {
        val params = HashMap<String, String>()
        if (exclude != null) params["exclude"] = exclude
        if (since != null) params["since"] = since
        return downloadToFile("/api/full_sync/download", target, params.ifEmpty { null })
    }
}

/** 关闭 SSL 校验时使用的 Trust-all 管理器。 */
object TrustAllCerts {
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }
    val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
    }
}