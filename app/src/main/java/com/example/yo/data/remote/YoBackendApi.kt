package com.example.yo.data.remote

import com.example.yo.domain.model.YoIdentity
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

interface YoBackendApi {
    suspend fun register(
        username: String,
        fcmToken: String,
    ): Boolean

    suspend fun fetchFriends(): List<String>

    suspend fun sendYo(
        sender: String,
        recipient: String,
    ): Boolean

    suspend fun uploadPhoto(
        messageId: String,
        base64Data: String,
        mimeType: String,
    ): Boolean
}

class HttpYoBackendApi(
    baseUrl: String,
    private val sharedKey: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : YoBackendApi {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun register(
        username: String,
        fcmToken: String,
    ): Boolean {
        val body =
            JSONObject()
                .put("username", username)
                .put("fcm_token", fcmToken)
                .toString()
        return execute(method = "POST", path = "/v1/register", body = body).isSuccessful
    }

    override suspend fun fetchFriends(): List<String> {
        val username = URLEncoder.encode(YoIdentity.CURRENT_USERNAME, Charsets.UTF_8.name())
        val response = execute(method = "GET", path = "/v1/friends?username=$username")
        if (!response.isSuccessful) {
            throw IOException("Backend returned HTTP ${response.statusCode}")
        }

        val friends = JSONObject(response.body).getJSONArray("friends")
        return List(friends.length()) { index -> friends.getString(index) }
    }

    override suspend fun sendYo(
        sender: String,
        recipient: String,
    ): Boolean {
        val body =
            JSONObject()
                .put("sender", sender)
                .put("recipient", recipient)
                .toString()
        val response = execute(method = "POST", path = "/v1/send", body = body)
        return response.isSuccessful && JSONObject(response.body).optBoolean("delivered", false)
    }

    override suspend fun uploadPhoto(
        messageId: String,
        base64Data: String,
        mimeType: String,
    ): Boolean {
        val body =
            JSONObject()
                .put("message_id", messageId)
                .put("mime_type", mimeType)
                .put("data", base64Data)
                .toString()
        val response = execute(method = "POST", path = "/v1/photo", body = body)
        return response.isSuccessful && JSONObject(response.body).optBoolean("stored", false)
    }

    private suspend fun execute(
        method: String,
        path: String,
        body: String? = null,
    ): BackendResponse =
        withContext(ioDispatcher) {
            val connection =
                (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = TIMEOUT_MILLIS
                    readTimeout = TIMEOUT_MILLIS
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Yo-Key", sharedKey)
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                }

            try {
                if (body != null) {
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(body)
                    }
                }

                val statusCode = connection.responseCode
                val responseStream =
                    if (statusCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                val responseBody =
                    responseStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        reader.readText()
                    }.orEmpty()
                BackendResponse(statusCode = statusCode, body = responseBody)
            } finally {
                connection.disconnect()
            }
        }

    private data class BackendResponse(
        val statusCode: Int,
        val body: String,
    ) {
        val isSuccessful: Boolean
            get() = statusCode in 200..299
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000
    }
}
