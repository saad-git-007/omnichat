package com.saad.omnichat.ui.chat

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.saad.omnichat.AppLog
import java.util.concurrent.Executors

/**
 * The live camera of voice chat. The preview runs at the camera's own rate; the model doesn't watch it as video (a
 * 2B model on a phone can't, and each picture costs ~1.3 s on the NPU). Instead the newest frame is kept, upright,
 * twice a second, and the one current when you finish speaking goes with your question: what the camera points at
 * as you ask "what's this?" is what the model sees.
 */
@Composable
fun VoiceCamera(front: Boolean, onFrame: (Bitmap) -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
  val analysisThread = remember { Executors.newSingleThreadExecutor { r -> Thread(r, "camera-frames") } }
  DisposableEffect(Unit) { onDispose { analysisThread.shutdown() } }

  LaunchedEffect(front) {
    val provider = runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrElse {
      AppLog.e("camera unavailable", it)
      return@LaunchedEffect
    }
    // ~1.2 MP: enough to read a label or a page held up to it; the frame is scaled to 1024 px before the model
    val size = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
    var last = 0L
    val analysis =
      ImageAnalysis.Builder()
        .setResolutionSelector(size)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()
        .also {
          it.setAnalyzer(analysisThread) { img ->
            img.use { frame ->
              val now = System.currentTimeMillis()
              if (now - last >= FRAME_EVERY_MS) {
                last = now
                runCatching { onFrame(upright(frame)) }.onFailure { e -> AppLog.w("camera frame: ${e.message}") }
              }
            }
          }
        }
    val selector = if (front && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    try {
      provider.unbindAll()
      provider.bindToLifecycle(owner, selector, preview, analysis)
    } catch (e: Exception) {
      AppLog.e("camera bind failed", e)
    }
  }
  DisposableEffect(Unit) {
    onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }
  }
  AndroidView({ view }, modifier)
}

/** How often the newest frame is kept for the next question. */
private const val FRAME_EVERY_MS = 500L

private fun upright(frame: ImageProxy): Bitmap {
  val bmp = frame.toBitmap()
  val deg = frame.imageInfo.rotationDegrees
  if (deg == 0) return bmp
  return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
}
