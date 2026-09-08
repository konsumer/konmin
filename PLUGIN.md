# Writing konmin plugins

A konmin widget is a small sandboxed JavaScript plugin. One plugin is one `.js` file. The file declares a `manifest` object and a `render(ctx)` function. You add the file through the launcher's settings, and konmin runs it and draws what it returns.

Plugins run inside QuickJS. There is no DOM, no network, and no filesystem unless you go through the `ctx` API below. Everything a plugin can reach is mediated and permission-gated.

For the user view of the launcher, see README.md. For building konmin itself, see DEVELOPER.md.

## A minimal plugin

```js
const manifest = {
  id: 'hello',
  name: 'Hello',
  description: 'My first widget.',
  intervalMinutes: 60
}

function render (ctx) {
  return {
    lines: [{ text: 'hello world', size: 0 }],
    nextCheckMinutes: 60
  }
}
```

`render` may be `async`. The examples in `app/src/main/assets/plugins/` in the repo (and installed on your device) are the reference documentation. Read them before writing your own.

## File structure

The host extracts the manifest by evaluating your file in a bare QuickJS context at install time. That evaluation has no `ctx` and no storage, so keep module-level code to definitions. Code that tries to do real work at load time makes the install fail.

```js
const manifest = { /* see below */ }

async function render (ctx) { /* required */ }

// Optional. Presence is what makes the widget's lines tappable.
async function onClick (ctx) { /* optional */ }
```

Extra keys in `manifest` are ignored. `handlesClick` is never declared: the host detects whether the source defines `onClick` and records that at install time. If you add an `onClick` to an already-installed plugin, reinstall it (add the same file again) so the host notices.

## Manifest fields

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `id` | string | required | Unique id. Letters, digits, dash, and underscore only. Becomes the plugin's file name and the namespace for its storage and fetch cache. |
| `name` | string | required | Shown in the plugin list. |
| `description` | string | `""` | Shown in the plugin list. |
| `intervalMinutes` | int | `30` | Fallback cadence when a render gives no `nextCheckMinutes`. |
| `maxHeightUnits` | number | `4` | Default height budget for the widget, in line units. |
| `networkHeavy` | boolean | `false` | Marks the plugin as expensive, so it is throttled much harder under battery saver. |
| `permissions` | array | `[]` | One or more of `NETWORK`, `LOCATION`, `CALENDAR`, `STORAGE`, `LAUNCH`. |
| `domains` | array of strings | `[]` | Host allowlist for `ctx.fetch`, for example `["api.open-meteo.com"]`. |

## What `render` returns

`render(ctx)` returns an object:

```js
{
  lines: [
    { text: '...', size: 1, weight: 1 },
    { text: '...', size: -1, align: 'CENTER' }
  ],
  nextCheckMinutes: 60
}
```

### `lines`

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `text` | string | required | The text to draw, up to 200 characters. |
| `size` | int | `0` | Relative size from `-2` to `2`. Mapped onto the user's base text size: `-2` is 0.70x, `-1` is 0.85x, `0` is 1.00x, `1` is 1.15x, `2` is 1.30x. Values outside the range are clamped. |
| `weight` | int | `0` | `1` is bold. Any nonzero value counts as bold. |
| `align` | string | `START` | `START`, `CENTER`, or `END`. |

A widget returns at most 12 lines. Each line occupies its size multiplier in height units. The widget has a height budget, the manifest's `maxHeightUnits` by default, adjustable per widget in settings. Lines are kept in order while their running height fits; the rest are dropped.

### `nextCheckMinutes`

How soon the launcher should run this plugin again. It overrides `intervalMinutes` for that one cycle. The host clamps it to 1-360 minutes, doubles the scheduled wait under battery saver, and doubles it again after a failed run. The widget is not re-rendered between checks, even while it is on screen.

Asking for a longer interval is the single most useful thing a plugin can do for battery life.

## `onClick`

Defining `onClick(ctx)` makes the widget's lines tappable. It runs when the user taps the widget, in a fresh context, so it has none of the variables `render` left behind. `ctx.clicked.index` is the tapped line's index and `ctx.clicked.text` its text. Re-read whatever data you need.

`onClick` may return the same shape `render` returns, which replaces the lines on screen immediately. Returning nothing leaves the cached lines alone. See `battery.js` (a tap that redraws the widget) and `agenda.js` (a tap that opens an app) for both styles.

The LAUNCH actions only work inside `onClick`. A scheduled background render calling them throws, because a widget refresh must never throw an app in the user's face.

## The `ctx` API

All `ctx` methods are synchronous host calls. They throw, they do not reject, so `.catch()` on a return value catches nothing. Use `try`/`catch`. `render` may still be `async`, and `await` on a host call is harmless.

| Method | Returns | Needs |
| --- | --- | --- |
| `ctx.now()` | Epoch millis. | |
| `ctx.formatTime(millis, pattern)` | Formatted string in the device locale and timezone. `pattern` is a `java.text.SimpleDateFormat` pattern. | |
| `ctx.is24Hour()` | Boolean, the device clock setting. | |
| `ctx.battery()` | `{ level, charging }`. `level` is 0-100, or -1 if unknown. | |
| `ctx.fetch(url, { cacheMinutes })` | The response body as a string. | `NETWORK` |
| `ctx.storageGet(key)` | The value string, or `null` when unset. | `STORAGE` |
| `ctx.storageSet(key, value)` | Nothing. | `STORAGE` |
| `ctx.location()` | `{ lat, lon }` rounded to about 1 km, or `null`. | `LOCATION` |
| `ctx.upcomingEvents(withinMinutes)` | Array of `{ id, title, startsAt, allDay }`, sorted by start time. | `CALENDAR` |
| `ctx.launchApp(packageName)` | Nothing. Opens an installed app. | `LAUNCH`, click only |
| `ctx.openUrl(url)` | Nothing. Opens an `http` or `https` URL. | `LAUNCH`, click only |
| `ctx.openCalendar(atMillis)` | Nothing. Opens the calendar at a moment in time, default now. | `LAUNCH`, click only |
| `ctx.openEvent(eventId)` | Nothing. Opens one event, by the `id` from `upcomingEvents`. | `LAUNCH`, click only |
| `ctx.openAlarms()` | Nothing. Opens the system alarm list. | `LAUNCH`, click only |

### `ctx.fetch`

- `https` only. Any other scheme throws.
- The host must match the URL's host against the manifest's `domains` allowlist. An entry allows that host and its subdomains; a `*.` prefix is allowed, so `*.example.com` covers any subdomain. A plugin cannot widen the list at runtime.
- `cacheMinutes` defaults to 15 and is capped at 0-1440. Cached bodies are keyed by plugin and URL, so two plugins never share a cache entry. On a network failure the host returns a stale cached body if one exists, which beats a blank widget.
- A response larger than 512 KB throws, and any non-2xx status throws.

### Permissions

Every capability is checked twice: once against what the manifest declared, and once against what Android actually granted. A denied or undeclared call throws a catchable error, never a crash and never somebody else's data.

- `NETWORK` needs only the declaration. Internet access is granted with the app install, so there is no runtime prompt.
- `STORAGE` is the app's own per-plugin key-value tables, not the filesystem. It needs only the declaration, and values are isolated per plugin.
- `LOCATION` needs the declaration plus the OS coarse-location grant. The grant is requested when you enable a widget that declares it. Positions are rounded to about 1 km, and the host never keeps a standing location listener.
- `CALENDAR` needs the declaration plus the OS read-calendar grant, requested on enable. Recurring events are expanded into the occurrences that actually fall in the window. See `agenda.js` for handling a refusal with `try`/`catch`.
- `LAUNCH` needs the declaration and a real click. The actions are a fixed named set, not a general start-any-intent call.

## Battery guidance

- Ask for the longest honest interval. The clock asks for 1 minute and needs it. The date asks for 60 and never needs more.
- Keep `cacheMinutes` on every `ctx.fetch`. Routing fetch through the host exists so the network is only touched once per cache window even if the widget re-renders early.
- Set `networkHeavy: true` on plugins that hit the network or cost real work. Under battery saver their interval is pushed to three times the manifest value by the background job and they are skipped by the on-screen ticker entirely.
- Drawing the home screen never runs JavaScript and never touches the network. It only draws the last cached render, so the interval you ask for is exactly how often your code runs.

## The bundled examples

Five plugins ship in `app/src/main/assets/plugins/` and are installed on first run. They are ordinary plugins running through the same sandbox as anything you write.

| Plugin | Teaches |
| --- | --- |
| `clock.js` | The smallest useful plugin. A 1-minute cadence, `ctx.is24Hour()`, `ctx.formatTime`, and `onClick` calling `ctx.openAlarms()`. |
| `date.js` | A one-line widget on a long interval. |
| `battery.js` | Varying its own refresh rate (`nextCheckMinutes` 5 when low, 15 otherwise), `ctx.storageGet`/`ctx.storageSet`, and an `onClick` that answers a tap by redrawing instead of launching anything. |
| `weather.js` | The full tour: `domains`, `networkHeavy`, `ctx.location()` with a stored fallback, and `ctx.fetch` with `cacheMinutes`. |
| `agenda.js` | `ctx.upcomingEvents`, catching a denied CALENDAR permission, and per-line `onClick` with `ctx.openEvent` and `ctx.openCalendar`. |

The bundled examples are disable-only. They are the API documentation, and removing them would leave no example to copy. **restore examples** in settings rewrites any you edited back to the originals. Deleting one from `files/plugins/` by hand does not help, the launcher re-installs it on the next launch.

## Installing a plugin

The user path: open Settings, tap **add plugin (.js)**, and pick the file. A new plugin starts disabled. Enabling it makes it due right away, and enabling one that declares `LOCATION` or `CALENDAR` triggers the OS permission prompt. You can remove a plugin you added in the same settings screen. The bundled examples refuse removal.

The developer path, while iterating on a connected device:

```bash
adb push myplugin.js /data/local/tmp/
adb shell run-as com.konsumer.konmin cp /data/local/tmp/myplugin.js files/plugins/myplugin.js
adb shell am force-stop com.konsumer.konmin
adb shell am start -n com.konsumer.konmin/.ui.MainActivity
```

Replacing an existing plugin's file keeps its widget row and settings. Adding a new id starts it disabled after the relaunch.

## Debugging a plugin

`console.log` output lands in logcat under the `konmin.js` tag, prefixed with the plugin id. Render failures are recorded on the widget row and shown in the plugin admin in settings, so a broken widget says why. A plugin that hits the render budget (10 seconds) is disabled with an explanatory message.

## Limitations

- QuickJS has `Date` but no full `Intl`, so locale-aware date formatting has to come from `ctx.formatTime`.
- There is no `require`, no module loading, and no way to split a plugin across files. One `.js` file is the unit.
- A plugin stuck in an infinite loop cannot be interrupted, the QuickJS binding exposes no interrupt handler. It is bounded by the render budget, after which the widget is disabled and the thread abandoned.
- `ctx.location()`'s one-shot fallback needs API 30+. Below that, only a cached fix is available, and there may be none.
- Plugins are trusted not to be slow, only prevented from being dangerous. There is no CPU accounting beyond the render budget.
- Files picked through the settings picker are read up to 512 KB. Anything larger fails to install.
