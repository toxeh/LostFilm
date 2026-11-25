package ru.twicker.lostfilmtv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import ru.twicker.lostfilmtv.network.SeriesDetails

/**
 * Full-text description presenter for SerieDetailsFragment.
 * Unlike Leanback's AbstractDetailsDescriptionPresenter (which caps body to 5 lines),
 * this renders the complete series description and allows smooth D-pad scrolling when
 * a description is taller than the overview card.
 */
class SeriesDetailsDescriptionPresenter : Presenter() {

    private class MaxHeightScrollView(
        context: Context,
        private val maxHeightPx: Int
    ) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cappedHeightSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, cappedHeightSpec)
        }
    }

    private class DescriptionViewHolder(
        root: View,
        val title: TextView,
        val subtitle: TextView,
        val scrollView: ScrollView,
        val body: TextView
    ) : ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(ctx).apply {
            setTextColor(Color.parseColor("#EEEEEE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val subtitle = TextView(ctx).apply {
            setTextColor(Color.parseColor("#B2EEEEEE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        root.addView(
            subtitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        )

        val scrollStep = dp(96)
        val scrollView = MaxHeightScrollView(ctx, maxHeightPx = dp(370)).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnKeyListener { v, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val sv = v as ScrollView
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (sv.canScrollVertically(1)) {
                            sv.smoothScrollBy(0, scrollStep)
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (sv.canScrollVertically(-1)) {
                            sv.smoothScrollBy(0, -scrollStep)
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
        }

        val body = TextView(ctx).apply {
            setTextColor(Color.parseColor("#E6EEEEEE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setLineSpacing(dp(3).toFloat(), 1.0f)
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setPadding(0, 0, dp(10), dp(12))
        }
        scrollView.addView(
            body,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )

        return DescriptionViewHolder(root, title, subtitle, scrollView, body)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val vh = viewHolder as DescriptionViewHolder
        val series = item as? SeriesDetails ?: return

        vh.title.text = series.titleRu.ifBlank { series.title }
        val showSubtitle = series.title.isNotBlank() &&
            !series.title.equals(series.titleRu, ignoreCase = true)
        vh.subtitle.visibility = if (showSubtitle) View.VISIBLE else View.GONE
        vh.subtitle.text = series.title
        vh.body.text = series.description
        vh.scrollView.scrollTo(0, 0)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        // Nothing to release
    }
}
