# Handoff status

Every item from the original punch list is done, and the app has been
built and exercised on an Android 14 emulator. Kept as a record of what
changed; safe to delete.

| # | Item | Outcome |
| --- | --- | --- |
| 0 | Verify it builds | Toolchain moved to AGP 8.7.3 / Kotlin 2.0.21 / KSP 2.0.21-1.0.28 / Compose compiler plugin / Gradle 8.11.1 (wrapper now checked in). Builds clean, no warnings. |
| 1 | Seed WidgetConfig rows | `widget/WidgetSeeder.kt`, run on every launch. Also picks up newly installed plugins and prunes rows for removed ones. |
| 2 | Clock / date / battery | Shipped as **JS plugins** in `app/src/main/assets/plugins/`, not native code, so they double as the API examples. Needed three new host calls: `ctx.formatTime`, `ctx.is24Hour`, `ctx.battery`. |
| 3 | QuickJS engine | `plugin/QuickJsPluginEngine.kt` on `wang.harlon.quickjs:wrapper-android:3.2.3`. Fresh context per render on a dedicated thread, 16MB heap cap, output validated and clamped. |
| 4 | Plugin loading & manifest | A plugin is one `.js` file declaring its own `manifest`; `plugin/ManifestExtractor.kt` reads it in a bare QuickJS context at install time and caches it as JSON beside the source. |
| 5 | Runtime permissions | Requested when a plugin declaring them is enabled. `RealPluginContext` re-checks the live grant on every call, so "declared but denied" throws a catchable JS error rather than crashing or silently returning nothing. |
| 6 | Foreground ticker | `worker/ForegroundTicker.kt`, 20s poll bound to the RESUMED lifecycle, honouring the same per-widget due dates as the background worker. |
| 7 | Settings UI | `ui/SettingsScreen.kt`: theme, text size, sort order, plus a plugin admin with add-from-file-picker, enable, reorder, height budget, per-plugin errors, remove, and restore-examples. Bundled examples are disable-only — they're the API docs, and deleting one leaves a user with no obvious way back. Enforced in `PluginRepository.uninstall`, not just hidden in the UI. |
| 8 | Wallpaper auto-accent | `theme/WallpaperAccent.kt`. Palette on a 256px sample, then the swatch is forced to 4.5:1 contrast against the wallpaper's dominant colour — a prominent swatch is very often an unreadable one. |
| 9 | Package add/remove | `data/PackageChangeReceiver.kt`, with the `package:` data scheme the filter needs, skipping the uninstall half of an app update. |
| 10 | Polish | Adaptive launcher icon (pure XML, no PNGs), long-press an app for system app-info, empty states for both the widget stack and the app list. |

## Bugs found by testing on the emulator

Worth recording, since three of these were only visible at runtime:

- **JNI abort on any host-call error.** Throwing a Kotlin exception out of
  a QuickJS callback left a pending Java exception, which aborts the whole
  process — so a plugin fetching a URL its manifest didn't allow
  hard-crashed the launcher, then crash-looped, since the failure was
  never recorded. `QuickJSContext.throwJSException` doesn't help; it
  raises a Java exception of its own. Errors now cross as a return-value
  sentinel that a small JS shim rethrows as a real `Error`.
- **The render timeout never fired.** `withTimeoutOrNull` around a
  blocking body can't interrupt it, so one slow plugin stalled every other
  widget behind it indefinitely. Replaced with a bounded `Future.get`.
- **Every line rendered bold.** An absent `weight` parsed as 1.
- **Location was effectively unusable.** Last-known-fix-only means weather
  never works until some *other* app happens to request location — there
  was no fix at all on a clean device. Now falls back to a single-shot
  request racing all providers, still with no standing listener.
- **Stale plugin list in settings.** A freshly installed plugin showed its
  raw id instead of its manifest name.

## Added after the original punch list

- **Bundled examples are disable-only.** They're the API documentation, and
  a user who deletes one has no obvious route back. Enforced in
  `PluginRepository.uninstall`, not just hidden in the UI.
- **Text glow** (`ui/TextGlow.kt`). Optional stroked outline behind text, so
  a wallpaper that's pale at one end and dark at the other doesn't defeat
  whatever single foreground colour you picked. Started as a blurred
  shadow — one property, no extra draw — but on the case that motivated it
  (white text on a white wallpaper) blur spreads the ink too thin and reads
  as washed-out grey rather than cut out, so it became a stroke. Off by
  default, and only costs the second draw pass when on.

- **Hide status bar** (optional, off by default). Transient-by-swipe so the
  bar is still reachable, and the root layout switches from
  `systemBarsPadding()` to `navigationBarsPadding()` when it's on, so the
  widget stack claims the freed strip.
- **Sort control moved into settings** and removed from the home screen.
  It was the only chrome left up there, and the setting already existed —
  the home screen is now nothing but widgets and app names.

- **Hide apps from the list.** The `AppEntry.hidden` column and the
  `WHERE hidden = 0` filter were already in the original scaffold but
  nothing could set them; added the DAO access and a collapsed settings
  section, plus a "show all" reset so hiding is never a one-way door.

- **Search** (`ui/AppListScreen.kt`), with three settings: show the field,
  let it reach hidden apps, and open on a single match. Uses
  `BasicTextField` rather than a Material `TextField` so there's no
  container or indicator chrome. The stroked glow can't apply to editable
  text (two draw passes would mean two carets), so the field falls back to
  a shadow — see `GlowStyle.asShadowStyle`.

- **Settings access is configurable** (`SettingsAccess`: long-press / icon /
  both, defaulting to both). Long-press alone is both undiscoverable and
  inaccessible to anyone who can't hold still for half a second, so a plain
  tap target is offered alongside it. The gear and the search magnifier are
  emoji with `contentDescription` semantics and padded hit areas.

## Verified on emulator (Android 14, arm64)

Clock/date/battery rendering; weather end-to-end (single-shot location →
live Open-Meteo fetch → parse → render); agenda against real calendar
events; the `domains` allowlist rejecting a non-allowlisted host and the
plugin catching it; install via the system file picker; enable, reorder,
remove (and bundled examples refusing to be removed); settings, theming,
and text glow against both a white background and a light-to-dark
wallpaper; hiding the status bar, revealing it by swipe, and its
persistence across a cold start; hiding apps, their absence from the home
list, survival of the `AppSync` re-sync on next launch, and "show all";
search ranking, hidden apps excluded by default and reachable once opted
in, and auto-open launching a hidden app then clearing the query; all
three settings-access modes, including that long-press really is inert in
icon-only mode.
