// Finds ONVIF cameras in the local network with WS-Discovery:
// a UDP multicast "probe" is sent to 239.255.255.250:3702 and cameras answer directly.
package pl.domowe.kamery

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.UUID

enum class Vendor { HIKVISION, DAHUA, REOLINK, OTHER }

// xaddr: the camera's ONVIF service address, needed later to ask it for stream addresses.
data class FoundDevice(
    val ip: String,
    val name: String,
    val model: String,
    val vendor: Vendor,
    val xaddr: String,
)

private const val DISCOVERY_ADDRESS = "239.255.255.250"
private const val DISCOVERY_PORT = 3702

// Throws IOException when the network cannot be used (e.g. Wi-Fi is off).
suspend fun discoverCameras(context: Context, timeoutMs: Long = 3_000): List<FoundDevice> =
    withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Without this lock many phones drop incoming multicast traffic to save battery.
        val lock = wifi.createMulticastLock("onvif-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val group = InetAddress.getByName(DISCOVERY_ADDRESS)
                // Some cameras answer only a typed probe, some only an untyped one;
                // UDP can lose packets, so everything is sent twice.
                repeat(2) {
                    listOf("dn:NetworkVideoTransmitter", null).forEach { types ->
                        val bytes = probeMessage(types).toByteArray(Charsets.UTF_8)
                        socket.send(DatagramPacket(bytes, bytes.size, group, DISCOVERY_PORT))
                    }
                }

                val found = LinkedHashMap<String, FoundDevice>()
                val buffer = ByteArray(65_535)
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    ensureActive()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val ip = packet.address.hostAddress ?: continue
                    if (ip in found) continue
                    val xml = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    parseProbeMatch(ip, xml)?.let { found[ip] = it }
                }
                found.values.sortedBy { ipToNumber(it.ip) }
            }
        } finally {
            lock.release()
        }
    }

private fun probeMessage(types: String?): String = """
    <?xml version="1.0" encoding="UTF-8"?>
    <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
        xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
        xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
        xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
      <s:Header>
        <a:MessageID>uuid:${UUID.randomUUID()}</a:MessageID>
        <a:To s:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>
        <a:Action s:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>
      </s:Header>
      <s:Body><d:Probe>${if (types != null) "<d:Types>$types</d:Types>" else ""}</d:Probe></s:Body>
    </s:Envelope>
""".trimIndent()

// Matches <Scopes>…</Scopes> and <XAddrs>…</XAddrs> with any namespace prefix.
private fun tagText(xml: String, tag: String): String =
    Regex("<(?:\\w+:)?$tag\\b[^>]*>([^<]*)</").find(xml)?.groupValues?.get(1)?.trim().orEmpty()

private fun parseProbeMatch(ip: String, xml: String): FoundDevice? {
    if (!xml.contains("ProbeMatch")) return null
    // Scopes look like "onvif://www.onvif.org/name/HIKVISION%20DS-2CD2143G2-I onvif://…/hardware/…".
    val scopes = tagText(xml, "Scopes").split(Regex("\\s+"))
    fun scope(kind: String): String = scopes
        .firstOrNull { it.startsWith("onvif://www.onvif.org/$kind/") }
        ?.substringAfter("onvif://www.onvif.org/$kind/")
        // A malformed "%" sequence must not break the whole search.
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        .orEmpty()

    val name = scope("name")
    val model = scope("hardware")
    return FoundDevice(
        ip = ip,
        name = name,
        model = model,
        vendor = detectVendor(scopes.joinToString(" "), model),
        xaddr = tagText(xml, "XAddrs").split(Regex("\\s+")).firstOrNull().orEmpty(),
    )
}

// Brand names in scopes are not standardised, so model prefixes are checked as well.
private fun detectVendor(scopes: String, model: String): Vendor {
    val text = scopes.lowercase()
    val upperModel = model.uppercase()
    return when {
        "hikvision" in text || upperModel.startsWith("DS-") -> Vendor.HIKVISION
        "dahua" in text || upperModel.startsWith("IPC-") || upperModel.startsWith("DH-") -> Vendor.DAHUA
        "reolink" in text || upperModel.startsWith("RLC-") || upperModel.startsWith("RLN") -> Vendor.REOLINK
        else -> Vendor.OTHER
    }
}

private fun ipToNumber(ip: String): Long =
    ip.split('.').fold(0L) { acc, part -> acc * 256 + (part.toLongOrNull() ?: 0) }
