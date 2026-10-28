package de.iptv.tv

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Approved IP geolocation and weather endpoints. Never sends playlist/account data.
 * Requests and bounded JSON parsing run on OkHttp's workers and cancel with the header. */
internal class WeatherService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).followRedirects(false).build(),
    private val locationUrl: HttpUrl = "https://ipwho.is/".toHttpUrl(),
    private val forecastUrl: HttpUrl = "https://api.open-meteo.com/v1/forecast".toHttpUrl(),
    private val now: () -> Long = System::currentTimeMillis
) : WeatherSource {
    override suspend fun load(): WeatherSnapshot {
        // Resolve on each weather refresh to pick up a changed Internet connection.
        val location = json(locationUrl.newBuilder()
            .addQueryParameter("fields", "success,latitude,longitude,city").build())
        require(location.getBoolean("success"))
        val latitude = location.getDouble("latitude")
        val longitude = location.getDouble("longitude")
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        val forecast = forecastUrl.newBuilder()
            .addQueryParameter("latitude", latitude.toString())
            .addQueryParameter("longitude", longitude.toString())
            .addQueryParameter("current", "temperature_2m,weather_code,is_day")
            .addQueryParameter("temperature_unit", "celsius")
            .addQueryParameter("timeformat", "unixtime")
            .addQueryParameter("forecast_days", "1").build()
        return WeatherSnapshot.parse(json(forecast), location.optString("city").take(100), now())
    }

    private suspend fun json(url: HttpUrl): JSONObject = suspendCancellableCoroutine { pending ->
        val call = client.newCall(Request.Builder().url(url).build())
        pending.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (pending.isActive) pending.resumeWithException(IOException("weather_network"))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException("weather_http")
                        val body = it.body ?: throw IOException("weather_empty")
                        val source = body.source()
                        source.request(MAX_BYTES + 1)
                        if (source.buffer.size > MAX_BYTES) throw IOException("weather_too_large")
                        JSONObject(source.readUtf8())
                    }
                }
                if (pending.isActive) result.fold(
                    onSuccess = { pending.resume(it) },
                    onFailure = { pending.resumeWithException(it) }
                )
            }
        })
    }

    private companion object { const val MAX_BYTES = 65_536L }
}
