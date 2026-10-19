package de.iptv.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Decorative compound drawable: the native Button supplies its role, name and selected state. */
internal class DetailActionIcon(context:Context,private val star:Boolean,private val selected:Boolean):Drawable(){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    init{setBounds(0,0,TvUi.dp(context,24),TvUi.dp(context,24))}
    override fun draw(canvas:Canvas){
        val size=bounds.width().toFloat();val cx=bounds.exactCenterX();val cy=bounds.exactCenterY()
        paint.color=if(star&&selected)TvUi.favoriteActive else Color.WHITE
        paint.style=if(star&&!selected)Paint.Style.STROKE else Paint.Style.FILL;paint.strokeWidth=size/12
        if(star)TvUi.starPath(path,cx,cy,size*.43f,size*.19f) else path.apply{
            reset();moveTo(cx-size*.22f,cy-size*.34f);lineTo(cx+size*.34f,cy);lineTo(cx-size*.22f,cy+size*.34f);close()
        }
        canvas.drawPath(path,paint)
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    override fun setColorFilter(colorFilter:ColorFilter?){paint.colorFilter=colorFilter}
    @Deprecated("Required by Drawable") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
