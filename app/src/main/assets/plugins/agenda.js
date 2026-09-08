// Agenda — next few calendar events, and what happens when you tap one.
//
// Demonstrates two things: a permission the user can refuse, and onClick.
// ctx.upcomingEvents throws if CALENDAR was declared but not granted at the
// OS level, so the try/catch is what turns a refusal into a helpful line
// instead of a broken widget.

const manifest = {
  id: 'agenda',
  name: 'Agenda',
  description: 'Your next few calendar events. Tap one to open it.',
  intervalMinutes: 15,
  maxHeightUnits: 4,
  permissions: ['CALENDAR', 'LAUNCH']
}

const MAX_SHOWN = 3
const WINDOW_MINUTES = 12 * 60

async function render (ctx) {
  let events
  try {
    events = await ctx.upcomingEvents(WINDOW_MINUTES)
  } catch (e) {
    return { lines: [{ text: 'agenda: no calendar access', size: -1 }], nextCheckMinutes: 60 }
  }

  if (!events.length) {
    return { lines: [{ text: 'nothing scheduled', size: -1 }], nextCheckMinutes: 60 }
  }

  const lines = events.slice(0, MAX_SHOWN).map(function (event) {
    const when = event.allDay ? 'all day' : ctx.formatTime(event.startsAt, ctx.is24Hour() ? 'HH:mm' : 'h:mm a')
    return { text: `${when}  ${event.title}`, size: -1 }
  })

  // Tighten the refresh as the next event approaches, so an imminent
  // meeting drops off the list promptly, but stay lazy when the next thing
  // is hours away.
  const minutesUntilNext = Math.max(1, Math.round((events[0].startsAt - ctx.now()) / 60000))

  return {
    lines,
    nextCheckMinutes: Math.min(60, minutesUntilNext)
  }
}

// Tapping a line opens that exact event; tapping anything else (the "nothing
// scheduled" line, say) falls back to the calendar itself.
//
// The events are looked up again rather than remembered from render: onClick
// runs in a fresh context with none of render's variables, and re-reading is
// more honest anyway, since the calendar may have changed since it was drawn.
async function onClick (ctx) {
  let events = []
  try {
    events = await ctx.upcomingEvents(WINDOW_MINUTES)
  } catch (e) {
    // Fall through to opening the calendar app.
  }

  const event = events[ctx.clicked.index]
  if (event) {
    await ctx.openEvent(event.id)
  } else {
    await ctx.openCalendar()
  }
}
