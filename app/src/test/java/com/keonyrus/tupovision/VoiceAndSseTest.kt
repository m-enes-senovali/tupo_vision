package com.keonyrus.tupovision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAndSseTest {

  @Test
  fun sentenceChunker_usesSmallerThresholdForFirstSentenceThenStandardThreshold() {
    val chunker = SentenceChunker()

    // First sentence length < MIN_FIRST_SENTENCE (4) does not split yet ("Ok." -> 3 chars).
    assertEquals(emptyList<String>(), chunker.push("Ok. "))

    // Once boundary reaches >= 4, first sentence emits.
    assertEquals(listOf("Ok. Evet."), chunker.push("Evet. "))

    // Subsequent sentences require MIN_SENTENCE (10); "Kisa." (5 chars) waits.
    assertEquals(emptyList<String>(), chunker.push("Kisa. "))
    assertEquals(listOf("Kisa. Uzun cumle."), chunker.push("Uzun cumle. "))

    assertEquals("Son parca", chunker.push("Son parca").let { empty ->
      assertEquals(emptyList<String>(), empty)
      chunker.flush()
    })
    assertNull(chunker.flush())
  }

  @Test
  fun sentenceChunker_splitsFirstSentenceAtFourCharacters() {
    val chunker = SentenceChunker()
    val emitted = chunker.push("Tam. Ikinci cumle burada.")
    assertEquals(listOf("Tam."), emitted)
    assertEquals("Ikinci cumle burada.", chunker.flush())
  }

  @Test
  fun sentenceChunker_preservesDecimalNumbersWithoutSplitting() {
    val chunker = SentenceChunker()
    val firstPass = chunker.push("Saat 3.5 gibi ")
    assertEquals(emptyList<String>(), firstPass)

    val secondPass = chunker.push("bulusalim. Sonra gideriz.")
    assertEquals(listOf("Saat 3.5 gibi bulusalim."), secondPass)
    assertEquals("Sonra gideriz.", chunker.flush())
  }

  @Test
  fun sentenceChunker_splitsOnNewlineWhenThresholdMet() {
    val chunker = SentenceChunker()
    val emitted = chunker.push("Merhaba\nIkinci satir devam ediyor.")
    assertEquals(listOf("Merhaba"), emitted)
    assertEquals("Ikinci satir devam ediyor.", chunker.flush())
  }

  @Test
  fun parseSseEventLine_handlesDeltaCompletedErrorAndInvalidLines() {
    assertEquals(
        SseEventResult.Delta("Merhaba "),
        Responses.parseSseEventLine("""data: {"type":"response.output_text.delta","delta":"Merhaba "}"""),
    )
    assertEquals(
        SseEventResult.Ignore,
        Responses.parseSseEventLine("""data: {"type":"response.output_text.delta","delta":""}"""),
    )

    assertEquals(
        SseEventResult.Completed,
        Responses.parseSseEventLine("""data: {"type":"response.completed"}"""),
    )

    val directError =
        Responses.parseSseEventLine("""data: {"type":"error","error":{"message":"Kota doldu"}}""")
    assertTrue(directError is SseEventResult.Error)
    assertEquals("Kota doldu", (directError as SseEventResult.Error).message)

    val failedNested =
        Responses.parseSseEventLine(
            """data: {"type":"response.failed","response":{"error":{"message":"Sunucu hatasi"}}}""",
        )
    assertEquals(SseEventResult.Error("Sunucu hatasi"), failedNested)

    val incomplete =
        Responses.parseSseEventLine(
            """data: {"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""",
        )
    assertEquals(SseEventResult.Error("The answer was cut short: max_output_tokens"), incomplete)

    assertEquals(SseEventResult.Ignore, Responses.parseSseEventLine(""))
    assertEquals(SseEventResult.Ignore, Responses.parseSseEventLine("   "))
    assertEquals(SseEventResult.Ignore, Responses.parseSseEventLine("event: response.completed"))
    assertEquals(SseEventResult.Ignore, Responses.parseSseEventLine(": keepalive"))
    assertEquals(SseEventResult.Ignore, Responses.parseSseEventLine("data:"))
    assertEquals(SseEventResult.Ignore, Responses.parseSseEventLine("data: [DONE]"))
    assertEquals(SseEventResult.Ignore, Responses.parseSseEventLine("data: {bozuk json"))
    assertEquals(
        SseEventResult.Ignore,
        Responses.parseSseEventLine("""data: {"type":"response.created"}"""),
    )
  }
}
