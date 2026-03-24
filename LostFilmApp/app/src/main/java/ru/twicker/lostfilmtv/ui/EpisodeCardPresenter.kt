package ru.twicker.lostfilmtv.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import coil.Coil
import coil.request.ImageRequest
import coil.target.Target
import android.graphics.drawable.Drawable
import ru.twicker.lostfilmtv.R
import ru.twicker.lostfilmtv.network.Episode

/**
 * Leanback Presenter for episode cards — based on decompiled EpisodePresenter.java
 * Uses ImageCardView (standard Leanback card with image + title + content)
 * with a watched eye icon overlay in top-right corner when episode is watched.
 */
class EpisodeCardPresenter(
    private val imageHost: String = "https://lostfilm.tv"
) : Presenter() {

    /** Holder that keeps reference to the overlay icon */
    class EpisodeViewHolder(
        val wrapper: FrameLayout,
        val card: ImageCardView,
        val watchedIcon: ImageView
    ) : ViewHolder(wrapper)

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val card = ImageCardView(ctx).apply {
            isFocusable = true
            isFocusableInTouchMode = false
            setCardType(ImageCardView.CARD_TYPE_INFO_UNDER_WITH_EXTRA)
            setInfoVisibility(ImageCardView.CARD_REGION_VISIBLE_ALWAYS)
            setInfoAreaBackgroundColor(Color.parseColor("#D6000000"))
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
            mainImageView?.scaleType = ImageView.ScaleType.FIT_XY
        }

        // Watched eye icon in top-right corner
        val watchedIcon = ImageView(ctx).apply {
            setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_watched)?.mutate()?.also {
                it.setTint(Color.parseColor("#CC745ec5"))
            })
            visibility = ImageView.GONE
        }
        val iconSize = (24 * ctx.resources.displayMetrics.density).toInt()
        val iconMargin = (6 * ctx.resources.displayMetrics.density).toInt()
        val iconParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, iconMargin, iconMargin, 0)
        }

        val wrapper = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(card)
            addView(watchedIcon, iconParams)
        }

        return EpisodeViewHolder(wrapper, card, watchedIcon)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val episode = item as? Episode ?: return
        val holder = viewHolder as EpisodeViewHolder
        val card = holder.card

        card.titleText = episode.title
        card.contentText = buildString {
            if (episode.episodeInfo.isNotEmpty()) append(episode.episodeInfo)
            if (episode.date.isNotEmpty()) {
                if (isNotEmpty()) append(" • ")
                append(episode.date)
            }
        }
        card.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)

        // Show/hide watched icon
        holder.watchedIcon.visibility = if (episode.isWatched) ImageView.VISIBLE else ImageView.GONE

        // Load poster via Coil with ciadpi proxy
        val imageUrl = episode.imageUrl
        if (imageUrl.isNotEmpty()) {
            val imageView = card.mainImageView ?: return
            val loader = ru.twicker.lostfilmtv.network.CoilFactory.get(imageView.context)
            loader.enqueue(
                ImageRequest.Builder(imageView.context)
                    .data(imageUrl)
                    .crossfade(true)
                    .target(object : Target {
                        override fun onStart(placeholder: Drawable?) {
                            card.mainImage = ColorDrawable(Color.DKGRAY)
                        }
                        override fun onSuccess(result: Drawable) {
                            card.mainImage = result
                        }
                        override fun onError(error: Drawable?) {
                            card.mainImage = ColorDrawable(Color.DKGRAY)
                        }
                    })
                    .build()
            )
        } else {
            card.mainImage = ColorDrawable(Color.DKGRAY)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val holder = viewHolder as EpisodeViewHolder
        holder.card.mainImage = ColorDrawable(Color.DKGRAY)
        holder.watchedIcon.visibility = ImageView.GONE
    }

    companion object {
        // approx 16:9 ratio, scaled for TV
        const val CARD_WIDTH = 313
        const val CARD_HEIGHT = 176
    }
}

