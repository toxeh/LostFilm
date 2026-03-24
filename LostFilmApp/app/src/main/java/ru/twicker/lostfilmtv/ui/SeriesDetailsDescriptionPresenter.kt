package ru.twicker.lostfilmtv.ui

import androidx.leanback.widget.AbstractDetailsDescriptionPresenter
import ru.twicker.lostfilmtv.network.SeriesDetails

class SeriesDetailsDescriptionPresenter : AbstractDetailsDescriptionPresenter() {
    override fun onBindDescription(viewHolder: ViewHolder, item: Any) {
        val series = item as SeriesDetails
        
        viewHolder.title.text = series.titleRu
        viewHolder.subtitle.text = series.title
        viewHolder.body.text = series.description
    }
}
