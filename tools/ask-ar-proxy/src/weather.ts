import { record } from "./context";

export interface EdgeLocation {
  latitude?: string;
  longitude?: string;
  city?: string;
  country?: string;
}

const weatherQuestion = /\b(weather|rain\w*|umbrella\w*|cold|coat\w*|jacket\w*|sun\w*|outside|outdoors|snow\w*|wind\w*|temperature|hot|m[eé]t[eé]o|pluie|pleu\w*|parapluie\w*|froid\w*|manteau\w*|veste\w*|soleil|dehors|sortir|neige|vent|chaud\w*)\b/i;

// WMO codes: https://open-meteo.com/en/docs#weathervariables
const descriptions: Record<number, string> = {
  0: "clear sky", 1: "mainly clear", 2: "partly cloudy", 3: "overcast",
  45: "fog", 48: "rime fog", 51: "light drizzle", 53: "moderate drizzle",
  55: "dense drizzle", 56: "light freezing drizzle", 57: "dense freezing drizzle",
  61: "slight rain", 63: "moderate rain", 65: "heavy rain", 66: "light freezing rain",
  67: "heavy freezing rain", 71: "slight snow", 73: "moderate snow", 75: "heavy snow",
  77: "snow grains", 80: "slight rain showers", 81: "moderate rain showers",
  82: "violent rain showers", 85: "slight snow showers", 86: "heavy snow showers",
  95: "thunderstorm", 96: "thunderstorm with slight hail", 97: "heavy thunderstorm",
  99: "thunderstorm with heavy hail",
};

/** Includes cache access and JSON reads in the deadline. Failure never delays an answer further. */
export async function weatherFacts(question: string, cf?: EdgeLocation): Promise<unknown> {
  if (!weatherQuestion.test(question) || !cf?.latitude?.trim() || !cf.longitude?.trim()) return undefined;
  const latitude = Number(cf.latitude);
  const longitude = Number(cf.longitude);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) ||
      Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return undefined;
  const url = new URL("https://api.open-meteo.com/v1/forecast");
  url.searchParams.set("latitude", latitude.toFixed(1));
  url.searchParams.set("longitude", longitude.toFixed(1));
  url.searchParams.set("current", "temperature_2m,weather_code,is_day,wind_speed_10m");
  const abort = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      (async () => {
        const cache = (caches as CacheStorage & { default: Cache }).default;
        const key = new Request(url);
        const cached = await cache.match(key);
        if (abort.signal.aborted) return undefined;
        if (cached) return await cached.json();
        const response = await fetch(url, { signal: abort.signal });
        if (!response.ok) return undefined;
        const data: unknown = await response.json();
        if (!record(data) || !record(data.current)) return undefined;
        const current = data.current;
        if (typeof current.temperature_2m !== "number" || !Number.isFinite(current.temperature_2m) ||
            typeof current.wind_speed_10m !== "number" || !Number.isFinite(current.wind_speed_10m) ||
            typeof current.weather_code !== "number" || !descriptions[current.weather_code] ||
            (current.is_day !== 0 && current.is_day !== 1)) return undefined;
        const weather = {
          source: "Open-Meteo current conditions, approximate IP location; not a forecast",
          temperatureCelsius: current.temperature_2m,
          conditions: descriptions[current.weather_code],
          isDay: current.is_day === 1,
          windSpeedKmh: current.wind_speed_10m,
        };
        if (abort.signal.aborted) return undefined;
        await cache.put(key, Response.json(weather, { headers: { "Cache-Control": "public, max-age=600" } }));
        return weather;
      })(),
      new Promise<undefined>((resolve) => {
        timer = setTimeout(() => { abort.abort(); resolve(undefined); }, 800);
      }),
    ]);
  } catch {
    // No logging: upstream errors can include coordinates.
    return undefined;
  } finally {
    clearTimeout(timer);
  }
}
