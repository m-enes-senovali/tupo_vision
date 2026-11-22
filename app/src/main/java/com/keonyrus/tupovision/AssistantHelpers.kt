package com.keonyrus.tupovision

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class AssistantProfile(val id: String, val label: String) {
  QUICK("quick", "Hizli Tarama (Varsayilan)"),
  DOCUMENT("document", "Belge, Fis ve Ilac"),
  MARKET("market", "Market ve Etiket"),
  WALK("walk", "Mekansal Yuruyus ve Engel"),
}

enum class LocalVoiceCommand {
  NONE,
  END_CONVERSATION,
  CAPTURE_HD_PHOTO,
  REPEAT_LAST,
  LOCAL_SCAN,
  SWITCH_PROFILE_QUICK,
  SWITCH_PROFILE_DOCUMENT,
  SWITCH_PROFILE_MARKET,
  SWITCH_PROFILE_WALK,
}

data class OcrBlock(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

enum class LocalBarcodeKind {
  PRODUCT,
  URL,
  WIFI,
  CONTACT,
  TEXT,
}

data class LocalBarcode(
    val formatLabel: String,
    val rawValue: String,
    val kind: LocalBarcodeKind,
    val displayTitle: String = "",
    val extraDetail: String = "",
)

data class FrameQualityReport(
    val meanLuma: Double,
    val sharpnessScore: Double,
    val isTooDark: Boolean,
    val isBlurry: Boolean,
)

enum class GeminiVoice(val apiName: String, val label: String) {
  KORE("Kore", "Kore (Dengeli ve Net)"),
  ZEPHYR("Zephyr", "Zephyr (Aydinlik ve Akici)"),
  ORUS("Orus", "Orus (Tok ve Sakin)"),
  AOEDE("Aoede", "Aoede (Sicak ve Dogal)"),
}

enum class VadSensitivity(
    val label: String,
    val startSensitivity: String,
    val endSensitivity: String,
    val prefixPaddingMs: Int,
    val silenceDurationMs: Int,
) {
  FAST("Hizli Yanit (Kisa Sessizlik)", "START_SENSITIVITY_HIGH", "END_SENSITIVITY_HIGH", 240, 480),
  BALANCED("Dengeli (Onerilen)", "START_SENSITIVITY_HIGH", "END_SENSITIVITY_LOW", 280, 620),
  PATIENT("Sabirli (Uzun Duraksama)", "START_SENSITIVITY_LOW", "END_SENSITIVITY_LOW", 320, 900),
}

enum class ChatSpeedProfile(val label: String) {
  FAST("Hizli Tarama (Dusuk Gecikme)"),
  DETAILED("Detayli Belge (Yuksek Dogruluk)"),
}

data class GeminiSetupOptions(
    val voiceName: String? = GeminiVoice.KORE.apiName,
    val vad: VadSensitivity? = VadSensitivity.BALANCED,
    val thinkingBudget: Int? = 0,
    val mediaResolution: String? = "MEDIA_RESOLUTION_MEDIUM",
    val proactiveAudio: Boolean = false,
)

enum class EarconCueKind {
  START,
  STOP,
  PAUSE,
  PHOTO,
  ERROR,
}

/**
 * Pure Kotlin helpers for Turkish/multilingual voice command parsing, accessible speech cleanup,
 * visually-impaired-oriented system prompts, and Gemini Live setup payloads. Kept Android-free so
 * every rule can be verified directly by JVM unit tests.
 */
object AssistantHelpers {
  val TR_LOCALE: Locale = Locale.forLanguageTag("tr-TR")

  private val END_CONVERSATION_PHRASES =
      listOf(
          "gorusmeyi bitir",
          "görüşmeyi bitir",
          "gorusmeyi kapat",
          "görüşmeyi kapat",
          "sohbeti kapat",
          "sohbeti bitir",
          "konusmayi kapat",
          "konuşmayı kapat",
          "konusmayi bitir",
          "konuşmayı bitir",
          "asistani kapat",
          "asistanı kapat",
          "end conversation",
          "stop conversation",
      )

  private val REPEAT_LAST_PHRASES =
      listOf(
          "son yaniti tekrar oku",
          "son yanıtı tekrar oku",
          "son cevabi tekrar oku",
          "son cevabı tekrar oku",
          "bunu tekrar oku",
          "tekrar oku",
          "tekrar et",
          "ne dedin",
          "repeat last",
          "repeat that",
      )

  private val SWITCH_QUICK_PHRASES =
      listOf(
          "hizli tarama moduna gec",
          "hızlı tarama moduna geç",
          "hizli moda gec",
          "hızlı moda geç",
          "hizli tarama modu",
          "hızlı tarama modu",
          "quick mode",
          "quick profile",
      )

  private val SWITCH_DOCUMENT_PHRASES =
      listOf(
          "belge moduna gec",
          "belge moduna geç",
          "ilac moduna gec",
          "ilaç moduna geç",
          "fis moduna gec",
          "fiş moduna geç",
          "belge modu",
          "document mode",
          "document profile",
      )

  private val SWITCH_MARKET_PHRASES =
      listOf(
          "market moduna gec",
          "market moduna geç",
          "etiket moduna gec",
          "etiket moduna geç",
          "market modu",
          "market mode",
          "market profile",
      )

  private val SWITCH_WALK_PHRASES =
      listOf(
          "yuruyus moduna gec",
          "yürüyüş moduna geç",
          "mekansal moda gec",
          "mekansal moda geç",
          "engel moduna gec",
          "engel moduna geç",
          "yuruyus modu",
          "yürüyüş modu",
          "walk mode",
          "walk profile",
      )

  private val LOCAL_SCAN_PHRASES =
      listOf(
          "cevrimdisi oku",
          "çevrimdışı oku",
          "internetsiz oku",
          "yerel tara",
          "yerel oku",
          "barkod tara",
          "barkodu tara",
          "barkodu oku",
          "barkod oku",
          "karekod tara",
          "karekod oku",
          "karekodu oku",
          "scan local",
          "scan barcode",
          "offline read",
      )

  private val HD_PHOTO_PHRASES =
      listOf(
          "yakindan oku",
          "yakından oku",
          "net fotograf cek",
          "net fotoğraf çek",
          "fotograf cek",
          "fotoğraf çek",
          "etiketi oku",
          "yaziyi oku",
          "yazıyı oku",
          "son kullanma tarihini oku",
          "take a photo",
          "read closely",
      )

  fun localeForTag(languageTag: String): Locale {
    val clean = languageTag.trim()
    if (clean.isEmpty()) return TR_LOCALE
    val parsed = Locale.forLanguageTag(clean)
    return if (parsed.language.isBlank()) TR_LOCALE else parsed
  }

  fun normalizeForMatch(text: String, locale: Locale = TR_LOCALE): String =
      text.lowercase(locale).replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()

  /**
   * Extracts the user's question after [wakeWord] using locale-aware case folding (safe for Turkish
   * dotted/dotless I). Returns null if the wake word is present without a follow-up question or not
   * present at all. When [wakeWord] is blank, returns trimmed [heard].
   */
  fun extractAfterWakeWord(heard: String, wakeWord: String, languageTag: String): String? {
    val cleanHeard = heard.trim()
    val cleanWake = wakeWord.trim()
    if (cleanWake.isEmpty()) return cleanHeard.ifEmpty { null }
    val locale = localeForTag(languageTag)
    val lowerHeard = cleanHeard.lowercase(locale)
    val lowerWake = cleanWake.lowercase(locale)
    val index = lowerHeard.indexOf(lowerWake)
    if (index < 0) return null
    val afterIndex =
        if (lowerHeard.length == cleanHeard.length) {
          (index + lowerWake.length).coerceAtMost(cleanHeard.length)
        } else {
          // Fallback if Unicode case folding changed string length.
          val prefixWords = lowerHeard.substring(0, index + lowerWake.length).trim().split(Regex("\\s+")).size
          val originalWords = cleanHeard.split(Regex("\\s+"))
          val remaining = originalWords.drop(prefixWords).joinToString(" ").trim()
          return remaining.trimStart(',', '.', '!', '?', ':', ';', ' ').ifEmpty { null }
        }
    val question = cleanHeard.substring(afterIndex).trim().trimStart(',', '.', '!', '?', ':', ';', ' ')
    return question.ifEmpty { null }
  }

  fun detectLocalCommand(text: String, languageTag: String): LocalVoiceCommand {
    val normalized = normalizeForMatch(text, localeForTag(languageTag))
    if (normalized.isEmpty()) return LocalVoiceCommand.NONE
    if (END_CONVERSATION_PHRASES.any { containsPhrase(normalized, it) }) {
      return LocalVoiceCommand.END_CONVERSATION
    }
    if (REPEAT_LAST_PHRASES.any { containsPhrase(normalized, it) }) {
      return LocalVoiceCommand.REPEAT_LAST
    }
    if (SWITCH_QUICK_PHRASES.any { containsPhrase(normalized, it) }) {
      return LocalVoiceCommand.SWITCH_PROFILE_QUICK
    }
    if (SWITCH_DOCUMENT_PHRASES.any { containsPhrase(normalized, it) }) {
      return LocalVoiceCommand.SWITCH_PROFILE_DOCUMENT
    }
    if (SWITCH_MARKET_PHRASES.any { containsPhrase(normalized, it) }) {
      return LocalVoiceCommand.SWITCH_PROFILE_MARKET
    }
    if (SWITCH_WALK_PHRASES.any { containsPhrase(normalized, it) }) {
      return LocalVoiceCommand.SWITCH_PROFILE_WALK
    }
    if (LOCAL_SCAN_PHRASES.any { containsPhrase(normalized, it) }) {
      return LocalVoiceCommand.LOCAL_SCAN
    }
    if (HD_PHOTO_PHRASES.any { containsPhrase(normalized, it) }) {
      return LocalVoiceCommand.CAPTURE_HD_PHOTO
    }
    return LocalVoiceCommand.NONE
  }

  private fun containsPhrase(normalizedText: String, phrase: String): Boolean {
    if (normalizedText == phrase) return true
    val padded = " $normalizedText "
    return padded.contains(" $phrase ")
  }

  fun cleanForSpeech(text: String): String =
      text.replace(Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)"), "$1")
          .replace(Regex("https?://\\S+"), "")
          .replace(Regex("[*#`_>|]"), "")
          .replace(Regex("(^|\\n)\\s*[-•+]\\s+"), "$1")
          .replace(Regex("\\s+"), " ")
          .trim()

  fun spokenError(languageTag: String, limit: Boolean): String {
    val lang = localeForTag(languageTag).language.lowercase(Locale.US)
    return when {
      limit && lang == "tr" -> "Abonelik kullanım limitine ulaşıldı."
      lang == "tr" -> "İstek başarısız oldu, lütfen tekrar deneyin."
      limit && lang == "ru" -> "Лимит подписки исчерпан."
      lang == "ru" -> "Ошибка запроса."
      limit -> "Plan limit reached."
      else -> "Request failed."
    }
  }

  private fun languageDisplayName(languageTag: String): String {
    val locale = localeForTag(languageTag)
    return when (locale.language.lowercase(Locale.US)) {
      "tr" -> "Türkçe ($languageTag)"
      "ru" -> "Rusça ($languageTag)"
      "en" -> "İngilizce ($languageTag)"
      else -> locale.getDisplayLanguage(TR_LOCALE).ifBlank { languageTag }
    }
  }

  fun profileDirective(profile: AssistantProfile): String =
      when (profile) {
        AssistantProfile.QUICK ->
            "Aktif profil: Hizli Tarama. Kullanicinin sorusunu 1 ila 2 kisa ve net cumlede yanitla; sorulmadikca gereksiz cevre betimlemesi yapma."
        AssistantProfile.DOCUMENT ->
            "Aktif profil: Belge, Fis ve Ilac. Metni kesinlikle ozetleme; ilac adi, etken madde, dozaj, kullanim talimati, son kullanma tarihi, fiyat ve toplam tutar gibi kritik satirlari eksiksiz ve sirayla oku."
        AssistantProfile.MARKET ->
            "Aktif profil: Market ve Etiket. Urun adini, markayi, gramaj veya hacmi, icindekiler/alerjen uyarisini, fiyati ve varsa son kullanma tarihini one cikar; varsa yerel barkod bilgisini temel al."
        AssistantProfile.WALK ->
            "Aktif profil: Mekansal Yuruyus ve Engel. Engel, basamak, kaldirim, kapi, arac veya insanlari saat yonu (ornegin saat 12, saat 2) ve adim veya metre cinsinden tahmini mesafeyle bildir; guvenlik risklerini en basta soyle."
      }

  fun parseProfileByIdOrName(raw: String): AssistantProfile? {
    val norm = normalizeForMatch(raw, TR_LOCALE)
    if (norm.isEmpty()) return null
    AssistantProfile.entries.firstOrNull { it.id == norm }?.let { return it }
    return when {
      norm.contains("quick") || norm.contains("hizli") || norm.contains("hızlı") || norm.contains("genel") ->
          AssistantProfile.QUICK
      norm.contains("document") ||
          norm.contains("belge") ||
          norm.contains("ilac") ||
          norm.contains("ilaç") ||
          norm.contains("fis") ||
          norm.contains("fiş") -> AssistantProfile.DOCUMENT
      norm.contains("market") || norm.contains("etiket") || norm.contains("urun") || norm.contains("ürün") ->
          AssistantProfile.MARKET
      norm.contains("walk") ||
          norm.contains("yuruyus") ||
          norm.contains("yürüyüş") ||
          norm.contains("mekansal") ||
          norm.contains("engel") -> AssistantProfile.WALK
      else -> null
    }
  }

  fun buildChatInstructions(
      hasImage: Boolean,
      languageTag: String,
      profile: AssistantProfile = AssistantProfile.QUICK,
      localContextHint: String? = null,
  ): String {
    val langName = languageDisplayName(languageTag)
    val picture =
        if (hasImage) {
          "Soruya gözlük kamerasından alınan güncel bir kare eklendi; kullanıcının şu an tam önünde olan görüntüyü temsil eder. " +
              "Önündeki sahne sorulduğunda bu kareyi temel al, sorulmadıkça çevreyi gereksiz betimleme."
        } else {
          "Şu an gözlük kamerasından görüntü karesi yok. Kullanıcı önündeki nesne veya metin hakkında soru sorarsa göremediğini açıkça belirt."
        }
    val hint =
        localContextHint?.trim()?.takeIf { it.isNotEmpty() }?.let {
          " Yerel cihaz taramasindan alinan ek ipucu: $it"
        }.orEmpty()
    return "Sen akıllı gözlük üzerinden görme engelli bir kullanıcıya yardımcı olan sesli asistansın. " +
        "Yanıtların yüksek sesle okunacak. Varsayılan yanıt dilin $langName; kullanıcı açıkça başka dil istemedikçe bu dilde yanıt ver. " +
        "Konum, yön veya engel tarif ederken saat yönünü (örneğin saat 2 yönünde) ve yaklaşık mesafeyi (adım veya metre) kullan. " +
        "Belge, ilaç kutusu, etiket, fiş, fatura veya ekran metni okunması istendiğinde özet geçme; son kullanma tarihi, dozaj, tutar, marka ve kritik satırları eksiksiz oku. " +
        "Görüntü bulanık, karanlık veya kadraj dışıysa kullanıcıya kamerayı nasıl düzeltmesi gerektiğini nazikçe söyle (örneğin biraz sola çevir veya bir adım uzaklaştır). " +
        "Metin okuma dışındaki normal sorularda kısa ve net ol (1 ila 3 cümle). Liste işareti, markdown, emoji veya ham bağlantı kullanma. " +
        profileDirective(profile) +
        " " +
        picture +
        hint
  }

  fun buildLiveInstructions(
      languageTag: String,
      enableTools: Boolean,
      profile: AssistantProfile = AssistantProfile.QUICK,
  ): String {
    val langName = languageDisplayName(languageTag)
    val toolGuide =
        if (enableTools) {
          " Kullanıcı küçük yazı, ilaç etiketi, son kullanma tarihi, fiş veya net fotoğraf okutmak istediğinde ya da video karesi bulanık olduğunda mutlaka capture_hd_photo aracını çağır ve gelen yüksek çözünürlüklü kareyi eksiksiz oku. " +
              "Kullanıcı mod değiştirmek istediğinde (hızlı tarama, belge/ilaç, market/etiket, yürüyüş/engel) switch_profile aracını, çevrimdışı yerel OCR veya barkod okumak istediğinde scan_local aracını çağır. " +
              "Kullanıcı görüşmeyi bitirmek veya asistanı kapatmak istediğini açıkça söylediğinde end_conversation aracını çağır."
        } else {
          ""
        }
    return "Sen akıllı gözlük üzerinden görme engelli bir kullanıcıya canlı eşlik eden sesli asistansın. " +
        "Kullanıcıyı dinliyor ve gözlüğün kamerasından gelen canlı görüntüyü görüyorsun. " +
        "Varsayılan dilin $langName; kullanıcı açıkça başka bir dile geçmedikçe her zaman bu dilde konuş. " +
        "Doğal, akıcı ve kısa konuş (normal sorularda 1 ila 3 cümle); metin/etiket/belge okuman istendiğinde ise özetlemeden, son kullanma tarihi, dozaj ve tutar gibi kritik detayları eksiksiz oku. " +
        "Nesne veya engel konumu sorulduğunda saat yönünü (örneğin saat 12 tam karşı, saat 3 sağ taraf) ve tahmini mesafeyi belirt. " +
        "Görüntü bulanık, kesik veya okunaksızsa uydurma; kullanıcıyı kısa kadraj yönlendirmesiyle (biraz sağa çevir, biraz uzaklaştır) yönlendir. " +
        "Yalnızca sana yöneltilen konuşmalara yanıt ver; emoji, markdown veya ham URL okuma. " +
        profileDirective(profile) +
        toolGuide
  }

  fun buildGeminiSetupMessage(
      model: String,
      instructions: String,
      resumeHandle: String?,
      fullSetup: Boolean,
      enableGoogleSearch: Boolean,
      enableFunctionTools: Boolean,
      options: GeminiSetupOptions = GeminiSetupOptions(),
  ): String {
    val generationConfig = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
    if (fullSetup) {
      val voice = options.voiceName?.trim()?.takeIf { it.isNotEmpty() }
      if (voice != null) {
        generationConfig.put(
            "speechConfig",
            JSONObject()
                .put(
                    "voiceConfig",
                    JSONObject()
                        .put(
                            "prebuiltVoiceConfig",
                            JSONObject().put("voiceName", voice),
                        ),
                ),
        )
      }
      if (options.thinkingBudget != null) {
        generationConfig.put(
            "thinkingConfig",
            JSONObject().put("thinkingBudget", options.thinkingBudget),
        )
      }
      val mediaRes = options.mediaResolution?.trim()?.takeIf { it.isNotEmpty() }
      if (mediaRes != null) {
        generationConfig.put("mediaResolution", mediaRes)
      }
    }

    val setup =
        JSONObject()
            .put("model", "models/${model.trim().ifEmpty { GeminiLive.DEFAULT_MODEL }}")
            .put("generationConfig", generationConfig)
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instructions))),
            )

    if (fullSetup) {
      setup
          .put("inputAudioTranscription", JSONObject())
          .put("outputAudioTranscription", JSONObject())
          // Video iceren canli oturumlarin 2 dakika sinirina takilmamasi icin kayan pencere sikistirmasi.
          .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
          .put(
              "sessionResumption",
              if (resumeHandle != null) JSONObject().put("handle", resumeHandle) else JSONObject(),
          )
      val vad = options.vad
      if (vad != null) {
        setup.put(
            "realtimeInputConfig",
            JSONObject()
                .put(
                    "automaticActivityDetection",
                    JSONObject()
                        .put("disabled", false)
                        .put("startOfSpeechSensitivity", vad.startSensitivity)
                        .put("endOfSpeechSensitivity", vad.endSensitivity)
                        .put("prefixPaddingMs", vad.prefixPaddingMs)
                        .put("silenceDurationMs", vad.silenceDurationMs),
                ),
        )
      }
      if (options.proactiveAudio) {
        setup.put("proactivity", JSONObject().put("proactiveAudio", true))
      }
    }

    val tools = JSONArray()
    if (enableGoogleSearch) {
      tools.put(JSONObject().put("googleSearch", JSONObject()))
    }
    if (enableFunctionTools) {
      val declarations =
          JSONArray()
              .put(
                  JSONObject()
                      .put("name", "capture_hd_photo")
                      .put(
                          "description",
                          "Gozluk kamerasindan yuksek cozunurluklu tek bir fotograf ceker. Kucuk yazilar, ilac kutulari, son kullanma tarihleri, fisler veya bulanik sahneler icin kullanilir.",
                      )
                      .put(
                          "parameters",
                          JSONObject()
                              .put("type", "OBJECT")
                              .put(
                                  "properties",
                                  JSONObject()
                                      .put(
                                          "reason",
                                          JSONObject()
                                              .put("type", "STRING")
                                              .put("description", "Fotografin neden cekildigi"),
                                      ),
                              ),
                      ),
              )
              .put(
                  JSONObject()
                      .put("name", "end_conversation")
                      .put(
                          "description",
                          "Kullanici gorusmeyi bitirmek veya asistani kapatmak istediginde canli oturumu sonlandirir.",
                      )
                      .put(
                          "parameters",
                          JSONObject()
                              .put("type", "OBJECT")
                              .put(
                                  "properties",
                                  JSONObject()
                                      .put(
                                          "reason",
                                          JSONObject()
                                              .put("type", "STRING")
                                              .put("description", "Kapatma nedeni"),
                                      ),
                              ),
                      ),
              )
              .put(
                  JSONObject()
                      .put("name", "switch_profile")
                      .put(
                          "description",
                          "Kullanici mod veya uzman profil degistirmek istediginde aktif asistan profilini gunceller.",
                      )
                      .put(
                          "parameters",
                          JSONObject()
                              .put("type", "OBJECT")
                              .put(
                                  "properties",
                                  JSONObject()
                                      .put(
                                          "profile",
                                          JSONObject()
                                              .put("type", "STRING")
                                              .put(
                                                  "enum",
                                                  JSONArray()
                                                      .put("quick")
                                                      .put("document")
                                                      .put("market")
                                                      .put("walk"),
                                              )
                                              .put(
                                                  "description",
                                                  "Hedef profil kimligi: quick, document, market veya walk",
                                              ),
                                      ),
                              )
                              .put("required", JSONArray().put("profile")),
                      ),
              )
              .put(
                  JSONObject()
                      .put("name", "scan_local")
                      .put(
                          "description",
                          "Gozluk kamerasindaki mevcut kareden cihaz ici cevrimdisi OCR metin okuma ve barkod/QR taramasi yapar.",
                      )
                      .put(
                          "parameters",
                          JSONObject()
                              .put("type", "OBJECT")
                              .put(
                                  "properties",
                                  JSONObject()
                                      .put(
                                          "mode",
                                          JSONObject()
                                              .put("type", "STRING")
                                              .put(
                                                  "enum",
                                                  JSONArray()
                                                      .put("auto")
                                                      .put("ocr")
                                                      .put("barcode"),
                                              )
                                              .put(
                                                  "description",
                                                  "Tarama modu: auto, ocr veya barcode",
                                              ),
                                      ),
                              ),
                      ),
              )
      tools.put(JSONObject().put("functionDeclarations", declarations))
    }
    if (tools.length() > 0) {
      setup.put("tools", tools)
    }

    return JSONObject().put("setup", setup).toString()
  }

  /**
   * Builds a stereo 16-bit PCM buffer (~ -84 dBFS alternating +/-amplitude, zero DC offset) so
   * Bluetooth A2DP decoders and offload timers stay awake without audible output.
   */
  fun createA2dpKeepAlivePcm(frameCount: Int, amplitude: Short = 2): ByteArray {
    if (frameCount <= 0) return ByteArray(0)
    val bytes = ByteArray(frameCount * 4)
    val mag = kotlin.math.abs(amplitude.toInt())
    for (i in 0 until frameCount) {
      val sample = if (i % 2 == 0) mag else -mag
      val lo = (sample and 0xFF).toByte()
      val hi = ((sample ushr 8) and 0xFF).toByte()
      val base = i * 4
      bytes[base] = lo
      bytes[base + 1] = hi
      bytes[base + 2] = lo
      bytes[base + 3] = hi
    }
    return bytes
  }

  private data class EarconSegment(val freqHz: Double, val durationMs: Int)

  /**
   * Generates a 16-bit mono signed little-endian PCM earcon with cosine fade envelopes (>= 8 ms)
   * per note, zero DC offset, and optional silent pre-roll to wake A2DP DACs without clipping.
   */
  fun createEarconPcm(
      kind: EarconCueKind,
      sampleRate: Int = 24_000,
      preRollMs: Int = 0,
  ): ByteArray {
    if (sampleRate <= 0) return ByteArray(0)
    val segments =
        when (kind) {
          EarconCueKind.START ->
              listOf(EarconSegment(523.25, 70), EarconSegment(0.0, 20), EarconSegment(659.25, 90))
          EarconCueKind.STOP ->
              listOf(EarconSegment(659.25, 70), EarconSegment(0.0, 20), EarconSegment(440.0, 90))
          EarconCueKind.PAUSE -> listOf(EarconSegment(493.88, 90))
          EarconCueKind.PHOTO ->
              listOf(EarconSegment(880.0, 40), EarconSegment(0.0, 25), EarconSegment(1046.5, 50))
          EarconCueKind.ERROR ->
              listOf(EarconSegment(330.0, 90), EarconSegment(0.0, 30), EarconSegment(261.63, 120))
        }
    val preRollSamples = (sampleRate.toLong() * preRollMs.coerceAtLeast(0) / 1000L).toInt()
    val segmentCounts = segments.map { (sampleRate.toLong() * it.durationMs / 1000L).toInt().coerceAtLeast(0) }
    val totalSamples = preRollSamples + segmentCounts.sum()
    val samples = ShortArray(totalSamples)
    val peakAmplitude = 7_200.0
    val targetFadeSamples = maxOf(1, (sampleRate.toLong() * 10L / 1000L).toInt())

    var cursor = preRollSamples
    for (idx in segments.indices) {
      val seg = segments[idx]
      val count = segmentCounts[idx]
      if (count <= 0) continue
      if (seg.freqHz > 0.0) {
        val fade = minOf(count / 2, targetFadeSamples)
        val raw = DoubleArray(count)
        var rawSum = 0.0
        for (i in 0 until count) {
          val env =
              when {
                fade > 0 && i < fade -> 0.5 * (1.0 - kotlin.math.cos(kotlin.math.PI * i / fade))
                fade > 0 && i >= count - fade ->
                    0.5 * (1.0 - kotlin.math.cos(kotlin.math.PI * (count - 1 - i) / fade))
                else -> 1.0
              }
          val value =
              peakAmplitude * env * kotlin.math.sin(2.0 * kotlin.math.PI * seg.freqHz * i / sampleRate)
          raw[i] = value
          rawSum += value
        }
        val dc = rawSum / count
        var sum = 0L
        for (i in 0 until count) {
          val s = kotlin.math.round(raw[i] - dc).toInt().coerceIn(-32767, 32767)
          samples[cursor + i] = s.toShort()
          sum += s
        }
        // Balance any remaining 1-LSB rounding residual inside the sustain window.
        val adjustStart = if (count > fade * 2) fade else 0
        val adjustEnd = if (count > fade * 2) count - fade else count
        var pos = adjustStart
        while (sum != 0L && adjustStart < adjustEnd) {
          val step = if (sum > 0L) -1 else 1
          val cur = samples[cursor + pos].toInt()
          if (cur + step in -32767..32767) {
            samples[cursor + pos] = (cur + step).toShort()
            sum += step
          }
          pos += 1
          if (pos >= adjustEnd) pos = adjustStart
        }
      }
      cursor += count
    }

    val bytes = ByteArray(totalSamples * 2)
    for (i in samples.indices) {
      val v = samples[i].toInt()
      bytes[i * 2] = (v and 0xFF).toByte()
      bytes[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
    }
    return bytes
  }

  fun nextReasoningEffortFallback(currentEffort: String?): String? =
      when (currentEffort?.trim()?.lowercase(Locale.US)) {
        "minimal" -> "low"
        "low" -> null
        else -> null
      }

  /**
   * Orders OCR blocks from top to bottom and left to right within row height tolerance so
   * multi-column receipts, medicine boxes, and labels read naturally.
   */
  fun orderOcrBlocks(blocks: List<OcrBlock>): List<String> {
    val clean =
        blocks
            .mapNotNull { block ->
              val text = block.text.trim().replace(Regex("\\s+"), " ")
              if (text.isEmpty()) null else block.copy(text = text)
            }
            .sortedWith(compareBy<OcrBlock> { it.top }.thenBy { it.left })
    if (clean.isEmpty()) return emptyList()

    val rows = ArrayList<MutableList<OcrBlock>>()
    for (block in clean) {
      val centerY = (block.top + block.bottom) / 2.0
      val height = (block.bottom - block.top).coerceAtLeast(1).toDouble()
      val lastRow = rows.lastOrNull()
      if (lastRow == null) {
        rows.add(mutableListOf(block))
      } else {
        val rowCenterY = lastRow.map { (it.top + it.bottom) / 2.0 }.average()
        val rowHeight = lastRow.map { (it.bottom - it.top).coerceAtLeast(1).toDouble() }.average()
        val tolerance = maxOf(rowHeight, height) * 0.55
        if (kotlin.math.abs(centerY - rowCenterY) <= tolerance) {
          lastRow.add(block)
        } else {
          rows.add(mutableListOf(block))
        }
      }
    }

    val ordered = ArrayList<String>(clean.size)
    for (row in rows) {
      row.sortWith(compareBy<OcrBlock> { it.left }.thenBy { it.top })
      for (block in row) {
        ordered.add(block.text)
      }
    }
    return ordered
  }

  fun formatBarcodesForSpeech(barcodes: List<LocalBarcode>, languageTag: String = "tr-TR"): String {
    if (barcodes.isEmpty()) return ""
    val isEnglish = localeForTag(languageTag).language.lowercase(Locale.US) == "en"
    val parts =
        barcodes.mapNotNull { barcode ->
          val title = barcode.displayTitle.trim()
          val detail = barcode.extraDetail.trim()
          val raw = barcode.rawValue.trim()
          val format = barcode.formatLabel.trim().ifEmpty { if (isEnglish) "Barcode" else "Barkod" }
          val primary = title.ifEmpty { raw }
          if (primary.isEmpty() && detail.isEmpty()) return@mapNotNull null

          if (isEnglish) {
            when (barcode.kind) {
              LocalBarcodeKind.PRODUCT ->
                  if (title.isNotEmpty() && raw.isNotEmpty() && title != raw) {
                    "$format product: $title ($raw)"
                  } else {
                    "$format barcode: $primary"
                  }
              LocalBarcodeKind.URL -> "QR link: $primary"
              LocalBarcodeKind.WIFI ->
                  if (detail.isNotEmpty()) "Wi-Fi network: $primary, $detail" else "Wi-Fi network: $primary"
              LocalBarcodeKind.CONTACT ->
                  if (detail.isNotEmpty()) "Contact card: $primary, $detail" else "Contact card: $primary"
              LocalBarcodeKind.TEXT -> "$format text: $primary"
            }
          } else {
            when (barcode.kind) {
              LocalBarcodeKind.PRODUCT ->
                  if (title.isNotEmpty() && raw.isNotEmpty() && title != raw) {
                    "$format urun kodu: $title ($raw)"
                  } else {
                    "$format barkod: $primary"
                  }
              LocalBarcodeKind.URL -> "Karekod baglantisi: $primary"
              LocalBarcodeKind.WIFI ->
                  if (detail.isNotEmpty()) "Wi-Fi agi: $primary, $detail" else "Wi-Fi agi: $primary"
              LocalBarcodeKind.CONTACT ->
                  if (detail.isNotEmpty()) "Kisi karti: $primary, $detail" else "Kisi karti: $primary"
              LocalBarcodeKind.TEXT -> "$format icerigi: $primary"
            }
          }
        }
    return parts.joinToString(". ")
  }

  fun formatLocalScanResult(
      orderedTextLines: List<String>,
      barcodes: List<LocalBarcode>,
      languageTag: String = "tr-TR",
  ): String? {
    val cleanLines = orderedTextLines.map { it.trim() }.filter { it.isNotEmpty() }
    val barcodeSpeech = formatBarcodesForSpeech(barcodes, languageTag).trim()
    if (cleanLines.isEmpty() && barcodeSpeech.isEmpty()) return null
    val isEnglish = localeForTag(languageTag).language.lowercase(Locale.US) == "en"
    val textBody = cleanLines.joinToString(". ")
    return when {
      barcodeSpeech.isNotEmpty() && textBody.isNotEmpty() ->
          if (isEnglish) "Barcode result: $barcodeSpeech. Recognized text: $textBody"
          else "Barkod sonucu: $barcodeSpeech. Okunan metin: $textBody"
      barcodeSpeech.isNotEmpty() -> barcodeSpeech
      else -> textBody
    }
  }

  fun evaluateFrameQualityFromLuma(
      lumaSamples: IntArray,
      width: Int,
      height: Int,
  ): FrameQualityReport {
    if (width <= 0 || height <= 0 || lumaSamples.isEmpty()) {
      return FrameQualityReport(meanLuma = 0.0, sharpnessScore = 0.0, isTooDark = true, isBlurry = true)
    }
    val count = minOf(lumaSamples.size, width * height)
    var sum = 0.0
    for (i in 0 until count) {
      sum += lumaSamples[i].coerceIn(0, 255)
    }
    val meanLuma = sum / count.toDouble()
    val isTooDark = meanLuma < 18.0

    val sharpnessScore =
        if (width >= 3 && height >= 3 && lumaSamples.size >= width * height) {
          var lapEnergy = 0.0
          var innerCount = 0
          for (y in 1 until height - 1) {
            val rowOffset = y * width
            for (x in 1 until width - 1) {
              val idx = rowOffset + x
              val center = lumaSamples[idx]
              val lap =
                  4 * center -
                      lumaSamples[idx - 1] -
                      lumaSamples[idx + 1] -
                      lumaSamples[idx - width] -
                      lumaSamples[idx + width]
              lapEnergy += lap.toDouble() * lap.toDouble()
              innerCount += 1
            }
          }
          if (innerCount > 0) lapEnergy / innerCount.toDouble() else 0.0
        } else if (count >= 2) {
          var diffEnergy = 0.0
          for (i in 1 until count) {
            val d = lumaSamples[i] - lumaSamples[i - 1]
            diffEnergy += d.toDouble() * d.toDouble()
          }
          diffEnergy / (count - 1).toDouble()
        } else {
          0.0
        }

    val isBlurry = sharpnessScore < 45.0
    return FrameQualityReport(
        meanLuma = meanLuma,
        sharpnessScore = sharpnessScore,
        isTooDark = isTooDark,
        isBlurry = isBlurry,
    )
  }
}
