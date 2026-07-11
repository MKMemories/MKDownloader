package com.mkmemories.mkdownloader

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Diffusion vers une TV **DLNA/UPnP** (ex. Samsung Smart TV, qui n'est pas un
 * récepteur Google Cast). Découverte SSDP des « MediaRenderer », puis commandes
 * SOAP AVTransport (SetAVTransportURI + Play). Sans dépendance externe.
 *
 * Marche pour les **URL de flux HTTP directes** (directs HLS, flux résolus).
 * Les fichiers locaux (content://) ne sont pas diffusables tels quels.
 */
object Dlna {

    data class Renderer(val name: String, val controlUrl: String, val serviceType: String)

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"

    suspend fun discover(context: Context, timeoutMs: Int = 3500): List<Renderer> =
        withContext(Dispatchers.IO) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("mkdl-dlna")?.apply {
                setReferenceCounted(true); runCatching { acquire() }
            }
            val found = LinkedHashMap<String, Renderer>()
            try {
                val socket = DatagramSocket().apply { soTimeout = 700; broadcast = true }
                val search = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $TARGET\r\n\r\n"
                    ).toByteArray()
                val addr = InetAddress.getByName(SSDP_ADDR)
                repeat(2) { socket.send(DatagramPacket(search, search.size, addr, SSDP_PORT)) }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                val seen = HashSet<String>()
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { socket.receive(pkt) } catch (_: SocketTimeoutException) { continue }
                    val resp = String(pkt.data, 0, pkt.length)
                    val loc = Regex("(?i)LOCATION:\\s*(\\S+)").find(resp)?.groupValues?.get(1)?.trim() ?: continue
                    if (!seen.add(loc)) continue
                    runCatching { parseDevice(loc) }.getOrNull()?.let { found[it.controlUrl] = it }
                }
                socket.close()
            } catch (_: Exception) {
            } finally {
                lock?.runCatching { if (isHeld) release() }
            }
            found.values.toList()
        }

    private fun parseDevice(location: String): Renderer? {
        val xml = fetch(location) ?: return null
        val name = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "TV" }
        val svc = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("AVTransport") } ?: return null
        val type = Regex("<serviceType>(.*?)</serviceType>").find(svc)?.groupValues?.get(1)?.trim() ?: return null
        val ctrl = Regex("<controlURL>(.*?)</controlURL>").find(svc)?.groupValues?.get(1)?.trim() ?: return null
        val base = baseUrl(location)
        val controlUrl = when {
            ctrl.startsWith("http") -> ctrl
            ctrl.startsWith("/") -> base + ctrl
            else -> "$base/$ctrl"
        }
        return Renderer(name, controlUrl, type)
    }

    /** Envoie l'URL au renderer puis lance la lecture. Renvoie true si accepté. */
    suspend fun cast(renderer: Renderer, url: String, title: String, mime: String): Boolean =
        withContext(Dispatchers.IO) {
            val meta = didl(title, url, mime)
            val setArgs = "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${esc(url)}</CurrentURI>" +
                "<CurrentURIMetaData>${esc(meta)}</CurrentURIMetaData>"
            if (!soapPost(renderer, "SetAVTransportURI", setArgs)) return@withContext false
            soapPost(renderer, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        }

    private fun soapPost(renderer: Renderer, action: String, argsXml: String): Boolean {
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"${renderer.serviceType}\">$argsXml</u:$action></s:Body></s:Envelope>"
        return runCatching {
            val conn = (URL(renderer.controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", "\"${renderer.serviceType}#$action\"")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        }.getOrDefault(false)
    }

    private fun didl(title: String, url: String, mime: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${esc(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:*\">${esc(url)}</res>" +
            "</item></DIDL-Lite>"

    private fun fetch(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 6000
        }
        try { conn.inputStream.use { it.readBytes().decodeToString() } } finally { conn.disconnect() }
    }.getOrNull()

    private fun baseUrl(location: String): String {
        val u = URL(location)
        val port = if (u.port == -1) "" else ":${u.port}"
        return "${u.protocol}://${u.host}$port"
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
