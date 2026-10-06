package com.stream4k60.app.data.backup

import android.content.Context
import android.content.Intent
import com.stream4k60.app.data.local.AppDatabase
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Settings backups: a snapshot of the database (scene collections, scenes, sources, filters, profiles with their
 * video / output / stream settings, hotkeys) and the app's preferences (layout, panels), taken whenever the app is
 * closed or sent to the background, and on request. Identical snapshots are skipped and the newest [KEEP] are kept.
 * They stay in the app's private storage because the profiles include the stream key.
 */
object SettingsBackups {
    const val KEEP = 10
    private const val DB_NAME = "stream4k60.db"
    // Account sign-in state is not a setting; restoring it could sign a different account back in.
    private val skippedPrefs = setOf("youtube_account.xml")
    /** App-private folders that sources point into (picked images are copied there; imported OBS profiles). */
    private val assetDirs = listOf("source_assets", "obs_profiles")

    data class Backup(val file: File, val createdMs: Long, val sizeBytes: Long)

    private fun dir(context: Context) = File(context.filesDir, "backups").apply { mkdirs() }

    fun list(context: Context): List<Backup> =
        dir(context).listFiles { f -> f.name.endsWith(".zip") }.orEmpty()
            .map { Backup(it, it.lastModified(), it.length()) }.sortedByDescending { it.createdMs }

    private fun prefsFiles(context: Context): List<File> =
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles { f -> f.name.endsWith(".xml") && f.name !in skippedPrefs }
            .orEmpty().sortedBy { it.name }

    /** Saves a backup; returns null when nothing changed since the newest one (unless [force]). */
    @Synchronized
    fun backup(context: Context, db: AppDatabase, reason: String, force: Boolean = false): File? {
        val snapshot = File(context.cacheDir, "backup-db.tmp").apply { delete() }
        // VACUUM INTO writes a consistent copy of the live database, WAL included.
        db.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf<Any>(snapshot.absolutePath))
        val prefs = prefsFiles(context)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(snapshot.readBytes())
        prefs.forEach { digest.update(it.name.toByteArray()); digest.update(it.readBytes()) }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val lastHash = File(dir(context), "last.hash")
        if (!force && lastHash.exists() && lastHash.readText() == hash) { snapshot.delete(); return null }
        val name = "settings-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
        val out = File(dir(context), name)
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(DB_NAME)); snapshot.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            prefs.forEach { p -> zip.putNextEntry(ZipEntry("shared_prefs/" + p.name)); p.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            zip.putNextEntry(ZipEntry("info.txt")); zip.write("Stream4k60 settings backup\nreason: $reason\n".toByteArray()); zip.closeEntry()
        }
        snapshot.delete()
        lastHash.writeText(hash)
        list(context).drop(KEEP).forEach { it.file.delete() }
        return out
    }

    /**
     * A complete backup written to [out] (a file the user picked, so it survives an uninstall or moves to another tablet):
     * the database (every scene collection, scene, source with its settings, position, crop and filters, profiles,
     * hotkeys), the preferences (layout, transition) and the files sources use. It includes the stream keys.
     */
    @Synchronized
    fun exportTo(context: Context, db: AppDatabase, out: java.io.OutputStream): Int {
        val snapshot = File(context.cacheDir, "export-db.tmp").apply { delete() }
        db.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf<Any>(snapshot.absolutePath))
        var files = 0
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(DB_NAME)); snapshot.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            prefsFiles(context).forEach { p -> zip.putNextEntry(ZipEntry("shared_prefs/" + p.name)); p.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            for (name in assetDirs) {
                val root = File(context.filesDir, name)
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry("files/" + f.relativeTo(context.filesDir).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); files++
                }
            }
            zip.putNextEntry(ZipEntry("info.txt"))
            val created = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            zip.write("Stream4k60 full backup\ncreated: $created\nsource files: $files\n".toByteArray())
            zip.closeEntry()
        }
        snapshot.delete()
        return files
    }

    /** Copies a backup file the user picked into the app and restores it (the app restarts). */
    fun importFrom(context: Context, db: AppDatabase, input: java.io.InputStream) {
        val copy = File(dir(context), "imported-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip")
        copy.outputStream().use { input.copyTo(it) }
        val hasDb = ZipInputStream(copy.inputStream().buffered()).use { zip -> generateSequence { zip.nextEntry }.any { it.name == DB_NAME } }
        if (!hasDb) { copy.delete(); error("That file isn't a Stream4k60 backup (no database inside).") }
        restore(context, db, copy)
    }

    /**
     * Replaces the database and preferences with [backup] and restarts the app. The current state is backed up first,
     * so a restore can be undone by restoring that one.
     */
    fun restore(context: Context, db: AppDatabase, backup: File) {
        backup(context, db, "before restoring ${backup.name}", force = true)
        db.close()
        val dbFile = context.getDatabasePath(DB_NAME)
        File(dbFile.path + "-wal").delete(); File(dbFile.path + "-shm").delete()
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        ZipInputStream(backup.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = when {
                    entry.name == DB_NAME -> dbFile
                    entry.name.startsWith("shared_prefs/") && !entry.name.contains("..") && entry.name.endsWith(".xml") ->
                        File(prefsDir, entry.name.removePrefix("shared_prefs/"))
                    // Full backups also carry the files sources use, back at the same paths the sources point to.
                    entry.name.startsWith("files/") && !entry.name.contains("..") && !entry.isDirectory &&
                        assetDirs.any { entry.name.startsWith("files/$it/") } ->
                        File(context.filesDir, entry.name.removePrefix("files/")).also { it.parentFile?.mkdirs() }
                    else -> null
                }
                if (target != null) target.outputStream().use { zip.copyTo(it) }
            }
        }
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) context.startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
