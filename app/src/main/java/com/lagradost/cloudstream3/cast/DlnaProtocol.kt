package com.lagradost.cloudstream3.cast

import java.net.URI

/** A DLNA/UPnP media renderer found on the local network (a TV, Kodi, ...). */
data class DlnaDevice(
    val id: String,
    val name: String,
    val avTransportUrl: String,
    val renderingControlUrl: String?,
)

/** Pure DLNA/UPnP helpers: parsing and message building, no I/O (unit tested). */
object DlnaProtocol {
    const val SSDP_HOST = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val RENDERER_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    fun searchRequest(target: String = RENDERER_TYPE): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $target\r\n\r\n"

    /** Returns the LOCATION header of an SSDP response, or null when it is not a usable reply. */
    fun ssdpLocation(response: String): String? {
        val lines = response.split("\r\n", "\n")
        if (lines.firstOrNull()?.let { it.startsWith("HTTP/1.1 200") || it.startsWith("NOTIFY") } != true) return null
        return lines.firstNotNullOfOrNull { line ->
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("location", ignoreCase = true))
                line.substring(colon + 1).trim().takeIf { it.startsWith("http") }
            else null
        }
    }

    /** Parses a UPnP device description; null when the device has no AVTransport service. */
    fun parseDescription(xml: String, location: String): DlnaDevice? {
        val base = tag(xml, "URLBase")?.takeIf { it.startsWith("http") } ?: location
        val services = Regex("<(?:\\w+:)?service>(.*?)</(?:\\w+:)?service>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }.toList()
        fun control(type: String): String? = services
            .firstOrNull { tag(it, "serviceType")?.startsWith(type.substringBeforeLast(':')) == true }
            ?.let { tag(it, "controlURL") }
            ?.let { resolve(base, it) }
        val av = control(AV_TRANSPORT) ?: return null
        val name = tag(xml, "friendlyName")?.let(::unescape)?.takeIf { it.isNotBlank() } ?: URI(location).host
        val id = tag(xml, "UDN") ?: location
        return DlnaDevice(id, name, av, control(RENDERING_CONTROL))
    }

    fun resolve(base: String, path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else URI(base).resolve(path.trim()).toString()

    fun soapEnvelope(service: String, action: String, args: List<Pair<String, String>>): String {
        val body = args.joinToString("") { (k, v) -> "<$k>${escape(v)}</$k>" }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$service\">$body</u:$action></s:Body></s:Envelope>"
    }

    fun soapAction(service: String, action: String) = "\"$service#$action\""

    /** DIDL-Lite item metadata. Returned unescaped; [soapEnvelope] escapes it as an argument. */
    fun didl(title: String, url: String, mime: String, subtitleUrl: String? = null): String {
        val protocol = "http-get:*:$mime:${dlnaFlags(mime)}"
        // Samsung TVs read subtitles from sec:CaptionInfoEx.
        val sub = subtitleUrl?.let {
            "<sec:CaptionInfoEx sec:type=\"srt\">${escape(it)}</sec:CaptionInfoEx>"
        }.orEmpty()
        // LG and Sony TVs read them from PacketVideo attributes on the video resource.
        val pv = subtitleUrl?.let { " pv:subtitleFileUri=\"${escape(it)}\" pv:subtitleFileType=\"srt\"" }.orEmpty()
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:sec=\"http://www.sec.co.kr/\" " +
            "xmlns:pv=\"http://www.pv.com/pvns/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${escape(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            sub +
            "<res protocolInfo=\"${escape(protocol)}\"$pv>${escape(url)}</res>" +
            // Kodi and many renderers pick subtitles up from an extra text/srt resource.
            (subtitleUrl?.let { "<res protocolInfo=\"http-get:*:text/srt:*\">${escape(it)}</res>" }.orEmpty()) +
            "</item></DIDL-Lite>"
    }

    fun dlnaFlags(mime: String): String =
        if (mime.contains("mpegurl")) "*"
        else "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    /** UPnP time "H:MM:SS" (optionally with fraction) to milliseconds; null for NOT_IMPLEMENTED etc. */
    fun parseTime(value: String?): Long? {
        val parts = value?.trim()?.split(':') ?: return null
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return ((h * 3600 + m * 60) * 1000 + s * 1000).toLong()
    }

    fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()

    /** SOAP fault detection: a fault body or an HTTP error both mean the action failed. */
    fun isFault(xml: String): Boolean = xml.contains("Fault>") || xml.contains(":Fault")

    fun escape(value: String): String = buildString(value.length) {
        for (c in value) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    fun unescape(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
