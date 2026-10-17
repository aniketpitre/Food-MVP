package app.foodfeed.data

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** An error the user can read: the server's own message, or a plain network message. */
class ApiException(val code: String, override val message: String, val status: Int = 0) : Exception(message)

fun Throwable.toApiException(): ApiException = when (this) {
    is ApiException -> this
    is HttpException -> {
        val body = response()?.errorBody()?.string()
        val parsed = body?.let { runCatching { AppJson.decodeFromString(ApiError.serializer(), it) }.getOrNull() }
        ApiException(parsed?.error ?: "http_${code()}", parsed?.message ?: "The server answered ${code()}.", code())
    }
    is IOException -> ApiException("offline", "Can't reach the WorthAbite server. Check your connection.")
    is kotlinx.serialization.SerializationException ->
        ApiException("bad_response", "The server sent something this app version doesn't understand.")
    else -> ApiException("unknown", message ?: "Something went wrong.")
}

/** Runs a call and turns any failure into an [ApiException]. */
suspend inline fun <T> apiCall(crossinline block: suspend () -> T): T =
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw e.toApiException()
    }

fun normaliseServerUrl(raw: String): String {
    var url = raw.trim().trimEnd('/')
    if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
    return url
}

/** "Google Pixel 6 - Android 14": how this phone shows in the list of signed-in devices. */
fun deviceName(): String {
    val maker = android.os.Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
    val model = android.os.Build.MODEL.orEmpty()
    val name = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
    // Header values must be plain ASCII.
    return "$name - Android ${android.os.Build.VERSION.RELEASE}".filter { it.code in 32..126 }.trim().take(80)
}

class NetworkFactory(
    private val tokenProvider: () -> String?,
    private val serverUrl: () -> String = { "" },
    /** Our server said this sign-in has ended (signed out elsewhere, password changed). */
    private val onSessionEnded: (token: String) -> Unit = {},
    private val device: String? = null,
) {
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            // The sign-in token and the JSON header go to our own server only, never to
            // other hosts the image loader fetches from (Google photos, map tiles...).
            val request = chain.request()
            val ours = serverUrl().toHttpUrlOrNull()?.host
            if (ours == null || request.url.host != ours) return@Interceptor chain.proceed(request)
            val token = tokenProvider()
            val req = request.newBuilder().apply {
                if (token != null) header("Authorization", "Bearer $token")
                if (request.header("Accept") == null) header("Accept", "application/json")
                device?.let { header("X-Device-Name", it) }
            }.build()
            val response = chain.proceed(req)
            if (token != null && response.code == 401) {
                val body = runCatching { response.peekBody(2048).string() }.getOrDefault("")
                if ("\"session_ended\"" in body || "\"sign_in_required\"" in body) onSessionEnded(token)
            }
            response
        })
        .build()

    fun api(baseUrl: String): WorthAbiteApi = Retrofit.Builder()
        .baseUrl("${normaliseServerUrl(baseUrl)}/")
        .client(okHttp)
        .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(WorthAbiteApi::class.java)
}
