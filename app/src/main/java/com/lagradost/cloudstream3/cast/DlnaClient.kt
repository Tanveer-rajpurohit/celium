package com.lagradost.cloudstream3.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.concurrent.thread
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Finds DLNA renderers on the Wi-Fi network using SSDP. */
object DlnaDiscovery {
    suspend fun scan(context: Context, timeoutMs: Long = 4_000): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("celium-cast")?.apply { setReferenceCounted(false); acquire() }
        val locations = try { search(timeoutMs) } finally { runCatching { lock?.release() } }
        describe(locations) { app.get(it, timeout = 4L).text }
    }

    /** Local IPv4 LAN addresses that can multicast (Wi-Fi, Ethernet). */
    private fun lanAddresses(): List<Pair<NetworkInterface, InetAddress>> =
        runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
            .flatMap { nif -> nif.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { nif to it } }

    /**
     * Sends M-SEARCH on every LAN interface (a phone or PC may also have mobile data, VPN or
     * virtual adapters, where the default route would miss the TV) and returns description URLs.
     */
    fun search(timeoutMs: Long = 4_000): Set<String> {
        val locations = java.util.Collections.synchronizedSet(linkedSetOf<String>())
        val group = InetAddress.getByName(DlnaProtocol.SSDP_HOST)
        val targets = listOf(DlnaProtocol.RENDERER_TYPE, DlnaProtocol.AV_TRANSPORT)
        val sockets = lanAddresses().mapNotNull { (nif, address) ->
            runCatching {
                MulticastSocket(InetSocketAddress(address, 0)).apply {
                    networkInterface = nif
                    timeToLive = 2
                    soTimeout = 300
                }
            }.getOrNull()
        }.ifEmpty { listOfNotNull(runCatching { MulticastSocket(0).apply { soTimeout = 300 } }.getOrNull()) }
        try {
            val readers = sockets.map { socket ->
                thread(isDaemon = true, name = "ssdp") {
                    val buffer = ByteArray(4096)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    var sent = 0
                    while (System.currentTimeMillis() < deadline) {
                        if (sent < 3) { // repeat: UDP multicast is lossy
                            targets.forEach { target ->
                                val bytes = DlnaProtocol.searchRequest(target).toByteArray()
                                runCatching { socket.send(DatagramPacket(bytes, bytes.size, group, DlnaProtocol.SSDP_PORT)) }
                            }
                            sent++
                        }
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            DlnaProtocol.ssdpLocation(String(packet.data, 0, packet.length))?.let(locations::add)
                        } catch (_: SocketTimeoutException) {
                        } catch (_: IOException) {
                            break
                        }
                    }
                }
            }
            readers.forEach { it.join(timeoutMs + 1_000) }
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
        return synchronized(locations) { locations.toSet() }
    }

    suspend fun describe(locations: Set<String>, fetch: suspend (String) -> String): List<DlnaDevice> = coroutineScope {
        locations.map { location ->
            async(Dispatchers.IO) { runCatching { DlnaProtocol.parseDescription(fetch(location), location) }.getOrNull() }
        }.awaitAll().filterNotNull().distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }
}

/** Sends UPnP AVTransport/RenderingControl commands to one renderer. */
class DlnaRenderer(val device: DlnaDevice, baseClient: OkHttpClient) {
    data class Status(val state: String, val positionMs: Long?, val durationMs: Long?, val trackUri: String? = null)

    private val client = baseClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val xml = "text/xml; charset=\"utf-8\"".toMediaType()

    val host: String get() = java.net.URI(device.avTransportUrl).host

    suspend fun load(url: String, metadata: String) =
        call(DlnaProtocol.AV_TRANSPORT, "SetAVTransportURI", "CurrentURI" to url, "CurrentURIMetaData" to metadata)

    suspend fun play() = call(DlnaProtocol.AV_TRANSPORT, "Play", "Speed" to "1")
    suspend fun pause() = call(DlnaProtocol.AV_TRANSPORT, "Pause")
    suspend fun stop() = call(DlnaProtocol.AV_TRANSPORT, "Stop")
    /** Renderers differ in the seek unit they accept; try the common one first. */
    suspend fun seek(ms: Long) {
        val target = DlnaProtocol.formatTime(ms)
        try {
            call(DlnaProtocol.AV_TRANSPORT, "Seek", "Unit" to "REL_TIME", "Target" to target)
        } catch (e: IOException) {
            call(DlnaProtocol.AV_TRANSPORT, "Seek", "Unit" to "ABS_TIME", "Target" to target)
        }
    }

    suspend fun status(): Status {
        val transport = call(DlnaProtocol.AV_TRANSPORT, "GetTransportInfo")
        val position = runCatching { call(DlnaProtocol.AV_TRANSPORT, "GetPositionInfo") }.getOrNull()
        return Status(
            state = DlnaProtocol.tag(transport, "CurrentTransportState") ?: "UNKNOWN",
            positionMs = DlnaProtocol.parseTime(position?.let { DlnaProtocol.tag(it, "RelTime") }),
            durationMs = DlnaProtocol.parseTime(position?.let { DlnaProtocol.tag(it, "TrackDuration") })?.takeIf { it > 0 },
            trackUri = position?.let { DlnaProtocol.tag(it, "TrackURI") }?.let(DlnaProtocol::unescape)?.takeIf { it.isNotBlank() },
        )
    }

    /** Returns the SOAP response body; throws when the renderer rejects the action. */
    private suspend fun call(service: String, action: String, vararg args: Pair<String, String>): String = withContext(Dispatchers.IO) {
        val url = if (service == DlnaProtocol.RENDERING_CONTROL) device.renderingControlUrl ?: throw IOException("No RenderingControl")
        else device.avTransportUrl
        val envelope = DlnaProtocol.soapEnvelope(service, action, listOf("InstanceID" to "0") + args)
        val request = Request.Builder().url(url)
            .header("SOAPACTION", DlnaProtocol.soapAction(service, action))
            .post(envelope.toRequestBody(xml))
            .build()
        client.newCall(request).execute().use { response ->
            @Suppress("UNNECESSARY_SAFE_CALL")
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful || DlnaProtocol.isFault(text)) {
                val code = DlnaProtocol.tag(text, "errorDescription") ?: DlnaProtocol.tag(text, "errorCode") ?: response.code.toString()
                throw IOException("$action rejected: $code")
            }
            text
        }
    }
}
