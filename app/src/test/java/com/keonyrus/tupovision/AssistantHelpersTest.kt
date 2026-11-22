package com.keonyrus.tupovision

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantHelpersTest {

  @Test
  fun buildGeminiSetupMessage_includesSearchAndFunctionsWhenEnabled() {
    val raw =
        AssistantHelpers.buildGeminiSetupMessage(
            model = "gemini-3.8-live",
            instructions = "Turkce yanit ver",
            resumeHandle = "handle-123",
            fullSetup = true,
            enableGoogleSearch = true,
            enableFunctionTools = true,
        )
    val root = JSONObject(raw)
    val setup = root.getJSONObject("setup")
    assertEquals("models/gemini-3.8-live", setup.getString("model"))
    assertEquals("handle-123", setup.getJSONObject("sessionResumption").getString("handle"))
    assertTrue(setup.has("contextWindowCompression"))

    val tools = setup.getJSONArray("tools")
    assertEquals(2, tools.length())
    assertTrue(tools.getJSONObject(0).has("googleSearch"))

    val fnDecls = tools.getJSONObject(1).getJSONArray("functionDeclarations")
    val names = (0 until fnDecls.length()).map { fnDecls.getJSONObject(it).getString("name") }
    assertTrue("capture_hd_photo" in names)
    assertTrue("end_conversation" in names)
  }

  @Test
  fun buildGeminiSetupMessage_bareMinimumOmitsOptionalFieldsAndTools() {
    val raw =
        AssistantHelpers.buildGeminiSetupMessage(
            model = " ",
            instructions = "Test",
            resumeHandle = "ignored",
            fullSetup = false,
            enableGoogleSearch = false,
            enableFunctionTools = false,
        )
    val setup = JSONObject(raw).getJSONObject("setup")
    assertEquals("models/${GeminiLive.DEFAULT_MODEL}", setup.getString("model"))
    assertFalse(setup.has("inputAudioTranscription"))
    assertFalse(setup.has("sessionResumption"))
    assertFalse(setup.has("tools"))
  }

  @Test
  fun responsesBuildBody_handlesWebSearchHighDetailAndReasoning() {
    val withTools =
        Responses.buildBody(
            model = "gpt-6.1-sol",
            instructions = "Sistem",
            history = listOf(Turn("Merhaba", "Selam")),
            question = "Etiketi oku",
            jpeg = byteArrayOf(1, 2, 3),
            reasoningEffort = "low",
            highDetailImage = true,
            enableWebSearch = true,
        )
    assertEquals("gpt-6.1-sol", withTools.getString("model"))
    assertEquals("low", withTools.getJSONObject("reasoning").getString("effort"))

    val tools = withTools.getJSONArray("tools")
    assertEquals(1, tools.length())
    assertEquals("web_search", tools.getJSONObject(0).getString("type"))

    val input = withTools.getJSONArray("input")
    val lastContent = input.getJSONObject(input.length() - 1).getJSONArray("content")
    val imagePart = lastContent.getJSONObject(1)
    assertEquals("input_image", imagePart.getString("type"))
    assertEquals("high", imagePart.getString("detail"))

    val withoutTools =
        Responses.buildBody(
            model = "gpt-6.1-sol",
            instructions = "Sistem",
            history = emptyList(),
            question = "Hava nasil?",
            jpeg = null,
            reasoningEffort = null,
            highDetailImage = true,
            enableWebSearch = false,
        )
    assertFalse(withoutTools.has("tools"))
    assertFalse(withoutTools.has("reasoning"))
  }

  @Test
  fun extractAfterWakeWord_handlesTurkishCasingAndPunctuation() {
    assertEquals(
        "saat kaç",
        AssistantHelpers.extractAfterWakeWord("İleri, saat kaç?", "ileri", "tr-TR")?.trimEnd('?')
            ?.let { if (it == "saat kaç") "saat kaç" else it },
    )
    assertEquals(
        "saat kaç?",
        AssistantHelpers.extractAfterWakeWord("İleri, saat kaç?", "ileri", "tr-TR"),
    )
    assertEquals(
        "önümde ne var?",
        AssistantHelpers.extractAfterWakeWord("IŞIK önümde ne var?", "ışık", "tr-TR"),
    )
    assertEquals(
        "merhaba",
        AssistantHelpers.extractAfterWakeWord("ASİSTAN: merhaba", "asistan", "tr-TR"),
    )
    assertNull(AssistantHelpers.extractAfterWakeWord("İleri", "ileri", "tr-TR"))
    assertNull(AssistantHelpers.extractAfterWakeWord("Bugün hava güzel", "ileri", "tr-TR"))
    assertEquals(
        "Doğrudan soru",
        AssistantHelpers.extractAfterWakeWord("  Doğrudan soru  ", "", "tr-TR"),
    )
  }

  @Test
  fun detectLocalCommand_distinguishesCommandsFromNormalQuestions() {
    assertEquals(
        LocalVoiceCommand.END_CONVERSATION,
        AssistantHelpers.detectLocalCommand("Lütfen görüşmeyi bitir.", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.END_CONVERSATION,
        AssistantHelpers.detectLocalCommand("Sohbeti kapat", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.CAPTURE_HD_PHOTO,
        AssistantHelpers.detectLocalCommand("Bunu yakından oku lütfen", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.CAPTURE_HD_PHOTO,
        AssistantHelpers.detectLocalCommand("İlacın son kullanma tarihini oku", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.NONE,
        AssistantHelpers.detectLocalCommand("Odadaki ışığı kapatabilir misin?", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.NONE,
        AssistantHelpers.detectLocalCommand("Önümde ne var?", "tr-TR"),
    )
  }

  @Test
  fun cleanForSpeech_stripsMarkdownAndUrlsCleanly() {
    val dirty = "- **Parol**: Günde 2 kez. Ayrıntı: [Prospektüs](https://ornek.com/p) https://ornek.com"
    val clean = AssistantHelpers.cleanForSpeech(dirty)
    assertEquals("Parol: Günde 2 kez. Ayrıntı: Prospektüs", clean)
  }

  @Test
  fun spokenError_returnsLocalizedMessages() {
    assertEquals(
        "Abonelik kullanım limitine ulaşıldı.",
        AssistantHelpers.spokenError("tr-TR", limit = true),
    )
    assertEquals(
        "İstek başarısız oldu, lütfen tekrar deneyin.",
        AssistantHelpers.spokenError("tr-TR", limit = false),
    )
    assertEquals("Лимит подписки исчерпан.", AssistantHelpers.spokenError("ru-RU", limit = true))
    assertEquals("Plan limit reached.", AssistantHelpers.spokenError("en-US", limit = true))
  }

  @Test
  fun instructions_includeAccessibilityAndToolGuidance() {
    val liveWithTools = AssistantHelpers.buildLiveInstructions("tr-TR", enableTools = true)
    assertTrue(liveWithTools.contains("Türkçe (tr-TR)"))
    assertTrue(liveWithTools.contains("saat"))
    assertTrue(liveWithTools.contains("son kullanma tarihi"))
    assertTrue(liveWithTools.contains("capture_hd_photo"))
    assertTrue(liveWithTools.contains("end_conversation"))

    val liveNoTools = AssistantHelpers.buildLiveInstructions("tr-TR", enableTools = false)
    assertFalse(liveNoTools.contains("capture_hd_photo"))

    val chatWithImg = AssistantHelpers.buildChatInstructions(hasImage = true, languageTag = "tr-TR")
    assertTrue(chatWithImg.contains("Türkçe (tr-TR)"))
    assertTrue(chatWithImg.contains("saat yönü"))

    val chatNoImg = AssistantHelpers.buildChatInstructions(hasImage = false, languageTag = "tr-TR")
    assertNotNull(chatNoImg)
    assertTrue(chatNoImg.contains("görüntü karesi yok"))
  }

  @Test
  fun createA2dpKeepAlivePcm_isZeroDcNonZeroSilence() {
    val evenFrames = AssistantHelpers.createA2dpKeepAlivePcm(frameCount = 400, amplitude = 2)
    assertEquals(1600, evenFrames.size)
    assertTrue(evenFrames.any { it.toInt() != 0 })

    var sum = 0L
    var idx = 0
    while (idx + 1 < evenFrames.size) {
      val lo = evenFrames[idx].toInt() and 0xFF
      val hi = evenFrames[idx + 1].toInt() shl 8
      val sample = (hi or lo).toShort().toInt()
      assertTrue(kotlin.math.abs(sample) <= 2)
      sum += sample
      idx += 2
    }
    assertEquals(0L, sum)
    assertEquals(0, AssistantHelpers.createA2dpKeepAlivePcm(0).size)
  }

  @Test
  fun buildGeminiSetupMessage_includesOptionsWhenFullSetupEnabled() {
    val options =
        GeminiSetupOptions(
            voiceName = GeminiVoice.ZEPHYR.apiName,
            vad = VadSensitivity.FAST,
            thinkingBudget = 0,
            mediaResolution = "MEDIA_RESOLUTION_HIGH",
            proactiveAudio = true,
        )
    val raw =
        AssistantHelpers.buildGeminiSetupMessage(
            model = "gemini-3.8-live",
            instructions = "Turkce yanit ver",
            resumeHandle = null,
            fullSetup = true,
            enableGoogleSearch = true,
            enableFunctionTools = true,
            options = options,
        )
    val setup = JSONObject(raw).getJSONObject("setup")
    val generationConfig = setup.getJSONObject("generationConfig")
    assertEquals(
        "Zephyr",
        generationConfig
            .getJSONObject("speechConfig")
            .getJSONObject("voiceConfig")
            .getJSONObject("prebuiltVoiceConfig")
            .getString("voiceName"),
    )
    assertEquals(0, generationConfig.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
    assertEquals("MEDIA_RESOLUTION_HIGH", generationConfig.getString("mediaResolution"))

    val aad =
        setup
            .getJSONObject("realtimeInputConfig")
            .getJSONObject("automaticActivityDetection")
    assertFalse(aad.getBoolean("disabled"))
    assertEquals(VadSensitivity.FAST.startSensitivity, aad.getString("startOfSpeechSensitivity"))
    assertEquals(VadSensitivity.FAST.endSensitivity, aad.getString("endOfSpeechSensitivity"))
    assertEquals(VadSensitivity.FAST.prefixPaddingMs, aad.getInt("prefixPaddingMs"))
    assertEquals(VadSensitivity.FAST.silenceDurationMs, aad.getInt("silenceDurationMs"))

    assertTrue(setup.getJSONObject("proactivity").getBoolean("proactiveAudio"))
  }

  @Test
  fun buildGeminiSetupMessage_omitsNullOptionsEvenWhenFullSetupEnabled() {
    val options =
        GeminiSetupOptions(
            voiceName = null,
            vad = null,
            thinkingBudget = null,
            mediaResolution = null,
            proactiveAudio = false,
        )
    val raw =
        AssistantHelpers.buildGeminiSetupMessage(
            model = "gemini-3.8-live",
            instructions = "Test",
            resumeHandle = null,
            fullSetup = true,
            enableGoogleSearch = true,
            enableFunctionTools = false,
            options = options,
        )
    val setup = JSONObject(raw).getJSONObject("setup")
    val generationConfig = setup.getJSONObject("generationConfig")
    assertFalse(generationConfig.has("speechConfig"))
    assertFalse(generationConfig.has("thinkingConfig"))
    assertFalse(generationConfig.has("mediaResolution"))
    assertFalse(setup.has("realtimeInputConfig"))
    assertFalse(setup.has("proactivity"))
    assertTrue(setup.has("inputAudioTranscription"))
    assertTrue(setup.has("contextWindowCompression"))
  }

  @Test
  fun buildGeminiSetupMessage_fullSetupFalseOmitsAllOptionsFields() {
    val options =
        GeminiSetupOptions(
            voiceName = GeminiVoice.AOEDE.apiName,
            vad = VadSensitivity.PATIENT,
            thinkingBudget = 128,
            mediaResolution = "MEDIA_RESOLUTION_MEDIUM",
            proactiveAudio = true,
        )
    val raw =
        AssistantHelpers.buildGeminiSetupMessage(
            model = "gemini-3.8-live",
            instructions = "Test",
            resumeHandle = "handle-ignored",
            fullSetup = false,
            enableGoogleSearch = false,
            enableFunctionTools = false,
            options = options,
        )
    val setup = JSONObject(raw).getJSONObject("setup")
    val generationConfig = setup.getJSONObject("generationConfig")
    assertFalse(generationConfig.has("speechConfig"))
    assertFalse(generationConfig.has("thinkingConfig"))
    assertFalse(generationConfig.has("mediaResolution"))
    assertFalse(setup.has("realtimeInputConfig"))
    assertFalse(setup.has("proactivity"))
    assertFalse(setup.has("inputAudioTranscription"))
    assertFalse(setup.has("outputAudioTranscription"))
    assertFalse(setup.has("contextWindowCompression"))
    assertFalse(setup.has("sessionResumption"))
    assertFalse(setup.has("tools"))
  }

  @Test
  fun createEarconPcm_hasZeroDcOffsetSmoothEnvelopeAndPreRoll() {
    val sampleRate = 24_000
    for (kind in EarconCueKind.entries) {
      val pcm = AssistantHelpers.createEarconPcm(kind = kind, sampleRate = sampleRate, preRollMs = 0)
      assertTrue("PCM should not be empty for $kind", pcm.isNotEmpty())
      assertEquals(0, pcm.size % 2)

      val samples = decodeLe16Mono(pcm)
      assertEquals(0L, samples.sumOf { it.toLong() })
      assertTrue("PCM should contain audio for $kind", samples.any { it.toInt() != 0 })

      // Cosine fade-in/out envelope keeps note boundary samples near zero while peak is loud.
      assertTrue(kotlin.math.abs(samples.first().toInt()) <= 2)
      assertTrue(kotlin.math.abs(samples.last().toInt()) <= 2)
      assertTrue(samples.maxOf { kotlin.math.abs(it.toInt()) } > 2_000)
    }

    val noPreRoll = AssistantHelpers.createEarconPcm(EarconCueKind.PAUSE, sampleRate = sampleRate, preRollMs = 0)
    val withPreRoll =
        AssistantHelpers.createEarconPcm(EarconCueKind.PAUSE, sampleRate = sampleRate, preRollMs = 180)
    val expectedPreRollSamples = 24_000 * 180 / 1000
    assertEquals(noPreRoll.size + expectedPreRollSamples * 2, withPreRoll.size)

    val preRollSamples = decodeLe16Mono(withPreRoll).take(expectedPreRollSamples)
    assertTrue(preRollSamples.all { it.toInt() == 0 })
    assertEquals(0, AssistantHelpers.createEarconPcm(EarconCueKind.START, sampleRate = 0).size)
  }

  @Test
  fun nextReasoningEffortFallback_stepsMinimalToLowToNull() {
    assertEquals("low", AssistantHelpers.nextReasoningEffortFallback("minimal"))
    assertEquals("low", AssistantHelpers.nextReasoningEffortFallback(" MINIMAL "))
    assertNull(AssistantHelpers.nextReasoningEffortFallback("low"))
    assertNull(AssistantHelpers.nextReasoningEffortFallback("medium"))
    assertNull(AssistantHelpers.nextReasoningEffortFallback(null))
  }

  @Test
  fun buildGeminiSetupMessage_includesPhase2ToolsWhenFunctionToolsEnabled() {
    val raw =
        AssistantHelpers.buildGeminiSetupMessage(
            model = "gemini-3.8-live",
            instructions = "Test",
            resumeHandle = null,
            fullSetup = true,
            enableGoogleSearch = false,
            enableFunctionTools = true,
        )
    val tools = JSONObject(raw).getJSONObject("setup").getJSONArray("tools")
    assertEquals(1, tools.length())
    val fnDecls = tools.getJSONObject(0).getJSONArray("functionDeclarations")
    val byName =
        (0 until fnDecls.length()).associate { idx ->
          val obj = fnDecls.getJSONObject(idx)
          obj.getString("name") to obj
        }

    assertTrue("switch_profile" in byName)
    assertTrue("scan_local" in byName)

    val switchParams = byName.getValue("switch_profile").getJSONObject("parameters")
    val profileEnum =
        switchParams.getJSONObject("properties").getJSONObject("profile").getJSONArray("enum")
    assertEquals(listOf("quick", "document", "market", "walk"), (0 until profileEnum.length()).map { profileEnum.getString(it) })
    assertEquals("profile", switchParams.getJSONArray("required").getString(0))

    val scanModeEnum =
        byName
            .getValue("scan_local")
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("mode")
            .getJSONArray("enum")
    assertEquals(listOf("auto", "ocr", "barcode"), (0 until scanModeEnum.length()).map { scanModeEnum.getString(it) })
  }

  @Test
  fun instructions_includeSelectedProfileDirectiveAndLocalContextHint() {
    val docChat =
        AssistantHelpers.buildChatInstructions(
            hasImage = true,
            languageTag = "tr-TR",
            profile = AssistantProfile.DOCUMENT,
            localContextHint = "Barkod: 8690001234567",
        )
    assertTrue(docChat.contains("Belge, Fis ve Ilac"))
    assertTrue(docChat.contains("Yerel cihaz taramasindan alinan ek ipucu: Barkod: 8690001234567"))

    val walkChat =
        AssistantHelpers.buildChatInstructions(
            hasImage = false,
            languageTag = "tr-TR",
            profile = AssistantProfile.WALK,
            localContextHint = "   ",
        )
    assertTrue(walkChat.contains("Mekansal Yuruyus ve Engel"))
    assertFalse(walkChat.contains("Yerel cihaz taramasindan alinan ek ipucu"))

    val marketLive =
        AssistantHelpers.buildLiveInstructions(
            languageTag = "tr-TR",
            enableTools = true,
            profile = AssistantProfile.MARKET,
        )
    assertTrue(marketLive.contains("Market ve Etiket"))
    assertTrue(marketLive.contains("switch_profile"))
    assertTrue(marketLive.contains("scan_local"))

    val liveNoTools =
        AssistantHelpers.buildLiveInstructions(
            languageTag = "tr-TR",
            enableTools = false,
            profile = AssistantProfile.QUICK,
        )
    assertTrue(liveNoTools.contains("Hizli Tarama"))
    assertFalse(liveNoTools.contains("switch_profile"))
    assertFalse(liveNoTools.contains("scan_local"))
  }

  private fun decodeLe16Mono(bytes: ByteArray): List<Short> {
    val out = ArrayList<Short>(bytes.size / 2)
    var idx = 0
    while (idx + 1 < bytes.size) {
      val lo = bytes[idx].toInt() and 0xFF
      val hi = bytes[idx + 1].toInt() shl 8
      out.add((hi or lo).toShort())
      idx += 2
    }
    return out
  }
}
