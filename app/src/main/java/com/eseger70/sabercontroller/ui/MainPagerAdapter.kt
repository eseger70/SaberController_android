package com.eseger70.sabercontroller.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.eseger70.sabercontroller.databinding.PageSaberBinding
import com.eseger70.sabercontroller.databinding.PageTracksBinding

class MainPagerAdapter(
    private val onSaberPageBound: (PageSaberBinding) -> Unit,
    private val onMusicPageBound: (PageTracksBinding) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount(): Int = 2

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SABER -> SaberPageViewHolder(
                PageSaberBinding.inflate(inflater, parent, false)
            )

            else -> MusicPageViewHolder(
                PageTracksBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SaberPageViewHolder -> onSaberPageBound(holder.binding)
            is MusicPageViewHolder -> onMusicPageBound(holder.binding)
        }
    }

    private class SaberPageViewHolder(
        val binding: PageSaberBinding
    ) : RecyclerView.ViewHolder(binding.root)

    private class MusicPageViewHolder(
        val binding: PageTracksBinding
    ) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val VIEW_TYPE_SABER = 0
    }
}
