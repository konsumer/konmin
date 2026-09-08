# konmin

A minimal Android launcher: a text-only home screen with a stack of
customisable widgets at the top, a sortable app list below, and nothing
else. Widgets are sandboxed JavaScript plugins — a plugin is one `.js`
file you pick from a file browser.

Built for battery life. Every plugin declares how often it wants to be
run, the launcher clamps and throttles that, and rendering the home
screen never executes JavaScript or touches the network — it only draws
what the last scheduled tick already cached.

![konmin home screen](docs/screenshot.png)

## Features

- **Widget stack** — clock, date, battery, weather and calendar agenda
  ship as example plugins. Reorder, resize and enable them in settings;
  plugins you add yourself can also be removed there.
- **JS plugin API** — `manifest` + `render(ctx)` in a single file. Network
  access is restricted to a per-plugin domain allowlist, storage is
  isolated per plugin, and location/calendar are gated on both the
  manifest and the OS permission.
- **App list** — sort alphabetically, by most recent, or by most used
  (chosen in settings, so the home screen stays bare). Hide apps you never
  launch, and optionally search — including a mode that opens an app as
  soon as the query is unambiguous. Long-press an app for the system
  app-info page (uninstall lives there).
- **Theme** — foreground/background colour, text size, an optional accent
  colour extracted from your wallpaper, and an optional **text glow**: a
  stroked outline behind text so a light wallpaper doesn't swallow light
  text. Off by default.
- **Hide the status bar** — optional, and when it's hidden the widget
  stack takes the reclaimed strip rather than leaving it blank. A swipe
  from the top brings the bar back over the content for a few seconds.

## Building from the CLI

No Android Studio required. On macOS:

```bash
brew install openjdk@17
brew install --cask android-commandlinetools

export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"

yes | sdkmanager --licenses
sdkmanager --install "platform-tools" "platforms;android-34" \
  "build-tools;34.0.0" "emulator" "system-images;android-34;google_apis;arm64-v8a"
```

On Linux, install a JDK 17 and the "command line tools only" package from
<https://developer.android.com/studio#command-tools>, then run the same
`sdkmanager` line.

The Gradle wrapper is checked in, so you don't need Gradle on your PATH:

```bash
./gradlew assembleDebug     # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug      # build + install to the connected device
```

`ANDROID_HOME` is enough for Gradle to find the SDK; `local.properties`
with `sdk.dir=...` works too and is gitignored.

### Running in an emulator

```bash
avdmanager create avd -n konmin \
  -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_6
emulator -avd konmin -no-boot-anim &

./gradlew installDebug
adb shell cmd package set-home-activity com.konsumer.konmin/.ui.MainActivity
adb shell input keyevent KEYCODE_HOME
```

The emulator's stock launcher will keep re-claiming the HOME intent after
each reinstall. Disabling it makes konmin the only home app:

```bash
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher
```

Useful while iterating:

```bash
adb logcat -s konmin.js konmin.render konmin.tick konmin.plugins
adb shell run-as com.konsumer.konmin sqlite3 databases/konmin.db \
  "select id,enabled,lastRenderedLinesJson,lastError from widget_configs order by position"
```

## Writing a plugin

A plugin is one `.js` file declaring a top-level `manifest` and a
`render(ctx)`. Install it with **add plugin (.js)** in settings, or push
it straight into place while developing:

```bash
adb push myplugin.js /data/local/tmp/
adb shell run-as com.konsumer.konmin cp /data/local/tmp/myplugin.js files/plugins/myplugin.js
```

```js
const manifest = {
  id: 'weather',              // also the storage/cache namespace
  name: 'Weather',
  description: 'shown in the plugin list',
  intervalMinutes: 60,        // default cadence
  maxHeightUnits: 3,          // default height budget
  networkHeavy: true,         // throttled much harder under battery saver
  permissions: ['NETWORK', 'LOCATION', 'STORAGE'],
  domains: ['api.open-meteo.com']   // fetch allowlist, enforced by the host
}

async function render (ctx) {
  const data = JSON.parse(await ctx.fetch(URL, { cacheMinutes: 30 }))
  return {
    lines: [
      { text: `${data.temp}°`, size: 1, weight: 1 },
      { text: data.condition, size: -1 }
    ],
    nextCheckMinutes: 60
  }
}
```

`lines[].size` is `-2..2`, relative to the user's base text size;
`weight: 1` is bold; `align` is `START`/`CENTER`/`END`. A widget's total
height is the sum of its lines' size multipliers, and lines past
`maxHeightUnits` are dropped.

`nextCheckMinutes` is the plugin asking when to be run again. The host
clamps it to 1–360 minutes, doubles it under battery saver, and doubles
it again on failure. Asking for a longer interval is the single most
useful thing a plugin can do for battery life.

### The `ctx` API

| Method | Needs | Notes |
| --- | --- | --- |
| `ctx.now()` | — | epoch millis |
| `ctx.formatTime(millis, pattern)` | — | `SimpleDateFormat` pattern, device locale |
| `ctx.is24Hour()` | — | device clock setting |
| `ctx.battery()` | — | `{ level, charging }` |
| `ctx.fetch(url, { cacheMinutes })` | `NETWORK` | https only, manifest `domains` only |
| `ctx.storageGet(key)` / `ctx.storageSet(key, value)` | `STORAGE` | isolated per plugin |
| `ctx.location()` | `LOCATION` | `{ lat, lon }` rounded to ~1km, or `null` |
| `ctx.upcomingEvents(withinMinutes)` | `CALENDAR` | `[{ title, startsAt, allDay }]` |

Two things to know:

- **`ctx` methods throw synchronously.** They are host calls, not
  promises, so `.catch()` on the return value will not catch them — use
  `try`/`catch`. `render` may still be `async`, and `await` on a host
  call is harmless.
- **Permissions are checked twice**: the manifest must declare it *and*
  the user must have granted it. A plugin that declares `CALENDAR` and is
  denied gets a thrown error, not a crash and not empty data. See
  `agenda.js` for handling that gracefully.

The five shipped plugins in `app/src/main/assets/plugins/` are the API
documentation: ordinary plugins, readable and editable, running through
exactly the same sandbox as anything you write.

They can be **disabled but not removed**, so there's no way to end up
without an example to copy from. If you edit one and want the original
back, **restore examples** in settings rewrites them; deleting one from
`files/plugins/` by hand just makes it reappear on the next launch.

### Text glow

The launcher draws over your wallpaper with no background by default, so
whether text is readable depends entirely on what's behind it — and a
wallpaper that's pale at the top and dark at the bottom will defeat any
single foreground colour.

**text glow** in settings puts a coloured outline around every glyph.
White text with a black glow stays readable on a white wallpaper, and the
outline disappears against a dark one, so one setting covers both ends of
the same image.

It's a stroked outline, not a blurred shadow. Blur was the cheaper
implementation, but spreading the same ink over more pixels makes the
hard case come out washed-out grey rather than cut out. The cost is
drawing each line twice, which is why it's only paid when the glow is
actually on — with it off (the default) each line is a single plain
`Text`.

### Getting to settings

**open settings with** offers `long-press` / `icon` / `both`, defaulting
to both.

Long-pressing the widget area is the conventional launcher gesture and
keeps the home screen completely bare, but it's undiscoverable and it
asks you to hold still for half a second — which is exactly what a tremor
or a motor impairment defeats. So there's also a gear you can simply tap,
sharing a row with the search field and carrying a proper content
description for screen readers. Neither option is imposed: pick one, or
keep both.

### Search

**search box** puts a field above the app list. When empty it shows a
magnifier emoji rather than the word "search" — it reads as a button,
takes a fraction of the width, and doesn't look like a stray app name
sitting above the list. Tapping anywhere along the row focuses it. It's on by default and
can be switched off for a list-only home screen. Two options go with it:

- **find hidden apps** — hidden apps stay off the list but can still be
  reached by typing their name. Off by default, so hidden means hidden
  unless you say otherwise. Launching one this way doesn't unhide it.
- **open on one match** — Olauncher's behaviour: launch as soon as the
  query narrows to a single app. Off by default, because with a short app
  list one keystroke is often already unique and it fires while you're
  still typing. Pressing Go always opens the top match regardless.

Matching ranks word-prefix hits above mid-word ones, so "ma" gives Maps
before Gmail, and "mu" still finds YT Music. The field isn't auto-focused
— pressing Home shouldn't raise the keyboard — and the query clears on
launch, so returning to the launcher doesn't leave a stale search (which,
with auto-open on, would immediately relaunch what you just left).

### Hiding apps

Everything shows by default. **apps** in settings lists every installed
app with a toggle; switching one off drops it from the home screen
without uninstalling it. **show all** unhides everything in one go, so
there's no way to hide something and then not be able to find it again.

The list stays collapsed behind "choose apps to hide" because on a real
device it's long, and hidden state lives on the app row in the database
rather than in a separate list, so it survives the package re-sync that
runs on every launch.

### Hiding the status bar

Turning **hide status bar** on uses `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`,
so the bar isn't gone for good — swiping down from the top edge slides it
back over the content for a few seconds, then it hides itself again. That
matters on a launcher, where the bar is often the only clock and battery
readout on screen.

The layout then pads for the navigation bar only, not the full system
bars, so the widget stack actually moves up into the space instead of
leaving a blank strip where the bar used to be.

## How it stays cheap

- One `WorkManager` periodic job for all widgets, at the 15-minute OS
  floor. It queries which widgets are actually *due* and runs only those,
  rather than one job per plugin.
- While the launcher is on screen, an in-process ticker checks the same
  due dates every 20s, so a clock can update every minute without the
  background job running more often. It's cancelled the moment the
  launcher leaves the foreground.
- Under battery saver, every interval is doubled and `networkHeavy`
  plugins are pushed out to 3× their interval and skipped by the
  foreground ticker entirely.
- `ctx.fetch` caches by plugin + URL, so re-rendering never re-hits the
  network inside the plugin's own `cacheMinutes` window.
- `ctx.location()` prefers a cached fix and only falls back to a single
  one-shot request — never a standing location listener.
- Drawing the home screen reads cached JSON. No JS, no network, no
  work proportional to how much you scroll.

## Layout of the code

| Path | What |
| --- | --- |
| `plugin/QuickJsPluginEngine.kt` | QuickJS execution, sandbox, timeout, output validation |
| `plugin/RealPluginContext.kt` | the host side of the `ctx` API and every permission check |
| `plugin/PluginRepository.kt` | install/list/remove, manifest extraction and caching |
| `worker/WidgetTickWorker.kt` | the 15-minute scheduler tick |
| `worker/ForegroundTicker.kt` | the on-screen ticker |
| `worker/WidgetRenderer.kt` | render → truncate → cache → schedule next check |
| `ui/` | home screen, app list, settings |

## Known limitations

- A plugin stuck in an infinite loop can't be interrupted (the QuickJS
  binding doesn't expose an interrupt handler). It's bounded by a 10s
  wait, after which the widget is disabled and the thread abandoned.
- `ctx.location()`'s one-shot fallback needs API 30+; below that only a
  cached fix is used.
- Plugins are trusted not to be *slow*, only prevented from being
  *dangerous*. There's no CPU accounting beyond the timeout.
