package com.keonyrus.tupovision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class LocalScanOutput(
    val textLines: List<String>,
    val barcodes: List<LocalBarcode>,
    val spokenSummary: String?,
    val quality: FrameQualityReport?,
    val durationMs: Long,
)

/**
 * On-device offline OCR and barcode scanner powered by bundled ML Kit models. Clients initialize
 * lazily on first scan and never perform external network requests.
 */
object LocalVision {
  private const val SCAN_MAX_SIDE = 1600
  private const val QUALITY_MAX_SIDE = 240

  private val textClientDelegate = lazy {
    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  }
  private val barcodeClientDelegate = lazy {
    BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build(),
    )
  }

  private val textClient: TextRecognizer
    get() = textClientDelegate.value

  private val barcodeClient: BarcodeScanner
    get() = barcodeClientDelegate.value

  suspend fun scanJpeg(
      jpeg: ByteArray,
      languageTag: String = "tr-TR",
      evaluateQuality: Boolean = false,
  ): LocalScanOutput =
      withContext(Dispatchers.Default) {
        val startNs = System.nanoTime()
        val bitmap =
            decodeDownsampled(jpeg, SCAN_MAX_SIDE)
                ?: return@withContext LocalScanOutput(
                    textLines = emptyList(),
                    barcodes = emptyList(),
                    spokenSummary = null,
                    quality = null,
                    durationMs = elapsedMs(startNs),
                )
        try {
          val quality = if (evaluateQuality) computeQuality(bitmap) else null
          val input = InputImage.fromBitmap(bitmap, 0)

          val ocrBlocks =
              runCatching { awaitTask(textClient.process(input)) }
                  .getOrNull()
                  ?.textBlocks
                  ?.flatMap { block ->
                    if (block.lines.isEmpty()) {
                      val box = block.boundingBox
                      listOf(
                          OcrBlock(
                              text = block.text,
                              left = box?.left ?: 0,
                              top = box?.top ?: 0,
                              right = box?.right ?: 0,
                              bottom = box?.bottom ?: 0,
                          ),
                      )
                    } else {
                      block.lines.map { line ->
                        val box = line.boundingBox
                        OcrBlock(
                            text = line.text,
                            left = box?.left ?: 0,
                            top = box?.top ?: 0,
                            right = box?.right ?: 0,
                            bottom = box?.bottom ?: 0,
                        )
                      }
                    }
                  }
                  .orEmpty()
          val orderedLines = AssistantHelpers.orderOcrBlocks(ocrBlocks)

          val barcodes =
              runCatching { awaitTask(barcodeClient.process(input)) }
                  .getOrNull()
                  ?.mapNotNull { toLocalBarcode(it) }
                  .orEmpty()

          val summary = AssistantHelpers.formatLocalScanResult(orderedLines, barcodes, languageTag)
          LocalScanOutput(
              textLines = orderedLines,
              barcodes = barcodes,
              spokenSummary = summary,
              quality = quality,
              durationMs = elapsedMs(startNs),
          )
        } finally {
          bitmap.recycle()
        }
      }

  suspend fun checkJpegQuality(jpeg: ByteArray): FrameQualityReport? =
      withContext(Dispatchers.Default) {
        val bitmap = decodeDownsampled(jpeg, QUALITY_MAX_SIDE) ?: return@withContext null
        try {
          computeQuality(bitmap)
        } finally {
          bitmap.recycle()
        }
      }

  fun close() {
    if (textClientDelegate.isInitialized()) {
      runCatching { textClient.close() }
    }
    if (barcodeClientDelegate.isInitialized()) {
      runCatching { barcodeClient.close() }
    }
  }

  private suspend fun <T> awaitTask(task: Task<T>): T =
      suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { result ->
          if (cont.isActive) cont.resume(result)
        }
        task.addOnFailureListener { error ->
          if (cont.isActive) cont.resumeWithException(error)
        }
        task.addOnCanceledListener {
          if (cont.isActive) cont.cancel()
        }
      }

  private fun decodeDownsampled(jpeg: ByteArray, maxSide: Int): Bitmap? {
    if (jpeg.isEmpty()) return null
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
      val longSide = maxOf(bounds.outWidth, bounds.outHeight)
      if (longSide <= 0) return null
      var sample = 1
      while (longSide / (sample * 2) >= maxSide) sample *= 2
      val options =
          BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
          }
      BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    } catch (_: Exception) {
      null
    } catch (_: OutOfMemoryError) {
      null
    }
  }

  private fun computeQuality(bitmap: Bitmap): FrameQualityReport {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) {
      return AssistantHelpers.evaluateFrameQualityFromLuma(IntArray(0), 0, 0)
    }
    val step = maxOf(1, maxOf(w, h) / 160)
    val sw = (w / step).coerceAtLeast(1)
    val sh = (h / step).coerceAtLeast(1)
    val luma = IntArray(sw * sh)
    var idx = 0
    for (y in 0 until sh) {
      val py = (y * step).coerceAtMost(h - 1)
      for (x in 0 until sw) {
        val px = (x * step).coerceAtMost(w - 1)
        val color = bitmap.getPixel(px, py)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        luma[idx++] = (299 * r + 587 * g + 114 * b) / 1000
      }
    }
    return AssistantHelpers.evaluateFrameQualityFromLuma(luma, sw, sh)
  }

  private fun toLocalBarcode(barcode: Barcode): LocalBarcode? {
    val raw = barcode.rawValue?.trim().orEmpty()
    val display = barcode.displayValue?.trim().orEmpty()
    if (raw.isEmpty() && display.isEmpty()) return null
    val formatLabel = barcodeFormatLabel(barcode.format)

    return when (barcode.valueType) {
      Barcode.TYPE_URL -> {
        val url = barcode.url?.url?.trim().orEmpty().ifEmpty { raw }
        val host = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull().orEmpty()
        val title = barcode.url?.title?.trim().orEmpty().ifEmpty { host }.ifEmpty { url }
        LocalBarcode(
            formatLabel = formatLabel,
            rawValue = url,
            kind = LocalBarcodeKind.URL,
            displayTitle = title,
        )
      }
      Barcode.TYPE_WIFI -> {
        val ssid = barcode.wifi?.ssid?.trim().orEmpty().ifEmpty { display.ifEmpty { raw } }
        val enc =
            when (barcode.wifi?.encryptionType) {
              Barcode.WiFi.TYPE_WPA -> "WPA sifreli"
              Barcode.WiFi.TYPE_WEP -> "WEP sifreli"
              Barcode.WiFi.TYPE_OPEN -> "Acik ag"
              else -> ""
            }
        LocalBarcode(
            formatLabel = formatLabel,
            rawValue = raw,
            kind = LocalBarcodeKind.WIFI,
            displayTitle = ssid,
            extraDetail = enc,
        )
      }
      Barcode.TYPE_CONTACT_INFO -> {
        val info = barcode.contactInfo
        val name = info?.name?.formattedName?.trim().orEmpty().ifEmpty { display.ifEmpty { raw } }
        val phone = info?.phones?.firstOrNull()?.number?.trim().orEmpty()
        val email = info?.emails?.firstOrNull()?.address?.trim().orEmpty()
        val detail = listOf(phone, email).filter { it.isNotEmpty() }.joinToString(", ")
        LocalBarcode(
            formatLabel = formatLabel,
            rawValue = raw,
            kind = LocalBarcodeKind.CONTACT,
            displayTitle = name,
            extraDetail = detail,
        )
      }
      Barcode.TYPE_PRODUCT,
      Barcode.TYPE_ISBN ->
          LocalBarcode(
              formatLabel = formatLabel,
              rawValue = raw.ifEmpty { display },
              kind = LocalBarcodeKind.PRODUCT,
              displayTitle = display.ifEmpty { raw },
          )
      else ->
          LocalBarcode(
              formatLabel = formatLabel,
              rawValue = raw.ifEmpty { display },
              kind = LocalBarcodeKind.TEXT,
              displayTitle = display.ifEmpty { raw },
          )
    }
  }

  private fun barcodeFormatLabel(format: Int): String =
      when (format) {
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_QR_CODE -> "QR"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_AZTEC -> "Aztec"
        Barcode.FORMAT_PDF417 -> "PDF417"
        else -> "Barkod"
      }

  private fun elapsedMs(startNs: Long): Long =
      TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).coerceAtLeast(0L)
}
