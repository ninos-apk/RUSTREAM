package de.iptv.tv

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar

/** Percentages are local to a measured stage; no guessed overall percentage or ETA. */
data class ImportProgress(val message: Int, val completed: Long = 0, val total: Long? = null, val bytes: Boolean = false) {
    val perMille: Int? get() = total?.takeIf { it > 0 }?.let { (completed.coerceIn(0,it).toDouble()*1000/it).toInt() }
    val step: Int get() = when(message) {
        R.string.progress_parsing -> 2
        R.string.progress_finalizing, R.string.progress_opening -> 3
        else -> 1
    }
}

/** Inline loading-screen progress. Never a D-pad stop. */
class ImportProgressView(context: Context) : LinearLayout(context) {
    private val bar = ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {
        max=1000; progressTintList=ColorStateList.valueOf(TvUi.accent)
        indeterminateTintList=ColorStateList.valueOf(TvUi.accent)
        progressBackgroundTintList=ColorStateList.valueOf(TvUi.control)
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val detail = TvUi.text(context,"",12f,TvUi.muted).apply { gravity=Gravity.CENTER; fontFeatureSettings="tnum" }
    init {
        orientation=VERTICAL
        addView(bar,LayoutParams(-1,TvUi.dp(context,6)))
        addView(detail,LayoutParams(-1,-2).apply { topMargin=TvUi.dp(context,8) })
        update(null)
    }
    fun update(state: ImportProgress?) {
        val value=state?.perMille
        bar.isIndeterminate=value==null && TvUi.animationsEnabled(context)
        bar.progress=value ?: 0
        val amount=when {
            value!=null -> context.getString(R.string.progress_percent,value/10)
            state?.bytes==true -> context.getString(R.string.progress_bytes,state.completed/1048576.0)
            else -> context.getString(R.string.progress_working)
        }
        detail.text=if(state==null)amount else context.getString(R.string.progress_step,state.step,amount)
    }
}
