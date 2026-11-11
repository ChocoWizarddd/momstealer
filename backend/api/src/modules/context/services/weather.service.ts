/**
 * Weather service — a server-side proxy to Open-Meteo (no API key, no
 * credentials in the APK). Location is resolved from the client IP when the
 * app doesn't supply coordinates (the app prefers not to ask for location).
 * Responses are cached for 30 minutes so Home never hammers the provider.
 */

type Condition =
  | 'SUNNY'
  | 'CLOUDY'
  | 'PARTLY_CLOUDY'
  | 'RAIN'
  | 'HEAVY_RAIN'
  | 'THUNDERSTORM'
  | 'SNOW'
  | 'FOG'
  | 'WINDY'
  | 'HOT'
  | 'COLD'
  | 'UNKNOWN'

export interface WeatherResult {
  condition: Condition
  label: string
  tempC: number | null
  isDay: boolean
  city: string
  fetchedAt: number
}

interface OpenMeteoResponse {
  current?: {
    temperature_2m?: number
    weather_code?: number
    is_day?: number
  }
}

interface IpGeo {
  city?: string
  region?: string
  country_name?: string
  latitude?: number
  longitude?: number
}

const DEFAULT_LAT = 28.6139
const DEFAULT_LON = 77.209
const DEFAULT_CITY = 'New Delhi'

const TTL_MS = 30 * 60 * 1000

function wmoToCondition(code: number | undefined, tempC: number | null): Condition {
  if (code === undefined) return 'UNKNOWN'
  if (code === 0) return tempC !== null && tempC >= 32 ? 'HOT' : 'SUNNY'
  if (code === 1) return 'SUNNY'
  if (code === 2) return 'PARTLY_CLOUDY'
  if (code === 3) return 'CLOUDY'
  if (code === 45 || code === 48) return 'FOG'
  if (code === 51 || code === 53 || code === 55 || code === 56 || code === 57 || code === 80 || code === 81)
    return 'RAIN'
  if (code === 61 || code === 63 || code === 65 || code === 66 || code === 67 || code === 82) return 'HEAVY_RAIN'
  if (code === 71 || code === 73 || code === 75 || code === 77 || code === 85 || code === 86) return 'SNOW'
  if (code === 95 || code === 96 || code === 99) return 'THUNDERSTORM'
  return 'CLOUDY'
}

function labelFor(condition: Condition): string {
  const map: Record<Condition, string> = {
    SUNNY: 'Sunny',
    CLOUDY: 'Cloudy',
    PARTLY_CLOUDY: 'Partly cloudy',
    RAIN: 'Rain',
    HEAVY_RAIN: 'Heavy rain',
    THUNDERSTORM: 'Thunderstorm',
    SNOW: 'Snow',
    FOG: 'Foggy',
    WINDY: 'Windy',
    HOT: 'Hot',
    COLD: 'Cold',
    UNKNOWN: ''
  }
  return map[condition]
}

export class WeatherService {
  private static cache: { key: string; data: WeatherResult; at: number } | null = null

  async getWeather(lat?: number, lon?: number, city?: string): Promise<WeatherResult> {
    let latV = lat
    let lonV = lon
    let cityV = (city || '').trim()

    if (latV === undefined || lonV === undefined) {
      try {
        const res = await fetch('https://ipapi.co/json/', {
          headers: { 'User-Agent': 'nightlight-backend' },
          signal: AbortSignal.timeout(4000)
        })
        if (res.ok) {
          const geo = (await res.json()) as IpGeo
          if (typeof geo.latitude === 'number' && typeof geo.longitude === 'number') {
            latV = geo.latitude
            lonV = geo.longitude
          }
          if (!cityV) cityV = [geo.city, geo.region, geo.country_name].filter(Boolean).join(', ')
        }
      } catch {
        // IP geolocation failed (e.g. localhost behind adb reverse) — default below.
      }
    }

    if (latV === undefined || lonV === undefined) {
      latV = DEFAULT_LAT
      lonV = DEFAULT_LON
    }
    if (!cityV) cityV = DEFAULT_CITY

    const key = `${latV.toFixed(2)},${lonV.toFixed(2)}`
    if (WeatherService.cache && WeatherService.cache.key === key && Date.now() - WeatherService.cache.at < TTL_MS) {
      return WeatherService.cache.data
    }

    const url = new URL('https://api.open-meteo.com/v1/forecast')
    url.searchParams.set('latitude', String(latV))
    url.searchParams.set('longitude', String(lonV))
    url.searchParams.set('current', 'temperature_2m,weather_code,is_day')
    url.searchParams.set('timezone', 'auto')

    let condition: Condition = 'UNKNOWN'
    let tempC: number | null = null
    let isDay = true
    try {
      const res = await fetch(url.toString(), { signal: AbortSignal.timeout(5000) })
      if (res.ok) {
        const body = (await res.json()) as OpenMeteoResponse
        tempC = typeof body.current?.temperature_2m === 'number' ? body.current.temperature_2m : null
        condition = wmoToCondition(body.current?.weather_code, tempC)
        isDay = body.current?.is_day !== 0
      }
    } catch {
      // Provider failure: return UNKNOWN condition; Home falls back to time/mood.
    }

    const result: WeatherResult = {
      condition,
      label: labelFor(condition),
      tempC,
      isDay,
      city: cityV,
      fetchedAt: Date.now()
    }
    WeatherService.cache = { key, data: result, at: Date.now() }
    return result
  }
}
