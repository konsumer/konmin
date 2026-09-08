# konmin, for developers

konmin is a minimal Android launcher: a stack of small JavaScript widgets over an app list. This file covers building, running, and navigating the code. For the user view, see README.md. For the plugin API, see PLUGIN.md.

## Requirements

- JDK 17
- Android SDK with API 34 components

### macOS

```bash
brew install openjdk@17
brew install --cask android-commandlinetools

export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"
```

### Linux

Install a JDK 17 and the "command line tools only" package from <https://developer.android.com/studio#command-tools>.

### SDK packages

```bash
yes | sdkmanager --licenses
sdkmanager --install "platform-tools" "platforms;android-34" \
  "build-tools;34.0.0" "emulator" "system-images;android-34;google_apis;arm64-v8a"
```

The Gradle wrapper is checked in, so no Gradle install is needed. `ANDROID_HOME` is enough for Gradle to find the SDK. A `local.properties` file with `sdk.dir=...` also works and is gitignored.

## Building

```bash
./gradlew assembleDebug     # APK at app/build/outputs/apk/debug/konmin-<versionName>-debug.apk
./gradlew assembleRelease
./gradlew installDebug      # build and install to the connected device
```

Toolchain: AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose compiler plugin, Gradle 8.11.1. Package `com.konsumer.konmin`, minSdk 26, targetSdk 34, version 0.1.0.

## Release signing

CI signs the release APK when the repo has a keystore configured as GitHub secrets. Without the secrets it falls back to a debug-signed APK. Generate one keystore, keep it private, and reuse it for every release so users can update in place.

Generate a key (any JDK 17 works; this uses the Docker image):

```sh
docker run --rm -it --user "$(id -u):$(id -g)" -v "$PWD":/work -w /work \
  eclipse-temurin:17-jdk \
  keytool -genkeypair -v -keystore konmin-release.keystore \
    -alias konmin -keyalg RSA -keysize 2048 -validity 10000

base64 -w0 konmin-release.keystore
```

On macOS use `base64 -i konmin-release.keystore` (no -w flag).

In GitHub, open the repo, then Settings, Secrets and variables, Actions, and add four repository secrets:

- KEYSTORE_BASE64 set to the base64 output from above
- KEYSTORE_PASSWORD set to your keystore password
- KEY_ALIAS set to konmin
- KEY_PASSWORD set to your key password (same as the keystore password if you reused it)

Keep the .keystore file out of the repo and back it up somewhere safe. Losing it means users have to uninstall to move to a later release.

## Running on an emulator

```bash
avdmanager create avd -n konmin \
  -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_6
emulator -avd konmin -no-boot-anim &

./gradlew installDebug
adb shell cmd package set-home-activity com.konsumer.konmin/.ui.MainActivity
adb shell input keyevent KEYCODE_HOME
```

The stock launcher re-claims the HOME intent after each reinstall. Disable it and konmin stays the only home app:

```bash
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher
```

## Debugging

Relevant logcat tags:

```bash
adb logcat -s konmin.js konmin.render konmin.tick konmin.plugins konmin.accent
```

- `konmin.js`: QuickJS engine, plus plugin `console.log` output (prefixed with the plugin id)
- `konmin.render`: per-widget render results and failures
- `konmin.tick`: foreground ticker, which widgets are due
- `konmin.plugins`: plugin install, list, remove
- `konmin.accent`: wallpaper accent extraction

Inspect the widget rows directly (debug builds only, via `run-as`):

```bash
adb shell run-as com.konsumer.konmin sqlite3 databases/konmin.db \
  "select id,enabled,lastRenderedLinesJson,lastError from widget_configs order by position"
```

Fast plugin iteration while developing. Push the file in, then relaunch so the seeder picks it up:

```bash
adb push myplugin.js /data/local/tmp/
adb shell run-as com.konsumer.konmin cp /data/local/tmp/myplugin.js files/plugins/myplugin.js
adb shell am force-stop com.konsumer.konmin
adb shell am start -n com.konsumer.konmin/.ui.MainActivity
```

A fresh plugin id starts disabled and only becomes visible after the relaunch. Replacing an existing id keeps its widget row and settings.

## Layout of the code

| Path | Responsibility |
| --- | --- |
| `plugin/QuickJsPluginEngine.kt` | QuickJS execution, sandbox, render timeout, output validation |
| `plugin/RealPluginContext.kt` | host side of the `ctx` API, every permission check |
| `plugin/PluginRepository.kt` | install/list/remove, manifest caching, bundled examples |
| `plugin/ManifestExtractor.kt` | reads the manifest out of a plugin's source at install |
| `plugin/PluginTypes.kt`, `PluginContext.kt` | data models and the `ctx` interface |
| `widget/WidgetSeeder.kt` | keeps `widget_configs` in step with installed plugins, on every launch |
| `data/` | Room database, `SettingsRepository`, `AppSync` and `PackageChangeReceiver` (package add/remove) |
| `worker/WidgetTickWorker.kt` | the 15-minute WorkManager tick |
| `worker/ForegroundTicker.kt` | the 20s on-screen ticker, bound to the RESUMED lifecycle |
| `worker/WidgetRenderer.kt` | render, truncate, cache, schedule the next check |
| `ui/` | `MainActivity`, widget stack, app list, settings, text glow |
| `theme/WallpaperAccent.kt` | wallpaper accent color extraction |
| `LauncherApp.kt` | application entry point |

## How it stays cheap

- One WorkManager periodic job serves all widgets, at the 15-minute OS floor. It queries which widgets are due and runs only those.
- While the launcher is on screen, an in-process ticker checks the same due dates every 20s. A clock can then update every minute without the background job running more often. The ticker is cancelled when the launcher leaves the foreground.
- Under battery saver, every interval is doubled. `networkHeavy` widgets are pushed to three times their interval by the background job and skipped by the foreground ticker entirely.
- `ctx.fetch` caches by plugin and URL, so a re-render never re-hits the network inside the plugin's own `cacheMinutes` window.
- `ctx.location()` prefers a cached fix and only falls back to a single one-shot request. It never registers a standing location listener.
- Drawing the home screen reads cached JSON. No JS runs and no network is touched at draw time.

## Design notes worth keeping

These came out of emulator testing and post-scope work. They explain why the code is the way it is.

### JNI host-call errors

Throwing a Kotlin exception out of a QuickJS callback left a pending Java exception, which aborts the whole process. A plugin fetching a URL its manifest did not allow hard-crashed the launcher and then crash-looped, because the failure was never recorded. `QuickJSContext.throwJSException` does not help, it raises a Java exception of its own. Errors now cross the JNI boundary as a return-value sentinel that a small JS shim rethrows as a real `Error` on the JS side.

### Render timeout

`withTimeoutOrNull` around a blocking body cannot interrupt it, so one slow plugin stalled every other widget indefinitely. The budget is enforced by a bounded wait on a dedicated worker thread (`Future.get` style), not by cancellation. On expiry the daemon thread is abandoned and the widget is disabled, so a runaway plugin costs one leaked thread exactly once.

### Manifest and render parsing defaults

An absent `weight` used to parse as 1, which rendered every line bold. It now defaults to 0. Output parsing validates and clamps every field, and a wrong type drops one line rather than failing the whole widget.

### Location

Last-known-fix-only meant weather never worked until some other app happened to request location. `ctx.location()` now falls back to a single-shot request racing all providers (fused, network, GPS), still with no standing listener. The single-shot path needs API 30+.

### Bundled examples are disable-only

The five example plugins are the API documentation, and a user who deletes one has no obvious route back. Enforced in `PluginRepository.uninstall`, not just hidden in the UI.

### Text glow

A stroked outline behind text, not a blurred shadow. Blur was the cheaper implementation, but spreading the same ink over more pixels makes the hard case (white text on a white wallpaper) read as washed-out grey. The stroke costs a second draw pass, paid only when the glow is on.

### Hide status bar

Uses `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, so the bar is still reachable. The root layout switches from `systemBarsPadding()` to `navigationBarsPadding()` when it is on, so the widget stack claims the freed strip instead of leaving a blank gap.

### Hidden apps and search

Hidden apps live on the app row (`AppEntry.hidden`) and survive the `AppSync` re-sync on every launch. A "show all" reset keeps hiding from being a one-way door. Search uses `BasicTextField`, not a Material `TextField`, so there is no container chrome. The stroked glow cannot apply to editable text (two draw passes would mean two carets), so the search field falls back to a shadow. The field is not auto-focused, and the query clears on launch so returning to the launcher does not relaunch the last app.

### Settings access

Long-press alone is undiscoverable and hard for anyone who cannot hold still. `SettingsAccess` offers long-press, icon, or both, defaulting to both. The gear and the search magnifier are emoji glyphs with `contentDescription` and padded hit areas.

### Emulator verification

Verified on an Android 14 arm64 emulator: all five example widgets render; weather end to end (single-shot location, live Open-Meteo fetch, a rejected non-allowlisted host caught by the plugin); agenda against real calendar events; install through the system file picker; enable, reorder, remove, and bundled examples refusing removal; theming and glow against a white background and a light-to-dark wallpaper; hide-status-bar with swipe reveal and persistence across a cold start; hidden apps surviving the re-sync plus "show all"; search ranking, hidden apps excluded by default and reachable once opted in, and auto-open clearing the query; and all three settings-access modes.
