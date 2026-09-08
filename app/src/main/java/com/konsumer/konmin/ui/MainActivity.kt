package com.konsumer.konmin.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.konsumer.konmin.data.AppDatabase
import com.konsumer.konmin.data.AppSync
import com.konsumer.konmin.data.SettingsRepository
import com.konsumer.konmin.data.ThemeSettings
import com.konsumer.konmin.plugin.ClickedLine
import com.konsumer.konmin.plugin.PluginPermission
import com.konsumer.konmin.plugin.PluginRepository
import com.konsumer.konmin.theme.WallpaperAccent
import com.konsumer.konmin.widget.WidgetSeeder
import com.konsumer.konmin.worker.ForegroundTicker
import com.konsumer.konmin.worker.WidgetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val settingsRepo by lazy { SettingsRepository(applicationContext) }
    private val pluginRepo by lazy { PluginRepository(applicationContext) }

    /** Last install attempt's result, shown under the "add plugin" button. */
    private val installStatus = mutableStateOf<String?>(null)

    /**
     * Installing a plugin is "pick a .js file". OpenDocument gives us a
     * persistable read grant for exactly that one file and nothing else — no
     * storage permission, no directory access.
     */
    private val pluginPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { pluginRepo.installFromUri(uri) }
                installStatus.value = result.fold(
                    onSuccess = { "installed ${it.name} — enable it below" },
                    onFailure = { "could not install: ${it.message}" }
                )
                // Give the new plugin a row so it shows up in the list.
                withContext(Dispatchers.IO) { WidgetSeeder.seed(applicationContext) }
            }
        }

    /**
     * Plugin permissions are requested lazily, at the moment a user enables a
     * plugin that declared them — not up front on first launch, since most
     * users will never install a plugin that wants the calendar.
     */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Nothing to do: RealPluginContext re-checks the live grant state
            // on every call, so a denial simply keeps that API unavailable.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val db = AppDatabase.get(applicationContext)

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppSync.sync(applicationContext)
                // Picks up plugins dropped into filesDir since last launch, and
                // creates the built-in clock/date/battery rows on first run.
                WidgetSeeder.seed(applicationContext)
            }
        }

        // Only meaningful while the launcher is actually on screen; cancelled
        // automatically when it isn't.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                ForegroundTicker.run(applicationContext)
            }
        }

        setContent {
            val settings by settingsRepo.settings.collectAsState(initial = ThemeSettings())
            var showSettings by remember { mutableStateOf(false) }
            var clickableWidgets by remember { mutableStateOf(emptySet<String>()) }

            LaunchedEffect(Unit) {
                clickableWidgets = withContext(Dispatchers.IO) {
                    pluginRepo.list().filter { it.manifest.handlesClick }
                        .map { it.manifest.id }.toSet()
                }
            }

            LaunchedEffect(settings.hideStatusBar) {
                applyStatusBarVisibility(settings.hideStatusBar)
            }

            // Solid backgrounds must suppress the system wallpaper at the
            // window level (not merely cover it), and wallpaper mode must
            // ask for it again. The theme starts FLAG_SHOW_WALLPAPER set, so
            // this toggles it from the observed settings instead of relying
            // on the static style. LifecycleResumeEffect re-applies on every
            // resume too (returning to the launcher, coming back from the
            // wallpaper picker), not just when the value changes.
            val bgOpaque = (settings.bgColorArgb ushr 24) == 0xFF
            LifecycleResumeEffect(bgOpaque) {
                applyWallpaperMode(bgOpaque)
                onPauseOrDispose { }
            }

            // System back closes settings rather than leaving the launcher.
            BackHandler(enabled = showSettings) { showSettings = false }

            KonminRoot(
                settings = settings,
                settingsRepo = settingsRepo,
                appDao = db.appDao(),
                widgetConfigDao = db.widgetConfigDao(),
                pluginRepo = pluginRepo,
                hideStatusBar = settings.hideStatusBar,
                clickableWidgetIds = clickableWidgets,
                onWidgetLineClick = ::onWidgetLineClick,
                showSettings = showSettings,
                onOpenSettings = { showSettings = true },
                onCloseSettings = { showSettings = false },
                isPermissionGranted = ::isGranted,
                requestPermissions = ::requestPluginPermissions,
                onAddPlugin = ::pickPlugin,
                installStatus = installStatus.value,
                onRestoreExamples = ::restoreExamples,
                onRemovePlugin = ::removePlugin,
                onLaunchApp = ::launchApp,
                onAppInfo = ::openAppInfo
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Recompute here as well as from the wallpaper-changed broadcast: on
        // Android 8+ the broadcast doesn't always reach a manifest receiver,
        // and returning to the home screen is exactly when a stale color
        // would be noticed.
        lifecycleScope.launch {
            runCatching { WallpaperAccent.refresh(applicationContext, settingsRepo) }
        }
    }

    /**
     * Plain text/JS mime types plus a wildcard: a .js file arrives as
     * text/javascript from some providers, application/octet-stream from
     * others, and occasionally with no type at all.
     */
    private fun pickPlugin() {
        installStatus.value = null
        pluginPicker.launch(arrayOf("text/javascript", "application/javascript", "text/plain", "*/*"))
    }

    private fun restoreExamples() {
        lifecycleScope.launch {
            val installed = withContext(Dispatchers.IO) {
                pluginRepo.installBundledExamples(overwrite = true).also {
                    WidgetSeeder.seed(applicationContext)
                }
            }
            installStatus.value = "restored ${installed.size} example plugins"
        }
    }

    private fun removePlugin(id: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                pluginRepo.uninstall(id).onSuccess {
                    // Drops the now-orphaned widget_configs row.
                    WidgetSeeder.seed(applicationContext)
                }
            }
            installStatus.value = result.fold(
                onSuccess = { "removed $id" },
                onFailure = { it.message ?: "could not remove $id" }
            )
        }
    }

    /**
     * Drives [WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER] from the
     * current background setting. Wallpaper mode (transparent bg) keeps the
     * flag so the system wallpaper — live or static — renders behind the
     * launcher, which draws nothing. Solid mode (opaque bg) clears it so no
     * wallpaper is rendered at all; the launcher's own opaque paint fills
     * the screen instead.
     */
    private fun applyWallpaperMode(bgOpaque: Boolean) {
        if (bgOpaque) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        }
    }

    /**
     * TRANSIENT_BARS_BY_SWIPE keeps the bar reachable: a swipe from the top
     * edge slides it back over the content for a few seconds, then it hides
     * itself again. Without that the only way to see the clock or battery
     * would be to leave the launcher.
     */
    private fun applyStatusBarVisibility(hide: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            // Hand the bars back exactly as they were. Leaving the transient
            // behaviour set while showing them changes how the system treats
            // bar visibility for no reason — the setting is off, so we should
            // have no opinion at all.
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    /**
     * A tap on a widget line. Runs on the activity's scope rather than the
     * composition's so a plugin that opens something isn't cancelled halfway
     * by the recomposition its own launch causes.
     */
    private fun onWidgetLineClick(widgetId: String, lineIndex: Int, text: String) {
        lifecycleScope.launch {
            val dao = AppDatabase.get(applicationContext).widgetConfigDao()
            val config = withContext(Dispatchers.IO) { dao.getById(widgetId) } ?: return@launch
            WidgetRenderer.handleClick(
                applicationContext,
                config,
                ClickedLine(index = lineIndex, text = text)
            )
        }
    }

    private fun isGranted(permission: PluginPermission): Boolean {
        val androidPermission = permission.toAndroidPermission() ?: return true
        return ContextCompat.checkSelfPermission(this, androidPermission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestPluginPermissions(permissions: List<PluginPermission>) {
        val toRequest = permissions.mapNotNull { it.toAndroidPermission() }
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
    }

    private fun PluginPermission.toAndroidPermission(): String? = when (this) {
        PluginPermission.LOCATION -> Manifest.permission.ACCESS_COARSE_LOCATION
        PluginPermission.CALENDAR -> Manifest.permission.READ_CALENDAR
        // NETWORK is granted at install; STORAGE is our own Room table, not
        // the filesystem; LAUNCH starts only a fixed set of intents. None of
        // them has a runtime prompt to request.
        PluginPermission.NETWORK, PluginPermission.STORAGE, PluginPermission.LAUNCH -> null
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        runCatching { startActivity(intent) }
    }

    /** Long-press target: the system app page, where uninstall lives. */
    private fun openAppInfo(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KonminRoot(
    settings: ThemeSettings,
    settingsRepo: SettingsRepository,
    appDao: com.konsumer.konmin.data.AppDao,
    widgetConfigDao: com.konsumer.konmin.data.WidgetConfigDao,
    pluginRepo: PluginRepository,
    hideStatusBar: Boolean,
    clickableWidgetIds: Set<String>,
    onWidgetLineClick: (String, Int, String) -> Unit,
    showSettings: Boolean,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    isPermissionGranted: (PluginPermission) -> Boolean,
    requestPermissions: (List<PluginPermission>) -> Unit,
    onAddPlugin: () -> Unit,
    installStatus: String?,
    onRestoreExamples: () -> Unit,
    onRemovePlugin: (String) -> Unit,
    onLaunchApp: (String) -> Unit,
    onAppInfo: (String) -> Unit
) {
    val fgColor = Color(settings.fgColorArgb)
    val baseSizeSp = settings.textSize.scale * 16f
    val glow = settings.glowStyle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(settings.bgColorArgb))
            // The window is edge-to-edge so the wallpaper reaches the corners.
            // With the status bar hidden there's nothing up there to avoid, so
            // pad only the nav bar and let the widget stack take that strip —
            // reclaiming the space is the whole point of hiding it.
            .then(
                if (hideStatusBar) Modifier.navigationBarsPadding() else Modifier.systemBarsPadding()
            )
            // Long-press anywhere that isn't an app row opens settings — the
            // conventional launcher gesture. The gear on the app-list row is
            // the alternative entry point; either or both are offered per the
            // user's "open settings with" choice.
            .then(
                if (settings.settingsAccess.allowsLongPress) {
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = onOpenSettings,
                        indication = null,
                        interactionSource = MutableInteractionSource()
                    )
                } else {
                    Modifier
                }
            )
    ) {
        if (showSettings) {
            SettingsScreen(
                settings = settings,
                settingsRepo = settingsRepo,
                appDao = appDao,
                widgetConfigDao = widgetConfigDao,
                pluginRepo = pluginRepo,
                fgColor = fgColor,
                baseSizeSp = baseSizeSp,
                isPermissionGranted = isPermissionGranted,
                requestPermissions = requestPermissions,
                onAddPlugin = onAddPlugin,
                installStatus = installStatus,
                onRestoreExamples = onRestoreExamples,
                onRemovePlugin = onRemovePlugin,
                onBack = onCloseSettings
            )
        } else {
            WidgetStack(
                widgetConfigDao = widgetConfigDao,
                fgColor = fgColor,
                baseSizeSp = baseSizeSp,
                glow = glow,
                onLongPress = onOpenSettings.takeIf { settings.settingsAccess.allowsLongPress },
                clickableWidgetIds = clickableWidgetIds,
                onLineClick = onWidgetLineClick
            )
            AppListScreen(
                appDao = appDao,
                sortOrder = settings.sortOrder,
                fgColor = fgColor,
                baseSizeSp = baseSizeSp,
                glow = glow,
                searchEnabled = settings.searchEnabled,
                searchIncludesHidden = settings.searchIncludesHidden,
                searchAutoLaunch = settings.searchAutoLaunch,
                showSettingsIcon = settings.settingsAccess.showsIcon,
                onOpenSettings = onOpenSettings,
                onLaunchApp = onLaunchApp,
                onAppInfo = onAppInfo,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
