package com.xyoye.local_component.ui.metadata

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.xyoye.local_component.R

data class MetadataCard(
    val key: String,
    val title: String,
    val subtitle: String = "",
    val image: String? = null
)

class MetadataCardAdapter(
    private val posters: Boolean = false,
    private val compact: Boolean = false,
    private val click: (MetadataCard) -> Unit,
    private val longClick: ((MetadataCard) -> Unit)? = null
) : ListAdapter<MetadataCard, MetadataCardAdapter.Holder>(
        object : DiffUtil.ItemCallback<MetadataCard>() {
            override fun areItemsTheSame(
                old: MetadataCard,
                new: MetadataCard
            ) = old.key == new.key

            override fun areContentsTheSame(
                old: MetadataCard,
                new: MetadataCard
            ) = old == new
        }
    ) {
    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        type: Int
    ): Holder {
        val view =
            LayoutInflater
                .from(
                    parent.context
                ).inflate(if (posters) R.layout.item_metadata_poster else R.layout.item_metadata_row, parent, false)
        if (compact) {
            view.layoutParams.width = (280 * parent.resources.displayMetrics.density).toInt()
            view.minimumHeight = (76 * parent.resources.displayMetrics.density).toInt()
        }
        return Holder(view).also { if (compact) it.subtitle.maxLines = 1 }
    }

    override fun onBindViewHolder(
        holder: Holder,
        position: Int
    ) {
        val card = getItem(position)
        holder.title.text = card.title
        holder.subtitle.text = card.subtitle
        holder.image.load(card.image) {
            placeholder(R.drawable.ic_local_file)
            error(R.drawable.ic_local_file)
        }
        holder.itemView.setOnClickListener { click(card) }
        holder.itemView.setOnLongClickListener {
            longClick?.invoke(card)
            longClick != null
        }
    }

    class Holder(
        view: View
    ) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.card_title)
        val subtitle: TextView = view.findViewById(R.id.card_subtitle)
        val image: ImageView = view.findViewById(R.id.card_image)
    }
}
