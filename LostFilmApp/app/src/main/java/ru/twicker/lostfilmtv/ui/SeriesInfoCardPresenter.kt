package ru.twicker.lostfilmtv.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.ImageView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import coil.request.ImageRequest
import coil.target.Target
import android.graphics.drawable.Drawable
import ru.twicker.lostfilmtv.network.SeriesDetails

/**
 * Presenter for series info card — shows poster, title (with ★ if favorite),
 * and description in a standard ImageCardView.
 */
class SeriesInfoCardPresenter : Presenter() {

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
        val details = item as? SeriesDetails ?: return
        val card = viewHolder.view as ImageCardView

        val favPrefix = if (details.isFavorite) "★ " else ""
        card.titleText = "${favPrefix}${details.titleRu}"
        card.contentText = "${details.title}\n${details.description}"
        card.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)

        // Load poster via Coil with ciadpi proxy
        if (details.posterUrl.isNotBlank()) {
            val imageView = card.mainImageView ?: return
            val loader = ru.twicker.lostfilmtv.network.CoilFactory.get(imageView.context)
            loader.enqueue(
                ImageRequest.Builder(imageView.context)
                    .data(details.posterUrl)
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
        // Larger card for info display — poster aspect ratio
        const val CARD_WIDTH = 240
        const val CARD_HEIGHT = 340
    }
}
