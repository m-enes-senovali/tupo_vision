package com.keonyrus.tupovision

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

/**
 * One connection to the Gemini Live API: microphone audio and camera frames go up, the model's
 * voice comes down, all over a single WebSocket. The model hears and speaks by itself, so there is
 * no separate speech recognition or synthesis and the user can interrupt it by talking.
 *
 * Wire format follows https://ai.google.dev/api/live. A connection lives for about ten minutes;
 * the owner reconnects with the resumption handle to keep the conversation.
 *
 * Callbacks arrive on background threads.
 */
class GeminiLive(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val instructions: String,
    private val resumeHandle: String?,
    /** False sends only the fields every Live model accepts, as a fallback when setup is refused. */
    private val fullSetup: Boolean,
    private val enableGoogleSearch: Boolean = true,
    private val enableFunctionTools: Boolean = true,
    /** When false the microphone is silenced while the model speaks, for setups with echo. */
    private val bargeIn: Boolean,
    private val inputDevice: AudioDeviceInfo?,
    private val callAudio: Boolean,
    private val frameSource: () -> ByteArray?,
    private val events: Events,
    private val setupOptions: GeminiSetupOptions = GeminiSetupOptions(),
    private val echoTailGuardMs: Long = 350L,
    private val endpointUrl: String = URL,
    private val enableAudioLoops: Boolean = true,
) {
  interface Events {
    fun onReady()

    fun onUserText(delta: String)

    fun onModelText(delta: String)

    fun onModelSpeaking(speaking: Boolean)

    fun onTurnComplete()

    fun onResumeHandle(handle: String)

    fun onGoAway()

    fun onToolCall(callId: String, name: String, argsJson: String)

    fun onMicError(reason: String)

    fun onFrameSent(jpeg: ByteArray)

    fun onClosed(reason: String, wasReady: Boolean)

    fun onLog(message: String)
  }

  companion object {
    const val DEFAULT_MODEL = "gemini-3.8-live"
    const val MODEL = DEFAULT_MODEL
    private const val URL =
        "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    private const val MIC_RATE = 16000
    private const val VOICE_RATE = 24000
    // 40 ms of 16-bit mono audio per message.
    private const val MIC_CHUNK_BYTES = MIC_RATE / 25 * 2
    // The API takes at most one video frame per second.
    private const val FRAME_INTERVAL_MS = 1000L
    private const val PREVIEW_EVERY = 5
    private const val MAX_WS_QUEUE_BYTES = 1_500_000L

    private val client: OkHttpClient by lazy {
      OkHttpClient.Builder()
          .connectTimeout(15, TimeUnit.SECONDS)
          .readTimeout(0, TimeUnit.MILLISECONDS)
          .pingInterval(20, TimeUnit.SECONDS)
          .build()
    }
  }

  @Volatile private var socket: WebSocket? = null
  @Volatile private var ready = false
  @Volatile private var stopped = false
  @Volatile private var closedReported = false

  private val voiceQueue = LinkedBlockingQueue<ByteArray>()
  @Volatile private var track: AudioTrack? = null
  @Volatile private var speaking = false
  @Volatile private var lastSpeakingEndedAtMs = 0L
  // Bumped on every interruption so the player drops the chunk it is in the middle of.
  private val playbackEpoch = AtomicInteger(0)

  private val threads = CopyOnWriteArrayList<Thread>()

  fun start() {
    val sep = if ('?' in endpointUrl) '&' else '?'
    val request = Request.Builder().url("$endpointUrl${sep}key=$apiKey").build()
    socket = client.newWebSocket(request, listener)
    if (enableAudioLoops) {
      threads += thread("gemini-mic") { micLoop() }
      threads += thread("gemini-voice") { voiceLoop() }
      threads += thread("gemini-video") { videoLoop() }
    }
  }

  /** Safe to call from any thread, more than once. No [Events.onClosed] follows a stop. */
  fun stop() {
    if (stopped) return
    stopped = true
    closedReported = true
    ready = false
    runCatching { socket?.close(1000, "bye") }
    voiceQueue.clear()
    runCatching { track?.pause() }
    threads.forEach { it.interrupt() }
  }

  private fun thread(name: String, body: () -> Unit): Thread =
      Thread(
              {
                try {
                  body()
                } catch (e: InterruptedException) {
                  // stop() was called
                } catch (e: Exception) {
                  events.onLog("$name: ${e.javaClass.simpleName}: ${e.message}")
                }
              },
              name,
          )
          .apply {
            isDaemon = true
            start()
          }

  // ---- socket ----

  private val listener =
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          webSocket.send(setupMessage())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
          handle(text)
        }

        // The server sends its JSON in binary frames.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
          handle(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
          webSocket.close(1000, null)
          reportClosed("code $code" + if (reason.isNotEmpty()) ": $reason" else "")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
          reportClosed("code $code" + if (reason.isNotEmpty()) ": $reason" else "")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          val http = response?.let { "HTTP ${it.code} " }.orEmpty()
          reportClosed(http + (t.message ?: t.javaClass.simpleName))
        }
      }

  private fun reportClosed(reason: String) {
    if (closedReported) return
    closedReported = true
    val wasReady = ready
    ready = false
    stopped = true
    voiceQueue.clear()
    threads.forEach { it.interrupt() }
    events.onClosed(reason, wasReady)
  }

  fun isSpeaking(): Boolean = speaking

  fun sendHighResFrame(jpeg: ByteArray): Boolean {
    val activeSocket = socket ?: return false
    if (!ready || stopped) return false
    val payload = Base64.encodeToString(jpeg, Base64.NO_WRAP)
    val ok = activeSocket.send("{\"realtimeInput\":{\"video\":{\"mimeType\":\"image/jpeg\",\"data\":\"$payload\"}}}")
    if (ok) events.onFrameSent(jpeg)
    return ok
  }

  fun sendToolResponse(callId: String, name: String, response: JSONObject): Boolean {
    val activeSocket = socket ?: return false
    if (!ready || stopped) return false
    val fnResponse = JSONObject().put("name", name).put("response", response)
    if (callId.isNotEmpty()) fnResponse.put("id", callId)
    val payload =
        JSONObject()
            .put(
                "toolResponse",
                JSONObject().put("functionResponses", JSONArray().put(fnResponse)),
            )
            .toString()
    return activeSocket.send(payload)
  }

  private fun setupMessage(): String =
      AssistantHelpers.buildGeminiSetupMessage(
          model = model,
          instructions = instructions,
          resumeHandle = resumeHandle,
          fullSetup = fullSetup,
          enableGoogleSearch = enableGoogleSearch,
          enableFunctionTools = enableFunctionTools,
          options = setupOptions,
      )

  private fun handle(text: String) {
    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
    if (message.has("setupComplete")) {
      ready = true
      events.onReady()
      return
    }
    val toolCall = message.optJSONObject("toolCall")
    if (toolCall != null) {
      val calls = toolCall.optJSONArray("functionCalls")
      if (calls != null) {
        for (i in 0 until calls.length()) {
          val call = calls.optJSONObject(i) ?: continue
          val id = call.optString("id")
          val name = call.optString("name")
          val argsJson = call.optJSONObject("args")?.toString() ?: "{}"
          if (name.isNotEmpty()) events.onToolCall(id, name, argsJson)
        }
      }
    }
    val content = message.optJSONObject("serverContent")
    if (content != null) {
      if (content.optBoolean("interrupted")) interruptPlayback()
      val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
      if (parts != null) {
        for (i in 0 until parts.length()) {
          val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
          if (!data.isNullOrEmpty()) voiceQueue.offer(Base64.decode(data, Base64.DEFAULT))
        }
      }
      val heard = content.optJSONObject("inputTranscription")?.optString("text")
      if (!heard.isNullOrEmpty()) events.onUserText(heard)
      val said = content.optJSONObject("outputTranscription")?.optString("text")
      if (!said.isNullOrEmpty()) events.onModelText(said)
      if (content.optBoolean("turnComplete")) events.onTurnComplete()
    }
    val resumption = message.optJSONObject("sessionResumptionUpdate")
    if (resumption != null) {
      val handle = resumption.optString("newHandle")
      if (resumption.optBoolean("resumable") && handle.isNotEmpty()) events.onResumeHandle(handle)
    }
    if (message.has("goAway")) events.onGoAway()
  }

  // ---- microphone up ----

  @SuppressLint("MissingPermission")
  private fun micLoop() {
    val minSize =
        AudioRecord.getMinBufferSize(
            MIC_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
    if (minSize <= 0) {
      if (!stopped) events.onMicError("Mikrofon 16 kHz desteklemiyor")
      return
    }
    // Use VOICE_COMMUNICATION only during SCO call routing; HD A2DP keeps VOICE_RECOGNITION
    // so MIUI audio HAL does not drop into narrowband VoIP/SCO mode.
    val source =
        if (callAudio) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
    val record =
        AudioRecord(
            source,
            MIC_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minSize, MIC_CHUNK_BYTES * 4),
        )
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      record.release()
      if (!stopped) events.onMicError("Mikrofon başlatılamadı")
      return
    }
    var echoCanceler: AcousticEchoCanceler? = null
    var noiseSuppressor: NoiseSuppressor? = null
    try {
      if (inputDevice != null) record.setPreferredDevice(inputDevice)
      runCatching {
        if (AcousticEchoCanceler.isAvailable()) {
          echoCanceler =
              AcousticEchoCanceler.create(record.audioSessionId)?.also { it.setEnabled(true) }
        }
      }
      runCatching {
        if (NoiseSuppressor.isAvailable()) {
          noiseSuppressor =
              NoiseSuppressor.create(record.audioSessionId)?.also { it.setEnabled(true) }
        }
      }
      record.startRecording()
      val routedTo = record.routedDevice?.productName ?: "bilinmiyor"
      events.onLog("Mikrofon yönlendirildi: $routedTo")

      val chunk = ByteArray(MIC_CHUNK_BYTES)
      val silence = ByteArray(MIC_CHUNK_BYTES)
      val head = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=$MIC_RATE\",\"data\":\""
      while (!stopped) {
        val read = record.read(chunk, 0, chunk.size)
        if (read < 0) {
          if (!stopped) events.onMicError("Mikrofon okuma hatası ($read)")
          break
        }
        if (read == 0 || !ready) continue
        val inEchoTail =
            !bargeIn &&
                !callAudio &&
                lastSpeakingEndedAtMs > 0L &&
                (SystemClock.elapsedRealtime() - lastSpeakingEndedAtMs < echoTailGuardMs)
        val muted = !bargeIn && (speaking || inEchoTail)
        val payload = Base64.encodeToString(if (muted) silence else chunk, 0, read, Base64.NO_WRAP)
        socket?.send("$head$payload\"}}}")
      }
    } finally {
      runCatching { record.stop() }
      runCatching { echoCanceler?.release() }
      runCatching { noiseSuppressor?.release() }
      record.release()
    }
  }

  // ---- model voice down ----

  private fun voiceLoop() {
    val minSize =
        AudioTrack.getMinBufferSize(
            VOICE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
    if (minSize <= 0) {
      events.onLog("The speaker does not support 24 kHz")
      return
    }
    val usage =
        if (callAudio) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT
    val player =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(VOICE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            // About 200 ms: small enough that an interruption cuts the voice off quickly.
            .setBufferSizeInBytes(maxOf(minSize, VOICE_RATE * 2 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    if (player.state != AudioTrack.STATE_INITIALIZED) {
      player.release()
      events.onLog("Could not open the speaker")
      return
    }
    track = player
    try {
      player.play()
      while (!stopped) {
        val chunk = voiceQueue.poll(250, TimeUnit.MILLISECONDS)
        if (chunk == null) {
          if (speaking) {
            speaking = false
            lastSpeakingEndedAtMs = SystemClock.elapsedRealtime()
            events.onModelSpeaking(false)
          }
          continue
        }
        if (!speaking) {
          speaking = true
          events.onModelSpeaking(true)
        }
        val epoch = playbackEpoch.get()
        var offset = 0
        while (offset < chunk.size && !stopped && epoch == playbackEpoch.get()) {
          val written = player.write(chunk, offset, chunk.size - offset)
          if (written <= 0) break
          offset += written
        }
      }
    } finally {
      track = null
      runCatching { player.stop() }
      player.release()
    }
  }

  /** The user started talking over the model: drop everything not yet played. */
  private fun interruptPlayback() {
    playbackEpoch.incrementAndGet()
    voiceQueue.clear()
    if (speaking) {
      speaking = false
      lastSpeakingEndedAtMs = SystemClock.elapsedRealtime()
      events.onModelSpeaking(false)
    }
    val player = track ?: return
    runCatching {
      player.pause()
      player.flush()
      player.play()
    }
  }

  // ---- camera up ----

  private fun videoLoop() {
    val head = "{\"realtimeInput\":{\"video\":{\"mimeType\":\"image/jpeg\",\"data\":\""
    var lastFrame = -1L
    var sent = 0
    var misses = 0
    while (!stopped) {
      Thread.sleep(FRAME_INTERVAL_MS)
      if (!ready) continue
      // Nothing new from the glasses, nothing to send.
      val current = Frames.count
      if (current == lastFrame) {
        misses += 1
        if (misses == 8 && sent == 0) events.onLog("Gözlükten henüz video karesi gelmedi, model göremiyor")
        continue
      }
      val activeSocket = socket ?: continue
      if (activeSocket.queueSize() > MAX_WS_QUEUE_BYTES) continue
      val jpeg = frameSource()
      if (jpeg == null) {
        misses += 1
        if (misses == 5 && sent == 0) {
          events.onLog("Gelen kare biçimi okunamadı (${Frames.info}), model göremiyor")
        }
        continue
      }
      lastFrame = current
      activeSocket.send(head + Base64.encodeToString(jpeg, Base64.NO_WRAP) + "\"}}}")
      if (sent % PREVIEW_EVERY == 0) events.onFrameSent(jpeg)
      sent += 1
    }
  }
}
