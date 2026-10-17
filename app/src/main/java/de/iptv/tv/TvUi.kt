package de.iptv.tv

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView

/** Shared TV sizing, surfaces and focus treatment. All icons are resolution-independent. */
object TvUi {
    val background = Color.rgb(12, 16, 23)
    val panel = Color.rgb(19, 25, 35)
    val muted = Color.rgb(143, 157, 176)
    val accent = Color.rgb(103, 222, 211)
    val warning = 0xFFFFC777.toInt()
    val control = Color.rgb(27, 43, 49)
    val focused = Color.rgb(31, 62, 70)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun surface(color: Int, radius: Float = 8f, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke != null) setStroke(2, stroke)
    }
    fun focusBackground(context: Context) = StateListDrawable().apply {
        val radius = dp(context, 5).toFloat()
        addState(intArrayOf(-android.R.attr.state_enabled), surface(panel, radius))
        addState(intArrayOf(android.R.attr.state_focused), surface(focused, radius, accent))
        addState(intArrayOf(android.R.attr.state_activated), surface(control, radius))
        addState(intArrayOf(), surface(Color.TRANSPARENT, radius))
    }
    fun animationsEnabled(context: Context): Boolean = if (android.os.Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
        else android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    fun button(context: Context, value: String, click: () -> Unit) = Button(context).apply {
        id = View.generateViewId(); text = value; textSize = 13f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = dp(context, 36); minimumHeight = minHeight
        setTextColor(Color.WHITE); setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
        focus(this, 1f); isFocusableInTouchMode = false; isActivated = true
        setOnClickListener { click() }
    }
    fun text(context: Context, value: String, size: Float = 14f, color: Int = Color.WHITE) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        includeFontPadding = false
    }
    fun focus(view: View, scale: Float = 1.035f, changed: ((Boolean) -> Unit)? = null) {
        view.isFocusable = true; view.isFocusableInTouchMode = true
        view.background = focusBackground(view.context)
        onFocus(view, changed)
    }
    /** Rebinding a recycled row only replaces the callback, not the focus drawables. */
    fun onFocus(view: View, changed: ((Boolean) -> Unit)? = null) {
        view.setOnFocusChangeListener { _, focused ->
            view.animate().cancel()
            // Dense remote navigation uses immediate static feedback, including when animations are off.
            view.scaleX = 1f; view.scaleY = 1f
            changed?.invoke(focused)
        }
    }
}

/** Bring a focused row on screen synchronously. With the default animated scroll, a held D-pad key
 * moves focus to rows outside the viewport; the next full layout (focused rows change height)
 * then scraps the focused row, and Android hands focus to the first focusable view in the window.
 */
class TvLinearLayoutManager(context: Context) : androidx.recyclerview.widget.LinearLayoutManager(context) {
    override fun requestChildRectangleOnScreen(parent: androidx.recyclerview.widget.RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean) =
        super.requestChildRectangleOnScreen(parent, child, rect, true, focusedChildVisible)
}
class TvGridLayoutManager(context: Context, columns: Int) : androidx.recyclerview.widget.GridLayoutManager(context, columns) {
    override fun requestChildRectangleOnScreen(parent: androidx.recyclerview.widget.RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean) =
        super.requestChildRectangleOnScreen(parent, child, rect, true, focusedChildVisible)
}

/** Vertical D-pad focus stays inside the list; only LEFT/RIGHT (or UP above the first row) leave it.
 * When the next row is not laid out yet, RecyclerView falls back to a window-wide focus search,
 * which picks the nearest focusable below, e.g. in the adjacent column or the status bar.
 */
class TvList(context: Context) : androidx.recyclerview.widget.RecyclerView(context) {
    /** Runs before this pass lays out children, so a new grid span applies to the same layout. */
    var onWidthChanged: (() -> Unit)? = null
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth) onWidthChanged?.invoke()
    }
    override fun focusSearch(focused: View?, direction: Int): View? {
        val result = super.focusSearch(focused, direction)
        if (direction != View.FOCUS_DOWN && direction != View.FOCUS_UP || focused == null) return result
        val item = findContainingItemView(focused) ?: return result
        if (result != null && result !== focused && findContainingItemView(result) != null) return result
        val position = getChildAdapterPosition(item)
        val count = adapter?.itemCount ?: 0
        if (position == NO_POSITION || count == 0) return result
        val columns = (layoutManager as? androidx.recyclerview.widget.GridLayoutManager)?.spanCount ?: 1
        val row = position / columns
        val down = direction == View.FOCUS_DOWN
        // Above the first row the search field remains reachable; at the end focus stays put.
        if (!down && row == 0) return result
        if (down && row == (count - 1) / columns) return focused
        val target = if (down) minOf(position + columns, count - 1) else position - columns
        findViewHolderForAdapterPosition(target)?.itemView?.let { return it }
        if (isComputingLayout) return focused
        // Scrolling lays out the next row synchronously, so a fast repeat can land on it directly.
        scrollBy(0, if (down) item.height else -item.height)
        return findViewHolderForAdapterPosition(target)?.itemView ?: focused
    }
}

class TvIconButton(context: Context, var symbol: String, description: String, action: () -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPath = Path()
    private val iconBounds = RectF()
    init {
        contentDescription = description; isClickable = true
        TvUi.focus(this, 1.12f)
        setOnClickListener { action() }
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = TvUi.dp(context, if (symbol == "back" || symbol == "forward") 28 else 23).toFloat()
        canvas.save(); canvas.translate((width - size) / 2, (height - size) / 2); canvas.scale(size / 24, size / 24)
        paint.color = if (isEnabled) Color.WHITE else TvUi.muted
        paint.alpha = if (isEnabled) 255 else 80
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.7f; paint.strokeCap = Paint.Cap.ROUND
        when (symbol) {
            "play" -> { paint.style = Paint.Style.FILL; canvas.drawPath(iconPath.apply { reset(); moveTo(7f,4f); lineTo(20f,12f); lineTo(7f,20f); close() },paint) }
            "pause" -> { paint.style = Paint.Style.FILL; canvas.drawRoundRect(6f,4f,10f,20f,1f,1f,paint); canvas.drawRoundRect(14f,4f,18f,20f,1f,1f,paint) }
            "stop" -> { paint.style = Paint.Style.FILL; canvas.drawRoundRect(5f,5f,19f,19f,2f,2f,paint) }
            "back", "forward" -> {
                // Circular arrow opening at the top, the filled head turning into the gap.
                // Forward mirrors the arrow only; the number stays upright and centred.
                if (symbol == "forward") { canvas.save(); canvas.scale(-1f,1f,12f,12f) }
                canvas.drawArc(iconBounds.apply { set(3.5f,4f,20.5f,21f) },-90f,300f,false,paint)
                paint.style = Paint.Style.FILL
                canvas.drawPath(iconPath.apply { reset(); moveTo(8.4f,4f); lineTo(12.9f,1.4f); lineTo(12.9f,6.6f); close() },paint)
                if (symbol == "forward") canvas.restore()
                paint.textSize = 7.6f; paint.typeface = Typeface.DEFAULT_BOLD; paint.textAlign = Paint.Align.CENTER
                canvas.drawText("30",12f,12.5f-(paint.descent()+paint.ascent())/2,paint)
            }
            "reconnect" -> {
                canvas.drawArc(iconBounds.apply { set(4f,4f,20f,20f) },30f,290f,false,paint)
                canvas.drawPath(iconPath.apply { reset(); moveTo(21f,2f); lineTo(21f,8f); lineTo(15f,8f) },paint)
            }
            "fullscreen", "restore" -> {
                for (i in 0..3) {
                    canvas.save(); canvas.rotate(i*90f,12f,12f)
                    canvas.drawPath(iconPath.apply {
                        reset()
                        if (symbol == "fullscreen") { moveTo(4f,9f); lineTo(4f,4f); lineTo(9f,4f) }
                        else { moveTo(4f,9f); lineTo(9f,9f); lineTo(9f,4f) }
                    },paint); canvas.restore()
                }
            }
            "settings" -> {
                canvas.drawCircle(12f,12f,6f,paint); canvas.drawCircle(12f,12f,2.2f,paint)
                for (i in 0..7) { canvas.save(); canvas.rotate(i*45f,12f,12f); canvas.drawLine(12f,3f,12f,5f,paint); canvas.restore() }
            }
        }
        canvas.restore()
    }
}
