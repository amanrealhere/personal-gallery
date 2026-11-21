package com.amanrealhere.personalgallery.ui.components

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.amanrealhere.personalgallery.R
import com.amanrealhere.personalgallery.data.local.MediaItemEntity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import java.io.File

/**
 * RecyclerView-backed media grid.
 * Keeps Room/Paging3 for large albums while using recycled native Views for cells.
 * Each Glide request is keyed by source identity and media metadata.
 */
class MediaGridPagingAdapter :
    PagingDataAdapter<MediaItemEntity, MediaGridPagingAdapter.MediaViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): MediaViewHolder =
        MediaViewHolder(MediaGridItemView(parent.context))

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val media = getItem(position)
        if (media != null) holder.bind(media) else holder.clear()
    }

    override fun onViewRecycled(holder: MediaViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    /** Requests Paging's access hint for an item without exposing PagingDataAdapter.getItem(). */
    fun requestLoad(position: Int) {
        if (position in 0 until itemCount) getItem(position)
    }

    class MediaViewHolder(private val cell: MediaGridItemView) : RecyclerView.ViewHolder(cell) {
        fun bind(media: MediaItemEntity) = cell.bind(media)
        fun clear() = cell.clear()
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MediaItemEntity>() {
            override fun areItemsTheSame(oldItem: MediaItemEntity, newItem: MediaItemEntity) =
                oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MediaItemEntity, newItem: MediaItemEntity) =
                oldItem == newItem
        }
    }
}

class MediaGridItemView(context: android.content.Context) : FrameLayout(context) {
    private val thumbnail = ImageView(context)
    private val filename = TextView(context)
    private var currentRequestKey: String? = null

    init {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(0xFF202020.toInt())
            setStroke(dp(1), 0xFF353535.toInt())
        }
        clipToOutline = true

        thumbnail.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1B1B1B.toInt())
        }
        addView(thumbnail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        filename.apply {
            setTextColor(0xFFEAEAEA.toInt())
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), dp(6), dp(8), dp(7))
            setShadowLayer(dp(1).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0xD9000000.toInt())
            )
        }
        addView(
            filename,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM)
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY))
        setMeasuredDimension(width, width)
    }

    fun bind(media: MediaItemEntity) {
        filename.text = media.displayName
        val cacheKey = "${media.sourceCanonicalPath}|${media.fileSizeBytes}|" +
            "${media.modifiedAtEpochMillis}|grid-center-crop-224dp-v2"
        val requestKey = "${media.sourcePath}|$cacheKey"
        if (currentRequestKey == requestKey) return

        currentRequestKey = requestKey
        Glide.with(thumbnail)
            .load(File(media.sourcePath))
            .signature(ObjectKey(cacheKey))
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .override((224f * resources.displayMetrics.density).toInt())
            .centerCrop()
            .dontAnimate()
            .placeholder(android.graphics.drawable.ColorDrawable(0xFF242424.toInt()))
            .error(R.drawable.ic_gallery_folder)
            .into(thumbnail)
    }

    fun clear() {
        Glide.with(thumbnail).clear(thumbnail)
        thumbnail.setImageDrawable(null)
        filename.text = ""
        currentRequestKey = null
    }
}
