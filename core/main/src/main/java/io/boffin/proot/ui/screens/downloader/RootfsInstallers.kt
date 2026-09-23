package io.boffin.proot.ui.screens.downloader

import android.content.Context
import com.rk.libcommons.child
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class InstallException(message: String) : Exception(message)

/**
 * Streams a URL's response body into context.filesDir/<outputFileName>, chunked with a
 * progress callback, via a .part temp file + atomic rename on success. Shared by the
 * DebianInstaller (official Debian rootfs) and the direct user-entered URL download (Boffin).
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
 * Official Debian rootfs for the main container session. The tarball is the same one the
 * Debian Project's own debuerreotype tooling publishes for its official Docker images
 * (`github.com/debuerreotype/docker-debian-artifacts`), fetched per-ABI directly from the
 * `stable` suite. It streams into `filesDir/debian.tar.gz` and is unpacked by init-host.sh
 * into `local/debian`.
 */
object DebianInstaller {
    private const val ROOTFS_RELEASE_BASE_URL =
        "https://github.com/debuerreotype/docker-debian-artifacts/raw"
    // Debian suite to fetch ("stable", "testing", "unstable", ...).
    private const val SUITE = "stable"

    private val abiToDebuerreotypeArch = mapOf(
        "arm64-v8a" to "arm64v8",
        "armeabi-v7a" to "arm32v7",
        "x86_64" to "amd64"
    )

    fun downloadIfNeeded(context: Context, onProgress: (Int) -> Unit) {
        val abi = abiToDebuerreotypeArch.keys.firstOrNull { it in android.os.Build.SUPPORTED_ABIS }
            ?: throw InstallException(
                "Unsupported CPU architecture: ${android.os.Build.SUPPORTED_ABIS.joinToString()}"
            )
        val arch = abiToDebuerreotypeArch.getValue(abi)
        val url = "$ROOTFS_RELEASE_BASE_URL/dist-$arch/$SUITE/oci/blobs/rootfs.tar.gz"
        downloadUrlToFile(
            url = url,
            outputFile = context.filesDir.child("debian.tar.gz"),
            connectTimeoutMs = 15_000,
            readTimeoutMs = 15_000,
            label = "Debian",
            onProgress = onProgress
        )
    }
}
