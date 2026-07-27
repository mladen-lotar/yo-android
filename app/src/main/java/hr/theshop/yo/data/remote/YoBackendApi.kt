package hr.theshop.yo.data.remote

import hr.theshop.yo.domain.model.AuthFailure
import hr.theshop.yo.domain.model.AuthResult
import hr.theshop.yo.domain.model.GoogleAuthResult
import hr.theshop.yo.domain.model.YoSession
import hr.theshop.yo.domain.repository.SessionStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Why an "add friend" attempt did not work, so the sheet can say something specific. */
enum class AddFriendOutcome {
    Added,
    NoSuchUser,
    Rejected,
    Unreachable,
}

interface YoBackendApi {
    suspend fun signUp(username: String, password: String): AuthResult

    suspend fun logIn(username: String, password: String): AuthResult

    /**
     * Exchange a Google ID token for a session. [username] is null on the first attempt and set
     * only after the backend has answered [GoogleAuthResult.UsernameRequired]; once the Google
     * account is known the server ignores it and returns the account it is already linked to.
     */
    suspend fun signInWithGoogle(idToken: String, username: String?): GoogleAuthResult

    suspend fun logOut(): Boolean

    /** Erases the account server-side. Required by Google Play for any app that creates one. */
    suspend fun deleteAccount(): Boolean

    suspend fun register(fcmToken: String): Boolean

    suspend fun fetchFriends(): List<String>

    suspend fun addFriend(username: String): AddFriendOutcome

    suspend fun removeFriend(username: String): Boolean

    suspend fun block(username: String): Boolean

    suspend fun sendYo(recipient: String): Boolean

    suspend fun uploadPhoto(
        messageId: String,
        base64Data: String,
        mimeType: String,
        recipient: String?,
    ): Boolean
}

class HttpYoBackendApi(
    baseUrl: String,
    private val sessionStore: SessionStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : YoBackendApi {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun signUp(username: String, password: String): AuthResult =
        authenticate("/v1/signup", username, password)

    override suspend fun logIn(username: String, password: String): AuthResult =
        authenticate("/v1/login", username, password)

    private suspend fun authenticate(
        path: String,
        username: String,
        password: String,
    ): AuthResult {
        val body =
            JSONObject()
                .put("username", username)
                .put("password", password)
                .toString()
        val response =
            try {
                execute(method = "POST", path = path, body = body)
            } catch (error: IOException) {
                return AuthResult.Failure(AuthFailure.Unreachable)
            }
        if (response.isSuccessful) {
            val payload = JSONObject(response.body)
            return AuthResult.Success(
                YoSession(
                    username = payload.getString("username"),
                    token = payload.getString("token"),
                ),
            )
        }
        return AuthResult.Failure(
            when (response.statusCode) {
                HttpURLConnection.HTTP_CONFLICT -> AuthFailure.UsernameTaken
                HttpURLConnection.HTTP_UNAUTHORIZED -> AuthFailure.InvalidCredentials
                HttpURLConnection.HTTP_BAD_REQUEST -> AuthFailure.Rejected
                TOO_MANY_REQUESTS -> AuthFailure.RateLimited
                else -> AuthFailure.Unreachable
            },
        )
    }

    override suspend fun signInWithGoogle(
        idToken: String,
        username: String?,
    ): GoogleAuthResult {
        val body =
            JSONObject()
                .put("id_token", idToken)
                .apply { if (username != null) put("username", username) }
                .toString()
        val response =
            try {
                execute(method = "POST", path = "/v1/google", body = body)
            } catch (error: IOException) {
                return GoogleAuthResult.Failure(AuthFailure.Unreachable)
            }
        if (response.isSuccessful) {
            val payload = JSONObject(response.body)
            return GoogleAuthResult.Success(
                YoSession(
                    username = payload.getString("username"),
                    token = payload.getString("token"),
                ),
            )
        }
        if (response.statusCode == HttpURLConnection.HTTP_NOT_FOUND &&
            errorCode(response.body) == "username_required"
        ) {
            return GoogleAuthResult.UsernameRequired
        }
        return GoogleAuthResult.Failure(
            when (response.statusCode) {
                HttpURLConnection.HTTP_CONFLICT -> AuthFailure.UsernameTaken
                HttpURLConnection.HTTP_UNAUTHORIZED -> AuthFailure.GoogleRejected
                HttpURLConnection.HTTP_BAD_REQUEST -> AuthFailure.Rejected
                HttpURLConnection.HTTP_UNAVAILABLE -> AuthFailure.GoogleUnavailable
                TOO_MANY_REQUESTS -> AuthFailure.RateLimited
                else -> AuthFailure.Unreachable
            },
        )
    }

    /** The `error` field, or empty when the body is not the JSON object we expect. */
    private fun errorCode(body: String): String =
        runCatching { JSONObject(body).optString("error") }.getOrDefault("")

    override suspend fun logOut(): Boolean =
        runCatching { execute(method = "DELETE", path = "/v1/session").isSuccessful }
            .getOrDefault(false)

    override suspend fun deleteAccount(): Boolean =
        runCatching { execute(method = "DELETE", path = "/v1/account").isSuccessful }
            .getOrDefault(false)

    override suspend fun register(fcmToken: String): Boolean {
        // The username is no longer sent: the server takes it from the token, so a device can
        // only ever claim its own account.
        val body = JSONObject().put("fcm_token", fcmToken).toString()
        return execute(method = "POST", path = "/v1/register", body = body).isSuccessful
    }

    override suspend fun fetchFriends(): List<String> {
        val response = execute(method = "GET", path = "/v1/friends")
        if (!response.isSuccessful) {
            throw IOException("Backend returned HTTP ${response.statusCode}")
        }

        val friends = JSONObject(response.body).getJSONArray("friends")
        return List(friends.length()) { index -> friends.getString(index) }
    }

    override suspend fun addFriend(username: String): AddFriendOutcome {
        val body = JSONObject().put("username", username).toString()
        val response =
            try {
                execute(method = "POST", path = "/v1/friends", body = body)
            } catch (error: IOException) {
                return AddFriendOutcome.Unreachable
            }
        return when {
            response.isSuccessful -> AddFriendOutcome.Added
            response.statusCode == HttpURLConnection.HTTP_NOT_FOUND -> AddFriendOutcome.NoSuchUser
            response.statusCode == HttpURLConnection.HTTP_BAD_REQUEST -> AddFriendOutcome.Rejected
            else -> AddFriendOutcome.Unreachable
        }
    }

    override suspend fun removeFriend(username: String): Boolean =
        runCatching {
            execute(method = "DELETE", path = "/v1/friends?username=${encode(username)}")
                .isSuccessful
        }.getOrDefault(false)

    override suspend fun block(username: String): Boolean {
        val body = JSONObject().put("username", username).toString()
        return runCatching {
            execute(method = "POST", path = "/v1/block", body = body).isSuccessful
        }.getOrDefault(false)
    }

    override suspend fun sendYo(recipient: String): Boolean {
        // No sender field: spoofing another user is impossible because the server reads the
        // sender off the bearer token.
        val body = JSONObject().put("recipient", recipient).toString()
        val response = execute(method = "POST", path = "/v1/send", body = body)
        return response.isSuccessful && JSONObject(response.body).optBoolean("delivered", false)
    }

    override suspend fun uploadPhoto(
        messageId: String,
        base64Data: String,
        mimeType: String,
        recipient: String?,
    ): Boolean {
        val body =
            JSONObject()
                .put("message_id", messageId)
                .put("mime_type", mimeType)
                .put("data", base64Data)
                .apply { if (recipient != null) put("recipient", recipient) }
                .toString()
        val response = execute(method = "POST", path = "/v1/photo", body = body)
        return response.isSuccessful && JSONObject(response.body).optBoolean("stored", false)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

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
                    sessionStore.current()?.let { session ->
                        setRequestProperty("Authorization", "Bearer ${session.token}")
                    }
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

        // HttpURLConnection has no constant for 429.
        const val TOO_MANY_REQUESTS = 429
    }
}
