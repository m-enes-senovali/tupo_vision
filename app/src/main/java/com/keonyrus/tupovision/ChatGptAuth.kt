package com.keonyrus.tupovision

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AuthException(message: String) : Exception(message)

/**
 * Sign in with ChatGPT for open-source apps: OAuth with PKCE, a client registered on first sign-in,
 * no client secret and no API key. The access token goes straight to api.openai.com and requests
 * count against the user's ChatGPT plan.
 *
 * Flow and endpoints follow https://developers.openai.com/siwc/token-sharing-open-source
 */
object ChatGptAuth {
  private const val AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
  private const val TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
  private const val RESOURCE = "https://api.openai.com/v1"
  private const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
  private const val AGENT_NAME = "Tupo Vision TR"
  private const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
  private const val SCOPE = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
  private const val CALLBACK_PATH = "/auth/callback"
  private const val PREFERRED_PORT = 1455
  private const val LOGIN_TIMEOUT_MS = 5 * 60 * 1000L
  private const val OFFLINE_RETRY_MS = 45 * 1000L
  // Refresh this long before expiry so a request never starts with a token about to die.
  private const val EXPIRY_MARGIN_MS = 3 * 60 * 1000L

  private const val STORE_KEY = "chatgpt_credentials"
  private const val PREFS = "auth"
  private const val PREF_HOST_ID = "host_id"

  private data class Credentials(
      val clientId: String,
      val access: String,
      val refresh: String,
      val expiresAtMs: Long,
  )

  private val refreshMutex = Mutex()
  @Volatile private var cached: Credentials? = null

  fun isSignedIn(context: Context): Boolean = load(context) != null

  fun signOut(context: Context) {
    cached = null
    SecureStore.put(context, STORE_KEY, null)
  }

  /**
   * Runs the whole sign-in. [openBrowser] is called with the authorization URL once the local
   * callback listener is up, and [onCodeReceived] when the browser has come back with the code;
   * the function returns when tokens are stored.
   */
  suspend fun login(
      context: Context,
      openBrowser: (Uri) -> Unit,
      onCodeReceived: () -> Unit,
      log: (String) -> Unit,
  ) {
    val appContext = context.applicationContext
    val verifier = randomUrlSafe()
    val challenge =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    val state = randomUrlSafe()
    val nonce = randomUrlSafe()

    val server = withContext(Dispatchers.IO) { openCallbackServer() }
    try {
      val redirectUri = "http://127.0.0.1:${server.localPort}$CALLBACK_PATH"
      val authorizationUrl =
          Uri.parse(AUTHORIZE_URL)
              .buildUpon()
              .appendQueryParameter("client_id", DYNAMIC_CLIENT_ID)
              .appendQueryParameter("agent_name_hint", AGENT_NAME)
              .appendQueryParameter("ext_agent_host_id", hostId(appContext))
              .appendQueryParameter("response_type", "code")
              .appendQueryParameter("redirect_uri", redirectUri)
              .appendQueryParameter("resource", RESOURCE)
              .appendQueryParameter("scope", SCOPE)
              .appendQueryParameter("state", state)
              .appendQueryParameter("code_challenge", challenge)
              .appendQueryParameter("code_challenge_method", "S256")
              .appendQueryParameter("nonce", nonce)
              .build()
      openBrowser(authorizationUrl)

      val callback = withContext(Dispatchers.IO) { awaitCallback(server, state) }
      log("Tarayıcı giriş kodunu döndürdü, jetonlar isteniyor")
      // The browser is still on top here, so the app counts as a background app, and saver modes
      // cut background apps off the network. Come back to the screen first, then keep trying
      // while the network opens up.
      onCodeReceived()
      val token =
          retryWhileOffline(log) {
            postForm(
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to callback.clientId,
                    "code" to callback.code,
                    "code_verifier" to verifier,
                    "redirect_uri" to redirectUri,
                    "resource" to RESOURCE,
                ),
            )
          }
      save(appContext, credentialsFrom(token, callback.clientId))
    } finally {
      runCatching { server.close() }
    }
  }

  /** A valid access token, refreshed when it is close to expiry. */
  suspend fun accessToken(
      context: Context,
      forceRefresh: Boolean = false,
      log: (String) -> Unit = {},
  ): String =
      refreshMutex.withLock {
        val appContext = context.applicationContext
        val current = load(appContext) ?: throw AuthException("ChatGPT hesabına giriş yapın")
        if (!forceRefresh && System.currentTimeMillis() < current.expiresAtMs) {
          return@withLock current.access
        }
        val token =
            try {
              retryWhileOffline(log) {
                postForm(
                    mapOf(
                        "grant_type" to "refresh_token",
                        "client_id" to current.clientId,
                        "refresh_token" to current.refresh,
                        "resource" to RESOURCE,
                    ),
                )
              }
            } catch (e: TokenEndpointException) {
              // 400/401 here means the refresh token is dead; a network blip is not.
              if (e.status == 400 || e.status == 401) {
                signOut(appContext)
                throw AuthException("ChatGPT oturumu sona erdi, yeniden giriş yapın")
              }
              throw e
            }
        val refreshed = credentialsFrom(token, current.clientId)
        save(appContext, refreshed)
        refreshed.access
      }

  /**
   * Repeats a request that could not reach the server at all. A reply from the server, even an
   * error, ends the attempts: the authorization code is single-use.
   */
  private suspend fun <T> retryWhileOffline(log: (String) -> Unit, block: suspend () -> T): T {
    val deadline = System.currentTimeMillis() + OFFLINE_RETRY_MS
    var pause = 500L
    var announced = false
    while (true) {
      try {
        return block()
      } catch (e: TokenEndpointException) {
        throw e
      } catch (e: IOException) {
        if (System.currentTimeMillis() > deadline) throw e
        if (!announced) {
          announced = true
          log("Uygulama ağa ulaşamıyor (${e.javaClass.simpleName}), yeniden deneniyor")
        }
        delay(pause)
        pause = (pause * 2).coerceAtMost(3000L)
      }
    }
  }

  // ---- callback listener ----

  private class Callback(val code: String, val clientId: String)

  private fun openCallbackServer(): ServerSocket {
    val loopback = InetAddress.getByName("127.0.0.1")
    return try {
      ServerSocket(PREFERRED_PORT, 8, loopback)
    } catch (e: IOException) {
      // Port taken by something else on the phone: any free loopback port is allowed too.
      ServerSocket(0, 8, loopback)
    }
  }

  private suspend fun awaitCallback(server: ServerSocket, expectedState: String): Callback {
    val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
    server.soTimeout = 1000
    while (System.currentTimeMillis() < deadline) {
      kotlin.coroutines.coroutineContext.ensureActive()
      val socket =
          try {
            server.accept()
          } catch (e: SocketTimeoutException) {
            continue
          }
      socket.use { client ->
        client.soTimeout = 5000
        val requestLine =
            runCatching { client.getInputStream().bufferedReader().readLine() }.getOrNull()
        // "GET /auth/callback?code=...&state=... HTTP/1.1"
        val target = requestLine?.split(" ")?.getOrNull(1)
        val uri = target?.let { Uri.parse("http://127.0.0.1$it") }
        if (uri == null || uri.path != CALLBACK_PATH) {
          respond(client.getOutputStream(), 404, "Not found", "")
          return@use
        }
        val error = uri.getQueryParameter("error")
        if (error != null) {
          respond(client.getOutputStream(), 400, "ChatGPT bağlanamadı", "Hata: $error")
          throw AuthException("ChatGPT giriş isteğini reddetti: $error")
        }
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val clientId = uri.getQueryParameter("client_id")?.trim()
        if (code.isNullOrEmpty() || state != expectedState) {
          // A stale tab or a stray request: answer it and keep waiting for the real one.
          respond(client.getOutputStream(), 400, "Beklenmeyen yanıt", "Girişi uygulamadan yeniden başlatın.")
          return@use
        }
        if (clientId.isNullOrEmpty()) {
          respond(client.getOutputStream(), 400, "ChatGPT bağlanamadı", "Yanıtta istemci kimliği yok.")
          throw AuthException("ChatGPT yanıtında istemci kimliği bulunamadı")
        }
        respond(
            client.getOutputStream(),
            200,
            "ChatGPT bağlandı",
            "Artık Tupo Vision TR uygulamasına dönebilirsiniz.",
        )
        return Callback(code, clientId)
      }
    }
    throw AuthException("Giriş işlemi 5 dakika içinde tamamlanmadı")
  }

  private fun respond(out: java.io.OutputStream, status: Int, title: String, text: String) {
    val html =
        "<!doctype html><html lang=\"tr\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>Tupo Vision TR</title></head>" +
            "<body style=\"font-family:sans-serif;text-align:center;padding:22vh 24px 0;" +
            "background:#101114;color:#f2f2f2\"><h1>$title</h1><p>$text</p></body></html>"
    val body = html.toByteArray(Charsets.UTF_8)
    val reason = if (status == 200) "OK" else "Error"
    val head =
        "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
    runCatching {
      out.write(head.toByteArray(Charsets.US_ASCII))
      out.write(body)
      out.flush()
    }
  }

  // ---- token endpoint ----

  private class TokenEndpointException(val status: Int, message: String) : IOException(message)

  private suspend fun postForm(fields: Map<String, String>): JSONObject =
      withContext(Dispatchers.IO) {
        val connection = URL(TOKEN_URL).openConnection() as HttpURLConnection
        try {
          connection.requestMethod = "POST"
          connection.doOutput = true
          connection.connectTimeout = 15000
          connection.readTimeout = 30000
          connection.setRequestProperty("Accept", "application/json")
          connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
          val body =
              fields.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
              }
          connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
          val status = connection.responseCode
          if (status !in 200..299) {
            val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw TokenEndpointException(status, "Sign-in error ($status): ${text.take(300)}")
          }
          JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
          connection.disconnect()
        }
      }

  private fun credentialsFrom(token: JSONObject, clientId: String): Credentials {
    val access = token.optString("access_token")
    val refresh = token.optString("refresh_token")
    val expiresIn = token.optLong("expires_in", 0)
    val scopes = token.optString("scope").split(" ").filter { it.isNotBlank() }
    if (access.isEmpty() || refresh.isEmpty() || expiresIn <= 0) {
      throw AuthException("ChatGPT eksik oturum yanıtı döndürdü")
    }
    if (PLAN_SCOPE !in scopes) {
      throw AuthException(
          "ChatGPT abonelik kullanım izni vermedi. Go, Plus veya Pro planı ve giriş ekranında onayınız gerekir.",
      )
    }
    return Credentials(
        clientId = clientId,
        access = access,
        refresh = refresh,
        expiresAtMs = System.currentTimeMillis() + expiresIn * 1000 - EXPIRY_MARGIN_MS,
    )
  }

  // ---- storage ----

  private fun load(context: Context): Credentials? {
    cached?.let {
      return it
    }
    val stored = SecureStore.get(context, STORE_KEY) ?: return null
    return try {
      val json = JSONObject(stored)
      Credentials(
              clientId = json.getString("client_id"),
              access = json.getString("access"),
              refresh = json.getString("refresh"),
              expiresAtMs = json.getLong("expires_at"),
          )
          .also { cached = it }
    } catch (e: Exception) {
      null
    }
  }

  private fun save(context: Context, credentials: Credentials) {
    cached = credentials
    SecureStore.put(
        context,
        STORE_KEY,
        JSONObject()
            .put("client_id", credentials.clientId)
            .put("access", credentials.access)
            .put("refresh", credentials.refresh)
            .put("expires_at", credentials.expiresAtMs)
            .toString(),
    )
  }

  /** OpenAI identifies each installation by a stable, opaque id that is not a credential. */
  private fun hostId(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val existing = prefs.getString(PREF_HOST_ID, null)
    if (existing != null) return existing
    val created = "urn:uuid:" + UUID.randomUUID().toString().lowercase()
    prefs.edit().putString(PREF_HOST_ID, created).apply()
    return created
  }

  private fun randomUrlSafe(): String = base64Url(ByteArray(32).also { SecureRandom().nextBytes(it) })

  private fun base64Url(bytes: ByteArray): String =
      Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
