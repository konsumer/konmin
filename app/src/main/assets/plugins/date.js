// Date — one line, refreshed hourly.
//
// Shows how little a plugin needs to do. Because it reports
// nextCheckMinutes: 60, the launcher won't wake it more often than that
// even while you're staring at the home screen.

const manifest = {
  id: 'date',
  name: 'Date',
  description: 'Day of the week and date.',
  intervalMinutes: 60,
  maxHeightUnits: 1,
  permissions: []
}

function render (ctx) {
  return {
    lines: [
      { text: ctx.formatTime(ctx.now(), 'EEEE, MMMM d'), size: 0 }
    ],
    nextCheckMinutes: 60
  }
}
