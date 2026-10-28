package de.iptv.tv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WeatherTest {
    private val now = 1_792_000_000_000L
    private fun payload() = JSONObject("""{"current":{"temperature_2m":-3.6,"weather_code":73,"is_day":0,"time":${now / 1000}}}""")

    @Test fun snowAtNightKeepsCelsiusSignAndDaylightFlag() {
        val result = WeatherSnapshot.parse(payload(), "Berlin", now)
        assertEquals("-4 °C", result.temperature)
        assertEquals(WeatherKind.SNOW, result.kind)
        assertFalse(result.isDay)
        assertEquals("Berlin", result.city)
    }

    @Test fun allDocumentedWmoCodesHaveAnIconAndUnknownIsNeutral() {
        val codes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        for (code in codes) assertNotEquals("Code $code", WeatherKind.UNKNOWN, WeatherKind.fromCode(code))
        assertEquals(WeatherKind.UNKNOWN, WeatherKind.fromCode(999))
        assertEquals(WeatherKind.CLEAR, WeatherKind.fromCode(0))
        assertEquals(WeatherKind.THUNDERSTORM, WeatherKind.fromCode(99))
    }

    @Test fun staleFutureMissingAndMalformedReadingsCannotLookLikeLiveWeather() {
        val current = payload().getJSONObject("current")
        for ((field, value) in listOf(
            "temperature_2m" to JSONObject.NULL, "temperature_2m" to 1000,
            "is_day" to 3, "is_day" to 0.5, "weather_code" to 2.5,
            "time" to (now / 1000 + 3600),
            "time" to (now / 1000 - WeatherSnapshot.MAX_AGE_MS / 1000 - 1)
        )) {
            val invalid = JSONObject(current.toString()).put(field, value)
            assertThrows("$field=$value", Exception::class.java) {
                WeatherSnapshot.parse(JSONObject().put("current", invalid), "Test", now)
            }
        }
        assertThrows(Exception::class.java) { WeatherSnapshot.parse(JSONObject(), "Test", now) }
    }

    @Test fun offlineReadingsExpireAndClockRollbackCannotKeepThemCurrent() {
        val result = WeatherSnapshot.parse(payload(), "Test", now)
        assertTrue(result.isFresh(now + WeatherSnapshot.MAX_AGE_MS))
        assertFalse(result.isFresh(now + WeatherSnapshot.MAX_AGE_MS + 1))
        assertFalse(result.isFresh(now - 1))
    }
}
