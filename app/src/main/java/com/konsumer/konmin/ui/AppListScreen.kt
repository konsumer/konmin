package com.konsumer.konmin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.konsumer.konmin.data.AppDao
import com.konsumer.konmin.data.AppEntry
import com.konsumer.konmin.data.SortOrder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppListScreen(
    appDao: AppDao,
    sortOrder: SortOrder,
    fgColor: Color,
    baseSizeSp: Float,
    glow: GlowStyle?,
    searchEnabled: Boolean,
    searchIncludesHidden: Boolean,
    searchAutoLaunch: Boolean,
    showSettingsIcon: Boolean,
    onOpenSettings: () -> Unit,
    onLaunchApp: (String) -> Unit,
    onAppInfo: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var hiddenApps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    // Re-subscribe whenever sortOrder changes, since the DAO query takes
    // the sort mode as a parameter.
    LaunchedEffect(sortOrder) {
        appDao.getApps(sortOrder.name).collectLatest { apps = it }
    }

    // Only pay for the hidden list when search is actually allowed to use it.
    LaunchedEffect(searchEnabled, searchIncludesHidden) {
        if (searchEnabled && searchIncludesHidden) {
            appDao.getHiddenApps().collectLatest { hiddenApps = it }
        } else {
            hiddenApps = emptyList()
        }
    }

    val searching = searchEnabled && query.isNotBlank()
    val results = remember(query, apps, hiddenApps, searching) {
        if (searching) matchApps(query, apps + hiddenApps) else apps
    }

    fun launch(app: AppEntry) {
        scope.launch { appDao.recordLaunch(app.packageName, System.currentTimeMillis()) }
        // Clear before launching, so coming back to the launcher doesn't land
        // on a stale query — which, with auto-launch on, would immediately
        // relaunch the app the user just left.
        query = ""
        keyboard?.hide()
        onLaunchApp(app.packageName)
    }

    // Olauncher's "open as soon as it's unambiguous". Off by default: with a
    // short app list a single keystroke is often unique, so it fires while
    // you're still typing.
    if (searchAutoLaunch) {
        LaunchedEffect(results, searching) {
            if (searching && results.size == 1) launch(results.first())
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        // Search and the settings button share one row, so turning both on
        // still costs a single line of chrome.
        if (searchEnabled || showSettingsIcon) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (searchEnabled) {
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onSubmit = { results.firstOrNull()?.let { launch(it) } },
                        fgColor = fgColor,
                        baseSizeSp = baseSizeSp,
                        glow = glow,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (showSettingsIcon) {
                    EmojiButton(
                        emoji = "⚙️",
                        description = "Open settings",
                        baseSizeSp = baseSizeSp,
                        onClick = onOpenSettings
                    )
                }
            }
        }

        if (results.isEmpty()) {
            GlowText(
                text = if (searching) "No match" else "No apps found",
                color = fgColor.copy(alpha = 0.45f),
                fontSize = baseSizeSp.sp,
                glow = glow,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(results, key = { it.packageName }) { app ->
                GlowText(
                    text = app.label,
                    color = fgColor,
                    fontSize = (baseSizeSp * 1.15f).sp,
                    glow = glow,
                    fillWidth = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { launch(app) },
                            // Conventional launcher gesture: long-press opens the
                            // system app-info page, which is where uninstall and
                            // permissions live. Cheaper and safer than hosting our
                            // own uninstall flow.
                            onLongClick = { onAppInfo(app.packageName) }
                        )
                        .padding(vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    fgColor: Color,
    baseSizeSp: Float,
    glow: GlowStyle?,
    modifier: Modifier = Modifier
) {
    // BasicTextField rather than a Material TextField: no container, no
    // label, no indicator line — the placeholder is the entire affordance.
    // Deliberately not auto-focused, or pressing Home would raise the
    // keyboard every single time.
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = (glow?.asShadowStyle() ?: TextStyle.Default).copy(
            color = fgColor,
            fontSize = (baseSizeSp * 1.1f).sp
        ),
        cursorBrush = SolidColor(fgColor),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        decorationBox = { field ->
            // An emoji rather than the word "search": it reads as a button,
            // takes a fraction of the width, and doesn't look like a stray
            // app name sitting above the list.
            if (query.isEmpty()) {
                Text(
                    text = "\uD83D\uDD0D",
                    fontSize = (baseSizeSp * 1.05f).sp,
                    modifier = Modifier.semantics { contentDescription = "Search apps" }
                )
            }
            field()
        },
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp)
    )
}

/**
 * A tappable emoji. The padding matters more than the glyph: it gives the
 * target a comfortable hit area rather than the few millimetres the emoji
 * itself occupies.
 */
@Composable
private fun EmojiButton(
    emoji: String,
    description: String,
    baseSizeSp: Float,
    onClick: () -> Unit
) {
    Text(
        text = emoji,
        fontSize = (baseSizeSp * 1.05f).sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp)
            .semantics { contentDescription = description }
    )
}

/**
 * Ranks a prefix match above a match in the middle of the label, so typing
 * "ma" puts Maps before Gmail. Word-start matches count as prefixes, which
 * is what makes "mu" find "YT Music".
 */
private fun matchApps(query: String, pool: List<AppEntry>): List<AppEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return pool

    val prefix = mutableListOf<AppEntry>()
    val contains = mutableListOf<AppEntry>()
    val seen = mutableSetOf<String>()

    pool.forEach { app ->
        if (!seen.add(app.packageName)) return@forEach
        val label = app.label.lowercase()
        when {
            label.split(' ', '-', '_').any { it.startsWith(q) } -> prefix.add(app)
            label.contains(q) -> contains.add(app)
        }
    }
    return prefix + contains
}
