package com.eseger70.sabercontroller.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.eseger70.sabercontroller.databinding.PageEffectsBinding
import com.eseger70.sabercontroller.databinding.PageLogBinding
import com.eseger70.sabercontroller.databinding.PageSaberBinding
import com.eseger70.sabercontroller.databinding.PageStylesBinding
import com.eseger70.sabercontroller.databinding.PageTracksBinding

class MainPagerAdapter(
    private val onSaberPageBound: (PageSaberBinding) -> Unit,
    private val onTracksPageBound: (PageTracksBinding) -> Unit,
    private val onStylesPageBound: (PageStylesBinding) -> Unit,
    private val onEffectsPageBound: (PageEffectsBinding) -> Unit,
    private val onLogPageBound: (PageLogBinding) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount(): Int = 5

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SABER -> SaberPageViewHolder(
                PageSaberBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_TRACKS -> TracksPageViewHolder(
                PageTracksBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_STYLES -> StylesPageViewHolder(
                PageStylesBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_EFFECTS -> EffectsPageViewHolder(
                PageEffectsBinding.inflate(inflater, parent, false)
            )

            else -> LogPageViewHolder(
                PageLogBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SaberPageViewHolder -> onSaberPageBound(holder.binding)
            is TracksPageViewHolder -> onTracksPageBound(holder.binding)
            is StylesPageViewHolder -> onStylesPageBound(holder.binding)
            is EffectsPageViewHolder -> onEffectsPageBound(holder.binding)
            is LogPageViewHolder -> onLogPageBound(holder.binding)
        }
    }

    private class SaberPageViewHolder(
        val binding: PageSaberBinding
    ) : RecyclerView.ViewHolder(binding.root)

    private class TracksPageViewHolder(
        val binding: PageTracksBinding
    ) : RecyclerView.ViewHolder(binding.root)

    private class StylesPageViewHolder(
        val binding: PageStylesBinding
    ) : RecyclerView.ViewHolder(binding.root)

    private class EffectsPageViewHolder(
        val binding: PageEffectsBinding
    ) : RecyclerView.ViewHolder(binding.root)

    private class LogPageViewHolder(
        val binding: PageLogBinding
    ) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val VIEW_TYPE_SABER = 0
        private const val VIEW_TYPE_TRACKS = 1
        private const val VIEW_TYPE_STYLES = 2
        private const val VIEW_TYPE_EFFECTS = 3
    }
}
