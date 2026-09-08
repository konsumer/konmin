// Weather — the full tour: network, location, caching and storage.
//
// Uses Open-Meteo, which needs no API key. Note the manifest: `domains`
// is an allowlist the host enforces, so this plugin physically cannot
// reach anywhere else even if its code is later changed to try.

const manifest = {
  id: 'weather',
  name: 'Weather',
  description: 'Current temperature and conditions for your location.',
  intervalMinutes: 60,
  maxHeightUnits: 3,
  // Marks this as expensive, so the launcher throttles it much harder when
  // the device is in battery saver.
  networkHeavy: true,
  permissions: ['NETWORK', 'LOCATION', 'STORAGE'],
  domains: ['api.open-meteo.com']
}

// https://open-meteo.com/en/docs -- WMO weather codes
const CONDITIONS = {
  0: 'clear',
  1: 'mostly clear',
  2: 'partly cloudy',
  3: 'overcast',
  45: 'fog',
  48: 'freezing fog',
  51: 'light drizzle',
  53: 'drizzle',
  55: 'heavy drizzle',
  61: 'light rain',
  63: 'rain',
  65: 'heavy rain',
  71: 'light snow',
  73: 'snow',
  75: 'heavy snow',
  80: 'showers',
  81: 'showers',
  82: 'heavy showers',
  95: 'thunderstorm',
  96: 'thunderstorm with hail',
  99: 'thunderstorm with hail'
}

async function render (ctx) {
  // Coarse location only, and only a last-known fix -- this never turns the
  // GPS on. It can legitimately be null (no fix yet, or permission denied),
  // so fall back to the last place we successfully looked up.
  let place = await ctx.location()

  if (place) {
    await ctx.storageSet('lastPlace', JSON.stringify(place))
  } else {
    const remembered = await ctx.storageGet('lastPlace')
    if (!remembered) {
      return { lines: [{ text: 'weather: waiting for location', size: -1 }], nextCheckMinutes: 30 }
    }
    place = JSON.parse(remembered)
  }

  const url = 'https://api.open-meteo.com/v1/forecast' +
    `?latitude=${place.lat}&longitude=${place.lon}` +
    '&current=temperature_2m,weather_code' +
    '&daily=temperature_2m_max,temperature_2m_min' +
    '&forecast_days=1&timezone=auto'

  // cacheMinutes is the whole point of routing fetch through the host: even
  // if something re-renders this widget early, the network is only touched
  // once every 30 minutes.
  const data = JSON.parse(await ctx.fetch(url, { cacheMinutes: 30 }))

  const temp = Math.round(data.current.temperature_2m)
  const unit = data.current_units.temperature_2m
  const condition = CONDITIONS[data.current.weather_code] || 'unknown'
  const high = Math.round(data.daily.temperature_2m_max[0])
  const low = Math.round(data.daily.temperature_2m_min[0])

  return {
    lines: [
      { text: `${temp}${unit}`, size: 1, weight: 1 },
      { text: condition, size: -1 },
      { text: `${high}${unit} / ${low}${unit}`, size: -2 }
    ],
    nextCheckMinutes: 60
  }
}
