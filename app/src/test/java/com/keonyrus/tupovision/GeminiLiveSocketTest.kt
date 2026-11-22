package com.keonyrus.tupovision

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiLiveSocketTest {
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
  fun handshakeToolCallResumptionAndGoAwayFlowWorksEndToEnd() {
    val setupReceived = AtomicReference<String?>(null)
    val toolResponseReceived = AtomicReference<String?>(null)
    val serverSocketRef = AtomicReference<WebSocket?>(null)
    val toolResponseLatch = CountDownLatch(1)

    server.enqueue(
        MockResponse()
            .withWebSocketUpgrade(
                object : WebSocketListener() {
                  override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocketRef.set(webSocket)
                  }

                  override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = JSONObject(text)
                    if (json.has("setup")) {
                      setupReceived.set(text)
                      webSocket.send("{\"setupComplete\":{}}")
                      webSocket.send(
                          "{\"toolCall\":{\"functionCalls\":[{\"id\":\"call-42\",\"name\":\"switch_profile\",\"args\":{\"profile\":\"document\"}}]}}",
                      )
                      webSocket.send(
                          "{\"serverContent\":{\"inputTranscription\":{\"text\":\"etiketi oku\"},\"outputTranscription\":{\"text\":\"Belge moduna gecildi\"},\"turnComplete\":true}}",
                      )
                      webSocket.send(
                          "{\"sessionResumptionUpdate\":{\"resumable\":true,\"newHandle\":\"handle-99\"}}",
                      )
                      webSocket.send("{\"goAway\":{}}")
                    } else if (json.has("toolResponse")) {
                      toolResponseReceived.set(text)
                      toolResponseLatch.countDown()
                    }
                  }
                },
            ),
    )

    val readyLatch = CountDownLatch(1)
    val toolLatch = CountDownLatch(1)
    val turnLatch = CountDownLatch(1)
    val resumeLatch = CountDownLatch(1)
    val goAwayLatch = CountDownLatch(1)

    val capturedToolName = AtomicReference<String?>()
    val capturedToolArgs = AtomicReference<String?>()
    val capturedResume = AtomicReference<String?>()
    val capturedUserText = AtomicReference<String?>()
    val capturedModelText = AtomicReference<String?>()
    lateinit var live: GeminiLive

    val events =
        object : GeminiLive.Events {
          override fun onReady() {
            readyLatch.countDown()
          }

          override fun onUserText(delta: String) {
            capturedUserText.set(delta)
          }

          override fun onModelText(delta: String) {
            capturedModelText.set(delta)
          }

          override fun onModelSpeaking(speaking: Boolean) {}

          override fun onTurnComplete() {
            turnLatch.countDown()
          }

          override fun onResumeHandle(handle: String) {
            capturedResume.set(handle)
            resumeLatch.countDown()
          }

          override fun onGoAway() {
            goAwayLatch.countDown()
          }

          override fun onToolCall(callId: String, name: String, argsJson: String) {
            capturedToolName.set(name)
            capturedToolArgs.set(argsJson)
            live.sendToolResponse(callId, name, JSONObject().put("status", "ok"))
            toolLatch.countDown()
          }

          override fun onMicError(reason: String) {}

          override fun onFrameSent(jpeg: ByteArray) {}

          override fun onClosed(reason: String, wasReady: Boolean) {}

          override fun onLog(message: String) {}
        }

    val wsUrl = server.url("/ws/live").toString().replaceFirst("http", "ws")
    live =
        GeminiLive(
            apiKey = "test-key",
            model = "gemini-3.8-live",
            instructions = "Turkce yanit ver",
            resumeHandle = "prev-handle",
            fullSetup = true,
            enableGoogleSearch = true,
            enableFunctionTools = true,
            bargeIn = false,
            inputDevice = null,
            callAudio = false,
            frameSource = { null },
            events = events,
            setupOptions =
                GeminiSetupOptions(
                    voiceName = GeminiVoice.ZEPHYR.apiName,
                    vad = VadSensitivity.FAST,
                    thinkingBudget = 0,
                ),
            endpointUrl = wsUrl,
            enableAudioLoops = false,
        )

    live.start()

    assertTrue("onReady zaman asimi", readyLatch.await(5, TimeUnit.SECONDS))
    assertTrue("onToolCall zaman asimi", toolLatch.await(5, TimeUnit.SECONDS))
    assertTrue("toolResponse zaman asimi", toolResponseLatch.await(5, TimeUnit.SECONDS))
    assertTrue("onTurnComplete zaman asimi", turnLatch.await(5, TimeUnit.SECONDS))
    assertTrue("onResumeHandle zaman asimi", resumeLatch.await(5, TimeUnit.SECONDS))
    assertTrue("onGoAway zaman asimi", goAwayLatch.await(5, TimeUnit.SECONDS))

    val setupJson = JSONObject(setupReceived.get()!!).getJSONObject("setup")
    assertEquals("models/gemini-3.8-live", setupJson.getString("model"))
    assertEquals("prev-handle", setupJson.getJSONObject("sessionResumption").getString("handle"))

    assertEquals("switch_profile", capturedToolName.get())
    assertTrue(capturedToolArgs.get()!!.contains("document"))
    assertEquals("etiketi oku", capturedUserText.get())
    assertEquals("Belge moduna gecildi", capturedModelText.get())
    assertEquals("handle-99", capturedResume.get())

    val toolResp = JSONObject(toolResponseReceived.get()!!)
    val fnResponses = toolResp.getJSONObject("toolResponse").getJSONArray("functionResponses")
    assertNotNull(fnResponses)
    assertEquals("call-42", fnResponses.getJSONObject(0).getString("id"))
    assertEquals("switch_profile", fnResponses.getJSONObject(0).getString("name"))

    live.stop()
    serverSocketRef.get()?.close(1000, "done")
  }
}
