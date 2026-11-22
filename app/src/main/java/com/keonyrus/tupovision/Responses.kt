package com.keonyrus.tupovision

import android.util.Base64
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ApiException(val status: Int, message: String) : IOException(message)

/** One finished exchange kept as text so follow-up questions have context. */
data class Turn(val question: String, val answer: String)

internal sealed interface SseEventResult {
  data class Delta(val text: String) : SseEventResult

  data object Completed : SseEventResult

  data class Error(val message: String) : SseEventResult

  data object Ignore : SseEventResult
}

/**
 * The OpenAI Responses API called with a ChatGPT plan token. Plan usage only accepts streamed,
 * unstored requests, takes text and images but no audio, and rejects sampling parameters.
 */
object Responses {
  private const val RESPONSES_URL = "https://api.openai.com/v1/responses"
  private const val MODELS_URL = "https://api.openai.com/v1/models"
  private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

  private val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .connectTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
  }

  fun buildBody(
      model: String,
      instructions: String,
      history: List<Turn>,
      question: String,
      jpeg: ByteArray?,
      reasoningEffort: String?,
      highDetailImage: Boolean = false,
      enableWebSearch: Boolean = false,
  ): JSONObject {
    val input = JSONArray()
    for (turn in history) {
      input.put(message("user", "input_text", turn.question))
      input.put(message("assistant", "output_text", turn.answer))
    }
    val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", question))
    if (jpeg != null) {
      val imageObj =
          JSONObject()
              .put("type", "input_image")
              .put(
                  "image_url",
                  "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP),
              )
      if (highDetailImage) {
        imageObj.put("detail", "high")
      }
      content.put(imageObj)
    }
    input.put(JSONObject().put("role", "user").put("content", content))

    val body =
        JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", input)
            .put("store", false)
            .put("stream", true)
    if (reasoningEffort != null) {
      body.put("reasoning", JSONObject().put("effort", reasoningEffort))
    }
    if (enableWebSearch) {
      body.put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
    }
    return body
  }

  private fun message(role: String, type: String, text: String): JSONObject =
      JSONObject()
          .put("role", role)
          .put("content", JSONArray().put(JSONObject().put("type", type).put("text", text)))

  internal fun parseSseEventLine(line: String): SseEventResult {
    if (!line.startsWith("data:")) return SseEventResult.Ignore
    val data = line.substring(5).trim()
    if (data.isEmpty() || data == "[DONE]") return SseEventResult.Ignore
    val event = runCatching { JSONObject(data) }.getOrNull() ?: return SseEventResult.Ignore
    return when (event.optString("type")) {
      "response.output_text.delta" -> {
        val delta = event.optString("delta")
        if (delta.isEmpty()) SseEventResult.Ignore else SseEventResult.Delta(delta)
      }
      "response.completed" -> SseEventResult.Completed
      "response.failed",
      "response.incomplete",
      "error" -> SseEventResult.Error(eventErrorMessage(event))
      else -> SseEventResult.Ignore
    }
  }

  /** Emits the answer text piece by piece and completes when the response is finished. */
  fun stream(token: String, body: JSONObject, url: String = RESPONSES_URL): Flow<String> = channelFlow {
    val callRef = AtomicReference<Call?>(null)
    launch(Dispatchers.IO) {
      try {
        val request =
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        val call = client.newCall(request)
        callRef.set(call)
        call.execute().use { response ->
          val responseBody = response.body
          if (!response.isSuccessful) {
            val text = responseBody?.string().orEmpty()
            throw ApiException(response.code, errorMessage(response.code, text))
          }
          val source = responseBody?.source() ?: throw ApiException(0, "Empty response body")
          readLoop@ while (true) {
            val line = source.readUtf8Line() ?: break
            when (val parsed = parseSseEventLine(line)) {
              is SseEventResult.Delta -> send(parsed.text)
              SseEventResult.Completed -> break@readLoop
              is SseEventResult.Error -> throw ApiException(0, parsed.message)
              SseEventResult.Ignore -> Unit
            }
          }
        }
        close()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        if (callRef.get()?.isCanceled() == true) {
          close()
        } else {
          close(e)
        }
      }
    }
    awaitClose {
      // OkHttp Call.cancel() is non-blocking and safe from any thread.
      callRef.get()?.cancel()
    }
  }

  /** Model ids this account may use with its plan; empty when the list cannot be read. */
  suspend fun listModels(token: String, url: String = MODELS_URL): List<String> =
      withContext(Dispatchers.IO) {
        val request =
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()
        client.newCall(request).execute().use { response ->
          val bodyText = response.body?.string().orEmpty()
          if (!response.isSuccessful) {
            throw ApiException(response.code, errorMessage(response.code, bodyText))
          }
          val root = JSONObject(bodyText)
          val items = root.optJSONArray("models") ?: root.optJSONArray("data") ?: JSONArray()
          val result = ArrayList<String>()
          for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val visibility = item.optString("visibility")
            if (visibility.isNotEmpty() && visibility != "list") continue
            val id = item.optString("slug").ifEmpty { item.optString("id") }
            if (id.isNotEmpty()) result.add(id)
          }
          result
        }
      }

  private fun errorMessage(status: Int, body: String): String {
    val parsed =
        runCatching {
              val error = JSONObject(body).optJSONObject("error")
              error?.optString("message").orEmpty()
            }
            .getOrDefault("")
    val detail = parsed.ifEmpty { body.take(200) }
    return "HTTP $status: $detail"
  }

  private fun eventErrorMessage(event: JSONObject): String {
    val error =
        event.optJSONObject("error") ?: event.optJSONObject("response")?.optJSONObject("error")
    val message = error?.optString("message").orEmpty()
    val incomplete =
        event.optJSONObject("response")?.optJSONObject("incomplete_details")?.optString("reason")
    return when {
      message.isNotEmpty() -> message
      !incomplete.isNullOrEmpty() -> "The answer was cut short: $incomplete"
      else -> "The request failed (${event.optString("type")})"
    }
  }
}
