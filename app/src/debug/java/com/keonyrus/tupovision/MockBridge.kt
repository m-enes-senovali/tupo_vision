package com.keonyrus.tupovision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import java.io.File
import java.io.FileOutputStream

/**
 * Yalnızca debug derlemesinde çalışan Meta MockDeviceKit köprüsü. Fiziksel gözlük olmadan eşleşme,
 * kamera akışı, örnek ilaç kutusu fotoğrafı ve capture tuşu testleri yapılmasını sağlar.
 */
object MockBridge {
  val isAvailable: Boolean = true

  private var mockGlasses: MockGlasses? = null

  fun enable(context: Context, log: (String) -> Unit): Boolean {
    return runCatching {
          val kit = MockDeviceKit.getInstance(context.applicationContext)
          kit.enable(
              MockDeviceKitConfig(
                  initiallyRegistered = true,
                  initialPermissionsGranted = true,
                  isDevMode = true,
              ),
          )
          kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
          kit.permissions.setRequestResult(Permission.CAMERA, PermissionStatus.Granted)
          val glasses = mockGlasses ?: kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
          mockGlasses = glasses
          glasses.powerOn()
          glasses.unfold()
          glasses.don()
          glasses.setBatteryLevel(92)
          createAndAttachSamplePhoto(context.applicationContext, glasses)
          log("Sanal gözlük (Mock Ray-Ban Meta) açıldı ve örnek test fotoğrafı yüklendi")
          true
        }
        .getOrElse { error ->
          log("Sanal gözlük başlatılamadı: ${error.message}")
          false
        }
  }

  fun disable(context: Context, log: (String) -> Unit) {
    val current = mockGlasses
    mockGlasses = null
    runCatching {
      val kit = MockDeviceKit.getInstance(context.applicationContext)
      if (current != null) kit.unpairDevice(current)
      kit.disable()
      log("Sanal gözlük kapatıldı")
    }.onFailure { error -> log("Sanal gözlük kapatılırken hata: ${error.message}") }
  }

  fun useBackCamera(log: (String) -> Unit): Boolean {
    val glasses =
        mockGlasses
            ?: run {
              log("Önce sanal gözlüğü açın")
              return false
            }
    return runCatching {
          glasses.services.camera.setCameraFeed(CameraFacing.BACK)
          log("Sanal gözlük video beslemesi telefon arka kamerasına bağlandı")
          true
        }
        .getOrElse { error ->
          log("Telefon arka kamerası bağlanamadı: ${error.message}")
          false
        }
  }

  fun useSampleDocumentPhoto(context: Context, log: (String) -> Unit): Boolean {
    val glasses =
        mockGlasses
            ?: run {
              log("Önce sanal gözlüğü açın")
              return false
            }
    return runCatching {
          val file = createAndAttachSamplePhoto(context.applicationContext, glasses)
          log("Örnek Türkçe ilaç etiketi fotoğrafı yüklendi (${file.name})")
          true
        }
        .getOrElse { error ->
          log("Örnek fotoğraf oluşturulamadı: ${error.message}")
          false
        }
  }

  fun simulateDonDoff(worn: Boolean, log: (String) -> Unit): Boolean {
    val glasses =
        mockGlasses
            ?: run {
              log("Önce sanal gözlüğü açın")
              return false
            }
    return runCatching {
          if (worn) glasses.don() else glasses.doff()
          log(if (worn) "Sanal gözlük takıldı (don)" else "Sanal gözlük çıkarıldı (doff)")
          true
        }
        .getOrElse { error ->
          log("Takma/çıkarma simülasyonu başarısız: ${error.message}")
          false
        }
  }

  fun simulateCapturePress(doublePress: Boolean, log: (String) -> Unit): Boolean {
    val glasses =
        mockGlasses
            ?: run {
              log("Önce sanal gözlüğü açın")
              return false
            }
    return runCatching {
          val press = if (doublePress) CapturePressType.DOUBLE_PRESS else CapturePressType.SHORT_PRESS
          glasses.services.input.capture(press)
          log(
              if (doublePress) "Sanal capture tuşuna çift basıldı"
              else "Sanal capture tuşuna kısa basıldı",
          )
          true
        }
        .getOrElse { error ->
          log("Capture tuşu simülasyonu başarısız: ${error.message}")
          false
        }
  }

  private fun createAndAttachSamplePhoto(context: Context, glasses: MockGlasses): File {
    val width = 1280
    val height = 960
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    val borderPaint =
        Paint().apply {
          color = Color.BLACK
          style = Paint.Style.STROKE
          strokeWidth = 8f
        }
    canvas.drawRect(36f, 36f, width - 36f, height - 36f, borderPaint)

    val titlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.BLACK
          textSize = 68f
          isFakeBoldText = true
        }
    val bodyPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.BLACK
          textSize = 48f
        }
    val smallPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.DKGRAY
          textSize = 38f
        }

    canvas.drawText("ÖRNEK İLAÇ KUTUSU", 90f, 160f, titlePaint)
    canvas.drawText("PAROL 500 MG TABLET", 90f, 290f, titlePaint)
    canvas.drawText("Etken Madde: Parasetamol 500 mg", 90f, 410f, bodyPaint)
    canvas.drawText("Son Kullanma Tarihi (SKT): 12.2028", 90f, 520f, bodyPaint)
    canvas.drawText("Kullanım: Günde 2 kez, tok karnına 1 tablet", 90f, 630f, bodyPaint)
    canvas.drawText("Parti No: TR-2026-0418  |  20 Tablet", 90f, 760f, smallPaint)

    val outFile = File(context.cacheDir, "mock_sample_ilac.jpg")
    FileOutputStream(outFile).use { stream ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
    }
    bitmap.recycle()
    glasses.services.camera.setCapturedImage(Uri.fromFile(outFile))
    return outFile
  }
}
