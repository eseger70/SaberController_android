package com.eseger70.sabercontroller.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.eseger70.sabercontroller.R
import java.util.Locale

class SectionedListAdapter<T>(
    context: Context,
    private val labelProvider: (T) -> String,
    private val subtitleProvider: (T) -> String? = { null },
    private val headerProvider: (T) -> Boolean,
    private val enabledProvider: (T) -> Boolean,
    private val levelProvider: (T) -> Int = { 0 },
    private val headerStateProvider: (T) -> HeaderState? = { null }
) : BaseAdapter() {
    data class HeaderState(
        val expanded: Boolean,
        val childCount: Int
    )

    private val context = context
    private val inflater = LayoutInflater.from(context)
    private val horizontalPadding = dp(context, 16)
    private val nestedPadding = dp(context, 32)
    private val indentStep = dp(context, 18)
    private val verticalPadding = dp(context, 13)
    private val primaryTextColor = ContextCompat.getColor(context, R.color.app_text_primary)
    private val secondaryTextColor = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val headerTextColor = ContextCompat.getColor(context, R.color.app_header_text)

    var items: List<T> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var selectedPosition: Int = -1
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): T = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun areAllItemsEnabled(): Boolean = false

    override fun isEnabled(position: Int): Boolean = enabledProvider(getItem(position))

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val itemView = convertView ?: inflater.inflate(
            R.layout.item_sectioned_row,
            parent,
            false
        )
        val textView = itemView.findViewById<TextView>(R.id.textRowLabel)
        val subtitleView = itemView.findViewById<TextView>(R.id.textRowSubtitle)
        val indicatorView = itemView.findViewById<TextView>(R.id.textRowIndicator)
        val countView = itemView.findViewById<TextView>(R.id.textRowCount)
        val container = itemView.findViewById<View>(R.id.rowContainer)
        val item = getItem(position)
        val isHeader = headerProvider(item)
        val isEnabled = enabledProvider(item)
        val level = levelProvider(item).coerceAtLeast(0)
        val headerState = headerStateProvider(item)

        val rawLabel = labelProvider(item)
        textView.text = rawLabel
        textView.setTypeface(null, if (isHeader) Typeface.BOLD else Typeface.NORMAL)
        textView.setAllCaps(false)
        textView.alpha = if (isEnabled || isHeader) 1.0f else 0.6f
        textView.setTextColor(
            when {
                isHeader -> headerTextColor
                isEnabled -> primaryTextColor
                else -> secondaryTextColor
            }
        )
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isHeader) 14f else 16f)
        textView.letterSpacing = if (isHeader) 0.02f else 0.01f
        val subtitle = subtitleProvider(item)?.takeIf { it.isNotBlank() }
        if (subtitle != null) {
            subtitleView.visibility = View.VISIBLE
            subtitleView.text = subtitle
            subtitleView.alpha = if (isEnabled || isHeader) 0.95f else 0.6f
            subtitleView.setTextColor(secondaryTextColor)
        } else {
            subtitleView.visibility = View.GONE
            subtitleView.text = ""
        }
        val leftPadding = (if (isHeader) horizontalPadding else nestedPadding) + (level * indentStep)
        container.setPadding(
            leftPadding,
            verticalPadding,
            horizontalPadding,
            verticalPadding
        )
        itemView.isActivated = position == selectedPosition
        container.background = AppCompatResources.getDrawable(
            context,
            when {
                position == selectedPosition -> R.drawable.bg_list_row_selected
                isHeader -> R.drawable.bg_list_row_header
                else -> R.drawable.bg_list_row
            }
        )
        if (isHeader) {
            indicatorView.visibility = View.VISIBLE
            indicatorView.text = if (headerState?.expanded == true) "v" else ">"
            indicatorView.alpha = if ((headerState?.childCount ?: 0) > 0) 1.0f else 0.45f
            indicatorView.background = AppCompatResources.getDrawable(
                context,
                if (position == selectedPosition) R.drawable.bg_list_row_indicator_active
                else R.drawable.bg_list_row_indicator
            )
            val childCount = headerState?.childCount ?: 0
            if (childCount > 0) {
                countView.visibility = View.VISIBLE
                countView.text = childCount.toString()
            } else {
                countView.visibility = View.GONE
                countView.text = ""
            }
        } else {
            indicatorView.visibility = View.GONE
            countView.visibility = View.GONE
            countView.text = ""
        }
        itemView.isEnabled = isEnabled
        return itemView
    }

    companion object {
        private fun dp(context: Context, value: Int): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }
    }
}
