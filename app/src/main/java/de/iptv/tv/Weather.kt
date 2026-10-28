package de.iptv.tv

import org.json.JSONObject
import kotlin.math.roundToInt

internal enum class WeatherKind {
    CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SLEET, SNOW, THUNDERSTORM, UNKNOWN;

    companion object {
        fun fromCode(code: Int): WeatherKind = when (code) {
            0, 1 -> CLEAR
            2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55, 61, 63, 65, 80, 81, 82 -> RAIN
            56, 57, 66, 67 -> SLEET
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> THUNDERSTORM
            else -> UNKNOWN
        }
    }
}

internal data class WeatherSnapshot(
    val celsius: Double, val code: Int, val isDay: Boolean, val observedAt: Long, val city: String
) {
    val kind get() = WeatherKind.fromCode(code)
    val temperature get() = "${celsius.roundToInt()} °C"
    fun isFresh(now: Long) = now - observedAt in 0..MAX_AGE_MS

    companion object {
        const val MAX_AGE_MS = 45 * 60_000L
        fun parse(json: JSONObject, city: String, now: Long): WeatherSnapshot {
            val current = json.getJSONObject("current")
            val temperature = current.getDouble("temperature_2m")
            val code = current.getDouble("weather_code")
            val day = current.getDouble("is_day")
            val time = current.getLong("time")
            require(temperature.isFinite() && temperature in -100.0..70.0)
            require(code.isFinite() && code == code.toInt().toDouble())
            require(day == 0.0 || day == 1.0)
            require(time in 0..Long.MAX_VALUE / 1000)
            return WeatherSnapshot(temperature, code.toInt(), day == 1.0, time * 1000, city).also {
                require(it.isFresh(now))
            }
        }
    }
}

/** Transport is deliberately separate from the passive header and its refresh lifecycle. */
internal fun interface WeatherSource {
    suspend fun load(): WeatherSnapshot
}
