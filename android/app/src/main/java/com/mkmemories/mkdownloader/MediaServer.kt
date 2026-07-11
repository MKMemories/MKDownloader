package com.mkmemories.mkdownloader

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Mini-serveur HTTP local : expose un fichier téléchargé (content://) sur le
 * Wi-Fi pour qu'une TV DLNA (Samsung, etc.) puisse le lire directement.
 * Gère l'en-tête `Range` (indispensable pour l'avance/retour sur la TV).
 */
object MediaServer {

    private data class Item(val uri: Uri, val mime: String, val size: Long)

    private var server: ServerSocket? = null

    @Volatile private var current: Item? = null

    /** Publie le fichier et renvoie son URL http://ip:port/media.ext (ou null). */
    fun serve(context: Context, uri: Uri, mime: String, name: String): String? {
        val app = context.applicationContext
        val size = runCatching {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
        if (size <= 0) return null
        current = Item(uri, mime, size)
        val port = ensureStarted(app) ?: return null
        val ip = lanIp() ?: return null
        val ext = name.substringAfterLast('.', "").ifBlank { extFor(mime) }
        return "http://$ip:$port/media.$ext"
    }

    private fun ensureStarted(context: Context): Int? {
        server?.let { if (!it.isClosed) return it.localPort }
        return runCatching {
            val s = ServerSocket(0)
            server = s
            Thread {
                while (!s.isClosed) {
                    val sock = try { s.accept() } catch (_: Exception) { break }
                    Thread { runCatching { handle(context, sock) }; runCatching { sock.close() } }
                        .apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
            s.localPort
        }.getOrNull()
    }

    private fun handle(context: Context, sock: Socket) {
        val reader = sock.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        var rangeStart = 0L
        var rangeEnd = -1L
        var hasRange = false
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) {
                Regex("bytes=(\\d*)-(\\d*)").find(line)?.let { m ->
                    rangeStart = m.groupValues[1].toLongOrNull() ?: 0L
                    rangeEnd = m.groupValues[2].toLongOrNull() ?: -1L
                    hasRange = true
                }
            }
        }
        val item = current ?: return
        val total = item.size
        val end = if (rangeEnd in 0 until total) rangeEnd else total - 1
        val start = if (rangeStart in 0..end) rangeStart else 0L
        val length = end - start + 1
        val isHead = requestLine.startsWith("HEAD", ignoreCase = true)

        val out = BufferedOutputStream(sock.getOutputStream())
        val head = buildString {
            append(if (hasRange) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${item.mime}\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $length\r\n")
            if (hasRange) append("Content-Range: bytes $start-$end/$total\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        if (isHead) { out.flush(); return }

        context.contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                fis.channel.position(start)
                val buf = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val r = fis.read(buf, 0, toRead)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    remaining -= r
                }
            }
        }
        out.flush()
    }

    private fun lanIp(): String? {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        for (ni in ifaces) {
            val usable = runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)
            if (!usable) continue
            for (addr in ni.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }

    private fun extFor(mime: String): String = when {
        mime.contains("mp4") -> "mp4"
        mime.contains("webm") -> "webm"
        mime.contains("matroska") -> "mkv"
        mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
        mime.contains("aac") || mime.contains("m4a") -> "m4a"
        else -> "mp4"
    }
}
