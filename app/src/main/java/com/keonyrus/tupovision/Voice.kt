package com.keonyrus.tupovision

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

enum class AudioProfile(val label: String) {
  HD_A2DP("HD Ses (A2DP + Telefon Mikrofonu)"),
  GLASSES_SCO("Gozluk Mikrofonu (SCO Arama Modu)"),
}

data class AudioRouteResult(
    val profile: AudioProfile,
    val callAudio: Boolean,
    val onHeadset: Boolean,
    val fallbackToPhoneMic: Boolean = false,
)

/**
 * Speech recognition for one utterance at a time. All calls and callbacks are on the main thread,
 * which is what SpeechRecognizer requires.
 */
class Stt(private val context: Context, private val callbacks: Callbacks) {
  interface Callbacks {
    fun onSpeechStart()

    fun onPartial(text: String)

    fun onFinal(text: String)

    fun onError(code: Int)
  }

  private var recognizer: SpeechRecognizer? = null
  // False once the current utterance has been delivered or cancelled, so late callbacks are dropped.
  private var active = false

  fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

  fun start(languageTag: String) {
    val current =
        recognizer
            ?: SpeechRecognizer.createSpeechRecognizer(context).also {
              it.setRecognitionListener(listener)
              recognizer = it
            }
    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
          putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
          // A hint only; recognizers are free to ignore it.
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 550L)
        }
    active = true
    current.startListening(intent)
  }

  fun cancel() {
    active = false
    recognizer?.cancel()
  }

  fun destroy() {
    active = false
    recognizer?.destroy()
    recognizer = null
  }

  private fun firstResult(bundle: Bundle?): String =
      bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

  private val listener =
      object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {
          if (active) callbacks.onSpeechStart()
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
          if (!active) return
          active = false
          callbacks.onError(error)
        }

        override fun onResults(results: Bundle?) {
          if (!active) return
          active = false
          callbacks.onFinal(firstResult(results))
        }

        override fun onPartialResults(partialResults: Bundle?) {
          if (active) callbacks.onPartial(firstResult(partialResults))
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
      }
}

/** The phone's text-to-speech engine, fed sentence by sentence as the answer streams in. */
class Tts(context: Context, private val onReady: (Boolean) -> Unit) {
  @Volatile
  var ready = false
    private set

  private val counter = AtomicInteger(0)
  private val pendingUtterances = AtomicInteger(0)
  private val activeUtterances = ConcurrentHashMap.newKeySet<String>()
  private val engine: TextToSpeech

  private val utteranceListener =
      object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
          finishUtterance(utteranceId)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
          finishUtterance(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
          finishUtterance(utteranceId)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
          finishUtterance(utteranceId)
        }
      }

  init {
    engine =
        TextToSpeech(context.applicationContext) { status ->
          ready = status == TextToSpeech.SUCCESS
          if (ready) {
            engine.setOnUtteranceProgressListener(utteranceListener)
          }
          onReady(ready)
        }
  }

  private fun finishUtterance(utteranceId: String?) {
    if (utteranceId == null || activeUtterances.remove(utteranceId)) {
      pendingUtterances.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }
  }

  /** Returns false when the engine has no voice for this language. */
  fun configure(languageTag: String, throughCallAudio: Boolean): Boolean {
    val usage =
        if (throughCallAudio) AudioAttributes.USAGE_VOICE_COMMUNICATION
        else AudioAttributes.USAGE_ASSISTANT
    engine.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
    )
    engine.setSpeechRate(1.1f)
    val result = engine.setLanguage(Locale.forLanguageTag(languageTag))
    return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
  }

  fun speak(text: String) {
    val id = "utterance-${counter.incrementAndGet()}"
    activeUtterances.add(id)
    pendingUtterances.incrementAndGet()
    val status = engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    if (status != TextToSpeech.SUCCESS) {
      finishUtterance(id)
    }
  }

  val isSpeaking: Boolean
    get() = engine.isSpeaking

  val isBusy: Boolean
    get() = pendingUtterances.get() > 0 || engine.isSpeaking

  suspend fun awaitIdle(timeoutMs: Long = 30_000L) {
    withTimeoutOrNull(timeoutMs) {
      while (isBusy) delay(25)
    }
    // Kisa akustik sonumleme payi; mikrofon acilmadan son hecenin sismesini engeller.
    delay(100L)
  }

  fun stop() {
    activeUtterances.clear()
    pendingUtterances.set(0)
    engine.stop()
  }

  fun shutdown() {
    activeUtterances.clear()
    pendingUtterances.set(0)
    engine.stop()
    engine.shutdown()
  }
}

/**
 * Coordinates Bluetooth audio routing: HD_A2DP keeps MODE_NORMAL for 48 kHz media playback and
 * phone microphone capture, while GLASSES_SCO routes call-style audio over Bluetooth HFP/SCO.
 */
object AudioRouter {
  private const val ROUTE_TIMEOUT_MS = 3000L
  private const val KEEP_ALIVE_RATE = 16000
  @Volatile private var routed = false
  private var previousMode = AudioManager.MODE_NORMAL
  // Identity of the running keep-alive loop; a loop stops when it is no longer the current one.
  @Volatile private var keepAliveToken: Any? = null

  @Volatile private var onHeadset = false
  @Volatile private var scoActiveOrAttempted = false
  @Volatile private var lastScoDisabledAtMs = 0L

  val isRouted: Boolean
    get() = routed

  suspend fun configureForProfile(context: Context, profile: AudioProfile): AudioRouteResult {
    return when (profile) {
      AudioProfile.HD_A2DP -> {
        ensureNormalMode(context)
        AudioRouteResult(
            profile = AudioProfile.HD_A2DP,
            callAudio = false,
            onHeadset = false,
            fallbackToPhoneMic = false,
        )
      }
      AudioProfile.GLASSES_SCO -> {
        if (enable(context)) {
          AudioRouteResult(
              profile = AudioProfile.GLASSES_SCO,
              callAudio = true,
              onHeadset = true,
              fallbackToPhoneMic = false,
          )
        } else {
          // SCO baglantisi kurulamadiginda hoparlor+MODE_IN_COMMUNICATION yerine HD_A2DP'ye dus.
          ensureNormalMode(context)
          AudioRouteResult(
              profile = AudioProfile.HD_A2DP,
              callAudio = false,
              onHeadset = false,
              fallbackToPhoneMic = true,
          )
        }
      }
    }
  }

  /**
   * Call-style audio for a live two-way conversation. Uses the glasses when [preferHeadset] is set
   * and they are connected; when [preferHeadset] is false or SCO fails, stays in MODE_NORMAL
   * without call-audio keep-alive so A2DP quality is preserved.
   */
  suspend fun enableCall(context: Context, preferHeadset: Boolean): Boolean {
    val profile = if (preferHeadset) AudioProfile.GLASSES_SCO else AudioProfile.HD_A2DP
    return configureForProfile(context, profile).onHeadset
  }

  fun preferredInputDevice(context: Context, profile: AudioProfile): AudioDeviceInfo? {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return runCatching {
          val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
          when (profile) {
            AudioProfile.GLASSES_SCO ->
                inputs.firstOrNull {
                  it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                      it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            AudioProfile.HD_A2DP ->
                inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
          }
        }
        .getOrNull()
  }

  /** The glasses microphone as a recording device, or null when audio is on the phone. */
  fun inputDevice(context: Context): AudioDeviceInfo? {
    if (!onHeadset) return null
    return preferredInputDevice(context, AudioProfile.GLASSES_SCO)
  }

  fun recentScoTeardownRemainingMs(cooldownMs: Long = 350L): Long {
    if (cooldownMs <= 0L) return 0L
    val disabledAt = lastScoDisabledAtMs
    if (disabledAt <= 0L) return 0L
    val elapsed = SystemClock.elapsedRealtime() - disabledAt
    if (elapsed < 0L) return 0L
    return (cooldownMs - elapsed).coerceAtLeast(0L)
  }

  private fun ensureNormalMode(context: Context) {
    keepAliveToken = null
    if (routed) {
      disable(context)
    }
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    if (audio.mode != AudioManager.MODE_NORMAL) {
      runCatching { audio.clearCommunicationDevice() }
      runCatching { audio.mode = AudioManager.MODE_NORMAL }
    }
    routed = false
    onHeadset = false
  }

  /** Returns true when microphone and speaker are now on a Bluetooth headset. */
  suspend fun enable(context: Context): Boolean {
    if (routed) return true
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val headset =
        runCatching {
              audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
              }
            }
            .getOrNull() ?: return false
    previousMode = audio.mode
    routed = true
    scoActiveOrAttempted = true
    lastScoDisabledAtMs = 0L
    val accepted =
        runCatching {
              audio.mode = AudioManager.MODE_IN_COMMUNICATION
              audio.setCommunicationDevice(headset)
            }
            .getOrDefault(false)
    startKeepAlive()
    val connected =
        accepted &&
            withTimeoutOrNull(ROUTE_TIMEOUT_MS) {
              while (audio.communicationDevice?.type != headset.type) delay(100)
              true
            } == true
    if (!connected) disable(context)
    onHeadset = connected
    return connected
  }

  fun disable(context: Context) {
    if (!routed) return
    val wasSco = onHeadset || scoActiveOrAttempted
    keepAliveToken = null
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    runCatching { audio.clearCommunicationDevice() }
    runCatching { audio.mode = previousMode }
    routed = false
    onHeadset = false
    scoActiveOrAttempted = false
    if (wasSco) {
      lastScoDisabledAtMs = SystemClock.elapsedRealtime()
    }
  }

  /**
   * Android drops an app's call-audio route a few seconds after the app stops playing or recording
   * call audio itself. Recognition and speech run in system services, not in this process, so a
   * silent call-audio track keeps the route to the glasses open between sentences.
   */
  private fun startKeepAlive() {
    if (keepAliveToken != null) return
    val minSize =
        AudioTrack.getMinBufferSize(
            KEEP_ALIVE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
    if (minSize <= 0) return
    val track =
        try {
          AudioTrack.Builder()
              .setAudioAttributes(
                  AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                      .build(),
              )
              .setAudioFormat(
                  AudioFormat.Builder()
                      .setSampleRate(KEEP_ALIVE_RATE)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                      .build(),
              )
              .setBufferSizeInBytes(minSize * 2)
              .setTransferMode(AudioTrack.MODE_STREAM)
              .build()
        } catch (e: Exception) {
          return
        }
    if (track.state != AudioTrack.STATE_INITIALIZED) {
      track.release()
      return
    }
    val token = Any()
    keepAliveToken = token
    Thread(
            {
              // 20 ms of silence per write; the blocking write paces the loop.
              val silence = ByteArray(KEEP_ALIVE_RATE / 50 * 2)
              try {
                track.play()
                while (keepAliveToken === token) {
                  if (track.write(silence, 0, silence.size) < 0) break
                }
              } catch (e: Exception) {
                // The track died with the route; the next enable() starts a new one.
              } finally {
                if (keepAliveToken === token) keepAliveToken = null
                runCatching { track.stop() }
                track.release()
              }
            },
            "call-audio-keepalive",
        )
        .apply { isDaemon = true }
        .start()
  }
}

/** Cuts a stream of text into sentences so speech can start before the answer is complete. */
class SentenceChunker {
  private val buffer = StringBuilder()
  private var firstEmitted = false

  fun push(delta: String): List<String> {
    buffer.append(delta)
    val sentences = ArrayList<String>()
    while (true) {
      val minLength = if (!firstEmitted) MIN_FIRST_SENTENCE else MIN_SENTENCE
      val cut = boundary(minLength)
      if (cut < 0) break
      val sentence = buffer.substring(0, cut).trim()
      buffer.delete(0, cut)
      if (sentence.isNotEmpty()) {
        sentences.add(sentence)
        firstEmitted = true
      }
    }
    return sentences
  }

  fun flush(): String? {
    val rest = buffer.toString().trim()
    buffer.setLength(0)
    return rest.ifEmpty { null }
  }

  /** Index just past the first sentence end, or -1. "3.5" and very short fragments do not split. */
  private fun boundary(minLength: Int): Int {
    for (i in buffer.indices) {
      val ch = buffer[i]
      val end =
          ch == '\n' ||
              ((ch == '.' || ch == '!' || ch == '?' || ch == '…') &&
                  i + 1 < buffer.length &&
                  buffer[i + 1].isWhitespace())
      if (end && i + 1 >= minLength) return i + 1
    }
    return -1
  }

  private companion object {
    const val MIN_FIRST_SENTENCE = 4
    const val MIN_SENTENCE = 10
  }
}
