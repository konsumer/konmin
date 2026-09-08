// Weather — the full tour: network, location, caching and storage.
//
// Uses Open-Meteo, which needs no API key. Note the manifest: `domains`
// is an allowlist the host enforces, so this plugin physically cannot
// reach anywhere else even if its code is later changed to try.

const manifest = {
  id: 'weather',
  name: 'Weather',
  description: "Today's high and low, with conditions.",
  intervalMinutes: 60,
  maxHeightUnits: 3,
  // Marks this as expensive, so the launcher throttles it much harder when
  // the device is in battery saver.
  networkHeavy: true,
  permissions: ['NETWORK', 'LOCATION', 'STORAGE'],
  domains: ['api.open-meteo.com']
}

// https://open-meteo.com/en/docs -- WMO weather codes, mapped to a small
// set of emoji so the widget stays to one line.
const EMOJI = (() => {
  const lookup = {
    0: '☀️', // clear
    1: '☀️', // mostly clear
    2: '⛅', // partly cloudy
    3: '☁️', // overcast
    45: '🌫️', // fog
    48: '🌫️', // freezing fog
    95: '⛈️', // thunderstorm
    96: '⛈️', // thunderstorm with hail
    99: '⛈️' // thunderstorm with hail
  }
  const drizzle = [51, 53, 55] // light drizzle .. heavy drizzle
  const rain = [61, 63, 65, 80, 81, 82] // light rain .. heavy showers
  const snow = [71, 73, 75] // light snow .. heavy snow
  for (const code of drizzle) lookup[code] = '🌧️'
  for (const code of rain) lookup[code] = '🌧️'
  for (const code of snow) lookup[code] = '❄️'
  return code => lookup[code] || '❔'
})()

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
    '&current=weather_code' +
    '&daily=temperature_2m_max,temperature_2m_min' +
    '&forecast_days=1&timezone=auto'

  // cacheMinutes is the whole point of routing fetch through the host: even
  // if something re-renders this widget early, the network is only touched
  // once every 30 minutes.
  const data = JSON.parse(await ctx.fetch(url, { cacheMinutes: 30 }))

  const unit = data.current_units.temperature_2m
  const emoji = EMOJI(data.current.weather_code)
  const high = Math.round(data.daily.temperature_2m_max[0])
  const low = Math.round(data.daily.temperature_2m_min[0])

  return {
    lines: [
      { text: `${high}${unit} / ${low}${unit}  ${emoji}`, size: 1, weight: 1 }
    ],
    nextCheckMinutes: 60
  }
}
