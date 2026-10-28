package de.iptv.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Passive status: no extra D-pad stop or live-region announcement on refresh. */
internal class WeatherHeader(context: Context, private val source: WeatherSource = WeatherService()) : LinearLayout(context) {
    private val icon = WeatherIcon(context)
    private val temperature = TextView(context).apply {
        tag = "navbar_temperature"
        textSize = 14f; setTextColor(android.graphics.Color.WHITE)
        includeFontPadding = false; fontFeatureSettings = "tnum"
        setSingleLine(); textDirection = View.TEXT_DIRECTION_LTR
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        minWidth = paint.measureText("−88 °C").toInt()
    }
    private val weather = LinearLayout(context).apply {
        tag = "navbar_weather"
        gravity = Gravity.CENTER_VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
        addView(icon, LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(5) })
        addView(temperature, LayoutParams(-2, -2))
    }
    private var work: Job? = null
    private var latest: WeatherSnapshot? = null
    private var nextRefreshAt = 0L

    init {
        tag = "navbar_date_weather"
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(36)
        addView(TextClock(context).apply {
            tag = "navbar_date"
            format12Hour = "d.MMM"; format24Hour = "d.MMM"
            textSize = 14f; setTextColor(TvUi.muted); includeFontPadding = false
            setSingleLine(); isFocusable = false
            // German abbreviated months may end with a dot: keep the compact 28.Okt form.
            doAfterTextChanged { value ->
                if (value?.endsWith(".") == true) text = value.toString().trimEnd('.')
            }
            text = text.toString().trimEnd('.')
        }, LayoutParams(-2, -2).apply { marginEnd = dp(12) })
        addView(weather, LayoutParams(-2, -2))
        render(null)
    }

    fun start(scope: CoroutineScope) {
        if (work?.isActive == true) return
        work = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                render(latest?.takeIf { it.isFresh(now) }, latest == null && nextRefreshAt == 0L)
                if (android.os.SystemClock.elapsedRealtime() >= nextRefreshAt) {
                    try {
                        latest = source.load()
                        nextRefreshAt = android.os.SystemClock.elapsedRealtime() + 15 * 60_000L
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        nextRefreshAt = android.os.SystemClock.elapsedRealtime() + 5 * 60_000L
                    }
                    render(latest?.takeIf { it.isFresh(System.currentTimeMillis()) }, false)
                }
                // Expire old data even while offline; never leave an old sunny icon overnight.
                delay(30_000)
            }
        }
    }

    fun stop() { work?.cancel(); work = null }

    internal fun render(snapshot: WeatherSnapshot?, loading: Boolean = false) {
        temperature.text = snapshot?.temperature ?: "— °C"
        temperature.setTextColor(if (snapshot == null) TvUi.muted else android.graphics.Color.WHITE)
        icon.kind = snapshot?.kind ?: WeatherKind.UNKNOWN
        icon.isDay = snapshot?.isDay ?: true
        val condition = when (snapshot?.kind) {
            WeatherKind.CLEAR -> if (snapshot.isDay) R.string.weather_sunny else R.string.weather_clear_night
            WeatherKind.PARTLY_CLOUDY -> if (snapshot.isDay) R.string.weather_partly_cloudy else R.string.weather_partly_cloudy_night
            WeatherKind.CLOUDY -> R.string.weather_cloudy
            WeatherKind.FOG -> R.string.weather_fog
            WeatherKind.RAIN -> R.string.weather_rain
            WeatherKind.SLEET -> R.string.weather_sleet
            WeatherKind.SNOW -> R.string.weather_snow
            WeatherKind.THUNDERSTORM -> R.string.weather_thunderstorm
            else -> R.string.weather_unknown
        }
        weather.contentDescription = if (snapshot == null) context.getString(
            if (loading) R.string.weather_loading else R.string.weather_unavailable
        ) else context.getString(R.string.weather_description, snapshot.city, context.getString(condition), snapshot.temperature)
        icon.invalidate()
    }

    private fun dp(value: Int) = TvUi.dp(context, value)
}

/** Outlined Canvas icons match the existing TV controls, with no bitmap/emoji dependency. */
internal class WeatherIcon(context: Context) : View(context) {
    var kind = WeatherKind.UNKNOWN
    var isDay = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.65f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        val size = minOf(width, height).toFloat()
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 24f, size / 24f)
        paint.color = if (kind == WeatherKind.UNKNOWN) TvUi.muted else TvUi.accent
        when (kind) {
            WeatherKind.CLEAR -> sky(canvas)
            WeatherKind.PARTLY_CLOUDY -> {
                canvas.save(); canvas.translate(0f, -1f); canvas.scale(.72f, .72f); sky(canvas); canvas.restore()
                cloud(canvas, maskSky = true)
            }
            WeatherKind.CLOUDY -> cloud(canvas)
            WeatherKind.FOG -> {
                cloud(canvas)
                canvas.drawLine(4f, 19f, 20f, 19f, paint)
                canvas.drawLine(7f, 22f, 17f, 22f, paint)
            }
            WeatherKind.RAIN, WeatherKind.SLEET -> {
                cloud(canvas)
                canvas.drawLine(8f, 19f, 6.5f, 22f, paint)
                canvas.drawLine(17f, 19f, 15.5f, 22f, paint)
                if (kind == WeatherKind.SLEET) snow(canvas, 12f, 20.5f, 1.5f)
                else canvas.drawLine(12.5f, 18f, 11f, 21f, paint)
            }
            WeatherKind.SNOW -> {
                cloud(canvas); snow(canvas, 8f, 20.5f, 1.7f); snow(canvas, 16f, 20.5f, 1.7f)
            }
            WeatherKind.THUNDERSTORM -> {
                cloud(canvas)
                path.reset(); path.moveTo(13f, 17f); path.lineTo(10f, 20f)
                path.lineTo(14f, 20f); path.lineTo(11f, 23f); canvas.drawPath(path, paint)
            }
            WeatherKind.UNKNOWN -> canvas.drawLine(7f, 12f, 17f, 12f, paint)
        }
        canvas.restore()
    }

    private fun sky(canvas: Canvas) {
        if (isDay) {
            canvas.drawCircle(12f, 12f, 4f, paint)
            repeat(8) { ray ->
                canvas.save(); canvas.rotate(ray * 45f, 12f, 12f)
                canvas.drawLine(12f, 2f, 12f, 4f, paint); canvas.restore()
            }
        } else {
            path.reset(); path.moveTo(19.5f, 14.5f)
            path.cubicTo(10f, 17f, 7f, 8f, 11f, 3f)
            path.cubicTo(-1f, 6f, 4f, 25f, 16f, 20f)
            path.cubicTo(18f, 19f, 19f, 17f, 19.5f, 14.5f)
            path.close(); canvas.drawPath(path, paint)
            canvas.drawLine(19f, 3f, 19f, 7f, paint); canvas.drawLine(17f, 5f, 21f, 5f, paint)
        }
    }

    private fun cloud(canvas: Canvas, maskSky: Boolean = false) {
        path.reset(); path.moveTo(6f, 16f)
        path.cubicTo(0f, 16f, 0f, 8f, 6f, 8f)
        path.cubicTo(7f, 1f, 17f, 2f, 18f, 9f)
        path.cubicTo(24f, 8f, 24f, 16f, 18f, 16f)
        path.close()
        if (maskSky) {
            val tint = paint.color
            paint.style = Paint.Style.FILL; paint.color = TvUi.background
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE; paint.color = tint
        }
        canvas.drawPath(path, paint)
    }

    private fun snow(canvas: Canvas, x: Float, y: Float, radius: Float) {
        repeat(3) { arm ->
            canvas.save(); canvas.rotate(arm * 60f, x, y)
            canvas.drawLine(x, y - radius, x, y + radius, paint); canvas.restore()
        }
    }
}
