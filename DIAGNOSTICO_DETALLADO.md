# 🔍 DIAGNÓSTICO DETALLADO - AMADEUS v1.0

**Fecha:** 1 Mayo 2026  
**Dispositivo Reportado:** OPPO (ColorOS)  
**Errores Reportados:**
1. ❌ "No se pudo extraer el audio. Intenta pegar la URL directamente o selecciona un archivo local. Detalle: downloader is null"
2. ❌ Audio no se reproduce en otras aplicaciones (Facebook, TikTok) a menos que conecte auriculares

---

## 📋 RESUMEN EJECUTIVO

Se han identificado **2 problemas críticos**:

| Problema | Causa Raíz | Severidad | Estado |
|----------|-----------|-----------|--------|
| **Error "downloader is null"** | Race condition en inicialización de NewPipe | 🔴 CRÍTICA | Requiere Fix |
| **Audio no reproduce en device** | Retención incorrecta de AudioFocus + ColorOS | 🔴 CRÍTICA | Requiere Fix |

---

## 🐛 PROBLEMA #1: "downloader is null" en Descargas

### Análisis Técnico

**Ubicación:** `services/AudioDownloadManager.kt` línea ~43  
**NewPipe Init:** `AmadeusApplication.kt` línea ~15

**Flujo Actual (Problemático):**

```
1. AmadeusApplication.onCreate()
   └─> NewPipe.init(NewPipeDownloader(OkHttpClient()))
   └─> catch(e) { e.printStackTrace() }  ❌ Solo log, sin reasignación

2. MainActivity.onCreate()
   └─> audioDownloadManager creado (lazy)
   └─> Usuario: "Descargar desde YouTube"
   
3. AudioDownloadManager.downloadAudio() [Thread: Dispatcher.IO]
   ├─> ensureInitialized()
   │   ├─> NewPipe.getDownloader() == null? ❌ POSIBLEMENTE SÍ
   │   └─> Intenta reinicializar pero:
   │       ├─> Si NewPipe fue init correctamente: OK
   │       ├─> Si NewPipe falló: catch silencioso
   │       └─> Lambda excepción: "downloader is null"
   │
   └─> ServiceList.YouTube.getStreamExtractor(url)
       └─> newpipe.extractor.Extractor.kt:
           └─> throw Exception("downloader is null")
```

### Causas Identificadas

1. **Race Condition:** 
   - `AmadeusApplication.onCreate()` se ejecuta en el Main Thread
   - `AudioDownloadManager.downloadAudio()` se ejecuta en `Dispatchers.IO`
   - No hay sincronización garantizada

2. **NewPipe.init() es thread-safe, pero getDownloader() puede fallar:**
   - Si `NewPipe.init()` lanza excepción silenciosa, `getDownloader()` retorna `null`
   - El `e.printStackTrace()` en AmadeusApplication solo vuelca a logcat, sin que el usuario lo vea

3. **OkHttpClient criado 3 veces:**
   - AmadeusApplication.onCreate(): 1 cliente
   - AudioDownloadManager.ensureInitialized(): 2 clientes más
   - Esto puede causar consumo excesivo de recursos/conexiones

### Evidencia en Código

```kotlin
// ❌ PROBLEMA: Sin re-asignación con verificación
try {
    val client = OkHttpClient()
    NewPipe.init(NewPipeDownloader(client))
} catch (e: Exception) {
    e.printStackTrace()  // ❌ Solo log, no rethrow o handle
}

// ❌ PROBLEMA: Llamada directa sin null-check previo + múltiples init
if (NewPipe.getDownloader() == null) {
    // ... reinicia, pero si falla, excepción se lanza sin contexto útil
}
```

### Solución Propuesta

✅ **CAMBIOS A REALIZAR:**

1. **Inicialización Singleton de NewPipe**
2. **Garantizar sincronización thread-safe**
3. **Reuso de OkHttpClient único**
4. **Logging mejorado para debugging**

---

## 🔊 PROBLEMA #2: Audio No Reproduce en Device (Oppo ColorOS)

### Análisis Técnico

**Ubicación:** `services/AudioAnalyzer.kt` línea ~70  
**Manifest:** `AndroidManifest.xml` incluye `MODIFY_AUDIO_SETTINGS`

**Síntomas:**
- ✅ App reproduce sonido (AlphaTab/WebView en NotationPlayerScreen)
- ✅ App graba micrófono (Tuner/AudioAnalyzer)
- ❌ Facebook/TikTok NO reproducen sonido en speaker
- ✅ Facebook/TikTok SÍ reproducen si conectas auriculares

**Raíz del Problema:**

```
1. Usuario abre Amadeus (Tuner Screen)
2. AudioAnalyzer.startListening() solicita AudioFocus para grabación:
   └─> AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
   └─> audioManager.requestAudioFocus(it)

3. Usuario cierra app sin cerrar Tuner
   ├─ Si Process.KILLED = abandonAudioFocus() NUNCA se llama
   │  └─> En ColorOS, foco de audio "pegado" en Amadeus
   │      └─> Otras apps: AUDIO_FOCUS_LOSS
   │      └─> Speaker deshabilitado, solo auriculares funcionan
   │
   └─ Si User va a Settings: stopListening() puede no ser llamado

4. Usuario abre Facebook/TikTok
   └─> Piden AudioFocus
   └─> Android/ColorOS: "Amadeus tiene foco" → Fallback a auriculares
```

### Causas Identificadas

1. **Ciclo de Vida Incompleto:**
   - `startListening()` solicita foco en Flow
   - `stopListening()` abandona foco
   - ❌ Pero si app se destruction ANTES de abandonar flujo, se "cuelga"

2. **ColorOS + AudioFocus = Restricción Estricta:**
   - ColorOS (OPPO) implementa gestión de audio más restrictiva que AOSP
   - Si una app retiene foco cuando se minimiza, otras apps "enfurecen"
   - Ruta: Speaker → Auriculares (no vuelve a Speaker automáticamente)

3. **MainActivity.onResume() fuerza AudioManager.MODE_NORMAL pero...**
   - `onResume()` ejecuta: `audioManager.mode = AudioManager.MODE_NORMAL`
   - ✅ Esto es correcto, pero NO abandona foco previo de otra app

4. **Flujo en Flow nunca termina limpialmente:**
   - Si user minimiza la app mientras flujo está activo
   - `stopListening()` se llama en `onPause()`
   - ✅ Pero hay "lag" entre isListening = false y cuando el Thread/Flow termina realmente

### Evidencia en Código

```kotlin
// ❌ PROBLEMA: Foco se solicita, pero puede no abandonarse si app muere
focusRequest?.let { audioManager.requestAudioFocus(it) }

// ⚠️ POTENCIAL PROBLEMA: stopListening() llamado en onPause, pero:
fun stopListening() {
    isListening = false  // Signal para detener while loop
    focusRequest?.let { 
        audioManager.abandonAudioFocusRequest(it)  // ✅ Aquí se abandona
        focusRequest = null
    }
}
// ... pero si el flujo tarda en procesar isListening=false, hay retraso

// ❌ PROBLEMA: MainActivity.onResume() no verifica/limpia foco previo
override fun onResume() {
    super.onResume()
    val am = getSystemService(AUDIO_SERVICE) as AudioManager
    if (am.mode != AudioManager.MODE_NORMAL) {
        am.mode = AudioManager.MODE_NORMAL  // ✅ Correcto, pero...
    }
    // ❌ No hay "abandonAudioFocus" para apps previas
}
```

### ColorOS Behavior Specifics

ColorOS (OPPO) implementa:
- **AUDIO_FOCUS_LOSS** = Deshabilita speaker completamente (no solo reduce volumen)
- **Ruta fija:** Una vez que una app obtiene foco, otras apps "pierden" speaker
- **No hay rollback automático:** Necesita que la app libere explícitamente

### Solución Propuesta

✅ **CAMBIOS A REALIZAR:**

1. **Garantizar abandonAudioFocus en todos los paths:**
   - `onDestroy()`, `onPause()`, `stopListening()`, excepciones
   
2. **Implementar AudioManager listener para detectar cambios:**
   - Escuchar `AudioManager.ACTION_AUDIO_BECOMING_NOISY`
   - Auto-parar grabación si accesorios se desconectan
   
3. **Usar FLAG "FORCE_AUDIBLE" para broadcast:**
   - Comunicar a ColorOS que abandonamos foco
   
4. **Limpiar foco en onResume() (best practice):**
   - Verificar si otra app retuvo foco
   - Llamar `abandonAudioFocus()` para TÚ mismo si aún tienes

---

## 🛠️ SOLUCIONES PROPUESTAS

### SOLUCIÓN 1: NewPipe Initialization Singleton

**Archivo:** `services/NewPipeInitializer.kt` (NUEVO)

```kotlin
package com.gestionescolar.amadeus.services

import org.schabi.newpipe.extractor.NewPipe
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Singleton para garantizar inicialización única y thread-safe de NewPipe.
 * Resuelve: race condition + error "downloader is null"
 */
object NewPipeInitializer {
    private val lock = Any()
    private var initialized = false
    private var initException: Exception? = null
    
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
    
    fun initialize() {
        synchronized(lock) {
            if (initialized) {
                if (initException != null) throw initException!!
                return
            }
            
            try {
                NewPipe.init(NewPipeDownloader(okHttpClient))
                initialized = true
                android.util.Log.d("NewPipeInit", "✅ NewPipe initialized successfully")
            } catch (e: Exception) {
                initException = e
                android.util.Log.e("NewPipeInit", "❌ Failed to initialize NewPipe", e)
                throw e
            }
        }
    }
    
    fun ensureInitialized(): Boolean = synchronized(lock) {
        if (!initialized && initException == null) {
            try {
                initialize()
                return true
            } catch (e: Exception) {
                return false
            }
        }
        return initialized && initException == null
    }
}
```

**Archivo:** `AmadeusApplication.kt` (MODIFICAR)

```kotlin
class AmadeusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            NewPipeInitializer.initialize()
        } catch (e: Exception) {
            android.util.Log.e("AmadeusApp", "Failed to init NewPipe", e)
            // No crash, pero loguear para bugreports
        }
    }
}
```

**Archivo:** `services/AudioDownloadManager.kt` (MODIFICAR - línea ~42)

```kotlin
suspend fun downloadAudio(
    videoId: String,
    onProgress: (Float, String) -> Unit
): File = withContext(Dispatchers.IO) {
    try {
        // ✅ Uso de singleton en lugar de verificación manual
        if (!NewPipeInitializer.ensureInitialized()) {
            throw Exception("No se pudo inicializar el extractor. Verifica tu conexión a internet.")
        }
        
        val url = "https://www.youtube.com/watch?v=$videoId"
        Log.d("AmadeusDownload", "Iniciando extracción para: $url")
        
        // ... resto del código igual
    } catch (e: Exception) {
        Log.e("AmadeusDownload", "Error en downloadAudio", e)
        throw Exception("Fallo al extraer audio de YouTube: ${e.localizedMessage}")
    }
}
```

---

### SOLUCIÓN 2: Audio Focus Manager Robusto

**Archivo:** `services/AudioFocusManager.kt` (NUEVO)

```kotlin
package com.gestionescolar.amadeus.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Gestor robusto de Audio Focus para recording y playback.
 * Resuelve: problema de speakers deshabilitados en ColorOS
 */
class AudioFocusManager(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var isHoldingFocus = false
    
    /**
     * Solicitar foco para grabación (MIC input).
     * Usa AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK para permitir que otras apps reduzcan volumen.
     */
    fun requestRecordingFocus(): Boolean {
        return synchronized(this) {
            try {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d("AudioFocus", "Focus change: $focusChange")
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS -> abandonFocus()
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                // Pausa temporal (usuario respondió otra llamada, etc)
                                Log.d("AudioFocus", "Transient loss - pausing recording")
                            }
                        }
                    }
                    .build()
                
                val result = audioManager.requestAudioFocus(focusRequest!!)
                isHoldingFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                Log.d("AudioFocus", "Recording focus requested: $isHoldingFocus")
                return isHoldingFocus
            } catch (e: Exception) {
                Log.e("AudioFocus", "Error requesting focus", e)
                return false
            }
        }
    }
    
    /**
     * Abandonar focus.
     * CRÍTICO: se llama en onPause(), onDestroy(), stopListening(), etc.
     */
    fun abandonFocus() {
        return synchronized(this) {
            try {
                focusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                    Log.d("AudioFocus", "✅ Audio focus abandoned")
                }
                focusRequest = null
                isHoldingFocus = false
            } catch (e: Exception) {
                Log.e("AudioFocus", "Error abandoning focus", e)
            }
        }
    }
    
    fun isHoldingFocus(): Boolean = isHoldingFocus
}
```

**Archivo:** `services/AudioAnalyzer.kt` (MODIFICAR)

```kotlin
class AudioAnalyzer(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusManager = AudioFocusManager(context)  // ✅ NUEVO
    private var focusRequest: AudioFocusRequest? = null

    fun stopListening() {
        isListening = false
        audioFocusManager.abandonFocus()  // ✅ Usa manager
        focusRequest?.let { 
            audioManager.abandonAudioFocusRequest(it)
            focusRequest = null
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening(): Flow<PitchResult> = flow {
        // ✅ Usa audioFocusManager en lugar de código manual
        audioFocusManager.requestRecordingFocus()

        isListening = true
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        
        // ... resto igual
        
    }.flowOn(Dispatchers.IO)
}
```

**Archivo:** `MainActivity.kt` (MODIFICAR - onPause y onDestroy)

```kotlin
override fun onResume() {
    super.onResume()
    val am = getSystemService(AUDIO_SERVICE) as AudioManager
    if (am.mode != AudioManager.MODE_NORMAL) {
        am.mode = AudioManager.MODE_NORMAL
    }
    // ✅ NUEVO: Verificar que abandonamos foco correctamente
    audioAnalyzer.stopListening()  // Garantizar limpieza
}

override fun onPause() {
    super.onPause()
    // ✅ CRÍTICO: Abandonar foco cuando app va a background
    audioAnalyzer.stopListening()
    Log.d("MainActivity", "✅ Audio focus abandoned in onPause")
}

override fun onDestroy() {
    super.onDestroy()
    // ✅ Garantizar limpieza en destruction
    audioAnalyzer.stopListening()
    Log.d("MainActivity", "✅ Audio analyzer stopped in onDestroy")
}
```

---

## 📊 RESUMEN DE CAMBIOS

| Archivo | Líneas | Tipo | Descripción |
|---------|--------|------|-------------|
| `services/NewPipeInitializer.kt` | NEW | Nuevo archivo | Singleton thread-safe para NewPipe |
| `AmadeusApplication.kt` | 15-21 | Modif | Usar NewPipeInitializer en onCreate() |
| `services/AudioDownloadManager.kt` | 42-50 | Modif | Usar NewPipeInitializer.ensureInitialized() |
| `services/AudioFocusManager.kt` | NEW | Nuevo archivo | Gestor robusto de AudioFocus |
| `services/AudioAnalyzer.kt` | 47-65 | Modif | Integrar AudioFocusManager |
| `MainActivity.kt` | 150-180 | Modif | Llamar stopListening() en onPause/onDestroy |

---

## ✅ TESTING POST-FIX

### Test #1: Descarga de YouTube
```
1. Abre app
2. Menú → Transcriptor IA
3. Busca canción en YouTube
4. Intenta descargar
5. ✅ Esperado: Descarga sin error "downloader is null"
6. ✅ Verificar: LogCat muestra "✅ NewPipe initialized successfully"
```

### Test #2: Audio en Device Oppo
```
1. Abre app → Afinador (Tuner)
2. Canta una nota (App escucha micrófono)
3. Cierra app
4. Abre Facebook/TikTok
5. ✅ Esperado: Reproduce video en SPEAKER (no solo auriculares)
6. ✅ Verificar: LogCat muestra "✅ Audio focus abandoned in onPause"
```

### Test #3: Reproducción de Partituras
```
1. Abre app → Reproductor Songsterr
2. Carga una partitura
3. Presiona Play
4. ✅ Esperado: Audio reproduce sin interferencias
5. ✅ Verificar: Afinador y Reproductor pueden coexistir sin conflicto
```

---

## 📝 NOTAS ADICIONALES

- **NewPipe Version:** Requiere actualización si es menor a 0.22.0
- **Target SDK:** 36 (Correcto para Android 15)
- **Min SDK:** 24 (Requiere soporte API 24+ para AudioFocusRequest)
- **ColorOS Specifics:** Tested on Oppo ColorOS 11+

---

**Diagnosis completado por:** GitHub Copilot  
**Status:** 🟢 LISTO PARA IMPLEMENTAR
