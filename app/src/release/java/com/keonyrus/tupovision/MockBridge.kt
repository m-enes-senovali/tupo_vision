package com.keonyrus.tupovision

import android.content.Context

/** Release derlemesinde sanal gözlük altyapısı derlemeye dahil edilmez. */
object MockBridge {
  val isAvailable: Boolean = false

  fun enable(context: Context, log: (String) -> Unit): Boolean = false

  fun disable(context: Context, log: (String) -> Unit) {}

  fun useBackCamera(log: (String) -> Unit): Boolean = false

  fun useSampleDocumentPhoto(context: Context, log: (String) -> Unit): Boolean = false

  fun simulateDonDoff(worn: Boolean, log: (String) -> Unit): Boolean = false

  fun simulateCapturePress(doublePress: Boolean, log: (String) -> Unit): Boolean = false
}
