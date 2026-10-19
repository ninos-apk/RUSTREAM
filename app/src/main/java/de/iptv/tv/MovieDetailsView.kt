package de.iptv.tv

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.net.toUri
import androidx.core.view.isGone
import org.json.JSONObject
import java.util.Locale

/** A separate TV route. Persistent controls never get replaced when asynchronous data arrives. */
@android.annotation.SuppressLint("ViewConstructor") // This route is constructed in code, never inflated from XML.
class MovieDetailsView(context:Context,private val item:MetadataItem,private val artwork:ArtworkLoader,
    back:()->Unit,play:()->Unit,favorite:()->Unit,correct:()->Unit,retry:()->Unit):ScrollView(context) {
    private fun dp(n:Int)=TvUi.dp(context,n)
    private val content=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPaddingRelative(dp(20),dp(12),dp(20),dp(20))}
    val backButton=TvUi.button(context,context.getString(R.string.buffer_back),back)
    val favoriteButton=TvUi.button(context,"",favorite).apply{
        minHeight=dp(44);minimumHeight=minHeight;minWidth=dp(44);minimumWidth=minWidth
        setPaddingRelative(dp(10),dp(10),dp(10),dp(10))
    }
    private val poster=ImageView(context).apply{scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    private val title=TvUi.text(context,item.title,22f).apply{androidx.core.view.ViewCompat.setAccessibilityHeading(this,true)}
    private val summary=TvUi.text(context,item.year?.toString().orEmpty(),14f,TvUi.muted)
    val playButton=TvUi.button(context,context.getString(R.string.play),play).apply{
        textSize=15f;setTypeface(typeface,android.graphics.Typeface.BOLD)
        minHeight=dp(44);minimumHeight=minHeight;compoundDrawablePadding=dp(10)
        setCompoundDrawablesRelative(DetailActionIcon(context,false,false),null,null,null)
    }
    private val rating=TvUi.text(context,"",14f)
    private val message=TvUi.text(context,"",14f,TvUi.muted).apply{setLineSpacing(0f,1.4f);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
    private val overview=readingText()
    private val facts=readingText()
    private val retryButton=TvUi.button(context,context.getString(R.string.metadata_retry),retry)
    private var trailerUrl:String?=null
    private val trailer=TvUi.button(context,context.getString(R.string.movie_trailer)){
        trailerUrl?.let{url->try{context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,url.toUri()))}
            catch(_:android.content.ActivityNotFoundException){android.app.AlertDialog.Builder(context).setMessage(R.string.movie_trailer_unavailable).setPositiveButton(R.string.close,null).show()}}
    }
    private val castTitle=TvUi.text(context,context.getString(R.string.movie_cast),18f).apply{androidx.core.view.ViewCompat.setAccessibilityHeading(this,true)}
    private val castList=object:RecyclerView(context){
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
            super.onSizeChanged(w,h,oldw,oldh)
            val minimum=(dp(130)*resources.configuration.fontScale.coerceAtLeast(1f)).toInt()
            (layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount=(w/minimum).coerceIn(1,6)
        }
    }.apply{layoutManager=TvGridLayoutManager(context,1);isNestedScrollingEnabled=false;itemAnimator=null}
    private val castAdapter=CastAdapter()
    private val moreCast=TvUi.button(context,context.getString(R.string.movie_more_cast)){castAdapter.more()}
    private var members=emptyList<JSONObject>()
    private val portraitArea=android.graphics.Rect()
    private fun updatePortraits(){
        for(i in 0 until castList.childCount)(castList.getChildViewHolder(castList.getChildAt(i)) as? CastAdapter.Person)?.let{castAdapter.loadPortrait(it)}
    }
    override fun onScrollChanged(l:Int,t:Int,oldl:Int,oldt:Int){super.onScrollChanged(l,t,oldl,oldt);updatePortraits()}
    init {
        layoutDirection=context.resources.configuration.layoutDirection
        id=View.generateViewId();setBackgroundColor(TvUi.background);isFillViewport=true;isSmoothScrollingEnabled=false;isVerticalScrollBarEnabled=true;isScrollbarFadingEnabled=false
        addView(content);content.addView(backButton,LinearLayout.LayoutParams(-2,-2))
        val header=LinearLayout(context).apply{orientation=if(context.resources.configuration.screenWidthDp>=600)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL}
        header.addView(poster,LinearLayout.LayoutParams(dp(110),dp(165)).apply{marginEnd=dp(20);bottomMargin=dp(12)})
        val identity=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
        identity.addView(title,LinearLayout.LayoutParams(-1,-2))
        identity.addView(summary,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        val actions=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
        actions.addView(playButton,LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(12)})
        actions.addView(favoriteButton,LinearLayout.LayoutParams(dp(44),dp(44)))
        identity.addView(actions,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
        header.addView(identity,LinearLayout.LayoutParams(if(header.orientation==LinearLayout.HORIZONTAL)0 else -1,-2,if(header.orientation==LinearLayout.HORIZONTAL)1f else 0f))
        content.addView(header,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
        for(view in listOf(rating,message,retryButton,overview,facts))content.addView(view,LinearLayout.LayoutParams(if(view===overview||view===facts)-2 else -1,-2).apply{topMargin=dp(12)})
        retryButton.visibility=GONE
        content.addView(TvUi.button(context,context.getString(R.string.metadata_correct),correct),LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(12)})
        content.addView(trailer,LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(12)});trailer.visibility=GONE
        content.addView(castTitle,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(24)})
        castList.adapter=castAdapter;content.addView(castList,LinearLayout.LayoutParams(-1,-2))
        content.addView(moreCast,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        moreCast.visibility=GONE;castTitle.visibility=GONE
        content.addView(TvUi.text(context,context.getString(R.string.tmdb_attribution),12f,TvUi.muted),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(24)})
        message.setText(R.string.metadata_loading)
        overview.visibility=GONE;facts.visibility=GONE
        favorite(false)
    }
    private fun readingText()=TvUi.text(context,"",15f).apply{
        setLineSpacing(0f,1.5f);setPaddingRelative(dp(8),dp(8),dp(8),dp(8))
        maxWidth=(paint.measureText("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")/52*70).toInt()+paddingLeft+paddingRight
        TvUi.focus(this,1f)
    }
    fun bindPoster(key:String,resolve:suspend ()->String?){artwork.bind(poster,key,resolve)}
    fun favorite(active:Boolean){
        favoriteButton.contentDescription=context.getString(if(active)R.string.favorite_remove else R.string.favorite_add)
        favoriteButton.isSelected=active
        androidx.core.view.ViewCompat.setStateDescription(favoriteButton,context.getString(if(active)R.string.movie_favorite_on else R.string.movie_favorite_off))
        favoriteButton.setCompoundDrawablesRelative(DetailActionIcon(context,true,active),null,null,null)
    }
    fun loading(){message.setText(R.string.metadata_loading);retryButton.visibility=GONE}
    fun render(result:MetadataResult,configuration:JSONObject?){
        val locale=context.resources.configuration.localesCompat()
        rating.text=if(result.ratings.isEmpty())ratingText(context,null,locale,true)
            else result.ratings.joinToString("\n"){ratingText(context,it,locale,true)}
        message.text=context.getString(when(result.state){"token_missing"->R.string.metadata_token_missing;"error"->R.string.metadata_error;"ambiguous"->R.string.metadata_ambiguous;"unmapped"->R.string.metadata_unmapped;else->R.string.metadata_source})
        retryButton.visibility=if(result.state=="error")VISIBLE else GONE
        overview.text=result.overview.orEmpty();overview.visibility=if(overview.text.isEmpty())GONE else VISIBLE
        val doc=result.tmdb;val provider=result.provider
        summary.text=listOfNotNull(doc.clean("release_date")?.take(4)?:item.year?.toString(),
            doc.number("runtime")?.takeIf{it>0}?.let{context.getString(R.string.movie_minutes,it.toInt())}?:provider.clean("duration")).joinToString(" · ")
        val lines=mutableListOf<String>()
        fun fact(label:Int,value:String?){if(!value.isNullOrBlank())lines+=context.getString(label)+": "+value}
        fun names(field:String)=doc.optJSONArray(field)?.objects()?.mapNotNull{it.clean("name")}?.joinToString(", ")?.takeIf{it.isNotEmpty()}
        fact(R.string.movie_original_title,doc.clean("original_title"))
        fact(R.string.movie_release,doc.clean("release_date")?:provider.clean("releasedate"))
        fact(R.string.movie_runtime,doc.number("runtime")?.takeIf{it>0}?.let{context.getString(R.string.movie_minutes,it.toInt())}?:provider.clean("duration"))
        fact(R.string.movie_genres,names("genres")?:provider.clean("genre"))
        val crew=doc.optJSONObject("credits")?.optJSONArray("crew")?.objects().orEmpty()
        fact(R.string.movie_director,crew.filter{it.optString("job")=="Director"}.mapNotNull{it.clean("name")}.distinct().joinToString(", ").ifBlank{provider.clean("director")})
        fact(R.string.movie_writer,crew.filter{it.optString("job") in listOf("Writer","Screenplay","Story")}.mapNotNull{it.clean("name")}.distinct().joinToString(", "))
        fact(R.string.movie_countries,names("production_countries"));fact(R.string.movie_companies,names("production_companies"))
        fact(R.string.movie_language,doc.clean("original_language"))
        val certification=doc.optJSONObject("release_dates")?.optJSONArray("results")?.objects()?.firstOrNull{it.optString("iso_3166_1")=="DE"}
            ?.optJSONArray("release_dates")?.objects()?.sortedBy{if(it.optInt("type")==3)0 else 1}?.firstNotNullOfOrNull{it.clean("certification")}
        fact(R.string.movie_certification,certification?.let{"DE · $it"})
        for((key,label) in listOf("budget" to R.string.movie_budget,"revenue" to R.string.movie_revenue))fact(label,doc.number(key)?.takeIf{it>0}?.let{java.text.NumberFormat.getIntegerInstance(locale).format(it)+" USD"})
        if(result.overview!=null&&doc.clean("overview_language")=="en-US")lines+=context.getString(R.string.metadata_english_fallback)
        facts.text=lines.joinToString("\n\n");facts.visibility=if(lines.isEmpty())GONE else VISIBLE
        val video=doc.optJSONObject("videos")?.optJSONArray("results")?.objects()?.firstOrNull{
            it.optString("site")=="YouTube"&&it.optString("type")=="Trailer"&&it.clean("key")?.matches(Regex("[A-Za-z0-9_-]{6,64}"))==true
        }
        trailerUrl=video?.clean("key")?.let{"https://www.youtube.com/watch?v=$it"};trailer.visibility=if(trailerUrl==null)GONE else VISIBLE
        castAdapter.submit(result.cast)
        castTitle.visibility=if(members.isEmpty())GONE else VISIBLE
        moreCast.visibility=if(members.size>castAdapter.limit)VISIBLE else GONE
        castList.post{updatePortraits()}
    }
    fun dispose(){artwork.unbind(poster);for(i in 0 until castList.childCount)(castList.getChildViewHolder(castList.getChildAt(i)) as? CastAdapter.Person)?.let{artwork.unbind(it.photo)}}
    private inner class CastAdapter:RecyclerView.Adapter<CastAdapter.Person>() {
        init{setHasStableIds(true)}
        var limit=12
        fun submit(value:List<JSONObject>){
            val previous=members.take(limit);val next=value.take(limit)
            val updates=androidx.recyclerview.widget.DiffUtil.calculateDiff(object:androidx.recyclerview.widget.DiffUtil.Callback(){
                override fun getOldListSize()=previous.size
                override fun getNewListSize()=next.size
                override fun areItemsTheSame(oldItemPosition:Int,newItemPosition:Int)=previous[oldItemPosition].optLong("id")==next[newItemPosition].optLong("id")
                override fun areContentsTheSame(oldItemPosition:Int,newItemPosition:Int)=previous[oldItemPosition].toString()==next[newItemPosition].toString()
            })
            members=value;updates.dispatchUpdatesTo(this)
        }
        override fun getItemId(position:Int)=members[position].optLong("id",position.toLong())
        inner class Person(val root:LinearLayout,val photo:ImageView,val name:TextView):RecyclerView.ViewHolder(root)
        override fun getItemCount()=minOf(limit,members.size)
        override fun onCreateViewHolder(parent:android.view.ViewGroup,viewType:Int):Person {
            val root=LinearLayout(context).apply{
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL
                layoutParams=RecyclerView.LayoutParams(-1,-2)
                setPaddingRelative(dp(8),dp(8),dp(8),dp(16));TvUi.focus(this,1f)
            }
            val photo=ImageView(context).apply{scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
            val name=TvUi.text(context,"",14f).apply{gravity=Gravity.CENTER;setLineSpacing(0f,1.4f);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
            root.addView(photo,LinearLayout.LayoutParams(dp(80),dp(120)))
            root.addView(name,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
            return Person(root,photo,name)
        }
        override fun onBindViewHolder(holder:Person,position:Int){val person=members[position];holder.name.text=listOfNotNull(person.clean("name"),person.clean("character")).joinToString("\n");holder.root.contentDescription=holder.name.text;holder.photo.setImageResource(android.R.drawable.ic_menu_gallery)}
        override fun onViewAttachedToWindow(holder:Person){super.onViewAttachedToWindow(holder);loadPortrait(holder)}
        fun loadPortrait(holder:Person){
            val position=holder.bindingAdapterPosition;if(position<0)return
            if(!holder.photo.getGlobalVisibleRect(portraitArea)){artwork.unbind(holder.photo);return}
            val person=members[position];val address=Portraits.address(person)
            artwork.bind(holder.photo,"person:${person.optLong("id")}:$address"){address}
        }
        override fun onViewDetachedFromWindow(holder:Person){artwork.unbind(holder.photo);super.onViewDetachedFromWindow(holder)}
        override fun onViewRecycled(holder:Person){artwork.unbind(holder.photo);super.onViewRecycled(holder)}
        fun more(){val start=itemCount;limit+=12;notifyItemRangeInserted(start,itemCount-start);moreCast.visibility=if(members.size>limit)VISIBLE else GONE;if(moreCast.isGone)castList.post{castList.findViewHolderForAdapterPosition(start)?.itemView?.requestFocus()}}
    }
}
internal fun android.content.res.Configuration.localesCompat():Locale=if(android.os.Build.VERSION.SDK_INT>=24)locales[0] else @Suppress("DEPRECATION") locale
internal fun ratingText(context:Context,rating:Rating?,locale:Locale,details:Boolean=false):String {
    if(rating==null)return context.getString(R.string.rating_missing)
    if(rating.status==RatingStatus.UNRATED)return context.getString(R.string.rating_unrated)
    val label=rating.text(locale).replace("Provider",context.getString(R.string.rating_provider))
    return label+if(details&&rating.votes!=null)" · "+context.getString(R.string.rating_votes,java.text.NumberFormat.getIntegerInstance(locale).format(rating.votes)) else ""
}
