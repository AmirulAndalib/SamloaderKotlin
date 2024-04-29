@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "EXPOSED_PARAMETER_TYPE")

package tk.zwander.common.tools

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.core.internal.DangerousInternalIoApi
import io.ktor.utils.io.core.toByteArray
import io.ktor.utils.io.jvm.nio.copyTo
import okio.BufferedSink
import tk.zwander.common.util.globalHttpClient
import tk.zwander.common.util.firstElementByTagName
import tk.zwander.common.util.trackOperationProgress
import kotlin.time.ExperimentalTime

/**
 * Manage communications with Samsung's server.
 */
@DangerousInternalIoApi
@OptIn(ExperimentalTime::class)
object FusClient {
    enum class Request(val value: String) {
        GENERATE_NONCE("NF_DownloadGenerateNonce.do"),
        BINARY_INFORM("NF_DownloadBinaryInform.do"),
        BINARY_INIT("NF_DownloadBinaryInitForMass.do")
    }

    private var encNonce = ""
    private var nonce = ""

    private var auth: String = ""
    private var sessionId: String = ""

    suspend fun getNonce(): String {
        if (nonce.isBlank()) {
            generateNonce()
        }

        return nonce
    }

    private suspend fun generateNonce() {
        println("Generating nonce.")
        makeReq(Request.GENERATE_NONCE)
        println("Nonce: $nonce")
        println("Auth: $auth")
    }

    private fun getAuthV(includeNonce: Boolean = true): String {
        return "FUS nonce=\"${if (includeNonce) encNonce else ""}\", signature=\"${this.auth}\", nc=\"\", type=\"\", realm=\"\", newauth=\"1\""
    }

    private fun getDownloadUrl(path: String): String {
        return "http://cloud-neofussvr.samsungmobile.com/NF_DownloadBinaryForMass.do?file=${path}"
    }

    /**
     * Make a request to Samsung, automatically inserting authorization data.
     * @param request the request to make.
     * @param data any body data that needs to go into the request.
     * @return the response body data, as text. Usually XML.
     */
    suspend fun makeReq(request: Request, data: String = "", includeNonce: Boolean = true): String {
        if (nonce.isBlank() && request != Request.GENERATE_NONCE) {
            generateNonce()
        }

        val authV = getAuthV(includeNonce)

        val response = globalHttpClient.request("https://neofussvr.sslcs.cdngc.net/${request.value}") {
            method = HttpMethod.Post
            headers {
                append("Authorization", authV)
                append("User-Agent", "Kiss2.0_FUS")
                append("Cookie", "JSESSIONID=${sessionId}")
                append("Set-Cookie", "JSESSIONID=${sessionId}")
                append(HttpHeaders.ContentLength, "${data.toByteArray().size}")
            }
            setBody(data)
        }

        val body = response.bodyAsText()

        if (request != Request.GENERATE_NONCE && response.is401(body)) {
            generateNonce()

            return makeReq(request, data, includeNonce)
        }

        if (response.headers["NONCE"] != null || response.headers["nonce"] != null) {
            encNonce = response.headers["NONCE"] ?: response.headers["nonce"] ?: ""
            nonce = CryptUtils.decryptNonce(encNonce)
            auth = CryptUtils.getAuth(nonce)
        }

        if (response.headers["Set-Cookie"] != null || response.headers["set-cookie"] != null) {
            sessionId = response.headers.entries().find { it.value.any { value -> value.contains("JSESSIONID=") } }
                ?.value?.find {
                    it.contains("JSESSIONID=")
                }
                ?.replace("JSESSIONID=", "")
                ?.replace(Regex(";.*$"), "") ?: sessionId
        }

        return body
    }

    /**
     * Download a file from Samsung's server.
     * @param fileName the name of the file to download.
     * @param start an optional offset. Used for resuming downloads.
     */
    suspend fun downloadFile(
        fileName: String,
        start: Long = 0,
        size: Long,
        output: BufferedSink,
        outputSize: Long,
        progressCallback: suspend (current: Long, max: Long, bps: Long) -> Unit,
    ): String? {
        val authV = getAuthV()
        val url = getDownloadUrl(fileName)

        val request = globalHttpClient.prepareRequest {
            method = HttpMethod.Get
            url(url)
            headers {
                append("Authorization", authV)
                append("User-Agent", "Kies2.0_FUS")
                if (start > 0) {
                    append("Range", "bytes=${start}-")
                }
            }
            timeout {
                this.requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                this.socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                this.connectTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            }
        }

        return request.execute { response ->
            val md5 = response.headers["Content-MD5"]
            val channel = response.bodyAsChannel()

            trackOperationProgress(
                size = size,
                progressCallback = progressCallback,
                operation = {
                    channel.copyTo(output, 1024 * 256L)
                },
                progressOffset = outputSize,
                condition = { !channel.isClosedForRead },
                throttle = false,
            )

            md5
        }
    }

    private fun HttpResponse.is401(body: String): Boolean {
        if (status.value == 401) {
            return true
        }

        try {
            val xml = Ksoup.parse(body)

            val status = xml.firstElementByTagName("FUSBody")
                ?.firstElementByTagName("Results")
                ?.firstElementByTagName("Status")
                ?.text()

            if (status == "401") {
                return true
            }
        } catch (_: Throwable) {
        }

        return false
    }
}
