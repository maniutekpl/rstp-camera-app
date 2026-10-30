// Saved camera list: add, edit or delete cameras; tap one to watch its RTSP stream
// (tap the video or rotate the phone for fullscreen).
package pl.domowe.kamery

import android.app.Activity
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = CameraStore(this)
        setContent {
            MaterialTheme {
                App(store)
            }
        }
    }
}

private const val SCREEN_LIST = "list"
private const val SCREEN_EDIT = "edit"
private const val SCREEN_VIEW = "view"
private const val SCREEN_GRID = "grid"
private const val SCREEN_SCAN = "scan"

@Composable
fun App(store: CameraStore) {
    val cameras by store.cameras.collectAsState(initial = null)
    var screen by rememberSaveable { mutableStateOf(SCREEN_LIST) }
    // Camera for the edit/view screen; null on the edit screen means "new camera".
    var cameraId by rememberSaveable { mutableStateOf<String?>(null) }
    // True when the view screen was opened from the grid, so "back" returns there.
    var fromGrid by rememberSaveable { mutableStateOf(false) }
    // Pre-filled new camera from the scan screen; null gives an empty form.
    var draft by remember { mutableStateOf<Camera?>(null) }
    val scope = rememberCoroutineScope()

    val goBack = {
        screen = if (screen == SCREEN_VIEW && fromGrid) SCREEN_GRID else SCREEN_LIST
    }
    BackHandler(enabled = screen != SCREEN_LIST) { goBack() }

    val list = cameras ?: return // still loading from storage
    val selected = list.find { it.id == cameraId }

    when (screen) {
        SCREEN_EDIT -> EditScreen(
            camera = selected ?: draft,
            isNew = selected == null,
            onSave = { camera ->
                scope.launch { store.save(camera) }
                screen = SCREEN_LIST
            },
            onDelete = { id ->
                scope.launch { store.delete(id) }
                screen = SCREEN_LIST
            },
            onCancel = { screen = SCREEN_LIST },
        )
        SCREEN_VIEW -> selected?.let { camera ->
            ViewScreen(camera = camera, onBack = goBack)
        }
        SCREEN_GRID -> GridScreen(
            cameras = list,
            onOpen = { camera ->
                cameraId = camera.id
                fromGrid = true
                screen = SCREEN_VIEW
            },
            onBack = goBack,
        )
        SCREEN_SCAN -> ScanScreen(
            savedCameras = list,
            onPick = { camera ->
                cameraId = null
                draft = camera
                screen = SCREEN_EDIT
            },
            onAddMany = { cameras ->
                scope.launch { store.addAll(cameras) }
                screen = SCREEN_LIST
            },
            onBack = goBack,
        )
        else -> ListScreen(
            cameras = list,
            onOpen = { camera ->
                cameraId = camera.id
                fromGrid = false
                screen = SCREEN_VIEW
            },
            onGrid = { screen = SCREEN_GRID },
            onScan = { screen = SCREEN_SCAN },
            onEdit = { camera ->
                cameraId = camera.id
                screen = SCREEN_EDIT
            },
            onMove = { camera, offset -> scope.launch { store.move(camera.id, offset) } },
            onAdd = {
                cameraId = null
                draft = null
                screen = SCREEN_EDIT
            },
        )
    }
}

@Composable
fun ListScreen(
    cameras: List<Camera>,
    onOpen: (Camera) -> Unit,
    onGrid: () -> Unit,
    onScan: () -> Unit,
    onEdit: (Camera) -> Unit,
    onMove: (Camera, offset: Int) -> Unit,
    onAdd: () -> Unit,
) {
    // In this mode each camera shows ▲▼ instead of "Edytuj" and tapping it does not open the view.
    var reordering by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd) { Text("Dodaj kamerę") }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Kamery",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                if (cameras.size > 1) {
                    TextButton(onClick = { reordering = !reordering }) {
                        Text(if (reordering) "Gotowe" else "Zmień kolejność")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onScan) { Text("Szukaj kamer") }
                if (cameras.isNotEmpty()) {
                    OutlinedButton(onClick = onGrid) { Text("Wszystkie kamery") }
                }
            }
            if (cameras.isEmpty()) {
                Text("Brak kamer. Wyszukaj je w sieci albo dodaj ręcznie przyciskiem na dole.")
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                // Leaves room so the last camera is not hidden under the add button.
                contentPadding = PaddingValues(bottom = 80.dp),
            ) {
                itemsIndexed(cameras, key = { _, camera -> camera.id }) { index, camera ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !reordering) { onOpen(camera) },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(camera.name, style = MaterialTheme.typography.titleMedium)
                                // Host only, so the password inside the address is never shown.
                                Text(
                                    Uri.parse(camera.url).host ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (reordering) {
                                TextButton(onClick = { onMove(camera, -1) }, enabled = index > 0) {
                                    Text("▲")
                                }
                                TextButton(
                                    onClick = { onMove(camera, 1) },
                                    enabled = index < cameras.lastIndex,
                                ) { Text("▼") }
                            } else {
                                TextButton(onClick = { onEdit(camera) }) { Text("Edytuj") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditScreen(
    camera: Camera?,
    isNew: Boolean,
    onSave: (Camera) -> Unit,
    onDelete: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(camera?.name ?: "") }
    var url by rememberSaveable { mutableStateOf(camera?.url ?: "rtsp://") }
    var previewUrl by rememberSaveable { mutableStateOf(camera?.previewUrl ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val trimmedUrl = url.trim()
    val urlValid = trimmedUrl.startsWith("rtsp://", ignoreCase = true) && trimmedUrl.length > 7
    val trimmedPreviewUrl = previewUrl.trim()
    val previewUrlValid = trimmedPreviewUrl.isEmpty() ||
        (trimmedPreviewUrl.startsWith("rtsp://", ignoreCase = true) && trimmedPreviewUrl.length > 7)

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (isNew) "Nowa kamera" else "Edycja kamery",
                style = MaterialTheme.typography.headlineMedium,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nazwa (np. Ogród)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Adres RTSP") },
                singleLine = true,
                isError = !urlValid,
                supportingText = { if (!urlValid) Text("Adres musi zaczynać się od rtsp://") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = previewUrl,
                onValueChange = { previewUrl = it },
                label = { Text("Adres podglądu (niższa jakość, opcjonalny)") },
                singleLine = true,
                isError = !previewUrlValid,
                supportingText = {
                    Text(
                        if (previewUrlValid) "Używany w widoku wszystkich kamer"
                        else "Adres musi zaczynać się od rtsp://"
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val id = camera?.id ?: UUID.randomUUID().toString()
                        onSave(Camera(id, name.trim(), trimmedUrl, trimmedPreviewUrl))
                    },
                    enabled = name.isNotBlank() && urlValid && previewUrlValid,
                ) { Text("Zapisz") }
                OutlinedButton(onClick = onCancel) { Text("Anuluj") }
            }
            if (!isNew) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Usuń kamerę", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete && camera != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć kamerę?") },
            text = { Text("„${camera.name}” zniknie z listy.") },
            confirmButton = {
                TextButton(onClick = { onDelete(camera.id) }) { Text("Usuń") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") }
            },
        )
    }
}

@Composable
fun ViewScreen(camera: Camera, onBack: () -> Unit) {
    var status by remember { mutableStateOf("Łączenie…") }
    // Bumped on "Połącz ponownie" to recreate the player.
    var attempt by remember { mutableIntStateOf(0) }
    var fullscreenTapped by rememberSaveable { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullscreen = fullscreenTapped || landscape

    HideSystemBars(fullscreen)
    KeepScreenOn()
    BackHandler(enabled = fullscreenTapped && !landscape) { fullscreenTapped = false }

    // The player stays at the same place in the tree in both modes,
    // so switching to fullscreen does not reconnect the stream.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (fullscreen) Color.Black else MaterialTheme.colorScheme.background)
            .then(if (fullscreen) Modifier else Modifier.safeDrawingPadding().padding(16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!fullscreen) {
            Text(camera.name, style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Wróć") }
                Button(onClick = { attempt++ }) { Text("Połącz ponownie") }
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
        Box(
            modifier = (if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                .background(Color.Black),
        ) {
            key(camera.url, attempt) {
                RtspPlayer(
                    url = camera.url,
                    onStatus = { status = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Transparent layer on top of the video: the video view itself would swallow taps.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTapGestures { fullscreenTapped = !fullscreenTapped }
                    },
            )
        }
        if (!fullscreen) {
            Text(
                "Dotknij obrazu albo obróć telefon, aby włączyć pełny ekran.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun GridScreen(cameras: List<Camera>, onOpen: (Camera) -> Unit, onBack: () -> Unit) {
    KeepScreenOn()
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
                "Wszystkie kamery",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onBack) { Text("Wróć") }
        }
        // Lazy grid: tiles scrolled off screen release their player.
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(cameras, key = { it.id }) { camera ->
                CameraTile(camera = camera, onClick = { onOpen(camera) })
            }
        }
    }
}

@Composable
private fun CameraTile(camera: Camera, onClick: () -> Unit) {
    var status by remember { mutableStateOf("Łączenie…") }
    // Lighter stream if set: several full-quality streams at once can overload the phone.
    val url = camera.previewUrl.ifBlank { camera.url }

    Card {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            key(url) {
                RtspPlayer(
                    url = url,
                    onStatus = { status = it },
                    modifier = Modifier.fillMaxSize(),
                    muted = true,
                    fallbackUrl = camera.url.takeIf { it != url },
                )
            }
            // Transparent layer on top of the video: the video view itself would swallow taps.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(camera.id) { detectTapGestures { onClick() } },
            )
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(camera.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

// Stops the screen from turning off while this composable is shown.
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

// Hides the status and navigation bars; a swipe from the screen edge shows them briefly.
@Composable
private fun HideSystemBars(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        if (hidden) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
