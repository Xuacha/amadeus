package com.gestionescolar.amadeus

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Base64
import android.widget.Toast
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import coil.compose.AsyncImage
import com.gestionescolar.amadeus.repository.*
import com.gestionescolar.amadeus.services.*
import com.gestionescolar.amadeus.models.*
import com.gestionescolar.amadeus.logic.*
import com.gestionescolar.amadeus.ui.*
import com.gestionescolar.amadeus.ui.theme.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.core.net.toUri
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.accompanist.permissions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val audioAnalyzer by lazy { AudioAnalyzer(this) }
    private val youtubeRepository by lazy { YouTubeSearchRepository() }
    private val downloadManager by lazy { AudioDownloadManager(this) }
    private val transcriptionEngine by lazy { MusicTranscriptionEngine(this, audioAnalyzer) }
    private val xmlGenerator by lazy { MusicXmlGenerator() }
    private val transcriptionOrchestrator by lazy {
        TranscriptionOrchestrator(this, downloadManager, transcriptionEngine, xmlGenerator)
    }
    private lateinit var profileManager: ProfileManager
    private lateinit var soundFontManager: SoundFontManager
    private lateinit var midiService: MidiService

    override fun onResume() {
        super.onResume()
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (am.mode != AudioManager.MODE_NORMAL) {
            am.mode = AudioManager.MODE_NORMAL
        }
        // ✅ Garantizar limpieza de audio focus cuando app regresa a foreground
        android.util.Log.d("MainActivity", "onResume() called")
    }

    override fun onPause() {
        super.onPause()
        // ✅ CRÍTICO: Abandonar audio focus cuando app va a background
        // Esto resuelve el problema de speakers deshabilitados en ColorOS
        audioAnalyzer.stopListening()
        android.util.Log.d("MainActivity", "✅ onPause() - Audio focus should be abandoned")
    }

    override fun onDestroy() {
        super.onDestroy()
        // ✅ Garantizar limpieza final en destrucción de activity
        audioAnalyzer.stopListening()
        android.util.Log.d("MainActivity", "✅ onDestroy() - Audio analyzer stopped")
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileManager = ProfileManager(this)
        soundFontManager = SoundFontManager(this)
        midiService = MidiService(this)
        
        // Initialize analyzer with persisted settings
        audioAnalyzer.setNoiseThreshold(profileManager.getNoiseThreshold())
        audioAnalyzer.setInstrument(profileManager.getSelectedInstrument())
        
        enableEdgeToEdge()
        setContent {
            AmadeusTheme {
                val navController = rememberNavController()
                val audioPermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
                
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (audioPermissionState.status.isGranted) {
                        NavHost(navController = navController, startDestination = "main_menu") {
                            composable("main_menu") { MainMenu(navController) }
                            composable("tuner") { TunerScreen(audioAnalyzer, profileManager, navController) }
                            composable("composition") { CompositionScreen(navController) }
                            composable("pdf_viewer") { PDFViewerScreen(audioAnalyzer, navController) }
                            composable(
                                route = "notation_player?uri={uri}",
                                arguments = listOf(navArgument("uri") { defaultValue = ""; type = NavType.StringType; nullable = true })
                            ) { backStackEntry ->
                                val uriString = backStackEntry.arguments?.getString("uri")
                                val uri = if (!uriString.isNullOrEmpty()) Uri.parse(Uri.decode(uriString)) else null
                                NotationPlayerScreen(audioAnalyzer, profileManager, soundFontManager, midiService, navController, uri)
                            }
                            composable(
                                route = "transcription?uri={uri}",
                                arguments = listOf(navArgument("uri") { defaultValue = ""; type = NavType.StringType; nullable = true })
                            ) { backStackEntry ->
                                val uriString = backStackEntry.arguments?.getString("uri")
                                val uri = if (!uriString.isNullOrEmpty()) Uri.parse(Uri.decode(uriString)) else null
                                TranscriptionScreen(youtubeRepository, transcriptionOrchestrator, navController, uri)
                            }
                            composable("stats") { StatsScreen(profileManager, navController) }
                            composable("settings") { SettingsScreen(audioAnalyzer, profileManager, navController) }
                        }
                    } else {
                        PermissionRequestScreen { audioPermissionState.launchPermissionRequest() }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenu(navController: NavController) {
    val items = listOf(
        MenuItem("Afinador Maestro", Icons.Default.MusicNote, "tuner"),
        MenuItem("Reproductor Songsterr", Icons.Default.LibraryMusic, "notation_player"),
        MenuItem("Transcriptor IA", Icons.Default.CloudUpload, "transcription"),
        MenuItem("Mi Progreso", Icons.Default.EmojiEvents, "stats"),
        MenuItem("Mis Partituras (PDF)", Icons.Default.PictureAsPdf, "pdf_viewer"),
        MenuItem("Composición IA", Icons.Default.AutoAwesome, "composition")
    )

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HellsingBlack)
                    .padding(top = 32.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "AMADEUS",
                    style = MaterialTheme.typography.titleLarge,
                    color = HellsingGold,
                    fontSize = 32.sp
                )
                Text(
                    "ORGANIZACIÓN HELLSING - DIVISIÓN MUSICAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = HellsingCrimson,
                    letterSpacing = 2.sp
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(HellsingBlack)
        ) {
            HellsingSectionLabel("Consola de Interpretación")
            
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                gridItems(items) { item ->
                    HellsingCard(
                        modifier = Modifier
                            .height(160.dp)
                            .clickable { navController.navigate(item.route) }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(item.icon, null, tint = HellsingGold, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                item.title.uppercase(),
                                color = HellsingGold,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
                
                // Botón de Diagnóstico de Audio al final del grid
                item {
                    val context = LocalContext.current
                    HellsingCard(
                        modifier = Modifier
                            .height(160.dp)
                            .clickable {
                                try {
                                    val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                                    tg.startTone(ToneGenerator.TONE_CDMA_PIP, 500)
                                    Toast.makeText(context, "Test de Audio: Tono generado", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error audio: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.BugReport, null, tint = HellsingCrimson, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("TEST AUDIO", color = HellsingGold, fontWeight = FontWeight.Bold)
                            Text("DIAGNÓSTICO", color = HellsingCrimson, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

data class TrackInfo(val index: Int, val name: String)

class AlphaTabInterface(
    private val onTracksLoaded: (List<TrackInfo>) -> Unit,
    private val onPlayerStateChanged: (Boolean) -> Unit,
    private val onPlaybackFinished: () -> Unit,
    private val onScoreCalculated: (Int) -> Unit,
    private val onNoteResult: (Boolean) -> Unit,
    private val onTempoDetected: (Int) -> Unit,
    private val soundFontManager: SoundFontManager,
    private val onReady: () -> Unit
) {
    private var _scoreData: String? = null
    
    private val _activeNoteFlow = MutableStateFlow<ActiveNote?>(null)
    val activeNoteFlow: StateFlow<ActiveNote?> = _activeNoteFlow.asStateFlow()

    fun setInternalScoreData(data: String?) {
        _scoreData = data
    }

    @JavascriptInterface
    fun getScoreData(): String? = _scoreData

    @JavascriptInterface
    fun notifyReady() {
        onReady()
    }

    @JavascriptInterface
    fun onActiveNoteChanged(midiPitch: Int, startTime: Double) {
        _activeNoteFlow.value = ActiveNote(midiPitch, startTime)
    }

    @JavascriptInterface
    fun setTracks(json: String) {
        val tracks = json.split("|").filter { it.isNotEmpty() }.mapNotNull {
            val parts = it.split(",")
            if (parts.size >= 2) TrackInfo(parts[0].toInt(), parts[1]) else null
        }
        onTracksLoaded(tracks)
    }

    @JavascriptInterface
    fun updatePlayerState(isPlaying: Boolean) {
        onPlayerStateChanged(isPlaying)
    }

    @JavascriptInterface
    fun notifyFinished() {
        onPlaybackFinished()
    }

    @JavascriptInterface
    fun reportScore(score: Int) {
        onScoreCalculated(score)
    }

    @JavascriptInterface
    fun reportNoteResult(isHit: Boolean) {
        onNoteResult(isHit)
    }

    @JavascriptInterface
    fun reportTempo(bpm: Int) {
        onTempoDetected(bpm)
    }

    @JavascriptInterface
    fun getSoundFontData(): String? {
        return soundFontManager.getSoundFontBase64Sync()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotationPlayerScreen(
    audioAnalyzer: AudioAnalyzer,
    profileManager: ProfileManager,
    soundFontManager: SoundFontManager,
    midiService: MidiService,
    navController: NavController,
    initialUri: Uri? = null
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val initialOrientation = remember { configuration.orientation }
    val scope = rememberCoroutineScope()

    // Manejo de giro: Cerrar si cambia la orientación según requerimiento
    LaunchedEffect(configuration.orientation) {
        if (configuration.orientation != initialOrientation) {
            Toast.makeText(context, "Giro detectado. Saliendo de la partitura.", Toast.LENGTH_SHORT).show()
            navController.popBackStack()
        }
    }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var isPlaying by remember { mutableStateOf(false) }
    var selectedUri by remember { 
        mutableStateOf<Uri?>(initialUri ?: Uri.parse("file:///android_asset/sample.gp5")) 
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var tracks by remember { mutableStateOf<List<TrackInfo>>(emptyList()) }
    var selectedTrackIndex by remember { mutableIntStateOf(0) }
    var showMixer by remember { mutableStateOf(false) }
    var showMidiDialog by remember { mutableStateOf(false) }
    var midiDevices by remember { mutableStateOf<List<android.media.midi.MidiDeviceInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var currentNote by remember { mutableStateOf("---") }
    var sessionScore by remember { mutableIntStateOf(100) }
    var showScoreDialog by remember { mutableStateOf(false) }
    var showPdfDialog by remember { mutableStateOf(false) }

    var hits by remember { mutableIntStateOf(0) }
    var misses by remember { mutableIntStateOf(0) }
    var originalBpm by remember { mutableIntStateOf(120) }
    var playbackBpm by remember { mutableIntStateOf(120) }
    var loopStartTick by remember { mutableStateOf<Int?>(null) }
    var loopEndTick by remember { mutableStateOf<Int?>(null) }
    var isLoopActive by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading = true
        // Pre-cargar el SoundFont desde assets a la memoria
        soundFontManager.getSoundFontBase64()
        isLoading = false
    }

    val alphaTabInterface = remember {
        lateinit var internalInterface: AlphaTabInterface
        internalInterface = AlphaTabInterface(
            onTracksLoaded = { tracks = it },
            onPlayerStateChanged = { isPlaying = it },
            onPlaybackFinished = { 
                isPlaying = false
                showScoreDialog = true 
            },
            onScoreCalculated = { score -> sessionScore = score },
            onNoteResult = { isHit -> if(isHit) hits++ else misses++ },
            onTempoDetected = { bpm -> 
                originalBpm = bpm
                playbackBpm = bpm
            },
            soundFontManager = soundFontManager,
            onReady = {
                // Inyectar listener de cambio de posición post-ready
                webViewRef?.post {
                    webViewRef?.evaluateJavascript("""
                        api.on('playerPositionChanged', (e) => {
                            const beat = e.currentBeat;
                            if (beat && beat.notes && beat.notes.length > 0) {
                                const note = beat.notes[0];
                                const midi = note.realValue; // MIDI pitch number
                                AndroidBridge.onActiveNoteChanged(midi, e.currentTime);
                            }
                        });
                    """.trimIndent(), null)
                }
                
                if (selectedUri != null) {
                    isLoading = true
                    scope.launch {
                        try {
                            val base64 = withContext(Dispatchers.IO) {
                                val uri = selectedUri!!
                                if (uri.toString().startsWith("file:///android_asset/")) {
                                    val assetPath = uri.toString().removePrefix("file:///android_asset/")
                                    context.assets.open(assetPath).use { input ->
                                        Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)
                                    }
                                } else {
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)
                                    }
                                }
                            }
                            base64?.let { b64 ->
                                internalInterface.setInternalScoreData(b64)
                                withContext(Dispatchers.Main) {
                                    webViewRef?.evaluateJavascript("requestScore()", null)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            isLoading = false
                        }
                    }
                }
            }
        )
        internalInterface
    }

    val loadUri = { uri: Uri ->
        selectedUri = uri
        isLoading = true
        scope.launch {
            try {
                val base64 = withContext(Dispatchers.IO) {
                    if (uri.toString().startsWith("file:///android_asset/")) {
                        val assetPath = uri.toString().removePrefix("file:///android_asset/")
                        context.assets.open(assetPath).use { input ->
                            Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)
                        }
                    } else {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)
                        }
                    }
                }
                base64?.let { b64 ->
                    alphaTabInterface.setInternalScoreData(b64)
                    withContext(Dispatchers.Main) {
                        webViewRef?.evaluateJavascript("requestScore()", null)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> 
        uri?.let { 
            if (it.toString().lowercase().endsWith(".pdf")) {
                showPdfDialog = true
            } else {
                loadUri(it)
            }
        } 
    }

    if (showPdfDialog) {
        AlertDialog(
            onDismissRequest = { showPdfDialog = false },
            title = { Text("PDF Detectado") },
            text = { Text("Los archivos PDF no son interactivos. ¿Deseas procesarlo con el Transcriptor IA para convertirlo en una partitura interactiva?") },
            confirmButton = {
                Button(onClick = { 
                    showPdfDialog = false
                    navController.navigate("transcription") 
                }) { Text("Procesar con IA") }
            },
            dismissButton = {
                TextButton(onClick = { showPdfDialog = false }) { Text("Cancelar") }
            }
        )
    }

    var correctCount by remember { mutableIntStateOf(0) }
    var wrongCount by remember { mutableIntStateOf(0) }
    val noteEvaluator = remember { NoteEvaluator() }
    val noteDetectionManager = remember { NoteDetectionManager(audioAnalyzer, midiService, scope) }
    val activeNote by alphaTabInterface.activeNoteFlow.collectAsState()

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            noteDetectionManager.start(isMidi = midiService.getDevices().isNotEmpty())
        } else {
            noteDetectionManager.stop()
            currentNote = "---"
        }
    }

    LaunchedEffect(activeNote) {
        activeNote?.let { expected ->
            // 1. Limpiar colores anteriores
            webViewRef?.evaluateJavascript("""
                (function() {
                    var els = document.querySelectorAll('.at-note-head');
                    els.forEach(function(el) { el.style.fill = ''; });
                })();
            """.trimIndent(), null)

            // 2. Evaluar nota del usuario con timeout de 2s
            scope.launch {
                var detectedNote = "---"
                val startTime = System.currentTimeMillis()
                
                // Esperar hasta que se detecte una nota o pasen 2 segundos
                withTimeoutOrNull(2000) {
                    noteDetectionManager.detectedNoteFlow.collect { note ->
                        detectedNote = note
                        if (note != "---") cancel() // Nota detectada, salir del collect
                    }
                }

                val result = noteEvaluator.evaluate(expected, detectedNote)
                
                withContext(Dispatchers.Main) {
                    when (result) {
                        NoteResult.Correct -> {
                            correctCount++
                            webViewRef?.evaluateJavascript("""
                                (function() {
                                    var els = document.querySelectorAll('.at-cursor-beat .at-note-head');
                                    els.forEach(function(el) {
                                        el.style.fill = '#1D9E75';
                                        el.style.transition = 'fill 0.15s';
                                    });
                                })();
                            """.trimIndent(), null)
                        }
                        NoteResult.Wrong, NoteResult.NoInput -> {
                            wrongCount++
                            webViewRef?.evaluateJavascript("""
                                (function() {
                                    var els = document.querySelectorAll('.at-cursor-beat .at-note-head');
                                    els.forEach(function(el) {
                                        el.style.fill = '#E24B4A';
                                        el.style.transition = 'fill 0.15s';
                                    });
                                })();
                            """.trimIndent(), null)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(correctCount, wrongCount) {
        val total = correctCount + wrongCount
        if (total > 0) {
            sessionScore = (correctCount.toFloat() / total * 100).toInt()
        }
    }

    if (showMidiDialog) {
        AlertDialog(
            onDismissRequest = { showMidiDialog = false },
            title = { Text("Dispositivos MIDI") },
            text = {
                Column {
                    if (midiDevices.isEmpty()) {
                        Text("No se detectaron dispositivos MIDI.")
                    } else {
                        midiDevices.forEach { device ->
                            ListItem(
                                headlineContent = { Text(device.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_NAME) ?: "Dispositivo Desconocido") },
                                modifier = Modifier.clickable {
                                    midiService.openDevice(device)
                                    showMidiDialog = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMidiDialog = false }) { Text("Cerrar") } }
        )
    }

    if (showScoreDialog) {
        AlertDialog(
            onDismissRequest = { showScoreDialog = false },
            title = { Text("¡Pieza Finalizada!") },
            text = { 
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Tu precisión fue de:", fontSize = 16.sp)
                    Text("$sessionScore%", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color(0xFF4CAF50))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(if (sessionScore > 90) "¡Virtuoso! Mozart estaría orgulloso." else "Buen intento, sigue practicando.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("¡Has ganado ${sessionScore * 2} XP!", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                Button(onClick = { 
                    profileManager.addXP(correctCount * 2)
                    showScoreDialog = false 
                }) { Text("¡Excelente!") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Amadeus Player", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(selectedUri?.lastPathSegment ?: "Sin título", fontSize = 10.sp, color = Color.Gray)
                    }
                },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { filePickerLauncher.launch("*/*") }) { Icon(Icons.Default.FileOpen, null) }
                    IconButton(onClick = { showMixer = true }) { Icon(Icons.Default.Tune, null) }
                    IconButton(onClick = { 
                        midiDevices = midiService.getDevices()
                        showMidiDialog = true 
                    }) { Icon(Icons.Default.SettingsInputComponent, null, tint = if (midiService.getDevices().isNotEmpty()) Color(0xFF4CAF50) else Color.Gray) }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { 
                            webViewRef?.evaluateJavascript("resetPosition()", null) 
                        }) { Icon(Icons.Default.SkipPrevious, null) }

                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(32.dp))
                                .clickable { webViewRef?.evaluateJavascript("togglePlayback()", null) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        IconButton(onClick = {
                            webViewRef?.evaluateJavascript("markLoopStart()") { result ->
                                loopStartTick = result?.toIntOrNull()
                                Toast.makeText(context, "Inicio de bucle fijado", Toast.LENGTH_SHORT).show()
                            }
                        }) { Icon(Icons.Default.Start, null, tint = if (loopStartTick != null) MaterialTheme.colorScheme.primary else Color.Gray) }

                        IconButton(onClick = {
                            webViewRef?.evaluateJavascript("markLoopEnd()") { result ->
                                loopEndTick = result?.toIntOrNull()
                                Toast.makeText(context, "Fin de bucle fijado", Toast.LENGTH_SHORT).show()
                            }
                        }) { Icon(Icons.Default.Stop, null, tint = if (loopEndTick != null) MaterialTheme.colorScheme.primary else Color.Gray) }

                        IconButton(onClick = {
                            isLoopActive = !isLoopActive
                            webViewRef?.evaluateJavascript("toggleLoop($isLoopActive)", null)
                        }) { Icon(Icons.Default.Repeat, null, tint = if (isLoopActive) MaterialTheme.colorScheme.primary else Color.Gray) }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Speed, null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                        Spacer(modifier = Modifier.width(8.dp))
                        Slider(
                            value = playbackSpeed,
                            onValueChange = { speed ->
                                playbackSpeed = speed
                                playbackBpm = (originalBpm * speed).toInt()
                                webViewRef?.evaluateJavascript("setPlaybackSpeed($speed)", null)
                            },
                            valueRange = 0.5f..2.0f,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${(playbackSpeed * 100).toInt()}%", modifier = Modifier.width(40.dp), fontSize = 12.sp)
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color.White)) {
            if (isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize().background(Color.White).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Iniciando motor Amadeus...", fontWeight = FontWeight.Medium)
                    Text("Cargando sonidos de alta calidad", fontSize = 12.sp, color = Color.Gray)
                }
            }

            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        webViewRef = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                            }
                        }
                        
                        addJavascriptInterface(alphaTabInterface, "Android")
                        
                        val htmlContent = """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                                <script src="https://cdn.jsdelivr.net/npm/@coderline/alphatab@1.3.0/dist/alphaTab.js"></script>
                                <style>
                                    body { margin: 0; padding: 0; background: #FFFFFF; overflow-x: hidden; }
                                    #alphaTab { width: 100%; min-height: 100vh; background: #FFFFFF; }
                                    .at-cursor-bar { background: #4CAF50 !important; width: 3px !important; opacity: 0.8; }
                                    .note-hit { 
                                        fill: #4CAF50 !important; 
                                        stroke: #4CAF50 !important; 
                                        stroke-width: 2px;
                                        filter: drop-shadow(0 0 5px #4CAF50);
                                    }
                                    .note-miss { fill: #F44336 !important; stroke: #F44336 !important; stroke-width: 2px; }
                                    #loadingOverlay {
                                        position: fixed; top: 0; left: 0; width: 100%; height: 100%;
                                        background: white; display: flex; justify-content: center; align-items: center;
                                        z-index: 2000; font-family: sans-serif;
                                    }
                                </style>
                            </head>
                            <body>
                                <div id="loadingOverlay">Cargando Motor AlphaTab...</div>
                                <div id="alphaTab"></div>
                                <script>
                                    var api = null;
                                    var el = document.getElementById('alphaTab');
                                    var currentBeat = null;
                                    var hitInCurrentBeat = false;
                                    var isApiReady = false;

                                    var settings = {
                                        core: { fontDirectory: 'https://cdn.jsdelivr.net/npm/@coderline/alphatab@1.3.0/dist/font/' },
                                        player: {
                                            enablePlayer: true,
                                            scrollElement: 'body'
                                        },
                                        display: { layoutMode: 'page', staveProfile: 'all' }
                                    };

                                    function initAlphaTab() {
                                        try {
                                            const sfData = Android.getSoundFontData();
                                            if (sfData) {
                                                settings.player.soundFont = "data:application/octet-stream;base64," + sfData;
                                            } else {
                                                settings.player.soundFont = 'https://cdn.jsdelivr.net/npm/@coderline/alphatab@1.3.0/dist/soundfont/sonivox.sf2';
                                            }

                                            api = new alphaTab.AlphaTabApi(el, settings);
                                            api.scoreLoaded.on((score) => {
                                                document.getElementById('loadingOverlay').style.display = 'none';
                                                let trackData = "";
                                                score.tracks.forEach((t, i) => { trackData += i + "," + t.name + "|"; });
                                                Android.setTracks(trackData);
                                                Android.reportTempo(score.tempo);
                                            });
                                            api.playerStateChanged.on((args) => { Android.updatePlayerState(args.state === 1); });
                                            api.activeBeatChanged.on((beat) => {
                                                if (currentBeat && api.playerState === 1) {
                                                    if (!hitInCurrentBeat) { 
                                                        highlightNotes(currentBeat, 'miss'); 
                                                        Android.reportNoteResult(false);
                                                        // En modo bucle no pausamos para permitir la práctica fluida
                                                        if (!window.isLoopActive) api.pause(); 
                                                    }
                                                    else { 
                                                        window.currentTotalNotes++; 
                                                        window.currentHits++; 
                                                        Android.reportNoteResult(true);
                                                        Android.reportScore(Math.round((window.currentHits / window.currentTotalNotes) * 100)); 
                                                    }
                                                }
                                                currentBeat = beat;
                                                hitInCurrentBeat = false;
                                                if (api.playerState === 1) {
                                                    const el = document.querySelector('[data-beat="' + beat.index + '"]');
                                                    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                                }
                                            });
                                            isApiReady = true;
                                            Android.notifyReady();
                                        } catch (e) {
                                            document.getElementById('loadingOverlay').innerText = "Error: " + e.message;
                                        }
                                    }

                                    window.onload = function() {
                                        window.currentHits = 0; window.currentTotalNotes = 0;
                                        initAlphaTab();
                                    };

                                    function requestScore() {
                                        if(!isApiReady) { setTimeout(requestScore, 500); return; }
                                        const base64 = Android.getScoreData();
                                        if(!base64) return;
                                        document.getElementById('loadingOverlay').style.display = 'flex';
                                        document.getElementById('loadingOverlay').innerText = "Cargando Obra...";
                                        try {
                                            const binaryString = atob(base64);
                                            const bytes = new Uint8Array(binaryString.length);
                                            for (let i = 0; i < binaryString.length; i++) { bytes[i] = binaryString.charCodeAt(i); }
                                            api.load(bytes);
                                        } catch(e) { 
                                            document.getElementById('loadingOverlay').innerText = "Error al cargar: " + e.message;
                                        }
                                    }

                                    function markLoopStart() {
                                        if(!api) return -1;
                                        window.loopStart = api.tickPosition;
                                        return window.loopStart;
                                    }

                                    function markLoopEnd() {
                                        if(!api) return -1;
                                        window.loopEnd = api.tickPosition;
                                        return window.loopEnd;
                                    }

                                    function toggleLoop(enabled) {
                                        if(!api) return;
                                        window.isLoopActive = enabled;
                                        if(enabled && window.loopStart !== undefined && window.loopEnd !== undefined) {
                                            api.settings.player.playRange = {
                                                startTick: window.loopStart,
                                                endTick: window.loopEnd
                                            };
                                            api.tickPosition = window.loopStart;
                                        } else {
                                            api.settings.player.playRange = null;
                                        }
                                        api.updateSettings();
                                    }

                                    function resetPosition() {
                                        if(!api) return;
                                        api.stop();
                                        api.tickPosition = 0;
                                    }

                                    function updateDetectedNote(frequency) {
                                        if(!api || !currentBeat || hitInCurrentBeat) return;
                                    }

                                    function togglePlayback() {
                                        if(!api) return;
                                        api.playPause();
                                    }

                                    function setPlaybackSpeed(speed) {
                                        if(!api) return;
                                        api.playbackSpeed = speed;
                                    }

                                    function highlightNotes(beat, status) {
                                        // Visual feedback handled via CSS classes or manual fill
                                    }

                                    function selectTrack(index) {
                                        if(!api) return;
                                        api.renderTracks([api.score.tracks[index]]);
                                    }
                                </script>
                            </body>
                            </html>
                        """.trimIndent()
                        loadDataWithBaseURL("https://alphatab.net/", htmlContent, "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Overlays de métricas Hellsing
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                StatPill("PRECISIÓN", "$sessionScore%", if(sessionScore > 80) Color(0xFF4CAF50) else Color(0xFFF44336))
                Spacer(modifier = Modifier.height(8.dp))
                StatPill("BPM", "$playbackBpm", HellsingGold)
                Spacer(modifier = Modifier.height(8.dp))
                StatPill("NOTA", currentNote, HellsingCrimson)
            }
        }
    }

    if (showMixer) {
        ModalBottomSheet(onDismissRequest = { showMixer = false }) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text("Instrumentos Disponibles", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn {
                    items(tracks) { track ->
                        ListItem(
                            headlineContent = { Text(track.name) },
                            leadingContent = { RadioButton(selected = selectedTrackIndex == track.index, onClick = {
                                selectedTrackIndex = track.index
                                webViewRef?.evaluateJavascript("selectTrack(${track.index})", null)
                                showMixer = false
                            }) },
                            modifier = Modifier.clickable { 
                                selectedTrackIndex = track.index
                                webViewRef?.evaluateJavascript("selectTrack(${track.index})", null)
                                showMixer = false
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun StatPill(label: String, value: String, color: Color) {
    Surface(
        color = HellsingCardBlack.copy(alpha = 0.9f),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, HellsingBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = HellsingGoldDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = value,
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunerScreen(analyzer: AudioAnalyzer, profileManager: ProfileManager, navController: NavController) {
    val instruments = listOf("Violín", "Guitarra", "Canto", "Batería", "General")
    var pitchResult by remember { mutableStateOf(PitchResult(0f, "---", 0f, 0f)) }
    var selectedInstrument by remember { 
        mutableStateOf(profileManager.getSelectedInstrument().let { if (it in instruments) it else "General" }) 
    }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val job = scope.launch {
            try {
                analyzer.startListening().collectLatest { pitchResult = it }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        onDispose {
            job.cancel()
            analyzer.stopListening()
        }
    }

    LaunchedEffect(selectedInstrument) {
        analyzer.setInstrument(selectedInstrument)
        profileManager.setSelectedInstrument(selectedInstrument)
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Afinador Maestro") },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, null) } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val selectedIndex = instruments.indexOf(selectedInstrument).coerceAtLeast(0)
            ScrollableTabRow(
                selectedTabIndex = selectedIndex, 
                edgePadding = 16.dp, 
                containerColor = Color.Transparent, 
                divider = {}
            ) {
                instruments.forEach { inst -> 
                    Tab(
                        selected = selectedInstrument == inst, 
                        onClick = { selectedInstrument = inst }, 
                        text = { Text(inst) }
                    ) 
                }
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(pitchResult.noteName, fontSize = 100.sp, fontWeight = FontWeight.Black, color = if (pitchResult.centsOff in -5f..5f) Color(0xFF4CAF50) else Color(0xFFF44336))
                TunerGauge(centsOff = pitchResult.centsOff)
            }
        }
    }
}

@Composable
fun TunerGauge(centsOff: Float) {
    Canvas(modifier = Modifier.fillMaxWidth().height(100.dp)) {
        val center = size.width / 2
        drawLine(Color.Gray, start = androidx.compose.ui.geometry.Offset(center, 0f), end = androidx.compose.ui.geometry.Offset(center, size.height), strokeWidth = 2f)
        val pointerX = center + (centsOff / 50f) * center
        drawCircle(if (centsOff in -5f..5f) Color(0xFF4CAF50) else Color(0xFFF44336), radius = 10f, center = androidx.compose.ui.geometry.Offset(pointerX.coerceIn(0f, size.width), size.height / 2))
    }
}

@Composable
fun CompositionScreen(navController: NavController) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Composición IA - Próximamente")
    }
}

@Composable
fun PDFViewerScreen(audioAnalyzer: AudioAnalyzer, navController: NavController) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Visor de PDF - Próximamente")
    }
}

@Composable
fun TranscriptionScreen(
    youtubeRepository: YouTubeSearchRepository,
    orchestrator: TranscriptionOrchestrator,
    navController: NavController,
    uri: Uri?
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<YouTubeResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var transcriptionState by remember { mutableStateOf<TranscriptionState?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(HellsingBlack).padding(16.dp)) {
        Text(
            "BÚSQUEDA DE ALMAS (YOUTUBE)",
            style = MaterialTheme.typography.headlineSmall,
            color = HellsingGold,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Invocar por nombre o URL", color = HellsingCrimson) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = HellsingGold,
                unfocusedBorderColor = HellsingCrimson,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            trailingIcon = {
                IconButton(onClick = {
                    scope.launch {
                        isLoading = true
                        errorMessage = null
                        val result = youtubeRepository.search(query)
                        result.onSuccess {
                            results = it
                            if (it.isEmpty()) errorMessage = "No se encontraron almas vinculadas"
                        }.onFailure {
                            errorMessage = "Fallo en la conexión astral: ${it.message}"
                        }
                        isLoading = false
                    }
                }) {
                    Icon(Icons.Default.Search, contentDescription = "Buscar", tint = HellsingGold)
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // UI de Progreso de Transcripción
        transcriptionState?.let { state ->
            HellsingCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val progress = when (state) {
                        is TranscriptionState.Downloading -> state.progress
                        is TranscriptionState.Analyzing -> state.progress
                        else -> 0f
                    }
                    val message = when (state) {
                        is TranscriptionState.Downloading -> state.message
                        is TranscriptionState.Analyzing -> state.message
                        is TranscriptionState.Generating -> state.message
                        is TranscriptionState.Done -> "Misión completada. Abriendo partitura."
                        is TranscriptionState.Error -> "ERROR: ${state.message}"
                    }
                    
                    Text(message.uppercase(), color = if (state is TranscriptionState.Error) HellsingCrimson else HellsingGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (state !is TranscriptionState.Done && state !is TranscriptionState.Error) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = HellsingCrimson,
                            trackColor = Color.DarkGray,
                        )
                    }
                }
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = HellsingGold)
        }

        errorMessage?.let {
            Text(it, color = HellsingCrimson, modifier = Modifier.padding(vertical = 8.dp), fontWeight = FontWeight.Bold)
        }

        LazyColumn {
            items(results) { video ->
                YouTubeResultItem(video) {
                    scope.launch {
                        try {
                            val file = orchestrator.transcribeFromYouTube(video) { state ->
                                transcriptionState = state
                            }
                            // Navegar al reproductor
                            val encodedUri = Uri.encode(file.toUri().toString())
                            navController.navigate("notation_player?uri=$encodedUri")
                        } catch (e: Exception) {
                            transcriptionState = TranscriptionState.Error(e.message ?: "Error fatal")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun YouTubeResultItem(video: YouTubeResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() },
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = null,
                modifier = Modifier.size(120.dp, 67.dp).background(Color.Gray),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(video.title, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(video.channelTitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun StatsScreen(profileManager: ProfileManager, navController: NavController) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Estadísticas - Próximamente")
    }
}

@Composable
fun SettingsScreen(audioAnalyzer: AudioAnalyzer, profileManager: ProfileManager, navController: NavController) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Ajustes - Próximamente")
    }
}

@Composable
fun PermissionRequestScreen(onRequest: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Se requiere permiso de audio")
        Button(onClick = onRequest) { Text("Conceder Permiso") }
    }
}

data class MenuItem(val title: String, val icon: ImageVector, val route: String)
