// Asks an ONVIF camera for its real RTSP addresses: the main stream and a lighter preview stream.
// Requests are SOAP over HTTP, signed with a WS-Security password digest.
package pl.domowe.kamery

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

class OnvifException(message: String, val badLogin: Boolean = false) : IOException(message)

// One video source: a camera, or one channel of a recorder (NVR/DVR).
// previewUrl is empty when the source has no usable lighter stream.
data class OnvifStreams(val mainUrl: String, val previewUrl: String)

private data class Profile(
    val token: String,
    val source: String,
    val encoding: String,
    val width: Int,
    val height: Int,
) {
    val pixels get() = width * height
}

// Returns one entry per video source, in the order the device lists them.
suspend fun fetchStreams(device: FoundDevice, login: String, password: String): List<OnvifStreams> =
    withContext(Dispatchers.IO) {
        val deviceUrl = device.xaddr.ifBlank { "http://${device.ip}/onvif/device_service" }
        // The digest contains a timestamp; using the camera's clock avoids
        // "wrong password" errors when the phone and the camera disagree on the time.
        val auth = Auth(login, password, cameraClockOffset(deviceUrl))

        val capabilities = call(
            deviceUrl,
            "<tds:GetCapabilities><tds:Category>Media</tds:Category></tds:GetCapabilities>",
            auth,
        )
        // Some cameras serve everything on the device address.
        val mediaUrl = capabilities.first("Media")?.first("XAddr")?.text() ?: deviceUrl

        val profiles = call(mediaUrl, "<trt:GetProfiles/>", auth)
            .descendants("Profiles")
            .mapNotNull(::parseProfile)
        if (profiles.isEmpty()) throw OnvifException("Kamera nie zwróciła żadnego strumienia wideo")

        // A camera has one video source; a recorder has one per channel.
        profiles.groupBy { it.source }.values.map { sameSource ->
            val main = sameSource.maxBy { it.pixels }
            // JPEG streams over RTSP are unreliable, so they are never used for preview.
            val preview = sameSource
                .filter { it.token != main.token && it.encoding != "JPEG" && it.pixels < main.pixels }
                .minByOrNull { it.pixels }

            OnvifStreams(
                mainUrl = streamUri(mediaUrl, main.token, auth).withCredentials(login, password),
                previewUrl = preview
                    ?.let { streamUri(mediaUrl, it.token, auth).withCredentials(login, password) }
                    .orEmpty(),
            )
        }
    }

private fun parseProfile(element: Element): Profile? {
    val token = element.getAttribute("token").ifBlank { return null }
    // Audio-only or metadata profiles have no video encoder.
    val encoder = element.first("VideoEncoderConfiguration") ?: return null
    val resolution = encoder.first("Resolution")
    return Profile(
        token = token,
        source = element.first("VideoSourceConfiguration")?.first("SourceToken")?.text().orEmpty(),
        encoding = encoder.first("Encoding")?.text()?.uppercase().orEmpty(),
        width = resolution?.first("Width")?.text()?.toIntOrNull() ?: 0,
        height = resolution?.first("Height")?.text()?.toIntOrNull() ?: 0,
    )
}

private fun streamUri(mediaUrl: String, profileToken: String, auth: Auth): String {
    val body = "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>" +
        "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>" +
        "<trt:ProfileToken>${xmlEscape(profileToken)}</trt:ProfileToken></trt:GetStreamUri>"
    return call(mediaUrl, body, auth).first("Uri")?.text()
        ?: throw OnvifException("Kamera nie podała adresu strumienia")
}

// ONVIF returns addresses without login; the RTSP server needs it in the address.
private fun String.withCredentials(login: String, password: String): String {
    if (!startsWith("rtsp://", ignoreCase = true)) return this
    return "rtsp://${Uri.encode(login)}:${Uri.encode(password)}@" + substring("rtsp://".length)
}

// Milliseconds to add to the phone's clock to get the camera's clock; 0 if unknown.
private fun cameraClockOffset(deviceUrl: String): Long {
    return try {
        val utc = call(deviceUrl, "<tds:GetSystemDateAndTime/>", auth = null).first("UTCDateTime")
            ?: return 0L
        fun part(name: String) = utc.first(name)?.text()?.toIntOrNull() ?: 0
        val cameraTime = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(part("Year"), part("Month") - 1, part("Day"), part("Hour"), part("Minute"), part("Second"))
        }
        cameraTime.timeInMillis - System.currentTimeMillis()
    } catch (e: IOException) {
        0L
    }
}

private class Auth(val login: String, val password: String, val clockOffsetMs: Long) {
    // WS-Security UsernameToken: digest = base64(sha1(nonce + created + password)).
    fun header(): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(System.currentTimeMillis() + clockOffsetMs))
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(nonce + created.toByteArray(Charsets.UTF_8) + password.toByteArray(Charsets.UTF_8))
        return """
            <wsse:Security s:mustUnderstand="1"
                xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
                xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
              <wsse:UsernameToken>
                <wsse:Username>${xmlEscape(login)}</wsse:Username>
                <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">${base64(digest)}</wsse:Password>
                <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${base64(nonce)}</wsse:Nonce>
                <wsu:Created>$created</wsu:Created>
              </wsse:UsernameToken>
            </wsse:Security>
        """.trimIndent()
    }
}

private fun call(url: String, body: String, auth: Auth?): Document {
    val envelope = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
            xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
            xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
            xmlns:tt="http://www.onvif.org/ver10/schema">
          <s:Header>${auth?.header().orEmpty()}</s:Header>
          <s:Body>$body</s:Body>
        </s:Envelope>
    """.trimIndent()

    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
        connection.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        if (code == 401 || "NotAuthorized" in text) {
            throw OnvifException("Zły login lub hasło", badLogin = true)
        }
        if (code >= 400) throw OnvifException("Kamera odrzuciła zapytanie (HTTP $code)")
        return parseXml(text)
    } finally {
        connection.disconnect()
    }
}

private fun parseXml(text: String): Document = try {
    DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(InputSource(StringReader(text)))
} catch (e: SAXException) {
    throw OnvifException("Niezrozumiała odpowiedź kamery")
}

// Finds elements by local name, ignoring namespace prefixes that differ between brands.
private fun Node.descendants(name: String): List<Element> {
    val nodes = when (this) {
        is Document -> getElementsByTagNameNS("*", name)
        is Element -> getElementsByTagNameNS("*", name)
        else -> return emptyList()
    }
    return List(nodes.length) { nodes.item(it) as Element }
}

private fun Node.first(name: String): Element? = descendants(name).firstOrNull()

private fun Element.text(): String? = textContent?.trim()?.ifEmpty { null }

private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

private fun xmlEscape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
