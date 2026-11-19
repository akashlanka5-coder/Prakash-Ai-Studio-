package com.prakash.aistudio

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality as CamQuality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

// ============================================================
// थीम
// ============================================================
val Bg = Color(0xFF0B0D12)
val Surface1 = Color(0xFF151923)
val Accent = Color(0xFF7C5CFF)
val Accent2 = Color(0xFF22D3EE)

private val DarkScheme = darkColorScheme(
    primary = Accent, secondary = Accent2,
    background = Bg, surface = Surface1,
    onPrimary = Color.White, onBackground = Color(0xFFE8EAF0), onSurface = Color(0xFFE8EAF0)
)

@Composable
fun PrakashTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = DarkScheme, content = content)

// ============================================================
// डेटाबेस (Room): प्रोजेक्ट सुरक्षित सेव
// ============================================================
@Entity
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceUri: String,
    val outputUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM Project ORDER BY createdAt DESC")
    fun all(): Flow<List<Project>>
    @Insert suspend fun insert(p: Project): Long
    @Delete suspend fun delete(p: Project)
}

@Database(entities = [Project::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun projects(): ProjectDao
    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            Room.databaseBuilder(c.applicationContext, AppDb::class.java, "studio.db")
                .build().also { inst = it }
        }
    }
}

// ============================================================
// गैलरी में सेव और शेयर
// ============================================================
object SaveShare {
    fun exportsDir(ctx: Context): File =
        ctx.getExternalFilesDir("exports") ?: File(ctx.filesDir, "exports").also { it.mkdirs() }

    fun saveToGallery(ctx: Context, file: File): Result<Uri> = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PrakashAIStudio")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("गैलरी में फ़ाइल नहीं बन सकी")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri
    }

    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "शेयर करें").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ============================================================
// वीडियो इंजन (Media3): ट्रिम, स्पीड, चमक, फ़िल्टर, फ़ॉर्मेट, क्वालिटी
// ============================================================
enum class Filter(val label: String) { NONE("कोई नहीं"), BW("ब्लैक एंड व्हाइट"), WARM("गर्म"), COOL("ठंडा") }
enum class OutQuality(val label: String, val height: Int) {
    Q720("720p", 720), Q1080("1080p", 1080), Q4K("4K", 2160)
}
enum class Target(val label: String, val vertical: Boolean?) {
    ORIGINAL("मूल", null), SHORTS("YouTube Shorts 9:16", true),
    REELS("Instagram Reels 9:16", true), FACEBOOK("Facebook 16:9", false)
}

data class EditSettings(
    val startMs: Long, val endMs: Long,
    val speed: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val filter: Filter = Filter.NONE,
    val quality: OutQuality = OutQuality.Q1080,
    val target: Target = Target.ORIGINAL
)

class VideoEngine(private val ctx: Context) {
    private var transformer: Transformer? = null

    private fun watchProgress(onProgress: (Int) -> Unit) {
        val h = Handler(Looper.getMainLooper())
        val holder = ProgressHolder()
        h.post(object : Runnable {
            override fun run() {
                val tr = transformer ?: return
                if (tr.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    onProgress(holder.progress)
                }
                h.postDelayed(this, 300)
            }
        })
    }

    private fun listener(out: File, quality: OutQuality?, onDone: (File) -> Unit, onError: (String) -> Unit) =
        object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                transformer = null; onDone(out)
            }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                transformer = null
                val hint = if (quality == OutQuality.Q4K)
                    " (आपका फ़ोन शायद 4K सपोर्ट नहीं करता, 1080p चुनें)" else ""
                onError("एक्सपोर्ट विफल: ${exportException.errorCodeName}$hint")
            }
        }

    fun export(
        src: Uri, s: EditSettings,
        onProgress: (Int) -> Unit, onDone: (File) -> Unit, onError: (String) -> Unit
    ) {
        val out = File(SaveShare.exportsDir(ctx), "PrakashAI_${System.currentTimeMillis()}.mp4")
        val fx = mutableListOf<Effect>()
        if (s.brightness != 0f) fx += Brightness(s.brightness)
        if (s.contrast != 0f) fx += Contrast(s.contrast)
        if (s.saturation != 0f) fx += HslAdjustment.Builder().adjustSaturation(s.saturation).build()
        when (s.filter) {
            Filter.BW -> fx += HslAdjustment.Builder().adjustSaturation(-100f).build()
            Filter.WARM -> fx += RgbAdjustment.Builder().setRedScale(1.12f).setBlueScale(0.9f).build()
            Filter.COOL -> fx += RgbAdjustment.Builder().setRedScale(0.9f).setBlueScale(1.12f).build()
            Filter.NONE -> {}
        }
        s.target.vertical?.let { v ->
            fx += Presentation.createForAspectRatio(
                if (v) 9f / 16f else 16f / 9f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        }
        fx += Presentation.createForHeight(s.quality.height)

        val audio = mutableListOf<AudioProcessor>()
        if (s.speed != 1f) {
            val sonic = SonicAudioProcessor().apply { setSpeed(s.speed) }
            audio += sonic
        }

        val item = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(src).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(s.startMs).setEndPositionMs(s.endMs).build()
            ).build()
        ).setEffects(Effects(audio, fx)).build()

        val t = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(listener(out, s.quality, onDone, onError))
            .build()
        transformer = t
        t.start(item, out.absolutePath)
        watchProgress(onProgress)
    }

    fun combine(
        clips: List<Uri>, music: Uri?, vertical: Boolean, clipSeconds: Int,
        onProgress: (Int) -> Unit, onDone: (File) -> Unit, onError: (String) -> Unit
    ) {
        if (clips.size < 2) { onError("कम से कम 2 क्लिप चुनें (5 या ज़्यादा बेहतर है)"); return }
        val out = File(SaveShare.exportsDir(ctx), "PrakashAI_combo_${System.currentTimeMillis()}.mp4")
        val aspect = if (vertical) 9f / 16f else 16f / 9f
        val items = clips.map { uri ->
            EditedMediaItem.Builder(
                MediaItem.Builder().setUri(uri).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(0).setEndPositionMs(clipSeconds * 1000L).build()
                ).build()
            ).setEffects(Effects(emptyList(), listOf(
                Presentation.createForAspectRatio(aspect, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            ))).build()
        }
        val sequences = mutableListOf(EditedMediaItemSequence(items))
        if (music != null) {
            val total = clipSeconds * 1000L * clips.size
            val m = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(music).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(0).setEndPositionMs(total).build()
                ).build()
            ).setRemoveVideo(true).build()
            sequences += EditedMediaItemSequence(listOf(m))
        }
        val t = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(listener(out, null, onDone, onError))
            .build()
        transformer = t
        t.start(Composition.Builder(sequences).build(), out.absolutePath)
        watchProgress(onProgress)
    }

    fun cancel() { transformer?.cancel(); transformer = null }
}

fun videoDurationMs(ctx: Context, uri: Uri): Long {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
    } finally { r.release() }
}

// ============================================================
// मुख्य Activity और नेविगेशन
// ============================================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PrakashTheme { App() } }
    }
}

val pages = listOf(
    "Home", "AI Studio", "Movie Editor", "AI Shorts Maker",
    "Camera Studio", "Video Editor", "My Projects", "Gallery", "Settings"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    var page by remember { mutableStateOf("Home") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet {
            Text("Prakash AI Studio", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            pages.forEach { p ->
                NavigationDrawerItem(label = { Text(p) }, selected = p == page,
                    onClick = { page = p; scope.launch { drawer.close() } })
            }
        }
    }) {
        Scaffold(
            containerColor = Bg,
            topBar = {
                TopAppBar(
                    title = { Text(page) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, null)
                        }
                    }
                )
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (page) {
                    "Home" -> HomePage { page = it }
                    "AI Studio" -> MultiCamPage()
                    "Movie Editor" -> ShortsInfoPage()
                    "AI Shorts Maker" -> ShortsInfoPage()
                    "Camera Studio" -> CameraStudioPage()
                    "Video Editor" -> ProEditorPage()
                    "My Projects" -> ProjectsPage()
                    "Gallery" -> GalleryPage()
                    "Settings" -> SettingsPage()
                }
            }
        }
    }
}

// ============================================================
// होम पेज
// ============================================================
data class Feature(val title: String, val sub: String, val icon: ImageVector, val route: String)

val features = listOf(
    Feature("AI Studio", "कई क्लिप जोड़ें", Icons.Default.AutoAwesome, "AI Studio"),
    Feature("Movie Editor", "लंबी फ़िल्म", Icons.Default.Movie, "Movie Editor"),
    Feature("AI Shorts", "लंबा वीडियो → Shorts", Icons.Default.ContentCut, "AI Shorts Maker"),
    Feature("Camera Studio", "सीधे रिकॉर्ड करें", Icons.Default.Videocam, "Camera Studio"),
    Feature("Video Editor", "ट्रिम, स्पीड, फ़िल्टर", Icons.Default.Edit, "Video Editor"),
    Feature("My Projects", "सेव किए प्रोजेक्ट", Icons.Default.Folder, "My Projects"),
    Feature("Gallery", "एक्सपोर्ट किए वीडियो", Icons.Default.PhotoLibrary, "Gallery"),
    Feature("Settings", "सेटिंग्स", Icons.Default.Settings, "Settings"),
)

@Composable
fun HomePage(go: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Accent, Accent2))).padding(24.dp)
        ) {
            Column {
                Text("Prakash AI Studio", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, color = Color.White)
                Text("वीडियो बनाएँ, एडिट करें, शेयर करें", color = Color.White)
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(
            GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(features.size) { i ->
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(i * 70L); shown = true }
                AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { it / 2 }) {
                    val f = features[i]
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                            .background(Surface1).clickable { go(f.route) }.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(f.icon, null, tint = Accent2)
                        Text(f.title, fontWeight = FontWeight.SemiBold)
                        Text(f.sub, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ============================================================
// वीडियो एडिटर पेज
// ============================================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProEditorPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var durSec by remember { mutableStateOf(0f) }
    var range by remember { mutableStateOf(0f..1f) }
    var speed by remember { mutableStateOf(1f) }
    var bright by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(0f) }
    var sat by remember { mutableStateOf(0f) }
    var filter by remember { mutableStateOf(Filter.NONE) }
    var quality by remember { mutableStateOf(OutQuality.Q1080) }
    var target by remember { mutableStateOf(Target.ORIGINAL) }
    var progress by remember { mutableStateOf(-1) }
    var status by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<File?>(null) }
    val engine = remember { VideoEngine(ctx) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        uri = u
        if (u != null) {
            durSec = videoDurationMs(ctx, u) / 1000f
            range = 0f..durSec
        }
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { pick.launch(PickVisualMediaRequest(
            ActivityResultContracts.PickVisualMedia.VideoOnly)) }) { Text("वीडियो चुनें") }
        if (uri != null && durSec > 0f) {
            Text("ट्रिम: ${range.start.toInt()}s से ${range.endInclusive.toInt()}s (कुल ${durSec.toInt()}s)")
            RangeSlider(range, { range = it }, valueRange = 0f..durSec)
            Text("स्पीड: ${"%.2f".format(speed)}x"); Slider(speed, { speed = it }, valueRange = 0.25f..4f)
            Text("चमक"); Slider(bright, { bright = it }, valueRange = -1f..1f)
            Text("कॉन्ट्रास्ट"); Slider(contrast, { contrast = it }, valueRange = -1f..1f)
            Text("रंग की गहराई"); Slider(sat, { sat = it }, valueRange = -100f..100f)
            Text("फ़िल्टर")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Filter.values().forEach { f -> FilterChip(filter == f, { filter = f }, { Text(f.label) }) }
            }
            Text("फ़ॉर्मेट")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Target.values().forEach { t -> FilterChip(target == t, { target = t }, { Text(t.label) }) }
            }
            Text("क्वालिटी")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutQuality.values().forEach { q -> FilterChip(quality == q, { quality = q }, { Text(q.label) }) }
            }
            Button(enabled = progress < 0, onClick = {
                if (range.endInclusive - range.start < 1f) {
                    status = "त्रुटि: ट्रिम कम से कम 1 सेकंड का रखें"; return@Button
                }
                progress = 0; status = "प्रोसेसिंग..."; last = null
                val s = EditSettings((range.start * 1000).toLong(), (range.endInclusive * 1000).toLong(),
                    speed, bright, contrast, sat, filter, quality, target)
                engine.export(uri!!, s,
                    onProgress = { progress = it },
                    onDone = { f ->
                        progress = -1; status = "तैयार: ${f.name}"; last = f
                        scope.launch(Dispatchers.IO) {
                            AppDb.get(ctx).projects().insert(
                                Project(name = f.name, sourceUri = uri.toString(), outputUri = f.absolutePath))
                        }
                    },
                    onError = { progress = -1; status = it })
            }) { Text("एक्सपोर्ट करें") }
            if (progress >= 0) {
                LinearProgressIndicator(progress / 100f, Modifier.fillMaxWidth()); Text("$progress%")
                TextButton(onClick = { engine.cancel(); progress = -1; status = "रद्द किया" }) { Text("रद्द करें") }
            }
            Text(status)
            last?.let { f ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { status = SaveShare.saveToGallery(ctx, f).fold(
                        { "गैलरी में सेव हो गया" }, { "सेव नहीं हुआ: ${it.message}" }) }) { Text("गैलरी में सेव") }
                    OutlinedButton(onClick = { SaveShare.share(ctx, f) }) { Text("शेयर") }
                }
            }
        }
    }
}

// ============================================================
// AI Studio: कई क्लिप जोड़कर एक वीडियो
// ============================================================
@Composable
fun MultiCamPage() {
    val ctx = LocalContext.current
    var clips by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var music by remember { mutableStateOf<Uri?>(null) }
    var vertical by remember { mutableStateOf(true) }
    var seconds by remember { mutableStateOf(4f) }
    var progress by remember { mutableStateOf(-1) }
    var status by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<File?>(null) }
    val engine = remember { VideoEngine(ctx) }

    val pickClips = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)) { clips = it }
    val pickMusic = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { music = it }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("चुनी हुई क्लिप एक के बाद एक जुड़ती हैं, हर क्लिप से तय सेकंड लिए जाते हैं। यह स्मार्ट चुनाव नहीं है, और ट्रांज़िशन अभी नहीं हैं।",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = { pickClips.launch(PickVisualMediaRequest(
            ActivityResultContracts.PickVisualMedia.VideoOnly)) }) { Text("क्लिप चुनें (5+ बेहतर)") }
        Text("चुनी गई क्लिप: ${clips.size}")
        OutlinedButton(onClick = { pickMusic.launch(arrayOf("audio/*")) }) {
            Text(if (music == null) "बैकग्राउंड म्यूज़िक चुनें (वैकल्पिक)" else "म्यूज़िक चुना गया") }
        Text("हर क्लिप से: ${seconds.toInt()} सेकंड")
        Slider(seconds, { seconds = it }, valueRange = 2f..10f, steps = 7)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(vertical, { vertical = it }); Text("  9:16 वर्टिकल")
        }
        Button(enabled = clips.size >= 2 && progress < 0, onClick = {
            progress = 0; status = "वीडियो बन रहा है..."; last = null
            engine.combine(clips, music, vertical, seconds.toInt(),
                onProgress = { progress = it },
                onDone = { progress = -1; status = "तैयार: ${it.name}"; last = it },
                onError = { progress = -1; status = it })
        }) { Text("वीडियो बनाएँ") }
        if (progress >= 0) {
            LinearProgressIndicator(progress / 100f, Modifier.fillMaxWidth()); Text("$progress%")
            TextButton(onClick = { engine.cancel(); progress = -1; status = "रद्द किया" }) { Text("रद्द करें") }
        }
        Text(status)
        last?.let { f ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { status = SaveShare.saveToGallery(ctx, f).fold(
                    { "गैलरी में सेव हो गया" }, { "सेव नहीं हुआ: ${it.message}" }) }) { Text("गैलरी में सेव") }
                OutlinedButton(onClick = { SaveShare.share(ctx, f) }) { Text("शेयर") }
            }
        }
    }
}

// ============================================================
// Shorts / Movie Editor: अभी सिर्फ़ ईमानदार सूचना
// (पूरा फ़ीचर अगले चरण में जुड़ेगा)
// ============================================================
@Composable
fun ShortsInfoPage() {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("यह फ़ीचर इस संस्करण में अभी जुड़ा नहीं है।", style = MaterialTheme.typography.titleMedium)
        Text("लंबे वीडियो से Shorts बनाने के लिए अभी Video Editor में ट्रिम और 'YouTube Shorts 9:16' फ़ॉर्मेट इस्तेमाल करें।")
        Text("आवाज़ की ऊर्जा से सबसे अच्छे हिस्से ढूँढने वाला कोड अगले चरण में जोड़ा जाएगा। सीन का अर्थ समझकर चुनाव के लिए बाहरी AI सेवा चाहिए।")
    }
}

// ============================================================
// कैमरा स्टूडियो (CameraX)
// ============================================================
@Composable
fun CameraStudioPage() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPerm by remember {
        mutableStateOf(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED })
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { r -> hasPerm = r.values.all { it } }

    if (!hasPerm) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("कैमरा और माइक्रोफ़ोन की अनुमति चाहिए")
            Button(onClick = { permLauncher.launch(arrayOf(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) { Text("अनुमति दें") }
        }
        return
    }

    var front by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(CamQuality.FHD) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var clipCount by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    val previewView = remember { PreviewView(ctx) }

    LaunchedEffect(front, quality) {
        try {
            val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(ctx).get() }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val recorder = Recorder.Builder().setQualitySelector(
                QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality))).build()
            val vc = VideoCapture.withOutput(recorder)
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                preview, vc)
            videoCapture = vc
        } catch (e: Exception) { msg = "कैमरा शुरू नहीं हो सका: ${e.message}" }
    }

    Column(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.weight(1f).fillMaxWidth())
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CamQuality.HD to "720p", CamQuality.FHD to "1080p", CamQuality.UHD to "4K").forEach { (q, label) ->
                    FilterChip(quality == q, onClick = { if (recording == null) quality = q },
                        label = { Text(label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = recording == null, onClick = { front = !front }) {
                    Text(if (front) "पीछे का कैमरा" else "सेल्फ़ी कैमरा") }
                Button(onClick = {
                    val rec = recording
                    if (rec != null) { rec.stop(); recording = null; return@Button }
                    val vc = videoCapture ?: return@Button
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "PrakashAI_clip_${System.currentTimeMillis()}")
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PrakashAIStudio")
                    }
                    val opts = MediaStoreOutputOptions.Builder(ctx.contentResolver,
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
                    @Suppress("MissingPermission")
                    recording = vc.output.prepareRecording(ctx, opts).withAudioEnabled()
                        .start(ContextCompat.getMainExecutor(ctx)) { ev ->
                            if (ev is VideoRecordEvent.Finalize) {
                                if (ev.hasError()) msg = "रिकॉर्डिंग त्रुटि: कोड ${ev.error}"
                                else { clipCount++; msg = "क्लिप $clipCount गैलरी में सेव हुई" }
                            }
                        }
                }) { Text(if (recording != null) "रोकें" else "रिकॉर्ड करें") }
            }
            Text("रिकॉर्ड की गई क्लिप: $clipCount")
            if (msg.isNotEmpty()) Text(msg)
        }
    }
}

// ============================================================
// माय प्रोजेक्ट्स
// ============================================================
@Composable
fun ProjectsPage() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).projects() }
    val projects by dao.all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Column(Modifier.padding(16.dp)) {
        Text("मेरे प्रोजेक्ट: ${projects.size}")
        if (projects.isEmpty()) Text("अभी कोई प्रोजेक्ट सेव नहीं है।")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(projects) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(p.name)
                            Text(DateFormat.getDateTimeInstance().format(Date(p.createdAt)),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { dao.delete(p) } }) { Text("हटाएँ") }
                    }
                }
            }
        }
    }
}

// ============================================================
// गैलरी
// ============================================================
private fun listExports(ctx: Context): List<File> =
    SaveShare.exportsDir(ctx).listFiles { f -> f.extension == "mp4" }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()

@Composable
fun GalleryPage() {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(listExports(ctx)) }
    var msg by remember { mutableStateOf("") }

    Column(Modifier.padding(16.dp)) {
        Text("एक्सपोर्ट किए वीडियो: ${files.size}")
        if (msg.isNotEmpty()) Text(msg)
        if (files.isEmpty()) Text("अभी कोई एक्सपोर्ट नहीं है। किसी एडिटर से वीडियो बनाइए।")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files) { f ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(f.name)
                        Text("${f.length() / 1024 / 1024} MB  •  " +
                            DateFormat.getDateTimeInstance().format(Date(f.lastModified())),
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                msg = SaveShare.saveToGallery(ctx, f).fold(
                                    { "गैलरी में सेव हो गया" }, { "सेव नहीं हुआ: ${it.message}" })
                            }) { Text("गैलरी में सेव") }
                            OutlinedButton(onClick = { SaveShare.share(ctx, f) }) { Text("शेयर") }
                            TextButton(onClick = {
                                if (f.delete()) { files = listExports(ctx); msg = "हटाया गया" }
                                else msg = "फ़ाइल हटाई नहीं जा सकी"
                            }) { Text("हटाएँ") }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// सेटिंग्स
// ============================================================
@Composable
fun SettingsPage() {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf("") }
    val dir = SaveShare.exportsDir(ctx)
    val used = dir.listFiles()?.sumOf { it.length() }?.div(1024 * 1024) ?: 0

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Prakash AI Studio v1.0", style = MaterialTheme.typography.titleMedium)
        Text("एक्सपोर्ट फ़ोल्डर का उपयोग: $used MB")
        Button(onClick = {
            val n = dir.listFiles()?.count { it.delete() } ?: 0
            msg = "$n फ़ाइलें हटाईं"
        }) { Text("एक्सपोर्ट फ़ाइलें साफ़ करें") }
        if (msg.isNotEmpty()) Text(msg)
        HorizontalDivider()
        Text("ऑन-डिवाइस और मुफ़्त (इस संस्करण में):", style = MaterialTheme.typography.titleSmall)
        Text("• ट्रिम, स्पीड, चमक, कॉन्ट्रास्ट, रंग, फ़िल्टर\n• Shorts/Reels/Facebook फ़ॉर्मेट, 720p/1080p/4K\n• कैमरा रिकॉर्डिंग\n• कई क्लिप जोड़कर एक वीडियो, म्यूज़िक के साथ\n• गैलरी में सेव और शेयर")
        Text("अभी जुड़ना बाकी:", style = MaterialTheme.typography.titleSmall)
        Text("• सबटाइटल, शोर हटाना, ऑटो-ज़ूम\n• सबसे अच्छे हिस्से ढूँढना (Shorts)\n• टेक्स्ट और स्टिकर")
        Text("बाहरी AI सेवा चाहिए:", style = MaterialTheme.typography.titleSmall)
        Text("• सीन का अर्थ समझकर सबसे दिलचस्प हिस्सा चुनना\n• चेहरे के पीछे चलने वाला ज़ूम\n• बहुत सटीक हिंदी सबटाइटल")
    }
}
