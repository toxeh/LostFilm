package ru.twicker.lostfilmtv.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.ImageView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import coil.Coil
import coil.request.ImageRequest
import coil.target.Target
import android.graphics.drawable.Drawable
import ru.twicker.lostfilmtv.network.Episode

/**
 * Leanback Presenter for episode cards — based on decompiled EpisodePresenter.java
 * Uses ImageCardView (standard Leanback card with image + title + content)
 */
class EpisodeCardPresenter(
    private val imageHost: String = "https://lostfilm.tv"
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = false
            setCardType(ImageCardView.CARD_TYPE_INFO_UNDER_WITH_EXTRA)
            setInfoVisibility(ImageCardView.CARD_REGION_VISIBLE_ALWAYS)
            setInfoAreaBackgroundColor(Color.parseColor("#D6000000"))
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
            mainImageView?.scaleType = ImageView.ScaleType.FIT_XY
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val episode = item as? Episode ?: return
        val card = viewHolder.view as ImageCardView

        card.titleText = episode.title
        card.contentText = buildString {
            if (episode.episodeInfo.isNotEmpty()) append(episode.episodeInfo)
            if (episode.date.isNotEmpty()) {
                if (isNotEmpty()) append(" • ")
                append(episode.date)
            }
        }
        card.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)

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
        val card = viewHolder.view as ImageCardView
        card.mainImage = ColorDrawable(Color.DKGRAY)
    }

    companion object {
        // approx 16:9 ratio, scaled for TV
        const val CARD_WIDTH = 313
        const val CARD_HEIGHT = 176
    }
}
