package com.xyoye.local_component.ui.metadata

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.xyoye.local_component.databinding.ItemMetadataEpisodeBinding

data class MetadataEpisodeCard(
    val card: MetadataCard,
    val number: String,
    val lastWatched: Boolean
)

class MetadataEpisodeAdapter(
    private val click: (String) -> Unit
) : ListAdapter<MetadataEpisodeCard, MetadataEpisodeAdapter.Holder>(
        object : DiffUtil.ItemCallback<MetadataEpisodeCard>() {
            override fun areItemsTheSame(
                oldItem: MetadataEpisodeCard,
                newItem: MetadataEpisodeCard
            ) = oldItem.card.key == newItem.card.key

            override fun areContentsTheSame(
                oldItem: MetadataEpisodeCard,
                newItem: MetadataEpisodeCard
            ) = oldItem == newItem
        }
    ) {
    var upTarget: Int = 0

    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ) = Holder(ItemMetadataEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        holder: Holder,
        position: Int
    ) {
        val item = getItem(position)
        holder.binding.apply {
            cardTitle.text = item.card.title
            cardSubtitle.text = item.card.subtitle
            episodeNumber.text = item.number
            cardImage.load(item.card.image)
            cardProgress.isVisible = item.card.progress != null
            cardProgress.progress = item.card.progress ?: 0
            lastWatched.isVisible = item.lastWatched
            root.contentDescription =
                listOf(item.card.title, item.card.subtitle, if (item.lastWatched) "上次观看" else "")
                    .filter { it.isNotBlank() }
                    .joinToString("，")
            root.nextFocusUpId = upTarget
            root.setOnClickListener { click(item.card.key) }
        }
    }

    class Holder(
        val binding: ItemMetadataEpisodeBinding
    ) : RecyclerView.ViewHolder(binding.root)
}
