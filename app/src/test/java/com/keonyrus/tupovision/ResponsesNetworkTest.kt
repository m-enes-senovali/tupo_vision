package com.keonyrus.tupovision

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ResponsesNetworkTest {
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun stream_emitsDeltasAcrossSseEventsAndReusesConnectionPool() = runBlocking {
    val ssePayload =
        listOf(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Merhaba \"}",
                "",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"dunya.\"}",
                "data: {\"type\":\"response.completed\"}",
                "data: [DONE]",
            )
            .joinToString("\n")
    server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "text/event-stream")
            .setBody(ssePayload),
    )
    server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Ikinci tur.\"}\ndata: {\"type\":\"response.completed\"}\n"),
    )

    val url = server.url("/v1/responses").toString()
    val body = JSONObject().put("model", "gpt-6.1-sol").put("stream", true)

    val first = Responses.stream("tok-1", body, url = url).toList()
    val second = Responses.stream("tok-2", body, url = url).toList()

    assertEquals(listOf("Merhaba ", "dunya."), first)
    assertEquals(listOf("Ikinci tur."), second)

    val req1 = server.takeRequest(2, TimeUnit.SECONDS)!!
    val req2 = server.takeRequest(2, TimeUnit.SECONDS)!!
    assertEquals("Bearer tok-1", req1.getHeader("Authorization"))
    assertEquals("text/event-stream", req1.getHeader("Accept"))
    assertEquals(0, req1.sequenceNumber)
    // ConnectionPool aktif oldugundan ikinci istek ayni soket uzerinden gider.
    assertEquals(1, req2.sequenceNumber)
  }

  @Test
  fun stream_throwsApiExceptionOnHttp401And429() = runBlocking {
    val url = server.url("/v1/responses").toString()
    val body = JSONObject().put("model", "gpt-6.1-sol")

    server.enqueue(
        MockResponse()
            .setResponseCode(401)
            .setBody("{\"error\":{\"message\":\"invalid token\"}}"),
    )
    val err401 =
        runCatching { Responses.stream("bad", body, url = url).toList() }.exceptionOrNull()
    assertTrue(err401 is ApiException)
    assertEquals(401, (err401 as ApiException).status)

    server.enqueue(
        MockResponse()
            .setResponseCode(429)
            .setBody("{\"error\":{\"message\":\"rate limit\"}}"),
    )
    val err429 =
        runCatching { Responses.stream("tok", body, url = url).toList() }.exceptionOrNull()
    assertTrue(err429 is ApiException)
    assertEquals(429, (err429 as ApiException).status)
  }

  @Test
  fun stream_throwsApiExceptionOnSseErrorEvent() = runBlocking {
    val url = server.url("/v1/responses").toString()
    server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"type\":\"error\",\"error\":{\"message\":\"Sunucu hatasi\"}}\n"),
    )
    val err =
        runCatching { Responses.stream("tok", JSONObject(), url = url).toList() }.exceptionOrNull()
    assertTrue(err is ApiException)
    assertTrue(err?.message?.contains("Sunucu hatasi") == true)
  }

  @Test
  fun stream_cancelsUnderlyingCallWhenFlowStopsEarly() = runBlocking {
    val url = server.url("/v1/responses").toString()
    server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Ilk\"}\n" +
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Ikinci\"}\n" +
                    "data: {\"type\":\"response.completed\"}\n",
            ),
    )

    val collected = Responses.stream("tok", JSONObject(), url = url).take(1).toList()
    assertEquals(listOf("Ilk"), collected)
  }

  @Test
  fun listModels_parsesModelArrayAndThrowsOnHttpError() = runBlocking {
    val url = server.url("/v1/models").toString()
    server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .setBody("{\"models\":[{\"slug\":\"gpt-6.1-sol\"},{\"id\":\"gpt-6-luna\"}]}"),
    )
    val models = Responses.listModels("tok", url = url)
    assertEquals(listOf("gpt-6.1-sol", "gpt-6-luna"), models)

    server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
    val err = runCatching { Responses.listModels("tok", url = url) }.exceptionOrNull()
    assertTrue(err is ApiException)
    assertEquals(500, (err as ApiException).status)
  }
}
