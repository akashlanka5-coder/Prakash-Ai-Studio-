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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.sp
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
// THEME
// ============================================================
val Bg = Color(0xFF0B0D12)
val Surface1 = Color(0xFF151923)
val Surface2 = Color(0xFF1E2430)
val Accent = Color(0xFF7C5CFF)
val Accent2 = Color(0xFF22D3EE)
val Muted = Color(0xFF9AA3B2)

private val DarkScheme = darkColorScheme(
    primary = Accent, secondary = Accent2,
    background = Bg, surface = Surface1,
    onPrimary = Color.White, onBackground = Color(0xFFE8EAF0), onSurface = Color(0xFFE8EAF0)
)

@Composable
fun PrakashTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = DarkScheme, content = content)

// ============================================================
// DATABASE (Room)
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
// SAVE TO GALLERY AND SHARE
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
            ?: error("Could not create file in gallery")
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
        ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ============================================================
// VIDEO ENGINE (Media3)
// ============================================================
enum class Filter(val label: String, val c1: Color, val c2: Color) {
    NONE("None", Color(0xFF475569), Color(0xFF64748B)),
    BW("B&W", Color(0xFF111827), Color(0xFF9CA3AF)),
    WARM("Warm", Color(0xFFF97316), Color(0xFFFBBF24)),
    COOL("Cool", Color(0xFF3B82F6), Color(0xFF22D3EE))
}
enum class OutQuality(val label: String, val height: Int) {
    Q720("720p", 720), Q1080("1080p", 1080), Q4K("4K", 2160)
}
enum class Target(val label: String, val vertical: Boolean?) {
    ORIGINAL("Original", null), SHORTS("YouTube Shorts 9:16", true),
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
                    " (your phone may not support 4K, try 1080p)" else ""
                onError("Export failed: ${exportException.errorCodeName}$hint")
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
        if (clips.size < 2) { onError("Select at least 2 clips (5 or more is better)"); return }
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
// MAIN ACTIVITY AND NAVIGATION
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

// Preset chosen on the Home screen, picked up by the Video Editor
var pendingTarget: Target? = null

data class NavItem(val label: String, val route: String, val icon: ImageVector)

val bottomNav = listOf(
    NavItem("Home", "Home", Icons.Default.Home),
    NavItem("Studio", "AI Studio", Icons.Default.AutoAwesome),
    NavItem("Camera", "Camera Studio", Icons.Default.Videocam),
    NavItem("Projects", "My Projects", Icons.Default.Folder),
    NavItem("Settings", "Settings", Icons.Default.Settings)
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
            },
            bottomBar = {
                NavigationBar(containerColor = Surface1) {
                    bottomNav.forEach { n ->
                        NavigationBarItem(
                            selected = page == n.route,
                            onClick = { page = n.route },
                            icon = { Icon(n.icon, null) },
                            label = { Text(n.label, fontSize = 11.sp) }
                        )
                    }
                }
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
// HOME PAGE
// ============================================================
data class Feature(
    val title: String, val sub: String, val icon: ImageVector,
    val route: String, val c1: Color, val c2: Color
)

val features = listOf(
    Feature("AI Studio", "Combine clips", Icons.Default.AutoAwesome, "AI Studio", Color(0xFF7C5CFF), Color(0xFFB794F6)),
    Feature("Movie Editor", "Long movies", Icons.Default.Movie, "Movie Editor", Color(0xFFEC4899), Color(0xFFF472B6)),
    Feature("AI Shorts", "Long video to Shorts", Icons.Default.ContentCut, "AI Shorts Maker", Color(0xFFF59E0B), Color(0xFFFBBF24)),
    Feature("Camera Studio", "Record videos", Icons.Default.Videocam, "Camera Studio", Color(0xFF10B981), Color(0xFF34D399)),
    Feature("Video Editor", "Trim, speed, filters", Icons.Default.Edit, "Video Editor", Color(0xFF3B82F6), Color(0xFF60A5FA)),
    Feature("Gallery", "Exported videos", Icons.Default.PhotoLibrary, "Gallery", Color(0xFF06B6D4), Color(0xFF22D3EE)),
)

@Composable
fun HomePage(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).projects() }
    val projects by dao.all().collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {

        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Accent, Accent2))).padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Prakash AI Studio", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Create, edit and share videos", fontSize = 14.sp, color = Color.White)
                Button(
                    onClick = { pendingTarget = null; go("Video Editor") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1E1B4B))
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("New Project", fontWeight = FontWeight.Bold)
                }
            }
        }

        Text("Start a project", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PresetCard("YouTube", "16:9", Icons.Default.PlayCircle, Color(0xFFEF4444), Modifier.weight(1f)) {
                pendingTarget = Target.FACEBOOK; go("Video Editor")
            }
            PresetCard("Shorts", "9:16", Icons.Default.PhoneAndroid, Color(0xFFF59E0B), Modifier.weight(1f)) {
                pendingTarget = Target.SHORTS; go("Video Editor")
            }
            PresetCard("Reels", "9:16", Icons.Default.CameraAlt, Color(0xFFEC4899), Modifier.weight(1f)) {
                pendingTarget = Target.REELS; go("Video Editor")
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Recent projects", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            TextButton(onClick = { go("My Projects") }) { Text("See all") }
        }
        if (projects.isEmpty()) {
            Text("No projects yet. Tap New Project to start.", color = Muted, fontSize = 13.sp)
        } else {
            projects.take(3).forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1)
                        .clickable { go("My Projects") }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(Accent, Accent2))),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Movie, null, tint = Color.White) }
                    Column {
                        Text(p.name, fontSize = 14.sp, maxLines = 1)
                        Text(DateFormat.getDateTimeInstance().format(Date(p.createdAt)),
                            fontSize = 11.sp, color = Muted)
                    }
                }
            }
        }

        Text("Tools", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        features.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { col, f ->
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { kotlinx.coroutines.delay((row * 2 + col) * 80L); shown = true }
                    AnimatedVisibility(shown, modifier = Modifier.weight(1f),
                        enter = fadeIn() + slideInVertically { it / 2 }) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                                .background(Surface1).clickable { go(f.route) }.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier.size(46.dp).clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(f.c1, f.c2))),
                                contentAlignment = Alignment.Center
                            ) { Icon(f.icon, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                            Text(f.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(f.sub, fontSize = 12.sp, color = Muted)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun PresetCard(
    title: String, sub: String, icon: ImageVector, color: Color,
    modifier: Modifier, onClick: () -> Unit
) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Surface1)
            .clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(sub, fontSize = 11.sp, color = Muted)
    }
}

// ===== END OF PART 1: now paste PART 2 directly below this line =====
