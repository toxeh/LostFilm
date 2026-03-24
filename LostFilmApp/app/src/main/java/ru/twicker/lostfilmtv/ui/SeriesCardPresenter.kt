package ru.twicker.lostfilmtv.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.ImageView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import coil.request.ImageRequest
import coil.target.Target
import ru.twicker.lostfilmtv.network.CoilFactory
import ru.twicker.lostfilmtv.network.Series

/**
 * Leanback Presenter for series cards in search results.
 * Shows series icon, Russian title and English title.
 */
class SeriesCardPresenter : Presenter() {

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
        val series = item as? Series ?: return
        val card = viewHolder.view as ImageCardView

        card.titleText = series.titleRu.ifBlank { series.title }
        card.contentText = series.title
        card.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)

        val imageUrl = series.poster
        if (imageUrl.isNotEmpty()) {
            val imageView = card.mainImageView ?: return
            val loader = CoilFactory.get(imageView.context)
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
        const val CARD_WIDTH = 176
        const val CARD_HEIGHT = 248
    }
}
