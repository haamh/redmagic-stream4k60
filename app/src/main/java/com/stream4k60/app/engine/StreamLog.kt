package com.stream4k60.app.engine

/**
 * How far each stream got (connecting, publishing, errors), kept in Android/data/com.stream4k60.app/files/stream-log.txt
 * (`adb shell cat …`), because logcat is disabled on the Astra. Never given stream keys.
 */
object StreamLog {
    @Volatile private var logFile: java.io.File? = null

    fun init(context: android.content.Context) {
        logFile = context.getExternalFilesDir(null)?.let { java.io.File(it, "stream-log.txt") }
    }

    /** The app's external files folder (where stream-log.txt lives), or null before init. */
    fun directory(): java.io.File? = logFile?.parentFile

    @Synchronized fun add(message: String) {
        android.util.Log.i("Stream4k60", "Stream: $message")
        logFile?.let { f ->
            runCatching {
                // Past 512 KB the older half goes; wiping it all lost the start of a stream when it mattered.
                if (f.length() > 512 * 1024) {
                    val text = f.readText()
                    f.writeText(text.substring(text.indexOf('\n', text.length / 2) + 1))
                }
                f.appendText("${java.time.LocalDateTime.now()}  $message\n")
            }
        }
    }
}
