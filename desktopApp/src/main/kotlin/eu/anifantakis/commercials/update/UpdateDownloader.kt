package eu.anifantakis.commercials.update

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Downloads an installer and hands it to the OS. Runs on Dispatchers.IO from
 * the dialog - everything here is plain blocking JDK I/O.
 */
object UpdateDownloader {

    /**
     * Streams [url] into the system temp dir, reporting progress as 0f..1f -
     * or null when the server sent no Content-Length (indeterminate bar).
     * Throws on any failure; the dialog catches and shows the reason.
     */
    fun download(url: String, onProgress: (Float?) -> Unit): File {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }

        val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        val target = File(
            File(System.getProperty("java.io.tmpdir")),
            fileNameFrom(url),
        )

        response.body().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                var received = 0L
                onProgress(if (total > 0) 0f else null)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    received += n
                    onProgress(if (total > 0) received.toFloat() / total else null)
                }
            }
        }
        return target
    }

    /** Last path segment of [url], URL-decoded ("CM%202-1.1.0.msi" → "CM 2-1.1.0.msi"). */
    fun fileNameFrom(url: String): String {
        val last = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(last, StandardCharsets.UTF_8) }.getOrDefault(last)
        return decoded.ifBlank { "installer" }
    }

    /**
     * Hands the downloaded installer to the OS installer flow and returns;
     * the caller then EXITS the app - on Windows especially, MSI cannot
     * replace files the running app still holds open.
     *
     * - Windows: msiexec runs the wizard; the upgradeUuid in the package
     *   makes it an in-place upgrade.
     * - macOS: `open` mounts the DMG; the user drags the app to Applications
     *   (the jpackage DMG carries no auto-installer).
     * - Linux: xdg-open routes the .deb to the distro's package installer.
     */
    fun launchInstaller(file: File, os: HostOs = HostOs.detect()) {
        val command = when (os) {
            HostOs.WINDOWS -> listOf("msiexec", "/i", file.absolutePath)
            HostOs.MAC -> listOf("open", file.absolutePath)
            HostOs.LINUX -> listOf("xdg-open", file.absolutePath)
        }
        ProcessBuilder(command).start()
    }
}
