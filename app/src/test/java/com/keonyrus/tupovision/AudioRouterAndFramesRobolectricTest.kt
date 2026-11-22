package com.keonyrus.tupovision

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioRouterAndFramesRobolectricTest {

  @Test
  fun hdA2dp_keepsModeNormalAndDoesNotRouteSco() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audio.mode = AudioManager.MODE_IN_COMMUNICATION

    val route = AudioRouter.configureForProfile(context, AudioProfile.HD_A2DP)

    assertEquals(AudioProfile.HD_A2DP, route.profile)
    assertFalse(route.callAudio)
    assertFalse(route.onHeadset)
    assertFalse(AudioRouter.isRouted)
    assertEquals(AudioManager.MODE_NORMAL, audio.mode)
  }

  @Test
  fun glassesSco_withoutBluetoothHeadsetFallsBackSafelyToHdA2dp() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val route = AudioRouter.configureForProfile(context, AudioProfile.GLASSES_SCO)

    assertEquals(AudioProfile.HD_A2DP, route.profile)
    assertFalse(route.callAudio)
    assertFalse(route.onHeadset)
    assertTrue(route.fallbackToPhoneMic)
    assertFalse(AudioRouter.isRouted)
    assertEquals(AudioManager.MODE_NORMAL, audio.mode)
  }

  @Test
  fun framesAndLocalVisionCheckJpegQualityHandleDarkAndBrightBitmaps() = runBlocking {
    val darkBitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
    darkBitmap.eraseColor(Color.BLACK)
    val darkBytes =
        ByteArrayOutputStream().use { out ->
          darkBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
          out.toByteArray()
        }
    darkBitmap.recycle()

    val darkReport = LocalVision.checkJpegQuality(darkBytes)
    assertNotNull(darkReport)
    assertTrue("Siyah kare isTooDark olmali", darkReport!!.isTooDark)

    val brightBitmap = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(brightBitmap)
    canvas.drawColor(Color.WHITE)
    val fastBytes = Frames.fastBitmapToJpeg(brightBitmap)
    brightBitmap.recycle()
    assertNotNull(fastBytes)
    assertTrue(fastBytes!!.isNotEmpty())

    val brightReport = LocalVision.checkJpegQuality(fastBytes)
    assertNotNull(brightReport)
    assertFalse("Beyaz aydinlik kare isTooDark olmamali", brightReport!!.isTooDark)
  }

  @Test
  fun engineAttachAndSettingsPersistAcrossPreferences() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    Engine.attach(app)

    Engine.setAudioProfile(AudioProfile.GLASSES_SCO)
    Engine.setGeminiVoice(GeminiVoice.AOEDE)
    Engine.setVadSensitivity(VadSensitivity.PATIENT)
    Engine.setChatSpeedProfile(ChatSpeedProfile.DETAILED)
    Engine.setAssistantProfile(AssistantProfile.DOCUMENT)
    Engine.setQualityWarningEnabled(true)
    Engine.setQuietSessionAccessibility(false)

    val state = Engine.state.value
    assertEquals(AudioProfile.GLASSES_SCO, state.audioProfile)
    assertTrue(state.glassesMic)
    assertEquals(GeminiVoice.AOEDE, state.geminiVoice)
    assertEquals(VadSensitivity.PATIENT, state.vadSensitivity)
    assertEquals(ChatSpeedProfile.DETAILED, state.chatSpeedProfile)
    assertEquals(AssistantProfile.DOCUMENT, state.assistantProfile)
    assertTrue(state.qualityWarningEnabled)
    assertFalse(state.quietSessionAccessibility)

    // Tekrar attach edildiginde SharedPreferences uzerinden korunmali.
    Engine.attach(app)
    val reloaded = Engine.state.value
    assertEquals(AudioProfile.GLASSES_SCO, reloaded.audioProfile)
    assertEquals(AssistantProfile.DOCUMENT, reloaded.assistantProfile)
    assertTrue(reloaded.qualityWarningEnabled)
    assertFalse(reloaded.quietSessionAccessibility)

    // Varsayilanlari temizle.
    Engine.setAudioProfile(AudioProfile.HD_A2DP)
    Engine.setAssistantProfile(AssistantProfile.QUICK)
    Engine.setQualityWarningEnabled(false)
    Engine.setQuietSessionAccessibility(true)
  }
}
