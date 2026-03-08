package com.eseger70.sabercontroller.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.eseger70.sabercontroller.R
import java.util.Locale

class SectionedListAdapter<T>(
    context: Context,
    private val labelProvider: (T) -> String,
    private val headerProvider: (T) -> Boolean,
    private val enabledProvider: (T) -> Boolean,
    private val levelProvider: (T) -> Int = { 0 }
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private val horizontalPadding = dp(context, 16)
    private val nestedPadding = dp(context, 32)
    private val indentStep = dp(context, 18)
    private val verticalPadding = dp(context, 12)
    private val selectedColor = ContextCompat.getColor(context, R.color.app_selected_row)
    private val headerRowColor = ContextCompat.getColor(context, R.color.app_header_row)
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
            android.R.layout.simple_list_item_activated_1,
            parent,
            false
        )
        val textView = itemView.findViewById<TextView>(android.R.id.text1)
        val item = getItem(position)
        val isHeader = headerProvider(item)
        val isEnabled = enabledProvider(item)
        val level = levelProvider(item).coerceAtLeast(0)

        val rawLabel = labelProvider(item)
        textView.text = if (isHeader) rawLabel.uppercase(Locale.getDefault()) else rawLabel
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
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isHeader) 12f else 16f)
        textView.letterSpacing = if (isHeader) 0.08f else 0.01f
        val leftPadding = (if (isHeader) horizontalPadding else nestedPadding) + (level * indentStep)
        textView.setPadding(
            leftPadding,
            verticalPadding,
            horizontalPadding,
            verticalPadding
        )
        itemView.isActivated = position == selectedPosition
        itemView.setBackgroundColor(
            when {
                position == selectedPosition -> selectedColor
                isHeader -> headerRowColor
                else -> 0
            }
        )
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
