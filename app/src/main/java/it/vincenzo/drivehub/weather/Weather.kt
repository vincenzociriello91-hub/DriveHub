package it.vincenzo.drivehub.weather

import it.vincenzo.drivehub.core.Http
import it.vincenzo.drivehub.core.Reports
import org.json.JSONObject

data class Hour(val time: String, val temp: Double, val rainProb: Int, val code: Int)
data class Forecast(val temp: Double, val wind: Double, val code: Int, val next: List<Hour>)

/** Open-Meteo: gratuito, senza chiave */
object Weather {
    fun describe(code: Int) = when (code) {
        0 -> "Sereno"
        1, 2 -> "Poco nuvoloso"
        3 -> "Coperto"
        45, 48 -> "Nebbia"
        in 51..57 -> "Pioviggine"
        in 61..67, in 80..82 -> "Pioggia"
        in 71..77, 85, 86 -> "Neve"
        in 95..99 -> "Temporale"
        else -> "—"
    }

    fun load(lat: Double, lon: Double): Forecast {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,wind_speed_10m" +
            "&hourly=temperature_2m,precipitation_probability,weather_code&forecast_hours=6&timezone=auto"
        val j = JSONObject(Http.get(url))
        val c = j.getJSONObject("current")
        val h = j.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val next = (0 until times.length()).map {
            Hour(
                times.getString(it).substringAfter('T'),
                h.getJSONArray("temperature_2m").getDouble(it),
                h.getJSONArray("precipitation_probability").optInt(it, 0),
                h.getJSONArray("weather_code").getInt(it),
            )
        }
        Reports.log("Meteo caricato: ${c.getDouble("temperature_2m")}°C, codice ${c.getInt("weather_code")}")
        return Forecast(c.getDouble("temperature_2m"), c.getDouble("wind_speed_10m"), c.getInt("weather_code"), next)
    }
}
