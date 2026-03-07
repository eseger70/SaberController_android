package com.eseger70.sabercontroller.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

class SectionedListAdapter<T>(
    context: Context,
    private val labelProvider: (T) -> String,
    private val headerProvider: (T) -> Boolean,
    private val enabledProvider: (T) -> Boolean
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private val horizontalPadding = dp(context, 16)
    private val nestedPadding = dp(context, 32)
    private val verticalPadding = dp(context, 12)
    private val selectedColor = 0x1F2196F3

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

        textView.text = labelProvider(item)
        textView.setTypeface(null, if (isHeader) Typeface.BOLD else Typeface.NORMAL)
        textView.alpha = if (isEnabled || isHeader) 1.0f else 0.6f
        textView.setPadding(
            if (isHeader) horizontalPadding else nestedPadding,
            verticalPadding,
            horizontalPadding,
            verticalPadding
        )
        itemView.isActivated = position == selectedPosition
        itemView.setBackgroundColor(if (position == selectedPosition) selectedColor else 0)
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
