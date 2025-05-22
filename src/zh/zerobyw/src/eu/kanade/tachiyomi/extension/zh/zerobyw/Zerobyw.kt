package eu.kanade.tachiyomi.extension.zh.zerobyw

import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import keiyoushi.utils.getPreferences
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

class Zerobyw : ParsedHttpSource(), ConfigurableSource {
    override val name: String = "zero搬运网"
    override val lang: String = "zh"
    override val supportsLatest: Boolean get() = false
    private val preferences = getPreferences { clearOldBaseUrl() }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(UpdateUrlInterceptor(preferences))
        .addInterceptor(AuthInterceptor(this))
        .rateLimit(2)
        .build()

    override val baseUrl get() = when {
        isCi -> ciGetUrl(client)
        else -> preferences.baseUrl
    }

    private val isCi = System.getenv("CI") == "true"

    // Preferences for username and password
    private var username: String? 
        get() = preferences.getString("username", null)
        set(value) = preferences.edit().putString("username", value).apply()

    private var password: String?
        get() = preferences.getString("password", null)
        set(value) = preferences.edit().putString("password", value).apply()

    // Login function
    internal fun login(): String {
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            throw IOException("Username or password not set")
        }

        val loginPageUrl = "$baseUrl/member.php?mod=logging&action=login"
        val loginPageRequest = GET(loginPageUrl, headers)
        val loginPageResponse = client.newCall(loginPageRequest).execute()
        val loginPageBody = loginPageResponse.body.string()
        val doc = Jsoup.parse(loginPageBody)

        val form = doc.selectFirst("form[method=post]") ?: throw IOException("Login form not found")
        val formUrl = form.absUrl("action").ifEmpty { loginPageUrl }

        val formData = mutableMapOf<String, String>()
        form.select("input").forEach { element ->
            val name = element.attr("name")
            if (name.isNotBlank()) {
                formData[name] = element.attr("value")
            }
        }

        formData["username"] = username!!
        formData["password"] = password!!
        formData["loginsubmit"] = "true"

        val formBody = FormBody.Builder().apply {
            formData.forEach { (key, value) ->
                add(key, value)
            }
        }.build()

        val loginRequest = Request.Builder()
            .url(formUrl)
            .post(formBody)
            .headers(headers)
            .build()

        val loginResponse = client.newCall(loginRequest).execute()

        if (!loginResponse.isSuccessful) {
            throw IOException("Login failed: ${loginResponse.code}")
        }

        val cookies = loginResponse.headers("Set-Cookie").orEmpty()
            .joinToString("; ") { it.split(";").first() }

        if (cookies.isEmpty()) {
            throw IOException("Login failed: No session cookies received")
        }

        preferences.edit().putString("cookies", cookies).apply()
        return cookies
    }

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/121.0")

    // Rest of your existing code (popularMangaRequest, searchMangaFromElement, etc.)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(getBaseUrlPreference(screen.context))
        // Add username preference
        screen.addPreference(createEditTextPreference(screen.context, "username", "Username"))
        // Add password preference
        screen.addPreference(createEditTextPreference(screen.context, "password", "Password", true))
    }

    private fun createEditTextPreference(context: Context, key: String, title: String, isPassword: Boolean = false): EditTextPreference {
        return EditTextPreference(context).apply {
            this.key = key
            this.title = title
            this.summary = title
            if (isPassword) {
                setOnBindEditTextListener { editText ->
                    editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or 
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
        }
    }

    // ... (Keep all your existing parsing methods unchanged)
}

class AuthInterceptor(private val source: Zerobyw) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cookies = source.preferences.getString("cookies", null)

        // Try request with existing cookies first
        cookies?.let {
            val newRequest = request.newBuilder()
                .header("Cookie", it)
                .build()
            val response = chain.proceed(newRequest)
            
            if (response.isSuccessful) return response
            response.close()
            
            // Clear invalid cookies
            source.preferences.edit().remove("cookies").apply()
        }

        // Attempt login if credentials exist
        if (!source.username.isNullOrEmpty() && !source.password.isNullOrEmpty()) {
            try {
                val newCookies = source.login()
                return chain.proceed(request.newBuilder()
                    .header("Cookie", newCookies)
                    .build())
            } catch (e: Exception) {
                throw IOException("Authentication failed: ${e.message}")
            }
        }

        // Proceed without authentication
        return chain.proceed(request)
    }
}
