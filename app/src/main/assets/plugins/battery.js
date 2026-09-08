// Battery — shows a plugin varying its own refresh rate, and answering a
// tap by changing its own text instead of opening anything.
//
// It asks to be polled every 15 minutes normally, but every 5 once the
// battery is low, which is the only time the exact number matters. The
// launcher clamps whatever you return to a sane range, so you can't ask to
// be run every second.

const manifest = {
  id: 'battery',
  name: 'Battery',
  description: 'Charge level. Tap to toggle the detail.',
  intervalMinutes: 15,
  maxHeightUnits: 1,
  permissions: ['STORAGE']
}

function line (ctx, verbose) {
  const { level, charging } = ctx.battery()
  const state = charging ? ' charging' : ''
  const text = verbose ? `battery ${level}%${state}` : `${level}%${state}`
  return {
    lines: [{ text, size: -1 }],
    nextCheckMinutes: level <= 20 ? 5 : 15
  }
}

async function render (ctx) {
  return line(ctx, (await ctx.storageGet('verbose')) === 'true')
}

// onClick can return the same shape render does. Returning lines replaces
// what's on screen immediately; returning nothing leaves it alone. Nothing
// is launched here at all -- a click is just another chance to re-render.
async function onClick (ctx) {
  const verbose = (await ctx.storageGet('verbose')) === 'true'
  await ctx.storageSet('verbose', verbose ? 'false' : 'true')
  return line(ctx, !verbose)
}
