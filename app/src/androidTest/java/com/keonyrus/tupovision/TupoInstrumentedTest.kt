package com.keonyrus.tupovision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TupoInstrumentedTest {

  companion object {
    @JvmStatic
    @BeforeClass
    fun enableAccessibilityChecks() {
      AccessibilityChecks.enable().setRunChecksFromRootView(true)
    }
  }

  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun mainActivityLaunchesWithAccessibleSectionsAndInteractiveControls() {
    composeRule.onNodeWithText("Tupo Vision TR").assertIsDisplayed()
    composeRule.onNodeWithText("Sohbet").assertIsDisplayed()
    composeRule.onNodeWithText("2. Asistan").performScrollTo().assertIsDisplayed()

    composeRule
        .onNodeWithContentDescription("Asistan Profili, ${AssistantProfile.DOCUMENT.label}")
        .performScrollTo()
        .performClick()
    assertEquals(AssistantProfile.DOCUMENT, Engine.state.value.assistantProfile)

    composeRule
        .onNodeWithContentDescription("Asistan Profili, ${AssistantProfile.QUICK.label}")
        .performScrollTo()
        .performClick()
    assertEquals(AssistantProfile.QUICK, Engine.state.value.assistantProfile)
  }

  @Test
  fun localVision_readsTurkishTextFromGeneratedJpegUsingBundledMlKit() = runBlocking {
    val bitmap = Bitmap.createBitmap(900, 360, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.BLACK
          textSize = 64f
        }
    canvas.drawText("SON KULLANMA 12 2027", 40f, 140f, paint)
    canvas.drawText("TOPLAM 145 TL", 40f, 260f, paint)

    val jpeg =
        ByteArrayOutputStream().use { out ->
          bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
          out.toByteArray()
        }
    bitmap.recycle()

    val result = LocalVision.scanJpeg(jpeg, languageTag = "tr-TR", evaluateQuality = true)
    assertNotNull(result.quality)
    assertFalse(result.quality!!.isTooDark)
    assertTrue("OCR cikti satirlari bos olmamali", result.textLines.isNotEmpty())
    val combined = result.textLines.joinToString(" ").uppercase()
    assertTrue("Beklenen metin okunmali: $combined", combined.contains("12") || combined.contains("TOPLAM"))
  }

  @Test
  fun mockBridge_enablesMockGlassesCreatesSampleMedicationAndScansWithMlKit() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val logs = mutableListOf<String>()
    val enabled = MockBridge.enable(context) { logs += it }
    assertTrue("MockBridge baslatilmali: $logs", enabled)

    val sampleFile = File(context.cacheDir, "mock_sample_ilac.jpg")
    assertTrue("Ornek ilac fotografi olusmali", sampleFile.exists())

    val result =
        LocalVision.scanJpeg(sampleFile.readBytes(), languageTag = "tr-TR", evaluateQuality = true)
    val combined = result.textLines.joinToString(" ").uppercase()
    assertTrue("Ornek ilac etiketi okunmali: $combined", combined.contains("PAROL"))
    assertTrue("SKT bilgisi okunmali: $combined", combined.contains("2028"))

    MockBridge.disable(context) { logs += it }
  }

  @Test
  fun audioRouter_keepsNormalModeOnRealApi31InHdA2dpProfile() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val audio = context.getSystemService(AudioManager::class.java)

    val route = AudioRouter.configureForProfile(context, AudioProfile.HD_A2DP)
    assertEquals(AudioProfile.HD_A2DP, route.profile)
    assertFalse(route.callAudio)
    assertEquals(AudioManager.MODE_NORMAL, audio.mode)
  }
}
