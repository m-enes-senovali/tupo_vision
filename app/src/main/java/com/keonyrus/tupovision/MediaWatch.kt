package com.keonyrus.tupovision

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRouter
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

enum class SoundCue {
  START,
  STOP,
  PAUSE,
  PHOTO,
  ERROR,
}

/**
 * Exists only so the user can grant this app "notification access", which Android requires before
 * an app may see other apps' media sessions. It reads no notifications.
 */
class MediaWatchService : NotificationListenerService()

/**
 * Standby: start and stop the conversation from the glasses, phone in a pocket.
 *
 * The glasses cannot launch this app, but their tap-and-hold gesture can be assigned to "Music"
 * in the Meta AI app, which makes them ask a music app on the phone to play. Standby uses that
 * music app as a tripwire: when it starts playing while the phone is locked or its screen is off,
 * it is paused and the conversation toggles. Starting music by hand, with the phone in use, is
 * left alone, so the music app stays usable for music.
 *
 * Android does not say who asked the music app to play. A car, a watch or other headphones can
 * start it with the phone locked just as well, so a start also counts only while music goes to
 * the glasses: the Bluetooth audio device that was in use when standby was switched on.
 *
 * Every media session change is logged as package and state, never titles.
 */
object MediaWatch {
  const val DEFAULT_TRIGGER = "com.spotify.music"
  // One gesture causes a burst of session changes; it must toggle once.
  private const val DEBOUNCE_MS = 4000L
  private const val SILENCE_MS = 6000L

  private val handler = Handler(Looper.getMainLooper())
  private var manager: MediaSessionManager? = null
  private var component: ComponentName? = null
  private var triggerPackage = DEFAULT_TRIGGER
  private var armed = false
  private var lastTriggerAt = 0L
  private var lastIgnoredAt = 0L
  // The music app may start playing a moment after the gesture was handled; keep it quiet.
  private var silenceUntil = 0L
  private var appContext: Context? = null
  // The glasses as Android's two audio APIs name them; the two names can differ.
  private var glassesOutput = ""
  private var glassesRoute: String? = null
  private val watched = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
  // A music app reports the same state many times over; the log keeps only the changes.
  private val lastLogged = HashMap<String, Int>()

  private val sessionsListener =
      MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onSessions(controllers.orEmpty())
      }

  val isOn: Boolean
    get() = manager != null

  fun hasAccess(context: Context): Boolean {
    val enabled =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
    return enabled.split(":").any {
      ComponentName.unflattenFromString(it)?.packageName == context.packageName
    }
  }

  /** Bluetooth devices that music can play through right now, by name. */
  fun bluetoothOutputs(context: Context): List<String> {
    val audio = context.getSystemService(AudioManager::class.java)
    return runCatching {
          audio
              .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
              .filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
              }
              .map { it.productName?.toString().orEmpty() }
              .filter { it.isNotBlank() }
              .distinct()
        }
        .getOrDefault(emptyList())
  }

  /** Kurulu yaygın müzik uygulamalarını (paket adı, görünen ad) çiftleri olarak listeler. */
  fun installedMusicApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val found = LinkedHashMap<String, String>()
    val known =
        listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.apple.android.music",
            "com.miui.player",
            "deezer.android.app",
            "com.fizy.android",
        )
    for (pkg in known) {
      runCatching {
        val info = pm.getApplicationInfo(pkg, 0)
        found[pkg] = pm.getApplicationLabel(info).toString()
      }
    }
    runCatching {
      val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
      for (res in pm.queryIntentActivities(intent, 0)) {
        val pkg = res.activityInfo?.packageName ?: continue
        if (pkg !in found) {
          found[pkg] = res.loadLabel(pm).toString().ifBlank { pkg }
        }
      }
    }
    return found.entries.map { it.key to it.value }
  }

  /** The Bluetooth device Android has selected for music, or null when it is not Bluetooth. */
  private fun bluetoothRoute(context: Context): String? =
      runCatching {
            val route =
                context
                    .getSystemService(MediaRouter::class.java)
                    .getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
            if (route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH) {
              route.name?.toString()
            } else {
              null
            }
          }
          .getOrNull()

  /**
   * Call on the main thread, with the glasses on. [glasses] is the Bluetooth audio device to
   * follow, one of [bluetoothOutputs]. Returns false when notification access is missing.
   */
  fun start(context: Context, trigger: String, glasses: String): Boolean {
    if (manager != null) return true
    glassesOutput = glasses
    glassesRoute = bluetoothRoute(context)
    val sessions = context.getSystemService(MediaSessionManager::class.java)
    val listener = ComponentName(context, MediaWatchService::class.java)
    return try {
      sessions.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
      manager = sessions
      component = listener
      appContext = context.applicationContext
      triggerPackage = trigger.ifBlank { DEFAULT_TRIGGER }
      // Sessions that already exist are the baseline, not a gesture.
      armed = false
      lastLogged.clear()
      onSessions(sessions.getActiveSessions(listener))
      armed = true
      true
    } catch (e: SecurityException) {
      false
    }
  }

  fun stop() {
    val sessions = manager ?: return
    manager = null
    armed = false
    runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsListener) }
    watched.values.forEach { (controller, callback) ->
      runCatching { controller.unregisterCallback(callback) }
    }
    watched.clear()
  }

  private fun onSessions(controllers: List<MediaController>) {
    val current = controllers.map { it.sessionToken }.toSet()
    for (token in watched.keys.filter { it !in current }) {
      watched.remove(token)?.let { (controller, callback) ->
        runCatching { controller.unregisterCallback(callback) }
        if (armed) Engine.log("Media: ${controller.packageName} session ended")
      }
    }
    for (controller in controllers) {
      if (controller.sessionToken in watched) continue
      val callback =
          object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
              onState(controller, state)
            }
          }
      controller.registerCallback(callback, handler)
      watched[controller.sessionToken] = controller to callback
      if (armed) {
        Engine.log(
            "Media: ${controller.packageName} session appeared, " +
                stateName(controller.playbackState),
        )
        if (controller.packageName == triggerPackage) trigger(controller)
      }
    }
  }

  private fun onState(controller: MediaController, state: PlaybackState?) {
    if (!armed) return
    val code = state?.state ?: -1
    if (lastLogged.put(controller.packageName, code) != code) {
      Engine.log("Media: ${controller.packageName} is ${stateName(state)}")
    }
    if (controller.packageName != triggerPackage || state == null) return
    if (state.state == PlaybackState.STATE_PLAYING && SystemClock.elapsedRealtime() < silenceUntil) {
      runCatching { controller.transportControls.pause() }
      return
    }
    // Asked to play: it either starts, or fails because nobody is signed in. Both count.
    val asked =
        state.state == PlaybackState.STATE_PLAYING ||
            state.state == PlaybackState.STATE_BUFFERING ||
            state.state == PlaybackState.STATE_CONNECTING ||
            state.state == PlaybackState.STATE_ERROR
    if (asked) trigger(controller)
  }

  private fun trigger(controller: MediaController) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastTriggerAt < DEBOUNCE_MS) return
    if (!phonePutAway()) {
      // Someone is using the phone, so this is them starting music, not a gesture.
      if (now - lastIgnoredAt > DEBOUNCE_MS) {
        lastIgnoredAt = now
        Engine.log("Media: started with the phone in use, not treated as a gesture")
      }
      return
    }
    val context = appContext ?: return
    val outputs = bluetoothOutputs(context)
    val route = bluetoothRoute(context)
    val expectedRoute = glassesRoute
    val elsewhere =
        glassesOutput !in outputs || (route != null && expectedRoute != null && route != expectedRoute)
    if (elsewhere) {
      // A car or other headphones: whoever started the music, it was not meant for the glasses.
      if (now - lastIgnoredAt > DEBOUNCE_MS) {
        lastIgnoredAt = now
        val where = route ?: outputs.firstOrNull() ?: "the phone"
        Engine.log("Media: music goes to $where, not to $glassesOutput; not treated as a gesture")
      }
      return
    }
    lastTriggerAt = now
    silenceUntil = now + SILENCE_MS
    // The music was only a signal; nobody wants to hear it.
    runCatching { controller.transportControls.pause() }
    Engine.toggleFromGesture()
  }

  /** Locked or screen off: the phone is in a pocket, not in a hand. */
  private fun phonePutAway(): Boolean {
    val context = appContext ?: return false
    val power = context.getSystemService(PowerManager::class.java)
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    return !power.isInteractive || keyguard.isKeyguardLocked
  }

  private fun stateName(state: PlaybackState?): String =
      when (state?.state) {
        null -> "no state"
        PlaybackState.STATE_NONE -> "none"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_ERROR -> "error"
        else -> "state ${state.state}"
      }

  /** A short tone in the glasses: high when the conversation starts, low when it stops. */
  fun beep(starting: Boolean) {
    playCue(if (starting) SoundCue.START else SoundCue.STOP, inCall = false)
  }

  /** Gözlük veya telefon kulaklığında durum için ayırt edici kısa sesli ton çalar. */
  fun playCue(cue: SoundCue, inCall: Boolean = false) {
    EarconPlayer.enqueue(cue, inCall)
  }
}

private object EarconPlayer {
  private const val SAMPLE_RATE = 24_000
  private const val PRE_ROLL_A2DP_MS = 180
  private const val PRE_ROLL_CALL_MS = 20

  private data class Request(val cue: SoundCue, val inCall: Boolean)

  private val queue = LinkedBlockingQueue<Request>()
  private val started = AtomicBoolean(false)

  fun enqueue(cue: SoundCue, inCall: Boolean) {
    ensureStarted()
    queue.offer(Request(cue, inCall))
  }

  private fun ensureStarted() {
    if (!started.compareAndSet(false, true)) return
    Thread(
            {
              while (true) {
                val req =
                    try {
                      queue.take()
                    } catch (_: InterruptedException) {
                      continue
                    }
                runCatching { playSync(req) }
              }
            },
            "earcon-player",
        )
        .apply { isDaemon = true }
        .start()
  }

  private fun playSync(req: Request) {
    if (!req.inCall) {
      val waitMs = AudioRouter.recentScoTeardownRemainingMs()
      if (waitMs > 0L) {
        runCatching { Thread.sleep(waitMs) }
      }
    }

    val kind =
        when (req.cue) {
          SoundCue.START -> EarconCueKind.START
          SoundCue.STOP -> EarconCueKind.STOP
          SoundCue.PAUSE -> EarconCueKind.PAUSE
          SoundCue.PHOTO -> EarconCueKind.PHOTO
          SoundCue.ERROR -> EarconCueKind.ERROR
        }
    val preRollMs = if (req.inCall) PRE_ROLL_CALL_MS else PRE_ROLL_A2DP_MS
    val pcm = AssistantHelpers.createEarconPcm(kind, sampleRate = SAMPLE_RATE, preRollMs = preRollMs)
    if (pcm.isEmpty()) return

    val minBytes =
        AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
    if (minBytes <= 0) return

    val usage =
        if (req.inCall) AudioAttributes.USAGE_VOICE_COMMUNICATION
        else AudioAttributes.USAGE_ASSISTANT
    val contentType =
        if (req.inCall) AudioAttributes.CONTENT_TYPE_SPEECH
        else AudioAttributes.CONTENT_TYPE_SONIFICATION
    val bufferBytes = maxOf(minBytes, 4096)

    val track =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

    if (track.state != AudioTrack.STATE_INITIALIZED) {
      track.release()
      return
    }

    try {
      track.play()
      var offset = 0
      while (offset < pcm.size) {
        val written = track.write(pcm, offset, pcm.size - offset)
        if (written <= 0) break
        offset += written
      }
      val totalFrames = pcm.size / 2
      val expectedDurationMs = (totalFrames * 1000L) / SAMPLE_RATE
      val deadline = SystemClock.elapsedRealtime() + expectedDurationMs + 250L
      while (SystemClock.elapsedRealtime() < deadline) {
        if (track.playbackHeadPosition >= totalFrames) break
        Thread.sleep(20L)
      }
      Thread.sleep(30L)
    } finally {
      runCatching { track.stop() }
      track.release()
    }
  }
}
