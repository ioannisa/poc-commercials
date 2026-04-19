package eu.anifantakis.poc.ctv.config

import android.content.Context
import java.io.File

/**
 * The Android host app must call [AndroidAppContext.init] with the application
 * context before [AppConfig.load] is called.
 */
object AndroidAppContext {
    @Volatile
    internal var appContext: Context? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

/**
 * Runtime config path (outside the APK, editable post-install):
 *   /sdcard/Android/data/<package>/files/config.properties
 *
 * On first launch — or any launch where that file doesn't exist — we copy
 * the bundled `assets/config.properties` into that location as a starting
 * template. The app always reads the external file, so user edits persist
 * across app updates.
 */
internal actual suspend fun loadAppConfig(): AppConfig {
    val context = AndroidAppContext.appContext
        ?: error("AndroidAppContext.init(context) must be called before AppConfig.load()")

    val externalDir = context.getExternalFilesDir(null)
        ?: error("External files directory unavailable")
    val externalFile = File(externalDir, "config.properties")

    if (!externalFile.exists()) {
        val defaults = context.assets.open("config.properties")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        externalFile.writeText(defaults, Charsets.UTF_8)
    }

    val text = externalFile.readText(Charsets.UTF_8)
    return parseProperties(text).toAppConfig()
}
