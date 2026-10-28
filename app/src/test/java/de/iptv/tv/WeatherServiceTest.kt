package de.iptv.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class WeatherServiceTest {
    private val now = 1_792_000_000_000L
    private val location = """{"success":true,"latitude":52.52,"longitude":13.41,"city":"Berlin"}"""
    private val forecast = """{"current":{"temperature_2m":8.6,"weather_code":2,"is_day":0,"time":${now / 1000}}}"""
    private fun service(server: MockWebServer) = WeatherService(
        locationUrl = server.url("/location"), forecastUrl = server.url("/forecast"), now = { now }
    )

    @Test fun resolvesNetworkLocationAndRequestsOnlyCurrentCelsiusWeather() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(location))
            server.enqueue(MockResponse().setBody(forecast))
            val result = service(server).load()
            assertEquals("Berlin", result.city)
            assertEquals("9 °C", result.temperature)
            assertFalse(result.isDay)
            assertEquals(WeatherKind.PARTLY_CLOUDY, result.kind)
            val geolocation = server.takeRequest().requestUrl!!
            assertEquals("success,latitude,longitude,city", geolocation.queryParameter("fields"))
            val weather = server.takeRequest()
            assertNull(weather.getHeader("Authorization"))
            assertEquals("52.52", weather.requestUrl!!.queryParameter("latitude"))
            assertEquals("13.41", weather.requestUrl!!.queryParameter("longitude"))
            assertEquals("temperature_2m,weather_code,is_day", weather.requestUrl!!.queryParameter("current"))
            assertEquals("celsius", weather.requestUrl!!.queryParameter("temperature_unit"))
            assertEquals("unixtime", weather.requestUrl!!.queryParameter("timeformat"))
        }
    }

    @Test fun refusedOrInvalidLocationDoesNotRequestWeatherForAnInventedPlace() {
        for (body in listOf("""{"success":false}""", location.replace("52.52", "152.52"))) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))
                assertThrows(Exception::class.java) { runBlocking { service(server).load() } }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun failedHttpAndOversizedBodiesAreRejected() {
        for (response in listOf(MockResponse().setResponseCode(429), MockResponse().setBody("x".repeat(65_537)))) {
            MockWebServer().use { server ->
                server.enqueue(response)
                assertThrows(java.io.IOException::class.java) { runBlocking { service(server).load() } }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun redirectsDoNotSendLocationRequestsToAnotherService() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
            assertThrows(java.io.IOException::class.java) { runBlocking { service(server).load() } }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun stoppingTheHeaderCancelsAnInFlightNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
            val service = WeatherService(client, server.url("/location"), server.url("/forecast"), { now })
            val work = async(Dispatchers.Default) { service.load() }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            val call = client.dispatcher.runningCalls().single()
            work.cancelAndJoin()
            assertTrue(call.isCanceled())
        }
    }
}
