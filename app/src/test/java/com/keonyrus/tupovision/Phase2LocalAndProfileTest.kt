package com.keonyrus.tupovision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2LocalAndProfileTest {

  @Test
  fun detectLocalCommand_recognizesPhase2CommandsAndEnforcesPriority() {
    assertEquals(
        LocalVoiceCommand.REPEAT_LAST,
        AssistantHelpers.detectLocalCommand("Son yanıtı tekrar oku lütfen", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.REPEAT_LAST,
        AssistantHelpers.detectLocalCommand("Ne dedin?", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.SWITCH_PROFILE_DOCUMENT,
        AssistantHelpers.detectLocalCommand("Belge moduna geç", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.SWITCH_PROFILE_MARKET,
        AssistantHelpers.detectLocalCommand("Market moduna geç", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.SWITCH_PROFILE_WALK,
        AssistantHelpers.detectLocalCommand("Yürüyüş moduna geç", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.SWITCH_PROFILE_QUICK,
        AssistantHelpers.detectLocalCommand("Hızlı tarama moduna geç", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.LOCAL_SCAN,
        AssistantHelpers.detectLocalCommand("Lütfen barkodu oku", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.LOCAL_SCAN,
        AssistantHelpers.detectLocalCommand("Çevrimdışı oku", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.CAPTURE_HD_PHOTO,
        AssistantHelpers.detectLocalCommand("Etiketi oku", "tr-TR"),
    )

    // Priority order: END_CONVERSATION > REPEAT_LAST > SWITCH_PROFILE_* > LOCAL_SCAN > CAPTURE_HD_PHOTO
    assertEquals(
        LocalVoiceCommand.END_CONVERSATION,
        AssistantHelpers.detectLocalCommand("Tekrar oku değil sohbeti kapat", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.REPEAT_LAST,
        AssistantHelpers.detectLocalCommand("Belge moduna geçmeden son yanıtı tekrar oku", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.SWITCH_PROFILE_DOCUMENT,
        AssistantHelpers.detectLocalCommand("Belge moduna geç ve etiketi oku", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.SWITCH_PROFILE_MARKET,
        AssistantHelpers.detectLocalCommand("Market moduna geç barkodu oku", "tr-TR"),
    )
    assertEquals(
        LocalVoiceCommand.LOCAL_SCAN,
        AssistantHelpers.detectLocalCommand("Barkodu oku ve etiketi oku", "tr-TR"),
    )
  }

  @Test
  fun parseProfileByIdOrName_mapsIdsTurkishAndEnglishAliases() {
    assertEquals(AssistantProfile.QUICK, AssistantHelpers.parseProfileByIdOrName("quick"))
    assertEquals(AssistantProfile.QUICK, AssistantHelpers.parseProfileByIdOrName("Hızlı tarama"))
    assertEquals(AssistantProfile.DOCUMENT, AssistantHelpers.parseProfileByIdOrName("document"))
    assertEquals(AssistantProfile.DOCUMENT, AssistantHelpers.parseProfileByIdOrName("İlaç ve Fiş"))
    assertEquals(AssistantProfile.MARKET, AssistantHelpers.parseProfileByIdOrName("market"))
    assertEquals(AssistantProfile.MARKET, AssistantHelpers.parseProfileByIdOrName("Ürün etiketi"))
    assertEquals(AssistantProfile.WALK, AssistantHelpers.parseProfileByIdOrName("walk"))
    assertEquals(AssistantProfile.WALK, AssistantHelpers.parseProfileByIdOrName("Yürüyüş engel"))
    assertNull(AssistantHelpers.parseProfileByIdOrName(""))
    assertNull(AssistantHelpers.parseProfileByIdOrName("bilinmeyen"))
  }

  @Test
  fun orderOcrBlocks_sortsMultiColumnRowsTopToBottomLeftToRight() {
    val shuffled =
        listOf(
            OcrBlock(text = "45,90 TL", left = 320, top = 82, right = 440, bottom = 112),
            OcrBlock(text = "  Sut   1L ", left = 20, top = 80, right = 180, bottom = 110),
            OcrBlock(text = "TOPLAM", left = 20, top = 160, right = 140, bottom = 190),
            OcrBlock(text = "MARKET   FISI", left = 40, top = 12, right = 260, bottom = 42),
            OcrBlock(text = "   ", left = 0, top = 0, right = 10, bottom = 10),
            OcrBlock(text = "145,90 TL", left = 310, top = 162, right = 445, bottom = 192),
            OcrBlock(text = "EKMEK", left = 22, top = 118, right = 120, bottom = 148),
            OcrBlock(text = "15,00 TL", left = 325, top = 120, right = 440, bottom = 150),
        )

    val ordered = AssistantHelpers.orderOcrBlocks(shuffled)
    assertEquals(
        listOf(
            "MARKET FISI",
            "Sut 1L",
            "45,90 TL",
            "EKMEK",
            "15,00 TL",
            "TOPLAM",
            "145,90 TL",
        ),
        ordered,
    )
    assertEquals(emptyList<String>(), AssistantHelpers.orderOcrBlocks(emptyList()))
  }

  @Test
  fun formatBarcodesForSpeech_formatsAllKindsInTurkishAndEnglish() {
    val barcodes =
        listOf(
            LocalBarcode(
                formatLabel = "EAN-13",
                rawValue = "8690504012345",
                kind = LocalBarcodeKind.PRODUCT,
                displayTitle = "Sut",
            ),
            LocalBarcode(
                formatLabel = "QR",
                rawValue = "https://ornek.com/menu",
                kind = LocalBarcodeKind.URL,
                displayTitle = "https://ornek.com/menu",
            ),
            LocalBarcode(
                formatLabel = "QR",
                rawValue = "WIFI:S:EvAgi;;",
                kind = LocalBarcodeKind.WIFI,
                displayTitle = "EvAgi",
                extraDetail = "WPA",
            ),
            LocalBarcode(
                formatLabel = "QR",
                rawValue = "BEGIN:VCARD...",
                kind = LocalBarcodeKind.CONTACT,
                displayTitle = "Ahmet Yilmaz",
                extraDetail = "05551112233",
            ),
            LocalBarcode(
                formatLabel = "QR",
                rawValue = "Seri no 42",
                kind = LocalBarcodeKind.TEXT,
            ),
        )

    val trSpeech = AssistantHelpers.formatBarcodesForSpeech(barcodes, "tr-TR")
    assertTrue(trSpeech.contains("EAN-13 urun kodu: Sut (8690504012345)"))
    assertTrue(trSpeech.contains("Karekod baglantisi: https://ornek.com/menu"))
    assertTrue(trSpeech.contains("Wi-Fi agi: EvAgi, WPA"))
    assertTrue(trSpeech.contains("Kisi karti: Ahmet Yilmaz, 05551112233"))
    assertTrue(trSpeech.contains("QR icerigi: Seri no 42"))

    val enSpeech = AssistantHelpers.formatBarcodesForSpeech(barcodes, "en-US")
    assertTrue(enSpeech.contains("EAN-13 product: Sut (8690504012345)"))
    assertTrue(enSpeech.contains("QR link: https://ornek.com/menu"))
    assertTrue(enSpeech.contains("Wi-Fi network: EvAgi, WPA"))
    assertTrue(enSpeech.contains("Contact card: Ahmet Yilmaz, 05551112233"))
    assertTrue(enSpeech.contains("QR text: Seri no 42"))

    assertEquals("", AssistantHelpers.formatBarcodesForSpeech(emptyList(), "tr-TR"))
  }

  @Test
  fun formatLocalScanResult_handlesEmptyTextOnlyBarcodeOnlyAndCombined() {
    assertNull(AssistantHelpers.formatLocalScanResult(emptyList(), emptyList(), "tr-TR"))
    assertNull(AssistantHelpers.formatLocalScanResult(listOf("   ", ""), emptyList(), "tr-TR"))

    val textOnly =
        AssistantHelpers.formatLocalScanResult(listOf("Parol 500 mg", "SKT 10/2027"), emptyList(), "tr-TR")
    assertEquals("Parol 500 mg. SKT 10/2027", textOnly)

    val barcodeOnly =
        AssistantHelpers.formatLocalScanResult(
            emptyList(),
            listOf(LocalBarcode("EAN-13", "8690001112223", LocalBarcodeKind.PRODUCT)),
            "tr-TR",
        )
    assertEquals("EAN-13 barkod: 8690001112223", barcodeOnly)

    val combinedTr =
        AssistantHelpers.formatLocalScanResult(
            listOf("Parol 500 mg"),
            listOf(LocalBarcode("EAN-13", "8690001112223", LocalBarcodeKind.PRODUCT)),
            "tr-TR",
        )
    assertEquals(
        "Barkod sonucu: EAN-13 barkod: 8690001112223. Okunan metin: Parol 500 mg",
        combinedTr,
    )
  }

  @Test
  fun evaluateFrameQualityFromLuma_detectsDarkBlurryAndSharpFrames() {
    val width = 10
    val height = 10
    val dark = IntArray(width * height) { 6 }
    val darkReport = AssistantHelpers.evaluateFrameQualityFromLuma(dark, width, height)
    assertTrue(darkReport.isTooDark)
    assertTrue(darkReport.isBlurry)
    assertEquals(6.0, darkReport.meanLuma, 0.01)
    assertEquals(0.0, darkReport.sharpnessScore, 0.01)

    val blurry = IntArray(width * height) { 140 }
    val blurryReport = AssistantHelpers.evaluateFrameQualityFromLuma(blurry, width, height)
    assertFalse(blurryReport.isTooDark)
    assertTrue(blurryReport.isBlurry)
    assertEquals(140.0, blurryReport.meanLuma, 0.01)

    val sharp =
        IntArray(width * height) { idx ->
          val x = idx % width
          val y = idx / width
          if ((x + y) % 2 == 0) 40 else 220
        }
    val sharpReport = AssistantHelpers.evaluateFrameQualityFromLuma(sharp, width, height)
    assertFalse(sharpReport.isTooDark)
    assertFalse(sharpReport.isBlurry)
    assertTrue(sharpReport.meanLuma > 18.0)
    assertTrue(sharpReport.sharpnessScore >= 45.0)

    val invalid = AssistantHelpers.evaluateFrameQualityFromLuma(IntArray(0), 0, 0)
    assertTrue(invalid.isTooDark)
    assertTrue(invalid.isBlurry)
  }
}
