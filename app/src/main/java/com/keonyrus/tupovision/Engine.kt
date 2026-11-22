package com.keonyrus.tupovision

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.inputs.Inputs
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.removeInputs
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val DEFAULT_MODEL = "gpt-6.1-sol"

/** Shown until the account's own list is read from the API. */
val FALLBACK_MODELS: List<String> = listOf("gpt-6.1-sol", "gpt-6-sol", "gpt-6-luna")

enum class Phase(val label: String) {
  IDLE("Durduruldu"),
  CONNECTING("Bağlanıyor"),
  LISTENING("Dinliyor"),
  THINKING("Düşünüyor"),
  SPEAKING("Konuşuyor"),
  PAUSED("Duraklatıldı"),
}

data class UiState(
    val metaInstalled: Boolean = false,
    val permissionsGranted: Boolean = false,
    val registration: String = "—",
    val registered: Boolean = false,
    val deviceCount: Int = 0,
    val hasActiveDevice: Boolean = false,
    val signedIn: Boolean = false,
    val loginInProgress: Boolean = false,
    val running: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val sessionState: String = "—",
    val streamState: String = "—",
    val frameInfo: String = "—",
    val partial: String = "",
    val question: String = "",
    val answer: String = "",
    val latencyMs: Long? = null,
    val frame: Bitmap? = null,
    val error: String? = null,
    val model: String = DEFAULT_MODEL,
    val models: List<String> = FALLBACK_MODELS,
    val geminiModel: String = GeminiLive.DEFAULT_MODEL,
    val webSearch: Boolean = true,
    val physicalInputs: Boolean = false,
    val glassesMic: Boolean = false,
    val audioProfile: AudioProfile = AudioProfile.HD_A2DP,
    val geminiVoice: GeminiVoice = GeminiVoice.KORE,
    val vadSensitivity: VadSensitivity = VadSensitivity.BALANCED,
    val chatSpeedProfile: ChatSpeedProfile = ChatSpeedProfile.FAST,
    val assistantProfile: AssistantProfile = AssistantProfile.QUICK,
    val qualityWarningEnabled: Boolean = false,
    val lastLocalScanSummary: String = "",
    val quietSessionAccessibility: Boolean = true,
    val photoMode: Boolean = false,
    val wakeWord: String = "",
    val language: String = "tr-TR",
    /** True: live voice through Gemini Live. False: ChatGPT plan with on-phone speech. */
    val liveMode: Boolean = false,
    val geminiKeySet: Boolean = false,
    val bargeIn: Boolean = true,
    /** A gesture on the glasses starts and stops the conversation. Not kept across restarts. */
    val standby: Boolean = false,
    val standbyAudio: String = "",
    /** Whether the user has granted the access needed to watch other apps' media sessions. */
    val mediaAccess: Boolean = false,
    val triggerPackage: String = MediaWatch.DEFAULT_TRIGGER,
    val installedMusicApps: List<Pair<String, String>> = emptyList(),
    val mockActive: Boolean = false,
    val mockWorn: Boolean = true,
    val mockStatus: String = "Kapalı",
    val log: List<String> = emptyList(),
)

/**
 * The whole assistant: glasses session and camera, the listen -> ask -> speak loop, and the state
 * the screen shows. It outlives the activity so the conversation continues with the screen off.
 * Everything here runs on the main thread unless a block says otherwise.
 */
object Engine {
  private const val TAG = "TupoVision"
  private const val PREFS = "settings"
  private const val META_AI_PACKAGE = "com.facebook.stella"
  private const val MAX_HISTORY = 6
  private const val MAX_LOG = 200
  private const val PHOTO_TIMEOUT_MS = 6000L
  private const val STREAM_WAIT_MS = 10_000L
  private const val QUALITY_WARNING_COOLDOWN_MS = 15_000L
  private const val GEMINI_KEY = "gemini_api_key"
  // Lighter than the per-question frame: one of these goes up every second.
  private const val LIVE_JPEG_QUALITY = 65

  val REQUIRED_PERMISSIONS: Array<String> =
      arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO)

  private lateinit var app: Application
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  private val logLines = ArrayDeque<String>()
  private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

  // Glasses
  private var wearablesReady = false
  private var wearablesObserving = false
  private val selector: DeviceSelector by lazy { AutoDeviceSelector() }
  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null
  private var inputsAttached: Inputs? = null
  private val sessionJobs = ArrayList<Job>()
  private var sessionStarted = false
  private var streaming = false
  private var serviceStarted = false

  // Voice
  private var stt: Stt? = null
  private var tts: Tts? = null
  private var voiceActive = false
  private var turnJob: Job? = null
  private var errorStreak = 0
  private val history = ArrayDeque<Turn>()
  private var lastSpokenAnswer: String = ""
  private var lastQualityWarningAtMs: Long = 0L
  @Volatile private var nextTurnForcePhoto = false

  // Live voice
  private var live: GeminiLive? = null
  private var liveHandle: String? = null
  // 0..5 progressive fallback ladder for Gemini Live setup options.
  private var liveAttempt = 0
  private var liveDrops = 0
  private var liveTurnDone = true
  private var activeAudioRoute: AudioRouteResult? = null
  @Volatile private var liveFirstConnect = true
  @Volatile private var pendingGoAway = false
  private var goAwayJob: Job? = null

  // ---- setup ----

  fun attach(application: Application) {
    app = application
    val prefs = prefs()
    val defaultTag = Locale.getDefault().toLanguageTag().ifBlank { "tr-TR" }
    val savedLang = prefs.getString("language", null)?.takeIf { it.isNotBlank() } ?: defaultTag
    val savedAudioProfile =
        prefs.getString("audio_profile", null)?.let { name ->
          AudioProfile.entries.firstOrNull { it.name == name }
        } ?: AudioProfile.HD_A2DP
    val savedGeminiVoice =
        prefs.getString("gemini_voice", null)?.let { name ->
          GeminiVoice.entries.firstOrNull { it.name == name }
        } ?: GeminiVoice.KORE
    val savedVadSensitivity =
        prefs.getString("vad_sensitivity", null)?.let { name ->
          VadSensitivity.entries.firstOrNull { it.name == name }
        } ?: VadSensitivity.BALANCED
    val savedChatSpeedProfile =
        prefs.getString("chat_speed_profile", null)?.let { name ->
          ChatSpeedProfile.entries.firstOrNull { it.name == name }
        } ?: ChatSpeedProfile.FAST
    val savedAssistantProfile =
        prefs.getString("assistant_profile", null)?.let { name ->
          AssistantProfile.entries.firstOrNull { it.name == name }
        } ?: AssistantProfile.QUICK
    update {
      it.copy(
          signedIn = ChatGptAuth.isSignedIn(app),
          model = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
          geminiModel = prefs.getString("gemini_model", GeminiLive.DEFAULT_MODEL) ?: GeminiLive.DEFAULT_MODEL,
          webSearch = prefs.getBoolean("web_search", true),
          physicalInputs = prefs.getBoolean("physical_inputs", false),
          audioProfile = savedAudioProfile,
          glassesMic = savedAudioProfile == AudioProfile.GLASSES_SCO,
          geminiVoice = savedGeminiVoice,
          vadSensitivity = savedVadSensitivity,
          chatSpeedProfile = savedChatSpeedProfile,
          assistantProfile = savedAssistantProfile,
          qualityWarningEnabled = prefs.getBoolean("quality_warning_enabled", false),
          quietSessionAccessibility = prefs.getBoolean("quiet_session_accessibility", true),
          photoMode = prefs.getBoolean("photo_mode", false),
          wakeWord = prefs.getString("wake_word", "") ?: "",
          language = savedLang,
          geminiKeySet = SecureStore.get(app, GEMINI_KEY) != null,
          liveMode = prefs.getBoolean("live_mode", false),
          bargeIn = prefs.getBoolean("barge_in", true),
          triggerPackage =
              prefs.getString("trigger_package", null) ?: MediaWatch.DEFAULT_TRIGGER,
      )
    }
    refreshEnvironment()
    if (_state.value.signedIn) refreshModels()
  }

  /** Re-reads what can change while the app is in the background: installs and permissions. */
  fun refreshEnvironment() {
    val metaInstalled =
        runCatching { app.packageManager.getPackageInfo(META_AI_PACKAGE, 0) }.isSuccess
    val granted =
        REQUIRED_PERMISSIONS.all {
          app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    val musicApps = MediaWatch.installedMusicApps(app)
    update {
      it.copy(
          metaInstalled = metaInstalled,
          permissionsGranted = granted,
          mediaAccess = MediaWatch.hasAccess(app),
          installedMusicApps = musicApps,
      )
    }
    if (granted) ensureWearables()
  }

  /** The SDK may only be touched after Bluetooth permission is granted. */
  private fun ensureWearables() {
    if (!wearablesReady) {
      wearablesReady = true
      Wearables.initialize(app).onFailure { error, _ ->
        log("Gözlük SDK başlatılamadı: ${error.description}")
      }
    }
    subscribeWearablesFlows()
  }

  private fun subscribeWearablesFlows() {
    if (wearablesObserving) return
    wearablesObserving = true
    scope.launch {
      Wearables.registrationState.collect { value ->
        update {
          it.copy(
              registration = value.toString(),
              registered = value == RegistrationState.REGISTERED,
          )
        }
      }
    }
    scope.launch {
      Wearables.devices.collect { devices -> update { it.copy(deviceCount = devices.size) } }
    }
    scope.launch {
      selector.activeDeviceFlow().collect { device ->
        update { it.copy(hasActiveDevice = device != null) }
      }
    }
  }

  fun register(activity: Activity) {
    if (!wearablesReady) {
      fail("Önce Bluetooth ve mikrofon izinlerini verin")
      return
    }
    Wearables.startRegistration(activity)
  }

  fun unregister(activity: Activity) {
    if (wearablesReady) Wearables.startUnregistration(activity)
  }

  // ---- ChatGPT ----

  private var loginJob: Job? = null

  /** A second call while a sign-in is pending drops it and starts a fresh one. */
  fun signIn(activity: Activity) {
    loginJob?.cancel()
    update { it.copy(loginInProgress = true, error = null) }
    val job =
        scope.launch(start = CoroutineStart.LAZY) {
          try {
            ChatGptAuth.login(
                context = app,
                openBrowser = { url -> openInBrowser(activity, url) },
                onCodeReceived = { bringToFront() },
                log = { message -> log(message) },
            )
            update { it.copy(signedIn = true) }
            log("ChatGPT bağlandı")
            bringToFront()
            refreshModels()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            val offline = e is java.net.UnknownHostException || e is java.net.ConnectException
            if (offline) {
              // Measured before returning to the screen, so it shows what the app saw.
              log("Uygulamanın gördüğü ağ durumu: " + NetDiag.report(app))
              fail(
                  "ChatGPT girişi: Tarayıcıda giriş yapıldı ancak uygulama auth.openai.com adresine ulaşamadı. Ayrıntılar günlükte.",
              )
            } else {
              fail("ChatGPT girişi başarısız: ${e.message}")
            }
            bringToFront()
          } finally {
            if (loginJob === coroutineContext[Job]) update { it.copy(loginInProgress = false) }
          }
        }
    loginJob = job
    job.start()
  }

  fun signOut() {
    if (_state.value.running) stop()
    ChatGptAuth.signOut(app)
    update { it.copy(signedIn = false) }
    log("ChatGPT oturumu kapatıldı. İsterseniz ChatGPT ayarlarından uygulama erişimini de kaldırabilirsiniz.")
  }

  private fun openInBrowser(activity: Activity, url: Uri) {
    try {
      CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, url)
    } catch (e: Exception) {
      activity.startActivity(Intent(Intent.ACTION_VIEW, url))
    }
  }

  /** Closes the sign-in tab by returning to the app's own screen. */
  private fun bringToFront() {
    runCatching {
      app.startActivity(
          Intent(app, MainActivity::class.java)
              .addFlags(
                  Intent.FLAG_ACTIVITY_NEW_TASK or
                      Intent.FLAG_ACTIVITY_CLEAR_TOP or
                      Intent.FLAG_ACTIVITY_SINGLE_TOP,
              ),
      )
    }
  }

  private fun refreshModels() {
    scope.launch {
      try {
        val token = ChatGptAuth.accessToken(app, log = ::log)
        val excluded =
            listOf("embed", "tts", "whisper", "dall-e", "moderation", "realtime", "transcribe", "audio")
        val ids =
            Responses.listModels(token).filter { id ->
              val lower = id.lowercase(Locale.US)
              (lower.startsWith("gpt") ||
                  lower.startsWith("o1") ||
                  lower.startsWith("o3") ||
                  lower.startsWith("o4") ||
                  lower.startsWith("chatgpt")) && excluded.none { it in lower }
            }
        if (ids.isNotEmpty()) {
          update { state ->
            state.copy(
                models = ids,
                model = if (state.model in ids) state.model else pickDefault(ids),
            )
          }
          log("Aboneliğinizde kullanılabilir model sayısı: ${ids.size}")
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("Model listesi yüklenemedi: ${e.message}")
      }
    }
  }

  private fun pickDefault(ids: List<String>): String =
      ids.firstOrNull { it == DEFAULT_MODEL } ?: ids.firstOrNull { "sol" in it } ?: ids.first()

  // ---- settings ----

  fun setModel(value: String) {
    update { it.copy(model = value) }
    prefs().edit().putString("model", value).apply()
  }

  fun setGeminiModel(value: String) {
    update { it.copy(geminiModel = value) }
    prefs().edit().putString("gemini_model", value).apply()
  }

  fun setWebSearch(value: Boolean) {
    update { it.copy(webSearch = value) }
    prefs().edit().putBoolean("web_search", value).apply()
  }

  fun setPhysicalInputs(value: Boolean) {
    update { it.copy(physicalInputs = value) }
    prefs().edit().putBoolean("physical_inputs", value).apply()
  }

  fun setAudioProfile(value: AudioProfile) {
    val useGlassesMic = value == AudioProfile.GLASSES_SCO
    update { it.copy(audioProfile = value, glassesMic = useGlassesMic) }
    prefs()
        .edit()
        .putString("audio_profile", value.name)
        .putBoolean("glasses_mic", useGlassesMic)
        .apply()
  }

  fun setGlassesMic(value: Boolean) {
    setAudioProfile(if (value) AudioProfile.GLASSES_SCO else AudioProfile.HD_A2DP)
  }

  fun setGeminiVoice(value: GeminiVoice) {
    update { it.copy(geminiVoice = value) }
    prefs().edit().putString("gemini_voice", value.name).apply()
  }

  fun setVadSensitivity(value: VadSensitivity) {
    update { it.copy(vadSensitivity = value) }
    prefs().edit().putString("vad_sensitivity", value.name).apply()
  }

  fun setChatSpeedProfile(value: ChatSpeedProfile) {
    update { it.copy(chatSpeedProfile = value) }
    prefs().edit().putString("chat_speed_profile", value.name).apply()
  }

  fun setAssistantProfile(value: AssistantProfile) {
    update { it.copy(assistantProfile = value) }
    prefs().edit().putString("assistant_profile", value.name).apply()
  }

  fun setQualityWarningEnabled(value: Boolean) {
    update { it.copy(qualityWarningEnabled = value) }
    prefs().edit().putBoolean("quality_warning_enabled", value).apply()
  }

  fun setQuietSessionAccessibility(value: Boolean) {
    update { it.copy(quietSessionAccessibility = value) }
    prefs().edit().putBoolean("quiet_session_accessibility", value).apply()
  }

  fun triggerLocalScanOnce() {
    val stateSnapshot = _state.value
    if (!stateSnapshot.running && Frames.latestJpeg() == null) {
      log("Yerel tarama için önce gözlüğü bağlayıp sohbeti başlatın")
      return
    }
    if (voiceActive && !stateSnapshot.liveMode) {
      stt?.cancel()
      turnJob?.cancel()
      turnJob =
          scope.launch {
            runLocalScanTurn("Yerel tarama")
            listen()
          }
      return
    }
    scope.launch {
      val startedAt = SystemClock.elapsedRealtime()
      val jpeg =
          withContext(Dispatchers.Default) { Frames.latestJpeg() }
              ?: withTimeoutOrNull(PHOTO_TIMEOUT_MS) { capturePhotoJpeg() }
      if (jpeg == null) {
        log("Yerel tarama: gözlükten kare alınamadı")
        return@launch
      }
      val scan =
          LocalVision.scanJpeg(
              jpeg,
              speechLanguage(),
              evaluateQuality = _state.value.qualityWarningEnabled,
          )
      maybeWarnFrameQuality(scan.quality)
      val summary = scan.spokenSummary ?: "Görüntüde okunabilir metin veya barkod bulunamadı."
      lastSpokenAnswer = summary
      update {
        it.copy(
            question = "Yerel tarama",
            answer = summary,
            lastLocalScanSummary = scan.spokenSummary.orEmpty(),
            latencyMs = SystemClock.elapsedRealtime() - startedAt,
        )
      }
      log("Yerel tarama tamamlandı (${scan.durationMs} ms)")
      if (!stateSnapshot.liveMode) {
        speakAndAwait(summary)
      }
    }
  }

  fun repeatLastAnswer() {
    val toRepeat = lastSpokenAnswer.ifBlank { _state.value.answer.trim() }
    val spoken = if (toRepeat.isNotEmpty()) toRepeat else "Henüz tekrarlanacak bir yanıt yok."
    val stateSnapshot = _state.value
    if (voiceActive && !stateSnapshot.liveMode) {
      stt?.cancel()
      turnJob?.cancel()
      turnJob =
          scope.launch {
            update { it.copy(phase = Phase.SPEAKING, answer = spoken) }
            speakAndAwait(spoken)
            listen()
          }
      return
    }
    scope.launch {
      if (toRepeat.isEmpty()) log(spoken)
      if (!stateSnapshot.liveMode || !stateSnapshot.running) {
        speakAndAwait(spoken)
      }
    }
  }

  fun setPhotoMode(value: Boolean) {
    update { it.copy(photoMode = value) }
    prefs().edit().putBoolean("photo_mode", value).apply()
  }

  fun setWakeWord(value: String) {
    update { it.copy(wakeWord = value) }
    prefs().edit().putString("wake_word", value).apply()
  }

  fun setLanguage(value: String) {
    update { it.copy(language = value) }
    prefs().edit().putString("language", value).apply()
  }

  fun setLiveMode(value: Boolean) {
    update { it.copy(liveMode = value) }
    prefs().edit().putBoolean("live_mode", value).apply()
  }

  fun setBargeIn(value: Boolean) {
    update { it.copy(bargeIn = value) }
    prefs().edit().putBoolean("barge_in", value).apply()
  }

  /** The key stays on this phone, encrypted; it is only ever sent to Google. */
  fun setGeminiKey(value: String) {
    val key = value.trim()
    if (key.isEmpty()) return
    SecureStore.put(app, GEMINI_KEY, key)
    update { it.copy(geminiKeySet = true) }
    setLiveMode(true)
    log("Gemini API anahtarı kaydedildi, canlı ses modu etkin")
  }

  fun clearGeminiKey() {
    if (_state.value.running && _state.value.liveMode) stop()
    SecureStore.put(app, GEMINI_KEY, null)
    update { it.copy(geminiKeySet = false) }
    setLiveMode(false)
    log("Gemini API anahtarı silindi")
  }

  fun setTriggerPackage(value: String) {
    update { it.copy(triggerPackage = value) }
    prefs().edit().putString("trigger_package", value).apply()
  }

  /**
   * Must be switched on from the screen: the background service has to start while the app is
   * visible to be allowed to use the microphone later, when a gesture starts the conversation.
   */
  fun setStandby(value: Boolean) {
    if (value == _state.value.standby) return
    if (!value) {
      MediaWatch.stop()
      update { it.copy(standby = false, standbyAudio = "") }
      if (!_state.value.running && serviceStarted) {
        KeepAliveService.stop(app)
        serviceStarted = false
      }
      log("Bekleme modu (Standby) kapatıldı")
      return
    }
    if (!wearablesReady) {
      fail("Önce Bluetooth ve mikrofon izinlerini verin")
      return
    }
    // Standby follows one audio device, so a car or other headphones cannot set it off.
    val glasses = MediaWatch.bluetoothOutputs(app).firstOrNull()
    if (glasses == null) {
      fail("Önce gözlüğü takın: Bekleme modu o an aktif olan Bluetooth ses cihazını takip eder")
      return
    }
    if (!MediaWatch.start(app, _state.value.triggerPackage.trim(), glasses)) {
      fail("Bekleme modu için Bildirim Erişimi gerekir. Anahtarın altındaki düğmeden izin verin.")
      return
    }
    try {
      if (!serviceStarted) KeepAliveService.start(app)
      serviceStarted = true
    } catch (e: Exception) {
      MediaWatch.stop()
      fail("Bekleme modu başlatılamadı: ${e.message}")
      return
    }
    update { it.copy(standby = true, standbyAudio = glasses) }
    log("Bekleme modu açık; ses $glasses üzerinden giderken ${_state.value.triggerPackage.trim()} izleniyor")
  }

  /** Called when the standby gesture arrives from the glasses. */
  fun toggleFromGesture() {
    if (_state.value.running) {
      log("Gözlük hareketi: durduruluyor")
      MediaWatch.playCue(SoundCue.STOP, AudioRouter.isRouted)
      stop()
    } else {
      log("Gözlük hareketi: başlatılıyor")
      MediaWatch.playCue(SoundCue.START, false)
      start(null)
    }
  }

  fun stopFromNotification() {
    log("Bildirim üzerinden durduruluyor")
    if (_state.value.standby) {
      MediaWatch.stop()
      update { it.copy(standby = false, standbyAudio = "") }
    }
    serviceStarted = false
    if (_state.value.running) {
      MediaWatch.playCue(SoundCue.STOP, AudioRouter.isRouted)
      shutdown()
    }
  }

  // ---- mock glasses (debug only) ----

  fun enableMockGlasses() {
    if (_state.value.running) {
      fail("Sanal gözlüğü açmadan önce aktif sohbeti durdurun")
      return
    }
    if (MockBridge.enable(app, ::log)) {
      wearablesReady = true
      subscribeWearablesFlows()
      update {
        it.copy(
            mockActive = true,
            mockWorn = true,
            mockStatus = "Açık (Örnek ilaç etiketi yüklü)",
            error = null,
        )
      }
    }
  }

  fun disableMockGlasses() {
    if (_state.value.running) stop()
    MockBridge.disable(app, ::log)
    update { it.copy(mockActive = false, mockWorn = true, mockStatus = "Kapalı") }
  }

  fun mockUsePhoneCamera() {
    if (MockBridge.useBackCamera(::log)) {
      update { it.copy(mockStatus = "Açık (Telefon arka kamerası)") }
    }
  }

  fun mockUseSamplePhoto() {
    if (MockBridge.useSampleDocumentPhoto(app, ::log)) {
      update { it.copy(mockStatus = "Açık (Örnek ilaç etiketi)") }
    }
  }

  fun mockToggleWorn() {
    val next = !_state.value.mockWorn
    if (MockBridge.simulateDonDoff(next, ::log)) {
      update { it.copy(mockWorn = next) }
    }
  }

  fun mockTriggerCapture(doublePress: Boolean) {
    MockBridge.simulateCapturePress(doublePress, ::log)
  }

  fun clearError() {
    update { it.copy(error = null) }
  }

  fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

  // ---- conversation: glasses side ----

  /**
   * Starts the conversation. [requestPermission] opens the Meta AI app to ask for camera access,
   * which needs the activity, so the screen passes it in. A start from a gesture has no screen
   * and passes null; camera access must already have been granted then.
   */
  fun start(requestPermission: (suspend (Permission) -> PermissionStatus)?) {
    if (_state.value.running) return
    if (_state.value.liveMode && !_state.value.geminiKeySet) {
      fail("Önce bir Gemini API anahtarı kaydedin")
      return
    }
    if (!_state.value.liveMode && !_state.value.signedIn) {
      fail("Önce ChatGPT hesabınıza giriş yapın")
      return
    }
    if (!wearablesReady) {
      fail("Önce Bluetooth ve mikrofon izinlerini verin")
      return
    }
    update { it.copy(running = true, phase = Phase.CONNECTING, error = null) }
    scope.launch {
      var granted = false
      Wearables.checkPermissionStatus(Permission.CAMERA)
          .onSuccess { status ->
            granted = status == PermissionStatus.Granted
            // A start from the glasses cannot ask, so the answer itself is worth seeing.
            if (requestPermission == null) log("Hareket: Meta AI kamera izin durumu $status")
          }
          .onFailure { error, _ -> log("Kamera izin kontrolü: ${error.description}") }
      if (!granted && requestPermission != null) {
        granted = requestPermission(Permission.CAMERA) == PermissionStatus.Granted
      }
      if (!_state.value.running) return@launch
      if (!granted) {
        if (requestPermission == null) MediaWatch.playCue(SoundCue.ERROR, false)
        fail(
            if (requestPermission == null) {
              "Kalıcı kamera izni yok. Uygulamadan bir kez başlatıp Meta AI içinde Her zaman izin ver seçin."
            } else {
              "Meta AI gözlük kamerası iznini vermedi"
            },
        )
        shutdown()
        return@launch
      }
      try {
        if (!serviceStarted) KeepAliveService.start(app)
        serviceStarted = true
      } catch (e: Exception) {
        log("Arka plan servisi başlatılamadı: ${e.message}")
      }
      openSession()
    }
  }

  fun stop() {
    if (!_state.value.running) return
    log("Durduruldu")
    shutdown()
  }

  private fun openSession() {
    Wearables.createSession(selector)
        .fold(
            onSuccess = { created ->
              session = created
              // Subscribe before start() so no transition is missed.
              sessionJobs +=
                  scope.launch { created.state.collect { onSessionState(created, it) } }
              sessionJobs +=
                  scope.launch {
                    created.errors.collect { error ->
                      log("Oturum hatası: ${error.description}")
                      update { it.copy(error = "Gözlük: ${error.description}") }
                    }
                  }
              created.start()
            },
            onFailure = { error, _ ->
              MediaWatch.playCue(SoundCue.ERROR, false)
              fail("Gözlükle oturum açılamadı: ${error.description}")
              shutdown()
            },
        )
  }

  private fun onSessionState(current: DeviceSession, value: DeviceSessionState) {
    update { it.copy(sessionState = value.toString()) }
    when (value) {
      DeviceSessionState.STARTED -> {
        sessionStarted = true
        if (camera == null) attachCamera(current) else resumeIfReady()
      }
      DeviceSessionState.PAUSED -> {
        sessionStarted = false
        pauseVoice("Gözlük oturumu duraklattı")
      }
      DeviceSessionState.STOPPED -> {
        if (_state.value.running) {
          log("Gözlük oturumu sonlandırdı")
          MediaWatch.playCue(SoundCue.STOP, AudioRouter.isRouted)
          shutdown()
        }
      }
      else -> Unit
    }
  }

  private fun attachCamera(current: DeviceSession) {
    current
        .addCamera(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 7))
        .fold(
            onSuccess = { added ->
              camera = added
              val video = added.stream
              stream = video
              sessionJobs +=
                  scope.launch(Dispatchers.Default) {
                    video.videoStream.collect { frame -> Frames.offer(frame) }
                  }
              sessionJobs += scope.launch { video.state.collect { onStreamState(it) } }
              sessionJobs +=
                  scope.launch {
                    video.errorStream.collect { error ->
                      log("Video akışı hatası: ${error.description}")
                    }
                  }
              video.start().onFailure { error, _ ->
                log("Video akışı başlatılamadı: ${error.description}")
              }
              if (_state.value.physicalInputs) {
                attachPhysicalInputs(current)
              }
              // Voice still works if the picture never arrives; say so and carry on without it.
              sessionJobs +=
                  scope.launch {
                    delay(STREAM_WAIT_MS)
                    if (_state.value.running && !streaming && !voiceActive) {
                      log("10 saniyedir gözlükten video gelmedi, yalnızca sesle devam ediliyor")
                      startVoice()
                    }
                  }
            },
            onFailure = { error, _ ->
              MediaWatch.playCue(SoundCue.ERROR, false)
              fail("Gözlük kamerası kullanılamıyor: ${error.description}")
              shutdown()
            },
        )
  }

  private fun attachPhysicalInputs(current: DeviceSession) {
    runCatching {
          current
              .addInputs(InputsConfiguration())
              .onSuccess { inputs ->
                inputsAttached = inputs
                log("Deneysel fiziksel gözlük tuşları etkin")
                sessionJobs += scope.launch { inputs.events.collect { onPhysicalInput(it) } }
                sessionJobs +=
                    scope.launch {
                      inputs.errors.collect { error ->
                        log("Fiziksel tuş akışı uyarısı: $error")
                      }
                    }
              }
              .onFailure { error, _ ->
                log("Fiziksel tuşlar desteklenmedi, standart moda devam ediliyor: ${error.description}")
              }
        }
        .onFailure { e -> log("Fiziksel tuş bağlantısı atlandı: ${e.message}") }
  }

  private fun onPhysicalInput(event: InputEvent) {
    if (event !is InputEvent.Capture) return
    when (event.pressType) {
      CapturePressType.SHORT_PRESS -> {
        log("Fiziksel capture tuşu (kısa basma): yüksek çözünürlüklü kare isteniyor")
        MediaWatch.playCue(SoundCue.PHOTO, AudioRouter.isRouted)
        if (_state.value.liveMode && live != null) {
          scope.launch {
            val jpeg = withContext(Dispatchers.Default) { capturePhotoJpeg() }
            if (jpeg != null) {
              live?.sendHighResFrame(jpeg)
              log("Capture tuşu ile yüksek çözünürlüklü kare canlı oturuma gönderildi")
            } else {
              log("Yüksek çözünürlüklü fotoğraf alınamadı")
            }
          }
        } else {
          nextTurnForcePhoto = true
        }
      }
      CapturePressType.DOUBLE_PRESS -> {
        if (voiceActive) {
          pauseVoice("Fiziksel tuş ile duraklatıldı")
        } else if (_state.value.running && sessionStarted && streaming) {
          startVoice()
        }
      }
      else -> Unit
    }
  }

  private fun onStreamState(value: StreamState) {
    update { it.copy(streamState = value.toString()) }
    when (value) {
      StreamState.STREAMING -> {
        streaming = true
        resumeIfReady()
      }
      // A single tap on the temple pauses the stream and a second one resumes it; the
      // conversation follows, which makes the tap a hands-free pause button.
      StreamState.PAUSED -> {
        streaming = false
        pauseVoice("Duraklatıldı. Devam etmek için gözlüğün sapına tek dokunun")
      }
      StreamState.STOPPED,
      StreamState.CLOSED -> streaming = false
      else -> Unit
    }
  }

  private fun resumeIfReady() {
    if (_state.value.running && sessionStarted && streaming && !voiceActive) startVoice()
  }

  private fun shutdown() {
    voiceActive = false
    nextTurnForcePhoto = false
    turnJob?.cancel()
    turnJob = null
    goAwayJob?.cancel()
    goAwayJob = null
    pendingGoAway = false
    stt?.destroy()
    stt = null
    tts?.stop()
    live?.stop()
    live = null
    liveHandle = null
    liveAttempt = 0
    liveDrops = 0
    liveTurnDone = true
    AudioRouter.disable(app)
    activeAudioRoute = null
    sessionJobs.forEach { it.cancel() }
    sessionJobs.clear()
    if (inputsAttached != null) {
      runCatching { session?.removeInputs() }
      inputsAttached = null
    }
    runCatching { camera?.stop() }
    runCatching { session?.stop() }
    camera = null
    stream = null
    session = null
    sessionStarted = false
    streaming = false
    history.clear()
    lastQualityWarningAtMs = 0L
    Frames.reset()
    runCatching { LocalVision.close() }
    // In standby the service stays up, waiting for the next gesture.
    if (serviceStarted && !_state.value.standby) {
      KeepAliveService.stop(app)
      serviceStarted = false
    }
    update {
      it.copy(
          running = false,
          phase = Phase.IDLE,
          partial = "",
          sessionState = "—",
          streamState = "—",
      )
    }
  }

  // ---- conversation: voice side ----

  private fun startVoice() {
    if (voiceActive) return
    voiceActive = true
    if (_state.value.liveMode) {
      startLive()
      return
    }
    scope.launch {
      val settings = _state.value
      val route = AudioRouter.configureForProfile(app, settings.audioProfile)
      activeAudioRoute = route
      if (route.fallbackToPhoneMic) {
        log("Gözlük mikrofonu Bluetooth üzerinden alınamadı, telefon mikrofonu ile HD ses moduna geçiliyor")
      }
      if (!voiceActive) return@launch

      val speech = tts ?: Tts(app) { ok -> if (!ok) log("Metin okuma motoru başlatılamadı") }.also { tts = it }
      withTimeoutOrNull(3000) { while (!speech.ready) delay(50) }
      if (!speech.configure(speechLanguage(), route.callAudio)) {
        log("${speechLanguage()} dili için telefonda konuşma sesi yüklü değil")
      }

      val recognizer = stt ?: Stt(app, sttCallbacks).also { stt = it }
      if (!recognizer.isAvailable()) {
        MediaWatch.playCue(SoundCue.ERROR, route.callAudio)
        fail("Bu telefonda konuşma tanıma servisi bulunamadı")
        shutdown()
        return@launch
      }
      if (voiceActive) {
        MediaWatch.playCue(SoundCue.START, route.callAudio)
        listen()
      }
    }
  }

  private fun pauseVoice(reason: String) {
    if (!voiceActive) return
    voiceActive = false
    turnJob?.cancel()
    turnJob = null
    goAwayJob?.cancel()
    goAwayJob = null
    pendingGoAway = false
    stt?.cancel()
    tts?.stop()
    // The live session is closed too; its handle lets the next one pick the conversation up.
    live?.stop()
    live = null
    liveAttempt = 0
    // Free the hands-free link so music can play through the glasses while paused.
    AudioRouter.disable(app)
    activeAudioRoute = null
    MediaWatch.playCue(SoundCue.PAUSE, false)
    log(reason)
    update { it.copy(phase = Phase.PAUSED, partial = "") }
  }

  private fun listen() {
    if (!voiceActive) return
    update { it.copy(phase = Phase.LISTENING, partial = "") }
    try {
      stt?.start(speechLanguage())
    } catch (e: Exception) {
      log("Konuşma tanıma başlatılamadı: ${e.message}")
      listenAfter(1000)
    }
  }

  private fun speechLanguage(): String =
      _state.value.language.ifBlank { Locale.getDefault().toLanguageTag().ifBlank { "tr-TR" } }

  private fun listenAfter(delayMs: Long) {
    scope.launch {
      delay(delayMs)
      if (voiceActive && turnJob?.isActive != true) listen()
    }
  }

  private val sttCallbacks =
      object : Stt.Callbacks {
        override fun onSpeechStart() {}

        override fun onPartial(text: String) {
          update { it.copy(partial = text) }
        }

        override fun onFinal(text: String) {
          errorStreak = 0
          onUtterance(text)
        }

        override fun onError(code: Int) {
          when (code) {
            // Nobody spoke: just listen again.
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listenAfter(150)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
              stt?.cancel()
              listenAfter(500)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
              MediaWatch.playCue(SoundCue.ERROR, AudioRouter.isRouted)
              fail("Mikrofon erişim izni yok")
              shutdown()
            }
            else -> {
              errorStreak += 1
              log("Konuşma tanıma hatası: $code")
              listenAfter(if (errorStreak > 5) 3000 else 800)
            }
          }
        }
      }

  private fun onUtterance(text: String) {
    val lang = speechLanguage()
    val question = AssistantHelpers.extractAfterWakeWord(text, _state.value.wakeWord, lang)
    if (question == null) {
      listen()
      return
    }
    when (val command = AssistantHelpers.detectLocalCommand(question, lang)) {
      LocalVoiceCommand.END_CONVERSATION -> {
        log("Sesli komut: görüşme sonlandırılıyor")
        MediaWatch.playCue(SoundCue.STOP, AudioRouter.isRouted)
        stop()
        return
      }
      LocalVoiceCommand.REPEAT_LAST -> {
        turnJob =
            scope.launch {
              val toRepeat = lastSpokenAnswer.ifBlank { _state.value.answer.trim() }
              val spoken =
                  if (toRepeat.isNotEmpty()) toRepeat else "Henüz tekrarlanacak bir yanıt yok."
              update { it.copy(phase = Phase.SPEAKING, question = question, answer = spoken) }
              speakAndAwait(spoken)
              listen()
            }
        return
      }
      LocalVoiceCommand.LOCAL_SCAN -> {
        turnJob =
            scope.launch {
              runLocalScanTurn(question)
              listen()
            }
        return
      }
      LocalVoiceCommand.SWITCH_PROFILE_QUICK,
      LocalVoiceCommand.SWITCH_PROFILE_DOCUMENT,
      LocalVoiceCommand.SWITCH_PROFILE_MARKET,
      LocalVoiceCommand.SWITCH_PROFILE_WALK -> {
        val targetProfile = commandToProfile(command)
        if (targetProfile != null) {
          turnJob =
              scope.launch {
                setAssistantProfile(targetProfile)
                MediaWatch.playCue(SoundCue.PHOTO, AudioRouter.isRouted)
                val note = "${targetProfile.label} seçildi."
                lastSpokenAnswer = note
                log("Sesli komut: $note")
                update { it.copy(phase = Phase.SPEAKING, question = question, answer = note) }
                speakAndAwait(note)
                listen()
              }
          return
        }
      }
      LocalVoiceCommand.CAPTURE_HD_PHOTO -> {
        nextTurnForcePhoto = true
        MediaWatch.playCue(SoundCue.PHOTO, AudioRouter.isRouted)
      }
      LocalVoiceCommand.NONE -> Unit
    }
    turnJob = scope.launch { runTurn(question) }
  }

  private fun commandToProfile(command: LocalVoiceCommand): AssistantProfile? =
      when (command) {
        LocalVoiceCommand.SWITCH_PROFILE_QUICK -> AssistantProfile.QUICK
        LocalVoiceCommand.SWITCH_PROFILE_DOCUMENT -> AssistantProfile.DOCUMENT
        LocalVoiceCommand.SWITCH_PROFILE_MARKET -> AssistantProfile.MARKET
        LocalVoiceCommand.SWITCH_PROFILE_WALK -> AssistantProfile.WALK
        else -> null
      }

  private suspend fun ensureTtsConfigured(): Tts? {
    val speech =
        tts ?: Tts(app) { ok -> if (!ok) log("Metin okuma motoru başlatılamadı") }.also { tts = it }
    withTimeoutOrNull(3000) { while (!speech.ready) delay(50) }
    speech.configure(speechLanguage(), AudioRouter.isRouted)
    return speech
  }

  private suspend fun speakAndAwait(text: String) {
    val clean = AssistantHelpers.cleanForSpeech(text)
    if (clean.isEmpty()) return
    val speech = ensureTtsConfigured() ?: return
    speech.speak(clean)
    speech.awaitIdle()
  }

  private fun maybeWarnFrameQuality(report: FrameQualityReport?) {
    if (report == null || !_state.value.qualityWarningEnabled) return
    if (!report.isTooDark && !report.isBlurry) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastQualityWarningAtMs < QUALITY_WARNING_COOLDOWN_MS) return
    lastQualityWarningAtMs = now
    val warning =
        when {
          report.isTooDark && report.isBlurry ->
              "Görüntü karanlık ve bulanık görünüyor, ışığa dönüp sabit tutabilirsiniz."
          report.isTooDark ->
              "Ortam karanlık görünüyor, ışığa dönmek okunurluğu artırabilir."
          else ->
              "Görüntü biraz bulanık görünüyor, kamerayı kısa süre sabit tutabilirsiniz."
        }
    log("Kare kalite uyarısı: $warning")
    if (voiceActive && !_state.value.liveMode) {
      tts?.speak(warning)
    }
  }

  private suspend fun runLocalScanTurn(questionLabel: String) {
    val startedAt = SystemClock.elapsedRealtime()
    update {
      it.copy(
          phase = Phase.THINKING,
          question = questionLabel,
          answer = "",
          partial = "",
          latencyMs = null,
          error = null,
      )
    }
    val (jpeg, _) =
        withContext(Dispatchers.Default) { acquireFrame(true, ChatSpeedProfile.DETAILED) }
    val frameInfoSnapshot = Frames.info
    val currentTurnJob = kotlin.coroutines.coroutineContext[Job]
    if (jpeg != null) {
      scope.launch(Dispatchers.Default) {
        val preview = Frames.preview(jpeg)
        if (preview != null && voiceActive && turnJob === currentTurnJob) {
          withContext(Dispatchers.Main.immediate) {
            if (voiceActive && turnJob === currentTurnJob) {
              update { it.copy(frame = preview, frameInfo = frameInfoSnapshot) }
            }
          }
        }
      }
    } else {
      update { it.copy(frame = null, frameInfo = frameInfoSnapshot) }
      val msg = "Gözlükten görüntü alınamadı, yerel tarama yapılamadı."
      lastSpokenAnswer = msg
      update { it.copy(phase = Phase.SPEAKING, answer = msg) }
      speakAndAwait(msg)
      return
    }

    val scan =
        LocalVision.scanJpeg(
            jpeg,
            speechLanguage(),
            evaluateQuality = _state.value.qualityWarningEnabled,
        )
    maybeWarnFrameQuality(scan.quality)
    val summary = scan.spokenSummary ?: "Görüntüde okunabilir metin veya barkod bulunamadı."
    lastSpokenAnswer = summary
    update {
      it.copy(
          phase = Phase.SPEAKING,
          answer = summary,
          lastLocalScanSummary = scan.spokenSummary.orEmpty(),
          latencyMs = SystemClock.elapsedRealtime() - startedAt,
      )
    }
    speakAndAwait(summary)
  }

  private suspend fun runTurn(question: String) {
    val startedAt = SystemClock.elapsedRealtime()
    val forcePhoto = nextTurnForcePhoto
    nextTurnForcePhoto = false
    update {
      it.copy(
          phase = Phase.THINKING,
          question = question,
          answer = "",
          partial = "",
          latencyMs = null,
          error = null,
      )
    }

    val settings = _state.value
    val preferHighRes = forcePhoto || settings.assistantProfile == AssistantProfile.DOCUMENT
    val (jpeg, usedHighResPhoto) =
        withContext(Dispatchers.Default) { acquireFrame(preferHighRes, settings.chatSpeedProfile) }
    val frameInfoSnapshot = Frames.info
    val currentTurnJob = kotlin.coroutines.coroutineContext[Job]
    if (jpeg != null) {
      scope.launch(Dispatchers.Default) {
        val preview = Frames.preview(jpeg)
        if (preview != null && voiceActive && turnJob === currentTurnJob) {
          withContext(Dispatchers.Main.immediate) {
            if (voiceActive && turnJob === currentTurnJob) {
              update { it.copy(frame = preview, frameInfo = frameInfoSnapshot) }
            }
          }
        }
      }
      if (settings.qualityWarningEnabled) {
        scope.launch(Dispatchers.Default) {
          val report = runCatching { LocalVision.checkJpegQuality(jpeg) }.getOrNull()
          withContext(Dispatchers.Main.immediate) {
            if (voiceActive && turnJob === currentTurnJob) {
              maybeWarnFrameQuality(report)
            }
          }
        }
      }
    } else {
      update { it.copy(frame = null, frameInfo = frameInfoSnapshot) }
      log("Gözlükten kare alınamadı, görüntüsüz yanıtlanıyor")
    }

    val localContextHint =
        if (jpeg != null && settings.assistantProfile == AssistantProfile.MARKET) {
          val marketScan =
              runCatching {
                    LocalVision.scanJpeg(jpeg, speechLanguage(), evaluateQuality = false)
                  }
                  .getOrNull()
          val hint = marketScan?.spokenSummary
          if (!hint.isNullOrBlank()) {
            update { it.copy(lastLocalScanSummary = hint) }
          }
          hint
        } else {
          null
        }

    val answer = StringBuilder()
    val chunker = SentenceChunker()
    var spoke = false
    fun say(sentence: String) {
      val clean = AssistantHelpers.cleanForSpeech(sentence)
      if (clean.isEmpty()) return
      if (!spoke) {
        spoke = true
        val latency = SystemClock.elapsedRealtime() - startedAt
        update { it.copy(phase = Phase.SPEAKING, latencyMs = latency) }
      }
      tts?.speak(clean)
    }

    try {
      var forceRefresh = false
      var effort: String? =
          if (settings.chatSpeedProfile == ChatSpeedProfile.FAST) "minimal" else "low"
      var useWebSearch = settings.webSearch
      var ioRetried = false
      var tries = 0
      var handledOfflineFallback = false
      val highDetailImage =
          jpeg != null &&
              (preferHighRes ||
                  settings.chatSpeedProfile == ChatSpeedProfile.DETAILED ||
                  (usedHighResPhoto && settings.photoMode))
      while (true) {
        tries += 1
        try {
          val token = ChatGptAuth.accessToken(app, forceRefresh, ::log)
          val body =
              Responses.buildBody(
                  model = settings.model,
                  instructions =
                      AssistantHelpers.buildChatInstructions(
                          hasImage = jpeg != null,
                          languageTag = speechLanguage(),
                          profile = settings.assistantProfile,
                          localContextHint = localContextHint,
                      ),
                  history = history.toList(),
                  question = question,
                  jpeg = jpeg,
                  reasoningEffort = effort,
                  highDetailImage = highDetailImage,
                  enableWebSearch = useWebSearch,
              )
          Responses.stream(token, body).collect { delta ->
            answer.append(delta)
            update { it.copy(answer = answer.toString()) }
            chunker.push(delta).forEach { say(it) }
          }
          break
        } catch (e: ApiException) {
          val canRetry = tries < 5 && answer.isEmpty()
          if (canRetry && e.status == 401) {
            forceRefresh = true
            continue
          }
          val msg = e.message ?: ""
          val aboutReasoning = msg.contains("reasoning", ignoreCase = true)
          if (canRetry && e.status == 400 && effort != null && aboutReasoning) {
            effort = AssistantHelpers.nextReasoningEffortFallback(effort)
            continue
          }
          val aboutWebSearch =
              msg.contains("web_search", ignoreCase = true) ||
                  msg.contains("tool", ignoreCase = true) ||
                  msg.contains("unsupported", ignoreCase = true) ||
                  msg.contains("not allowed", ignoreCase = true)
          if (canRetry && useWebSearch && (e.status == 400 || e.status == 403) &&
              (aboutWebSearch || !aboutReasoning)) {
            useWebSearch = false
            log("ChatGPT abonelik oturumu web aramasını desteklemedi, araçsız devam ediliyor")
            continue
          }
          throw e
        } catch (e: IOException) {
          if (!ioRetried && answer.isEmpty()) {
            ioRetried = true
            log("Geçici ağ kesintisi (${e.javaClass.simpleName}), yeniden deneniyor")
            delay(600)
            continue
          }
          if (answer.isEmpty() && jpeg != null) {
            val fallbackScan =
                runCatching {
                      LocalVision.scanJpeg(jpeg, speechLanguage(), evaluateQuality = false)
                    }
                    .getOrNull()
            val summary = fallbackScan?.spokenSummary
            if (!summary.isNullOrBlank()) {
              val fallbackText = "İnternet bağlantısı yok, yerel okuma sonucu: $summary"
              lastSpokenAnswer = fallbackText
              log("Ağ hatası nedeniyle yerel ML Kit emniyet ağı çalıştı")
              update {
                it.copy(
                    phase = Phase.SPEAKING,
                    answer = fallbackText,
                    lastLocalScanSummary = summary,
                    latencyMs = SystemClock.elapsedRealtime() - startedAt,
                )
              }
              tts?.speak(AssistantHelpers.cleanForSpeech(fallbackText))
              awaitSpeechEnd()
              handledOfflineFallback = true
              break
            }
          }
          throw e
        }
      }
      if (!handledOfflineFallback) {
        chunker.flush()?.let { say(it) }

        val text = answer.toString().trim()
        if (text.isNotEmpty()) {
          lastSpokenAnswer = text
          history.addLast(Turn(question, text))
          while (history.size > MAX_HISTORY) history.removeFirst()
        }
        awaitSpeechEnd()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: AuthException) {
      MediaWatch.playCue(SoundCue.ERROR, AudioRouter.isRouted)
      fail(e.message ?: "ChatGPT hesabına giriş yapın")
      update { it.copy(signedIn = ChatGptAuth.isSignedIn(app)) }
      shutdown()
      return
    } catch (e: Exception) {
      val limit = e is ApiException && e.status == 429
      MediaWatch.playCue(SoundCue.ERROR, AudioRouter.isRouted)
      fail(
          if (limit) "ChatGPT abonelik limitine ulaşıldı (chatgpt.com/settings/usage)"
          else "ChatGPT isteği başarısız: ${e.message}",
      )
      tts?.speak(AssistantHelpers.spokenError(speechLanguage(), limit))
      awaitSpeechEnd()
    }
    listen()
  }

  /** Listening must not start while the answer is still playing, or it would hear itself. */
  private suspend fun awaitSpeechEnd() {
    tts?.awaitIdle()
  }

  /** Runs off the main thread. Video frames are instant; a photo is sharper but takes longer. */
  private suspend fun acquireFrame(
      forcePhoto: Boolean,
      speedProfile: ChatSpeedProfile = _state.value.chatSpeedProfile,
  ): Pair<ByteArray?, Boolean> {
    val fastMode = !forcePhoto && speedProfile == ChatSpeedProfile.FAST
    val photoMaxSide = if (fastMode) Frames.FAST_HD_MAX_SIDE else Frames.HD_MAX_SIDE
    val photoQuality = if (fastMode) Frames.FAST_HD_JPEG_QUALITY else Frames.HD_JPEG_QUALITY
    val photoFirst = forcePhoto || _state.value.photoMode
    if (photoFirst) {
      val photo = capturePhotoJpeg(photoMaxSide, photoQuality)
      if (photo != null) return photo to true
      if (forcePhoto) {
        log("Yüksek çözünürlüklü fotoğraf alınamadı, video karesiyle devam ediliyor")
      }
    }
    val videoJpeg = if (fastMode) Frames.latestJpeg(Frames.FAST_JPEG_QUALITY) else Frames.latestJpeg()
    videoJpeg?.let { return it to false }
    if (!photoFirst) {
      capturePhotoJpeg(photoMaxSide, photoQuality)?.let { return it to true }
    }
    return null to false
  }

  private suspend fun capturePhotoJpeg(
      maxSide: Int = Frames.HD_MAX_SIDE,
      quality: Int = Frames.HD_JPEG_QUALITY,
  ): ByteArray? {
    val video = stream ?: return null
    if (!streaming) return null
    var result: ByteArray? = null
    withTimeoutOrNull(PHOTO_TIMEOUT_MS) {
      video
          .capturePhoto()
          .onSuccess { photo ->
            result =
                when (photo) {
                  is PhotoData.Bitmap -> Frames.bitmapToJpeg(photo.bitmap, maxSide, quality)
                  is PhotoData.HEIC -> Frames.heicToJpeg(photo.data, maxSide, quality)
                }
          }
          .onFailure { error, _ -> log("Gözlükten fotoğraf alınamadı: ${error.description}") }
    }
    return result
  }

  // ---- conversation: live voice ----

  private fun startLive() {
    liveFirstConnect = true
    scope.launch {
      val key = SecureStore.get(app, GEMINI_KEY)
      if (key == null) {
        MediaWatch.playCue(SoundCue.ERROR, false)
        fail("Gemini API anahtarı bulunamadı")
        shutdown()
        return@launch
      }
      val settings = _state.value
      val route = AudioRouter.configureForProfile(app, settings.audioProfile)
      activeAudioRoute = route
      if (route.fallbackToPhoneMic) {
        log("Gözlük sesi Bluetooth üzerinden alınamadı, telefon mikrofonu ile HD ses moduna geçiliyor")
      }
      if (voiceActive) connectLive(key)
    }
  }

  private fun connectLive(key: String) {
    live?.stop()
    pendingGoAway = false
    goAwayJob?.cancel()
    goAwayJob = null
    val settings = _state.value
    val fullSetup = liveAttempt < 5
    val enableGoogleSearch = settings.webSearch && liveAttempt < 5
    val enableFunctionTools = liveAttempt < 4
    val resume = if (liveAttempt == 0) liveHandle else null
    val setupOptions =
        when (liveAttempt) {
          0,
          1 ->
              GeminiSetupOptions(
                  voiceName = settings.geminiVoice.apiName,
                  vad = settings.vadSensitivity,
                  thinkingBudget = 0,
                  mediaResolution = "MEDIA_RESOLUTION_MEDIUM",
                  proactiveAudio = false,
              )
          2 ->
              GeminiSetupOptions(
                  voiceName = settings.geminiVoice.apiName,
                  vad = settings.vadSensitivity,
                  thinkingBudget = null,
                  mediaResolution = null,
                  proactiveAudio = false,
              )
          else ->
              GeminiSetupOptions(
                  voiceName = null,
                  vad = null,
                  thinkingBudget = null,
                  mediaResolution = null,
                  proactiveAudio = false,
              )
        }
    val routeProfile = activeAudioRoute?.profile ?: settings.audioProfile
    val callAudio = activeAudioRoute?.callAudio ?: AudioRouter.isRouted
    val events = LiveEvents()
    val session =
        GeminiLive(
            apiKey = key,
            model = settings.geminiModel,
            instructions =
                AssistantHelpers.buildLiveInstructions(
                    speechLanguage(),
                    enableFunctionTools,
                    settings.assistantProfile,
                ),
            resumeHandle = resume,
            fullSetup = fullSetup,
            enableGoogleSearch = enableGoogleSearch,
            enableFunctionTools = enableFunctionTools,
            bargeIn = settings.bargeIn,
            inputDevice = AudioRouter.preferredInputDevice(app, routeProfile),
            callAudio = callAudio,
            frameSource = { Frames.latestJpeg(LIVE_JPEG_QUALITY) },
            events = events,
            setupOptions = setupOptions,
        )
    events.owner = session
    live = session
    update { it.copy(phase = Phase.CONNECTING, partial = "") }
    session.start()
  }

  /** Session callbacks come from background threads; each is replayed on the main thread. */
  private class LiveEvents : GeminiLive.Events {
    var owner: GeminiLive? = null

    /** Drops callbacks from a session that has since been replaced or stopped. */
    private fun onMain(block: () -> Unit) {
      scope.launch { if (live === owner && voiceActive) block() }
    }

    override fun onReady() = onMain {
      liveDrops = 0
      liveTurnDone = true
      if (liveFirstConnect) {
        liveFirstConnect = false
        MediaWatch.playCue(SoundCue.START, AudioRouter.isRouted)
      }
      val modeNote =
          when (liveAttempt) {
            0,
            1 -> "Gemini Live bağlandı"
            2 -> "Gemini Live bağlandı (deneysel kurulum alanları olmadan)"
            3 -> "Gemini Live bağlandı (varsayılan ses ve algılama ile)"
            4 -> "Gemini Live bağlandı (araçlar olmadan)"
            else -> "Gemini Live bağlandı (temel kurulum)"
          }
      log(modeNote)
      update { it.copy(phase = Phase.LISTENING, error = null) }
    }

    override fun onUserText(delta: String) = onMain {
      if (liveTurnDone) {
        liveTurnDone = false
        update { it.copy(question = delta, answer = "") }
      } else {
        update { it.copy(question = it.question + delta) }
      }
    }

    override fun onModelText(delta: String) = onMain {
      if (liveTurnDone) {
        liveTurnDone = false
        update { it.copy(question = "", answer = delta) }
      } else {
        update { it.copy(answer = it.answer + delta) }
      }
    }

    override fun onModelSpeaking(speaking: Boolean) = onMain {
      update { it.copy(phase = if (speaking) Phase.SPEAKING else Phase.LISTENING) }
    }

    override fun onTurnComplete() = onMain {
      liveTurnDone = true
      val currentAnswer = _state.value.answer.trim()
      if (currentAnswer.isNotEmpty()) lastSpokenAnswer = currentAnswer
      if (pendingGoAway) {
        pendingGoAway = false
        goAwayJob?.cancel()
        goAwayJob = null
        val key = SecureStore.get(app, GEMINI_KEY)
        if (key != null) {
          log("Yanıt tamamlandı, Gemini yeni bağlantıya geçiyor")
          liveAttempt = 0
          connectLive(key)
        }
      }
    }

    override fun onResumeHandle(handle: String) = onMain { liveHandle = handle }

    override fun onGoAway() = onMain {
      // The server ends every connection after about ten minutes and says so in advance.
      val key = SecureStore.get(app, GEMINI_KEY) ?: return@onMain
      if (owner?.isSpeaking() == true) {
        pendingGoAway = true
        goAwayJob?.cancel()
        val targetOwner = owner
        goAwayJob =
            scope.launch {
              delay(12_000L)
              if (pendingGoAway && live === targetOwner && voiceActive) {
                pendingGoAway = false
                val freshKey = SecureStore.get(app, GEMINI_KEY) ?: return@launch
                log("Gemini oturum yenileme zaman aşımı, yeni bağlantıya geçiliyor")
                liveAttempt = 0
                connectLive(freshKey)
              }
            }
        log("Gemini oturum yenileme uyarısı geldi, konuşma bitince geçilecek")
      } else {
        log("Gemini yeni bağlantıya taşınıyor")
        liveAttempt = 0
        connectLive(key)
      }
    }

    override fun onToolCall(callId: String, name: String, argsJson: String) = onMain {
      val targetLive = owner
      when (name) {
        "capture_hd_photo" -> {
          log("Gemini Live yüksek çözünürlüklü fotoğraf istedi")
          MediaWatch.playCue(SoundCue.PHOTO, AudioRouter.isRouted)
          scope.launch {
            val jpeg =
                withTimeoutOrNull(15_000L) { withContext(Dispatchers.Default) { capturePhotoJpeg() } }
            if (live !== targetLive || !voiceActive) return@launch
            if (jpeg != null) {
              targetLive?.sendHighResFrame(jpeg)
              targetLive?.sendToolResponse(
                  callId,
                  name,
                  JSONObject()
                      .put("status", "ok")
                      .put(
                          "note",
                          "Yuksek cozunurluklu fotograf gonderildi. Goruntudeki tum yazilari ve detaylari eksiksiz oku.",
                      ),
              )
              log("Yüksek çözünürlüklü fotoğraf Gemini Live oturumuna gönderildi")
            } else {
              targetLive?.sendToolResponse(
                  callId,
                  name,
                  JSONObject()
                      .put("status", "error")
                      .put(
                          "note",
                          "Fotograf cekilemedi, mevcut canli video akisindan yanit vermeye devam et.",
                      ),
              )
              log("Yüksek çözünürlüklü fotoğraf alınamadı")
            }
          }
        }
        "end_conversation" -> {
          log("Gemini Live sesli komutla görüşmeyi bitiriyor")
          targetLive?.sendToolResponse(
              callId,
              name,
              JSONObject().put("status", "ok").put("note", "Gorusme kapatiliyor."),
          )
          scope.launch {
            delay(500)
            if (live === targetLive && voiceActive) {
              MediaWatch.playCue(SoundCue.STOP, AudioRouter.isRouted)
              stop()
            }
          }
        }
        "switch_profile" -> {
          val rawProfile =
              runCatching { JSONObject(argsJson).optString("profile", "") }.getOrDefault("")
          val parsed = AssistantHelpers.parseProfileByIdOrName(rawProfile)
          if (parsed != null) {
            setAssistantProfile(parsed)
            val directive = AssistantHelpers.profileDirective(parsed)
            log("Gemini Live profili değiştirdi: ${parsed.label}")
            targetLive?.sendToolResponse(
                callId,
                name,
                JSONObject()
                    .put("status", "ok")
                    .put("profile", parsed.id)
                    .put("note", "Profil guncellendi: ${parsed.label}. Yeni yonerge: $directive"),
            )
          } else {
            targetLive?.sendToolResponse(
                callId,
                name,
                JSONObject()
                    .put("status", "error")
                    .put(
                        "note",
                        "Gecersiz profil: $rawProfile. Gecerli degerler: quick, document, market, walk.",
                    ),
            )
          }
        }
        "scan_local" -> {
          log("Gemini Live yerel OCR ve barkod taraması istedi")
          scope.launch {
            val jpeg =
                withContext(Dispatchers.Default) { Frames.latestJpeg() }
                    ?: withTimeoutOrNull(10_000L) { capturePhotoJpeg() }
            if (live !== targetLive || !voiceActive) return@launch
            if (jpeg == null) {
              targetLive?.sendToolResponse(
                  callId,
                  name,
                  JSONObject().put("status", "error").put("note", "Gozlukten kare alinamadi."),
              )
              return@launch
            }
            val scan = LocalVision.scanJpeg(jpeg, speechLanguage(), evaluateQuality = false)
            if (live !== targetLive || !voiceActive) return@launch
            val summary = scan.spokenSummary
            if (!summary.isNullOrBlank()) {
              lastSpokenAnswer = summary
              update { it.copy(lastLocalScanSummary = summary) }
              targetLive?.sendToolResponse(
                  callId,
                  name,
                  JSONObject()
                      .put("status", "ok")
                      .put("summary", summary)
                      .put("lineCount", scan.textLines.size)
                      .put("barcodeCount", scan.barcodes.size),
              )
              log("Yerel tarama sonucu Gemini Live oturumuna iletildi")
            } else {
              targetLive?.sendToolResponse(
                  callId,
                  name,
                  JSONObject()
                      .put("status", "empty")
                      .put("note", "Yerel taramada metin veya barkod tespit edilemedi."),
              )
            }
          }
        }
        else -> {
          log("Bilinmeyen araç çağrısı: $name")
          targetLive?.sendToolResponse(
              callId,
              name,
              JSONObject().put("status", "error").put("note", "Desteklenmeyen arac: $name"),
          )
        }
      }
    }

    override fun onMicError(reason: String) = onMain {
      MediaWatch.playCue(SoundCue.ERROR, AudioRouter.isRouted)
      fail(reason)
      shutdown()
    }

    override fun onFrameSent(jpeg: ByteArray) {
      val preview = Frames.preview(jpeg)
      onMain { update { it.copy(frame = preview, frameInfo = Frames.info) } }
    }

    override fun onClosed(reason: String, wasReady: Boolean) = onMain {
      val key = SecureStore.get(app, GEMINI_KEY)
      if (key == null) {
        shutdown()
        return@onMain
      }
      val badKey =
          reason.contains("API key", ignoreCase = true) ||
              reason.contains("HTTP 401") ||
              reason.contains("HTTP 403")
      val outOfQuota =
          reason.contains("quota", ignoreCase = true) ||
              reason.contains("RESOURCE_EXHAUSTED") ||
              reason.contains("HTTP 429")
      when {
        badKey -> {
          MediaWatch.playCue(SoundCue.ERROR, AudioRouter.isRouted)
          fail("Gemini API anahtarını reddetti: $reason")
          shutdown()
        }
        outOfQuota -> {
          MediaWatch.playCue(SoundCue.ERROR, AudioRouter.isRouted)
          fail("Gemini kullanım kotası doldu: $reason")
          shutdown()
        }
        // Refused before the session opened: retry with fewer options each time.
        !wasReady -> {
          liveAttempt += 1
          if (liveAttempt > 5) {
            MediaWatch.playCue(SoundCue.ERROR, AudioRouter.isRouted)
            fail("Gemini bağlantıyı reddetti: $reason")
            shutdown()
          } else {
            log("Gemini bağlantıyı kapattı ($reason), daha sade seçeneklerle yeniden deneniyor (seviye $liveAttempt)")
            connectLive(key)
          }
        }
        else -> {
          liveDrops += 1
          if (liveDrops > 5) {
            MediaWatch.playCue(SoundCue.ERROR, AudioRouter.isRouted)
            fail("Gemini bağlantısı sürekli kopuyor: $reason")
            shutdown()
          } else {
            log("Gemini bağlantısı kesildi ($reason), yeniden bağlanılıyor")
            liveAttempt = 0
            val dropped = owner
            scope.launch {
              delay(400L * liveDrops)
              if (voiceActive && live === dropped) connectLive(key)
            }
          }
        }
      }
    }

    override fun onLog(message: String) {
      log(message)
    }
  }

  // ---- plumbing ----

  private fun prefs(): SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun update(block: (UiState) -> UiState) {
    _state.update(block)
  }

  private fun fail(message: String) {
    log(message)
    update { it.copy(error = message) }
  }

  /** Safe from any thread. The log is on screen because there is no other way to see inside. */
  fun log(message: String) {
    Log.i(TAG, message)
    val snapshot =
        synchronized(logLines) {
          logLines.addLast("${clock.format(Date())}  $message")
          while (logLines.size > MAX_LOG) logLines.removeFirst()
          logLines.toList()
        }
    update { it.copy(log = snapshot) }
  }
}
