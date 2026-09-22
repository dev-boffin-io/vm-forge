package io.boffin.proot.ui.screens.downloader

import android.content.Context
import com.rk.libcommons.child
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class InstallException(message: String) : Exception(message)

/**
 * Streams a URL's response body into context.filesDir/<outputFileName>, chunked with a
 * progress callback, via a .part temp file + atomic rename on success. Shared by the
 * manifest-indirected download (NetHunterInstaller) and the direct user-entered URL download
 * (Boffin).
 */
private fun downloadUrlToFile(
    url: String,
    outputFile: File,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    label: String,
    onProgress: (Int) -> Unit
) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        instanceFollowRedirects = true
    }
    connection.connect()
    if (connection.responseCode !in 200..299) {
        throw InstallException("Failed to download $label rootfs: HTTP ${connection.responseCode}")
    }

    val totalSize = connection.contentLengthLong
    val tempFile = File(outputFile.path + ".part")

    connection.inputStream.use { input ->
        FileOutputStream(tempFile).use { output ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var totalRead = 0L
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalSize > 0) {
                    onProgress(((totalRead * 100) / totalSize).toInt())
                }
            }
        }
    }

    if (!tempFile.renameTo(outputFile)) {
        throw InstallException("Failed to finalize downloaded $label rootfs")
    }
}

/**
 * Fetches a manifest.json, then downloads whatever URL it contains, into
 * context.filesDir/<outputFileName>. The manifest is a tiny JSON file kept in the repo
 * (NOT bundled as an APK asset) so its content — the actual rootfs download URL — can be
 * updated at any time on GitHub without rebuilding the app.
 */
private fun downloadManifestRootfs(
    context: Context,
    manifestUrl: String,
    outputFileName: String,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    label: String,
    onProgress: (Int) -> Unit
) {
    val outputFile = context.filesDir.child(outputFileName)
    if (outputFile.exists() && outputFile.length() > 0L) {
        return
    }

    val manifestConnection = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        instanceFollowRedirects = true
    }
    manifestConnection.connect()
    if (manifestConnection.responseCode !in 200..299) {
        throw InstallException("Failed to fetch $label manifest: HTTP ${manifestConnection.responseCode}")
    }
    val manifestText = manifestConnection.inputStream.bufferedReader().use { it.readText() }
    val downloadUrl = JSONObject(manifestText).getString("url")

    downloadUrlToFile(downloadUrl, outputFile, connectTimeoutMs, readTimeoutMs, label, onProgress)
}

/**
 * Downloads a rootfs archive straight from a URL the user typed in (no manifest indirection,
 * no file picker, no special storage permission - just INTERNET, which the app already has).
 * Used by "Boffin": earlier attempts routed this through the system file picker (SAF) and then
 * a fixed shared-storage path, but on at least one real device the SAF round-trip crashes
 * inside the OS itself (a MIUI-side bug in DocumentsUI's result delivery), and the fixed-path
 * approach needed "All files access", which was more friction than just asking for a URL.
 */
fun downloadDirectRootfs(
    context: Context,
    url: String,
    outputFileName: String,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    onProgress: (Int) -> Unit
) {
    val outputFile = context.filesDir.child(outputFileName)
    if (outputFile.exists() && outputFile.length() > 0L) {
        return
    }
    downloadUrlToFile(url, outputFile, connectTimeoutMs, readTimeoutMs, "Boffin", onProgress)
}

/**
 * NetHunter session (was briefly relabelled "Custom" in the UI; renamed back since "Custom"
 * is now upstream's own, differently implemented Custom Session/chroot feature). Manifest/
 * output filenames unchanged throughout so existing installs don't need to re-download.
 */
object NetHunterInstaller {
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/dev-boffin-io/proot-forge/main/nethunter-manifest.json"

    fun downloadIfNeeded(context: Context, onProgress: (Int) -> Unit) {
        downloadManifestRootfs(
            context = context,
            manifestUrl = MANIFEST_URL,
            outputFileName = "nethunter.tar.xz",
            connectTimeoutMs = 15_000,
            readTimeoutMs = 15_000,
            label = "NetHunter",
            onProgress = onProgress
        )
    }
}
