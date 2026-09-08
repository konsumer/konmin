// Clock — the simplest possible konmin plugin.
//
// A plugin is one .js file. It declares a `manifest` and a `render(ctx)`.
// render returns { lines, nextCheckMinutes }; `lines` is what gets drawn,
// `nextCheckMinutes` tells the launcher when to bother asking you again.
// Asking for a longer interval is the single biggest thing you can do for
// the user's battery.

const manifest = {
  id: 'clock',
  name: 'Clock',
  description: 'Current time. Tap to open your alarms.',
  intervalMinutes: 1,
  maxHeightUnits: 2,
  permissions: ['LAUNCH']
}

function render (ctx) {
  // QuickJS has no full Intl, so locale-aware formatting comes from the
  // host: ctx.formatTime(epochMillis, SimpleDateFormat pattern).
  const pattern = ctx.is24Hour() ? 'HH:mm' : 'h:mm a'

  return {
    lines: [
      { text: ctx.formatTime(ctx.now(), pattern), size: 2, weight: 1 }
    ],
    nextCheckMinutes: 1
  }
}

// Defining onClick is what makes a widget's lines tappable at all; without
// it they're inert text. ctx.openAlarms() is a standard action every clock
// app registers, so this works without knowing which clock is installed.
async function onClick (ctx) {
  await ctx.openAlarms()
}
