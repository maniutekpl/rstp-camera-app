// Searches the Wi-Fi network for ONVIF cameras and turns a picked one into a pre-filled camera.
package pl.domowe.kamery

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

@Composable
fun ScanScreen(
    savedCameras: List<Camera>,
    onPick: (Camera) -> Unit,
    onAddMany: (List<Camera>) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<FoundDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped by "Szukaj ponownie" to restart the search.
    var scanRound by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<FoundDevice?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var onvifError by remember { mutableStateOf<String?>(null) }
    // Recorder found after login: the device and its channels, waiting for the user's choice.
    var channelChoice by remember { mutableStateOf<Pair<FoundDevice, List<OnvifStreams>>?>(null) }
    val scope = rememberCoroutineScope()
    val savedHosts = savedCameras.mapNotNull { Uri.parse(it.url).host }.toSet()

    LaunchedEffect(scanRound) {
        scanning = true
        error = null
        try {
            devices = discoverCameras(context)
        } catch (e: IOException) {
            error = "Nie udało się przeszukać sieci: ${e.message}"
        }
        scanning = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Szukaj kamer",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onBack) { Text("Wróć") }
        }
        val message = error
        Text(
            when {
                scanning -> "Szukam kamer w sieci Wi-Fi…"
                message != null -> message
                devices.isEmpty() ->
                    "Nie znaleziono kamer. Sprawdź, czy telefon jest w tej samej sieci Wi-Fi " +
                        "i czy kamery mają włączony ONVIF."
                else -> "Znalezione kamery: ${devices.size}. Dotknij kamery, aby ją dodać."
            },
        )
        Button(onClick = { scanRound++ }, enabled = !scanning) { Text("Szukaj ponownie") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.ip }) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { picked = device },
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(device.name.ifBlank { "Kamera" }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOf(device.model, device.ip).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (device.ip in savedHosts) {
                            Text(
                                "Już jest na liście",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    picked?.let { device ->
        CredentialsDialog(
            device = device,
            busy = connecting,
            error = loginError,
            onvifError = onvifError,
            onDismiss = {
                if (!connecting) {
                    picked = null
                    loginError = null
                    onvifError = null
                }
            },
            onConfirm = { login, password ->
                connecting = true
                loginError = null
                onvifError = null
                scope.launch {
                    val sources = try {
                        fetchStreams(device, login, password)
                    } catch (e: IOException) {
                        Log.w("ScanScreen", "ONVIF failed for ${device.ip}", e)
                        if (e is OnvifException && e.badLogin) {
                            loginError = e.message
                        } else {
                            onvifError = "Nie udało się pobrać ustawień z kamery: ${e.message}"
                        }
                        null
                    }
                    connecting = false
                    if (sources != null) {
                        picked = null
                        if (sources.size == 1) {
                            val streams = sources.single()
                            onPick(Camera(UUID.randomUUID().toString(), device.displayName(), streams.mainUrl, streams.previewUrl))
                        } else {
                            // A recorder: let the user choose which channels to add.
                            channelChoice = device to sources
                        }
                    }
                }
            },
            onUseGuess = { login, password ->
                picked = null
                onvifError = null
                onPick(guessCamera(device, login, password))
            },
        )
    }

    channelChoice?.let { (device, sources) ->
        ChannelsDialog(
            device = device,
            channelCount = sources.size,
            onDismiss = { channelChoice = null },
            onConfirm = { chosen ->
                channelChoice = null
                onAddMany(
                    chosen.sorted().map { index ->
                        Camera(
                            id = UUID.randomUUID().toString(),
                            name = "${device.displayName()} – kanał ${index + 1}",
                            url = sources[index].mainUrl,
                            previewUrl = sources[index].previewUrl,
                        )
                    }
                )
            },
        )
    }
}

@Composable
private fun ChannelsDialog(
    device: FoundDevice,
    channelCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (chosen: Set<Int>) -> Unit,
) {
    var chosen by remember { mutableStateOf((0 until channelCount).toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wybierz kanały") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("${device.displayName()}, liczba kanałów: $channelCount. Każdy zostanie osobną kamerą.")
                for (index in 0 until channelCount) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chosen = chosen.toggle(index) },
                    ) {
                        Checkbox(checked = index in chosen, onCheckedChange = { chosen = chosen.toggle(index) })
                        Text("Kanał ${index + 1}")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen) }, enabled = chosen.isNotEmpty()) {
                Text("Dodaj (${chosen.size})")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

private fun Set<Int>.toggle(value: Int): Set<Int> = if (value in this) this - value else this + value

@Composable
private fun CredentialsDialog(
    device: FoundDevice,
    busy: Boolean,
    error: String?,
    onvifError: String?,
    onDismiss: () -> Unit,
    onConfirm: (login: String, password: String) -> Unit,
    onUseGuess: (login: String, password: String) -> Unit,
) {
    // "admin" is the factory login of Hikvision, Dahua and Reolink.
    var login by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Logowanie do kamery") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${device.name.ifBlank { "Kamera" }} (${device.ip})")
                OutlinedTextField(
                    value = login,
                    onValueChange = { login = it },
                    label = { Text("Login") },
                    singleLine = true,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Hasło") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (busy) Text("Pobieram ustawienia z kamery…")
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                if (onvifError != null) {
                    Text(onvifError, color = MaterialTheme.colorScheme.error)
                    // The brand's usual addresses; the password is not checked in this case.
                    TextButton(onClick = { onUseGuess(login.trim(), password) }, enabled = !busy) {
                        Text("Użyj typowych adresów")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(login.trim(), password) }, enabled = login.isNotBlank() && !busy) {
                Text("Dalej")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

private fun FoundDevice.displayName(): String = name.ifBlank { "Kamera $ip" }

// Fallback when ONVIF cannot give the addresses: usual main and sub stream paths of each brand.
private fun guessCamera(device: FoundDevice, login: String, password: String): Camera {
    // Uri.encode escapes characters like @ : / that would break the address.
    val base = "rtsp://${Uri.encode(login)}:${Uri.encode(password)}@${device.ip}:554"
    val (main, sub) = when (device.vendor) {
        Vendor.HIKVISION -> "/Streaming/Channels/101" to "/Streaming/Channels/102"
        Vendor.DAHUA -> "/cam/realmonitor?channel=1&subtype=0" to "/cam/realmonitor?channel=1&subtype=1"
        Vendor.REOLINK -> "/h264Preview_01_main" to "/h264Preview_01_sub"
        Vendor.OTHER -> "/" to ""
    }
    return Camera(
        id = UUID.randomUUID().toString(),
        name = device.displayName(),
        url = base + main,
        previewUrl = if (sub.isEmpty()) "" else base + sub,
    )
}
