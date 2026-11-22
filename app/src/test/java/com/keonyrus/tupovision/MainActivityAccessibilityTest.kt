package com.keonyrus.tupovision

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31])
class MainActivityAccessibilityTest {

  @get:Rule val composeRule = createComposeRule()

  private val emojiRegex =
      Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]")

  private fun renderScreen(state: UiState) {
    composeRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme()) { TupoScreen(state = state) }
    }
  }

  private fun collectAllNodes(root: SemanticsNode): List<SemanticsNode> {
    val result = mutableListOf(root)
    root.children.forEach { child -> result += collectAllNodes(child) }
    return result
  }

  @Test
  fun headingsExistAndScreenContainsZeroEmojis() {
    renderScreen(UiState())

    listOf(
            "Tupo Vision TR",
            "Sohbet",
            "1. Gözlük Bağlantısı",
            "2. Asistan",
            "ChatGPT Aboneliği",
            "Ayarlar",
            "Günlük",
        )
        .forEach { title ->
          composeRule
              .onNodeWithText(title)
              .performScrollTo()
              .assertExists()
              .assert(
                  androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
              )
        }

    val nodes = collectAllNodes(composeRule.onRootNodeCompat())
    for (node in nodes) {
      val texts = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
      val descs = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
      (texts + descs).forEach { value ->
        assertFalse("Emoji bulundu: $value", emojiRegex.containsMatchIn(value))
      }
    }
  }

  @Test
  fun activeSessionSuppressesPhaseLiveRegionWhenQuietAccessibilityEnabled() {
    renderScreen(
        UiState(
            running = true,
            phase = Phase.LISTENING,
            quietSessionAccessibility = true,
            error = "Deneme hatasi",
        ),
    )

    val phaseNode =
        composeRule.onNodeWithContentDescription("Sohbet durumu: Dinliyor").fetchSemanticsNode()
    assertFalse(
        "Sessiz ekran okuyucu acikken aktif oturumda liveRegion olmamali",
        phaseNode.config.contains(SemanticsProperties.LiveRegion),
    )

    // Hata karti her durumda Assertive kalmali.
    val errorNodes =
        collectAllNodes(composeRule.onRootNodeCompat()).filter {
          it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Assertive
        }
    assertTrue("Kritik hata kartinda Assertive liveRegion olmali", errorNodes.isNotEmpty())
  }

  @Test
  fun phaseLiveRegionAnnouncesWhenQuietAccessibilityDisabledOrIdle() {
    renderScreen(
        UiState(
            running = true,
            phase = Phase.THINKING,
            quietSessionAccessibility = false,
        ),
    )

    val phaseNode =
        composeRule.onNodeWithContentDescription("Sohbet durumu: Düşünüyor").fetchSemanticsNode()
    assertEquals(
        LiveRegionMode.Polite,
        phaseNode.config.getOrNull(SemanticsProperties.LiveRegion),
    )
  }

  @Test
  fun radioGroupsAndSwitchesExposeProperSemanticsAndZeroCopyLeftovers() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    Engine.attach(app)

    renderScreen(
        UiState(
            running = true,
            answer = "Ornek cevap",
            audioProfile = AudioProfile.HD_A2DP,
            assistantProfile = AssistantProfile.QUICK,
            qualityWarningEnabled = false,
        ),
    )

    // Kopya BT Ses Uyanik kaldirilmis olmali.
    assertTrue(
        composeRule.onAllNodesWithText("Bluetooth Sesini Uyanık Tut").fetchSemanticsNodes().isEmpty(),
    )

    // Yerel Tara ve Son Yaniti Tekrar Oku dugmeleri erisilebilir ve etkin olmali.
    composeRule
        .onNodeWithContentDescription("Gözlük görüntüsünü çevrimdışı metin ve barkod için tara")
        .assertExists()
        .assertIsEnabled()
        .assertHasClickAction()

    composeRule
        .onNodeWithContentDescription("Son yanıtı tekrar seslendir")
        .assertExists()
        .assertIsEnabled()
        .assertHasClickAction()

    // Secili profil RadioButton rolu, contentDescription ve stateDescription tasimali.
    val hdRadio =
        composeRule
            .onNodeWithContentDescription("Ses Profili, ${AudioProfile.HD_A2DP.label}")
            .performScrollTo()
    hdRadio.assertIsSelected()
    val hdNode = hdRadio.fetchSemanticsNode()
    assertEquals(Role.RadioButton, hdNode.config.getOrNull(SemanticsProperties.Role))
    assertEquals("Seçili", hdNode.config.getOrNull(SemanticsProperties.StateDescription))

    // Yeni profil tiklamasi Engine durumunu guncellemeli.
    composeRule
        .onNodeWithContentDescription("Asistan Profili, ${AssistantProfile.DOCUMENT.label}")
        .performScrollTo()
        .performClick()
    assertEquals(AssistantProfile.DOCUMENT, Engine.state.value.assistantProfile)

    // Isik uyarisi varsayilan kapali Switch semantigi tasimali.
    val lightSwitch =
        composeRule.onNodeWithText("Işık ve Bulanıklık Uyarısı (İsteğe Bağlı)").performScrollTo()
    val switchNode = lightSwitch.fetchSemanticsNode()
    assertEquals(Role.Switch, switchNode.config.getOrNull(SemanticsProperties.Role))
    assertEquals(
        "Kapalı",
        switchNode.config.getOrNull(SemanticsProperties.StateDescription),
    )

    Engine.setAssistantProfile(AssistantProfile.QUICK)
  }

  @Test
  fun localScanButtonDisabledWhenNotRunning() {
    renderScreen(UiState(running = false, answer = "Eski yanit"))
    composeRule
        .onNodeWithContentDescription("Gözlük görüntüsünü çevrimdışı metin ve barkod için tara")
        .assertIsNotEnabled()
    composeRule
        .onNodeWithContentDescription("Son yanıtı tekrar seslendir")
        .assertIsEnabled()
  }

  private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onRootNodeCompat():
      SemanticsNode = onNode(androidx.compose.ui.test.isRoot()).fetchSemanticsNode()
}
