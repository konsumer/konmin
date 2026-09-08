package com.konsumer.konmin.ui

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.konsumer.konmin.data.AppDao
import com.konsumer.konmin.data.SettingsAccess
import com.konsumer.konmin.data.SettingsRepository
import com.konsumer.konmin.data.SortOrder
import com.konsumer.konmin.data.TextSizeOption
import com.konsumer.konmin.data.ThemeSettings
import com.konsumer.konmin.data.WidgetConfig
import com.konsumer.konmin.data.WidgetConfigDao
import com.konsumer.konmin.plugin.PluginManifest
import com.konsumer.konmin.plugin.PluginPermission
import com.konsumer.konmin.plugin.PluginRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Presets rather than a full color wheel — this is a minimal launcher. */
private val FG_PRESETS = listOf(
    0xFFFFFFFF, 0xFFE0E0E0, 0xFF9E9E9E, 0xFF000000,
    0xFFEF5350, 0xFFFFB74D, 0xFF66BB6A, 0xFF42A5F5, 0xFFAB47BC
).map { it.toInt() }

/**
 * `0` is "off" — a fully transparent halo draws nothing. Black and white
 * cover almost every case; the tinted ones are for matching an accent.
 */
private val GLOW_PRESETS = listOf(
    0x00000000, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
    0xFF1A237E.toInt(), 0xFF4A148C.toInt(), 0xFF3E2723.toInt()
)

/** The transparent value that means "show the system wallpaper". */
private const val WALLPAPER_BG = 0x00000000

/**
 * Flat colors the home screen is painted when the wallpaper is off — all
 * fully opaque, since anything translucent is a wallpaper overlay. The name
 * pairs each swatch with its accessibility label.
 */
private val BG_SOLIDS = listOf(
    0xFF000000.toInt() to "black",
    0xFF212121.toInt() to "dark grey",
    0xFF424242.toInt() to "grey",
    0xFF1A237E.toInt() to "navy",
    0xFFE0E0E0.toInt() to "light grey",
    0xFFFFFFFF.toInt() to "white"
)

@Composable
fun SettingsScreen(
    settings: ThemeSettings,
    settingsRepo: SettingsRepository,
    appDao: AppDao,
    widgetConfigDao: WidgetConfigDao,
    pluginRepo: PluginRepository,
    fgColor: Color,
    baseSizeSp: Float,
    /** Returns true if the OS permission is currently granted. */
    isPermissionGranted: (PluginPermission) -> Boolean,
    /** Asks the OS for the permission a plugin needs; result arrives asynchronously. */
    requestPermissions: (List<PluginPermission>) -> Unit,
    /** Opens the system file browser to pick a .js plugin. */
    onAddPlugin: () -> Unit,
    /** Most recent install attempt's message, success or failure. */
    installStatus: String?,
    onRestoreExamples: () -> Unit,
    onRemovePlugin: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configs by widgetConfigDao.getAll().collectAsState(initial = emptyList())
    val allApps by appDao.getAllApps().collectAsState(initial = emptyList())
    var appsExpanded by remember { mutableStateOf(false) }
    var manifests by remember { mutableStateOf(emptyMap<String, PluginManifest>()) }

    // Keyed on the installed set, not Unit: installing or removing a plugin
    // has to refresh this, or a freshly added plugin shows its raw id instead
    // of the name and description its manifest declares.
    LaunchedEffect(configs.map { it.id }) {
        manifests = withContext(Dispatchers.IO) {
            pluginRepo.list().associate { it.manifest.id to it.manifest }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            // Settings is dense text over an arbitrary wallpaper, so it gets a
            // scrim the home screen deliberately doesn't have.
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 24.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "settings",
                    color = fgColor,
                    fontSize = (baseSizeSp * 1.3f).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "done",
                    color = fgColor.copy(alpha = 0.7f),
                    fontSize = baseSizeSp.sp,
                    modifier = Modifier.clickable { onBack() }.padding(8.dp)
                )
            }
        }

        // ---- Theme ----
        item { SectionHeader("theme", fgColor, baseSizeSp) }

        item {
            ToggleRow(
                label = "accent from wallpaper",
                subtitle = "picks a readable text color from your wallpaper",
                checked = settings.autoAccentFromWallpaper,
                fgColor = fgColor,
                baseSizeSp = baseSizeSp,
                onChange = { scope.launch { settingsRepo.setAutoAccent(it) } }
            )
        }

        item {
            // Just opens the OS picker — the wallpaper stays the system's, so
            // konmin bundles none and only ever reads what the OS supplies.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openWallpaperPicker(context) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("set wallpaper", color = fgColor, fontSize = baseSizeSp.sp)
                    Text(
                        "opens the system wallpaper picker",
                        color = fgColor.copy(alpha = 0.45f),
                        fontSize = (baseSizeSp * 0.72f).sp
                    )
                }
                Text(
                    "»",
                    color = fgColor.copy(alpha = 0.45f),
                    fontSize = baseSizeSp.sp
                )
            }
        }

        item {
            Label("text color", fgColor, baseSizeSp)
            SwatchRow(
                colors = FG_PRESETS,
                selected = settings.fgColorArgb,
                fgColor = fgColor,
                onPick = { picked ->
                    scope.launch {
                        // Choosing a color by hand is an implicit "stop
                        // overwriting it from the wallpaper".
                        settingsRepo.setAutoAccent(false)
                        settingsRepo.setColors(fg = picked, bg = settings.bgColorArgb)
                    }
                }
            )
        }

        item {
            Label("background", fgColor, baseSizeSp)
            Text(
                text = "system wallpaper, or a plain solid color",
                color = fgColor.copy(alpha = 0.45f),
                fontSize = (baseSizeSp * 0.72f).sp
            )
            Spacer(Modifier.height(6.dp))
            BackgroundChoiceRow(
                selectedBg = settings.bgColorArgb,
                fgColor = fgColor,
                baseSizeSp = baseSizeSp,
                onPick = { picked ->
                    scope.launch {
                        if (picked == WALLPAPER_BG) {
                            // Wallpaper shows again; leave the user's accent
                            // choice alone — only a solid color makes the
                            // wallpaper-derived accent meaningless.
                            settingsRepo.setColors(fg = settings.fgColorArgb, bg = picked)
                        } else {
                            // A solid covers the wallpaper, so an accent read
                            // from it would be invisible — same implicit stop
                            // as the manual text-color handler.
                            settingsRepo.setAutoAccent(false)
                            settingsRepo.setColors(fg = settings.fgColorArgb, bg = picked)
                        }
                    }
                }
            )
        }

        item {
            ToggleRow(
                label = "hide status bar",
                subtitle = "swipe down from the top to see it again",
                checked = settings.hideStatusBar,
                fgColor = fgColor,
                baseSizeSp = baseSizeSp,
                onChange = { scope.launch { settingsRepo.setHideStatusBar(it) } }
            )
        }

        item {
            Label("text glow", fgColor, baseSizeSp)
            Text(
                text = "a halo behind text, for busy wallpapers. leftmost is off.",
                color = fgColor.copy(alpha = 0.45f),
                fontSize = (baseSizeSp * 0.72f).sp
            )
            Spacer(Modifier.height(6.dp))
            SwatchRow(
                colors = GLOW_PRESETS,
                selected = settings.glowColorArgb,
                fgColor = fgColor,
                onPick = { picked ->
                    scope.launch { settingsRepo.setGlow(picked, settings.glowRadius) }
                }
            )
            if (settings.glowEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "strength ${"%.0f".format(settings.glowRadius)}",
                        color = fgColor.copy(alpha = 0.5f),
                        fontSize = (baseSizeSp * 0.75f).sp,
                        modifier = Modifier.width(120.dp)
                    )
                    Slider(
                        value = settings.glowRadius,
                        onValueChange = { radius ->
                            scope.launch { settingsRepo.setGlow(settings.glowColorArgb, radius) }
                        },
                        valueRange = 1f..6f,
                        colors = SliderDefaults.colors(
                            thumbColor = fgColor,
                            activeTrackColor = fgColor.copy(alpha = 0.6f),
                            inactiveTrackColor = fgColor.copy(alpha = 0.2f),
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Label("open settings with", fgColor, baseSizeSp)
            OptionRow(
                options = listOf(
                    SettingsAccess.LONG_PRESS to "long-press",
                    SettingsAccess.ICON to "icon",
                    SettingsAccess.BOTH to "both"
                ),
                selected = settings.settingsAccess,
                fgColor = fgColor,
                baseSizeSp = baseSizeSp,
                onPick = { scope.launch { settingsRepo.setSettingsAccess(it) } }
            )
            Text(
                text = "long-press the widget area, or tap the gear by the app list",
                color = fgColor.copy(alpha = 0.45f),
                fontSize = (baseSizeSp * 0.72f).sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        item {
            Label("text size", fgColor, baseSizeSp)
            OptionRow(
                options = TextSizeOption.entries.map { it to it.name.lowercase() },
                selected = settings.textSize,
                fgColor = fgColor,
                baseSizeSp = baseSizeSp,
                onPick = { scope.launch { settingsRepo.setTextSize(it) } }
            )
        }

        item {
            Label("app list order", fgColor, baseSizeSp)
            OptionRow(
                options = SortOrder.entries.map { it to sortLabel(it) },
                selected = settings.sortOrder,
                fgColor = fgColor,
                baseSizeSp = baseSizeSp,
                onPick = { scope.launch { settingsRepo.setSortOrder(it) } }
            )
        }

        // ---- Search ----
        item { SectionHeader("search", fgColor, baseSizeSp) }

        item {
            ToggleRow(
                label = "search box",
                subtitle = "a search field above the app list",
                checked = settings.searchEnabled,
                fgColor = fgColor,
                baseSizeSp = baseSizeSp,
                onChange = { scope.launch { settingsRepo.setSearchEnabled(it) } }
            )
        }

        // The other two only mean anything with the field on screen.
        if (settings.searchEnabled) {
            item {
                ToggleRow(
                    label = "find hidden apps",
                    subtitle = "hidden apps stay off the list but turn up in search",
                    checked = settings.searchIncludesHidden,
                    fgColor = fgColor,
                    baseSizeSp = baseSizeSp,
                    onChange = { scope.launch { settingsRepo.setSearchIncludesHidden(it) } }
                )
            }
            item {
                ToggleRow(
                    label = "open on one match",
                    subtitle = "launches as soon as the query narrows to a single app",
                    checked = settings.searchAutoLaunch,
                    fgColor = fgColor,
                    baseSizeSp = baseSizeSp,
                    onChange = { scope.launch { settingsRepo.setSearchAutoLaunch(it) } }
                )
            }
        }

        // ---- Plugins ----
        item {
            SectionHeader("plugins", fgColor, baseSizeSp)
            Text(
                text = "Every widget is a plugin. They render top to bottom in this order.",
                color = fgColor.copy(alpha = 0.45f),
                fontSize = (baseSizeSp * 0.75f).sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("add plugin (.js)", fgColor, baseSizeSp, onAddPlugin)
                ActionChip("restore examples", fgColor, baseSizeSp, onRestoreExamples)
            }
            installStatus?.let { status ->
                Spacer(Modifier.height(6.dp))
                Text(status, color = fgColor.copy(alpha = 0.7f), fontSize = (baseSizeSp * 0.75f).sp)
            }
            Spacer(Modifier.height(4.dp))
        }

        if (configs.isEmpty()) {
            item {
                Text(
                    "No plugins installed. Add a .js file, or restore the shipped examples.",
                    color = fgColor.copy(alpha = 0.5f),
                    fontSize = (baseSizeSp * 0.9f).sp
                )
            }
        }

        items(configs, key = { it.id }) { config ->
            WidgetRow(
                config = config,
                manifest = manifests[config.id],
                total = configs.size,
                bundled = pluginRepo.isBundled(config.id),
                fgColor = fgColor,
                baseSizeSp = baseSizeSp,
                isPermissionGranted = isPermissionGranted,
                onToggle = { enabled ->
                    val needed = manifests[config.id]?.permissions.orEmpty()
                        .filter { it == PluginPermission.LOCATION || it == PluginPermission.CALENDAR }
                    if (enabled && needed.any { !isPermissionGranted(it) }) {
                        requestPermissions(needed)
                    }
                    scope.launch {
                        // Enabling makes it due right away so the user sees
                        // something without waiting out the interval.
                        widgetConfigDao.update(
                            config.copy(
                                enabled = enabled,
                                nextDueAt = if (enabled) 0L else config.nextDueAt,
                                lastError = if (enabled) null else config.lastError
                            )
                        )
                    }
                },
                onMove = { delta ->
                    scope.launch { moveWidget(widgetConfigDao, configs, config, delta) }
                },
                onHeightChange = { units ->
                    scope.launch { widgetConfigDao.update(config.copy(maxHeightUnits = units)) }
                },
                onRemove = { onRemovePlugin(config.id) }
            )
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "A plugin is one .js file declaring `manifest` and `render(ctx)`. " +
                    "Installed copies live in ${pluginRepo.root.absolutePath}. " +
                    "Read the shipped examples to see the whole API.",
                color = fgColor.copy(alpha = 0.45f),
                fontSize = (baseSizeSp * 0.75f).sp
            )
        }

        // ---- Apps ----
        item {
            val hiddenCount = allApps.count { it.hidden }
            SectionHeader("apps", fgColor, baseSizeSp)
            Text(
                text = if (hiddenCount == 0) {
                    "All ${allApps.size} apps are shown."
                } else {
                    "$hiddenCount of ${allApps.size} hidden from the app list."
                },
                color = fgColor.copy(alpha = 0.45f),
                fontSize = (baseSizeSp * 0.75f).sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip(
                    label = if (appsExpanded) "done choosing" else "choose apps to hide",
                    fgColor = fgColor,
                    baseSizeSp = baseSizeSp,
                    onClick = { appsExpanded = !appsExpanded }
                )
                if (hiddenCount > 0) {
                    ActionChip(
                        label = "show all",
                        fgColor = fgColor,
                        baseSizeSp = baseSizeSp,
                        onClick = { scope.launch { appDao.unhideAll() } }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // The full list is long on a real device, so it stays collapsed until
        // asked for. LazyColumn only composes what's on screen either way.
        if (appsExpanded) {
            items(allApps, key = { "app-" + it.packageName }) { app ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = app.label,
                        color = if (app.hidden) fgColor.copy(alpha = 0.4f) else fgColor,
                        fontSize = (baseSizeSp * 0.95f).sp,
                        modifier = Modifier.weight(1f)
                    )
                    // Checked means shown, matching every other toggle here:
                    // on is the state you get by default.
                    Switch(
                        checked = !app.hidden,
                        onCheckedChange = { shown ->
                            scope.launch { appDao.setHidden(app.packageName, !shown) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = fgColor,
                            checkedTrackColor = fgColor.copy(alpha = 0.35f),
                            uncheckedThumbColor = fgColor.copy(alpha = 0.5f),
                            uncheckedTrackColor = Color.Transparent
                        )
                    )
                }
            }
        }

        item { Spacer(Modifier.height(48.dp)) }
    }
}

/**
 * Swaps two widgets' positions. Reads back from the list the UI is already
 * showing rather than re-querying, so the move matches what was on screen.
 */
private suspend fun moveWidget(
    dao: WidgetConfigDao,
    configs: List<WidgetConfig>,
    config: WidgetConfig,
    delta: Int
) {
    val ordered = configs.sortedBy { it.position }
    val index = ordered.indexOfFirst { it.id == config.id }
    val target = index + delta
    if (index < 0 || target !in ordered.indices) return

    val a = ordered[index]
    val b = ordered[target]
    dao.update(a.copy(position = b.position))
    dao.update(b.copy(position = a.position))
}

@Composable
private fun WidgetRow(
    config: WidgetConfig,
    manifest: PluginManifest?,
    total: Int,
    bundled: Boolean,
    fgColor: Color,
    baseSizeSp: Float,
    isPermissionGranted: (PluginPermission) -> Boolean,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onHeightChange: (Float) -> Unit,
    onRemove: () -> Unit
) {
    val name = manifest?.name ?: config.id
    var confirmingRemove by remember(config.id) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = fgColor, fontSize = baseSizeSp.sp)
                Text(
                    manifest?.description?.takeIf { it.isNotBlank() }
                        ?: "every ${config.manifestIntervalMinutes}m",
                    color = fgColor.copy(alpha = 0.5f),
                    fontSize = (baseSizeSp * 0.75f).sp
                )
            }
            Text(
                "↑",
                color = fgColor.copy(alpha = if (config.position > 0) 0.8f else 0.25f),
                fontSize = baseSizeSp.sp,
                modifier = Modifier.clickable { onMove(-1) }.padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Text(
                "↓",
                color = fgColor.copy(alpha = if (config.position < total - 1) 0.8f else 0.25f),
                fontSize = baseSizeSp.sp,
                modifier = Modifier.clickable { onMove(1) }.padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Switch(
                checked = config.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = fgColor,
                    checkedTrackColor = fgColor.copy(alpha = 0.35f),
                    uncheckedThumbColor = fgColor.copy(alpha = 0.5f),
                    uncheckedTrackColor = Color.Transparent
                )
            )
        }

        if (config.enabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "max height ${"%.1f".format(config.maxHeightUnits)}",
                    color = fgColor.copy(alpha = 0.5f),
                    fontSize = (baseSizeSp * 0.75f).sp,
                    modifier = Modifier.width(120.dp)
                )
                Slider(
                    value = config.maxHeightUnits,
                    onValueChange = onHeightChange,
                    valueRange = 1f..8f,
                    colors = SliderDefaults.colors(
                        thumbColor = fgColor,
                        activeTrackColor = fgColor.copy(alpha = 0.6f),
                        inactiveTrackColor = fgColor.copy(alpha = 0.2f),
                        // Ticks default to the M3 primary, which is a purple
                        // that has nothing to do with the user's chosen color.
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Surface permissions the plugin asked for but the OS hasn't granted,
        // so a widget that renders nothing has a visible reason why.
        val ungranted = manifest?.permissions.orEmpty()
            .filter { it == PluginPermission.LOCATION || it == PluginPermission.CALENDAR }
            .filterNot(isPermissionGranted)
        if (config.enabled && ungranted.isNotEmpty()) {
            Text(
                "needs ${ungranted.joinToString { it.name.lowercase() }} permission",
                color = Color(0xFFFFB74D),
                fontSize = (baseSizeSp * 0.75f).sp
            )
        }

        config.lastError?.let { error ->
            Text(
                error,
                color = Color(0xFFEF5350),
                fontSize = (baseSizeSp * 0.75f).sp
            )
        }

        // Bundled examples are the API documentation, so they're disable-only:
        // deleting one would leave a user with no obvious way to get it back.
        if (bundled) {
            Text(
                "built-in example · disable to hide it",
                color = fgColor.copy(alpha = 0.35f),
                fontSize = (baseSizeSp * 0.7f).sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            return@Column
        }

        // Two-step, because removing a plugin deletes its source file and
        // there's no undo.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (confirmingRemove) {
                Text(
                    "really remove?",
                    color = fgColor.copy(alpha = 0.6f),
                    fontSize = (baseSizeSp * 0.75f).sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    "yes",
                    color = Color(0xFFEF5350),
                    fontSize = (baseSizeSp * 0.75f).sp,
                    modifier = Modifier.clickable { onRemove() }.padding(vertical = 4.dp)
                )
                Text(
                    "cancel",
                    color = fgColor.copy(alpha = 0.6f),
                    fontSize = (baseSizeSp * 0.75f).sp,
                    modifier = Modifier.clickable { confirmingRemove = false }.padding(vertical = 4.dp)
                )
            } else {
                Text(
                    "remove",
                    color = fgColor.copy(alpha = 0.45f),
                    fontSize = (baseSizeSp * 0.75f).sp,
                    modifier = Modifier.clickable { confirmingRemove = true }.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, fgColor: Color, baseSizeSp: Float, onClick: () -> Unit) {
    Text(
        text = label,
        color = fgColor.copy(alpha = 0.85f),
        fontSize = (baseSizeSp * 0.8f).sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, fgColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun SectionHeader(text: String, fgColor: Color, baseSizeSp: Float) {
    Spacer(Modifier.height(24.dp))
    Text(
        text = text,
        color = fgColor.copy(alpha = 0.5f),
        fontSize = (baseSizeSp * 0.8f).sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Label(text: String, fgColor: Color, baseSizeSp: Float) {
    Spacer(Modifier.height(12.dp))
    Text(text, color = fgColor.copy(alpha = 0.7f), fontSize = (baseSizeSp * 0.85f).sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    fgColor: Color,
    baseSizeSp: Float,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = fgColor, fontSize = baseSizeSp.sp)
            Text(subtitle, color = fgColor.copy(alpha = 0.45f), fontSize = (baseSizeSp * 0.72f).sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = fgColor,
                checkedTrackColor = fgColor.copy(alpha = 0.35f),
                uncheckedThumbColor = fgColor.copy(alpha = 0.5f),
                uncheckedTrackColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun SwatchRow(
    colors: List<Int>,
    selected: Int,
    fgColor: Color,
    onPick: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { argb ->
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        width = if (argb == selected) 2.dp else 1.dp,
                        color = if (argb == selected) fgColor else fgColor.copy(alpha = 0.25f),
                        shape = CircleShape
                    )
                    .clickable { onPick(argb) }
            )
        }
    }
}

/**
 * The home-screen background choice: the system wallpaper or one flat
 * color. "wallpaper" is a labeled chip — a transparent swatch would be
 * invisible — followed by the opaque solids. A swatch that isn't in the
 * solid set (e.g. a translucent scrim left over from an older build) is
 * rendered as wallpaper mode, so it highlights the wallpaper chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackgroundChoiceRow(
    selectedBg: Int,
    fgColor: Color,
    baseSizeSp: Float,
    onPick: (Int) -> Unit
) {
    // Wallpaper shows for any non-opaque value, including legacy
    // translucent scrims; only a fully opaque bg is solid mode.
    val wallpaperMode = (selectedBg ushr 24) != 0xFF
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "wallpaper",
            color = if (wallpaperMode) fgColor else fgColor.copy(alpha = 0.5f),
            fontSize = (baseSizeSp * 0.8f).sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(
                    width = if (wallpaperMode) 2.dp else 1.dp,
                    color = if (wallpaperMode) fgColor else fgColor.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(6.dp)
                )
                .clickable { onPick(WALLPAPER_BG) }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
        BG_SOLIDS.forEach { (argb, name) ->
            val selected = argb == selectedBg
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) fgColor else fgColor.copy(alpha = 0.35f),
                        shape = CircleShape
                    )
                    .clickable { onPick(argb) }
                    .semantics { contentDescription = "$name background" }
            )
        }
    }
}

@Composable
private fun <T> OptionRow(
    options: List<Pair<T, String>>,
    selected: T,
    fgColor: Color,
    baseSizeSp: Float,
    onPick: (T) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                text = label,
                color = if (active) fgColor else fgColor.copy(alpha = 0.5f),
                fontSize = (baseSizeSp * 0.85f).sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        width = 1.dp,
                        color = if (active) fgColor else fgColor.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable { onPick(value) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

private fun sortLabel(order: SortOrder): String = when (order) {
    SortOrder.ALPHA -> "a-z"
    SortOrder.RECENT -> "recent"
    SortOrder.MOST_USED -> "most used"
}

/**
 * Best-effort open of the OS wallpaper chooser. There is no one public API
 * across Android versions, so try the static-wallpaper picker first and fall
 * back to the live-wallpaper chooser, which some builds hide the static one
 * behind. From an Activity (settings is shown inside MainActivity) the picker
 * hands control straight back, so no NEW_TASK is needed; only add it when all
 * we have is an application context.
 */
private fun openWallpaperPicker(context: Context) {
    val candidates = listOf(
        Intent(Intent.ACTION_SET_WALLPAPER),
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
    )
    val picker = candidates.firstOrNull { it.resolveActivity(context.packageManager) != null }
    if (picker == null) {
        Toast.makeText(context, "no wallpaper picker found", Toast.LENGTH_SHORT).show()
        return
    }
    if (context !is Activity) picker.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(picker)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "no wallpaper picker found", Toast.LENGTH_SHORT).show()
    }
}
