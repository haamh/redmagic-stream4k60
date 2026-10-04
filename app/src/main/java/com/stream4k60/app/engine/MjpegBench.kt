package com.stream4k60.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * What 4K MJPEG costs on this tablet against raw NV12, measured with the app's own MJPEG path. adb: push JPEGs as
 * files/bench*.jpg, then `am start -n com.stream4k60.app/.MainActivity --es bench mjpeg`; results go to stream-log.txt.
 */
object MjpegBench {
    fun start(dir: java.io.File) = Thread {
        runCatching { measure(dir) }.onFailure { StreamLog.add("MJPEG bench failed: $it") }
    }.apply { name = "MjpegBench" }.start()

    private fun decode(jpeg: ByteArray, reuse: Bitmap?): Bitmap? = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
        BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888; inBitmap = reuse })

    private fun measure(dir: java.io.File) {
        val hw = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/mjpeg", true) } }.map { it.name }
        StreamLog.add("MJPEG bench: MJPEG decoders on this tablet: ${hw.ifEmpty { listOf("none (software only)") }.joinToString()}")
        val files = dir.listFiles { f -> f.name.startsWith("bench") && f.name.endsWith(".jpg") }.orEmpty().sortedBy { it.name }
        for (f in files) {
            val jpeg = f.readBytes()
            var bmp: Bitmap? = null
            repeat(5) { bmp = decode(jpeg, bmp) }
            val w = bmp?.width ?: continue; val h = bmp!!.height
            val n = 40
            // 1. Decoding alone, one thread (what the source does now).
            var t = System.nanoTime(); var c = android.os.Debug.threadCpuTimeNanos()
            repeat(n) { bmp = decode(jpeg, bmp) }
            val decodeMs = (System.nanoTime() - t) / 1e6 / n; val decodeCpu = (android.os.Debug.threadCpuTimeNanos() - c) / 1e6 / n
            // 2. Decode + draw into a GPU surface with a hardware canvas: the whole software MJPEG path (RGBA upload included).
            val looper = android.os.HandlerThread("MjpegBenchReader").apply { start() }
            val reader = android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 3, android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, android.os.Handler(looper.looper))
            val surface = reader.surface
            val rect = android.graphics.Rect(0, 0, w, h)
            repeat(3) { val cv = surface.lockHardwareCanvas(); cv.drawBitmap(bmp!!, null, rect, null); surface.unlockCanvasAndPost(cv) }
            t = System.nanoTime(); c = android.os.Debug.threadCpuTimeNanos()
            repeat(n) { bmp = decode(jpeg, bmp); val cv = surface.lockHardwareCanvas(); cv.drawBitmap(bmp!!, null, rect, null); surface.unlockCanvasAndPost(cv) }
            val fullMs = (System.nanoTime() - t) / 1e6 / n; val fullCpu = (android.os.Debug.threadCpuTimeNanos() - c) / 1e6 / n
            reader.close(); looper.quitSafely()
            // 3. Several frames decoded at once on separate cores: the most a frame-parallel decoder could reach.
            val parallel = listOf(2, 4, 6).joinToString { k ->
                val per = 15
                val t0 = System.nanoTime()
                (1..k).map { Thread { var b: Bitmap? = null; repeat(per) { b = decode(jpeg, b) } }.apply { start() } }.forEach { it.join() }
                "$k threads %.0f fps".format(k * per / ((System.nanoTime() - t0) / 1e9))
            }
            // 4. NV12 at the same size: the zero-copy path's single copy of the frame into a hardware buffer.
            val src = java.nio.ByteBuffer.allocateDirect(w * h * 3 / 2); val dst = java.nio.ByteBuffer.allocateDirect(w * h * 3 / 2)
            t = System.nanoTime()
            repeat(n) { src.clear(); dst.clear(); dst.put(src) }
            val copyMs = (System.nanoTime() - t) / 1e6 / n
            StreamLog.add("MJPEG bench ${f.name} ${w}x$h ${jpeg.size / 1024} KB: decode %.1f ms (CPU %.1f) = %.0f fps max on one core; decode + GPU upload %.1f ms (CPU %.1f) = %.0f fps max; %s; per frame RGBA %.1f MB through the CPU and GPU upload vs NV12 %.1f MB; NV12 copy %.2f ms"
                .format(decodeMs, decodeCpu, 1000 / decodeMs, fullMs, fullCpu, 1000 / fullMs, parallel, w * h * 4 / 1e6, w * h * 1.5 / 1e6, copyMs))
        }
    }
}
