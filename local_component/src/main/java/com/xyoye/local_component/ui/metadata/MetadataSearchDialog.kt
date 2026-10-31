package com.xyoye.local_component.ui.metadata

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.collectAtStarted
import com.xyoye.common_component.extension.vertical
import com.xyoye.local_component.databinding.DialogMetadataSearchBinding

@Route(path = RouteTable.Local.MetadataSearch)
class MetadataSearchDialog : DialogFragment() {
    private var binding: DialogMetadataSearchBinding? = null
    private var current = MetadataLibraryState()
    private val cards =
        MetadataCardAdapter(searchResults = true, click = { card ->
            ARouter
                .getInstance()
                .build(RouteTable.Local.MetadataDetail)
                .withString("kind", card.key.substringBefore(':'))
                .withLong("tmdbId", card.key.substringAfter(':').toLong())
                .navigation()
        })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        DialogMetadataSearchBinding
            .inflate(inflater, container, false)
            .also {
                binding = it
            }.root

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        val content = requireNotNull(binding)
        content.searchResults.apply {
            layoutManager = vertical()
            adapter = cards
            setFocusOutAllowed(true, true)
        }
        content.searchQuery.addTextChangedListener { render() }
        content.searchQuery.setOnKeyListener { _, key, event ->
            if (key != android.view.KeyEvent.KEYCODE_DPAD_DOWN) return@setOnKeyListener false
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (content.searchResults.isVisible) content.searchResults.requestFocus() else content.close.requestFocus()
            }
            true
        }
        content.close.setOnClickListener { dismiss() }
        ViewModelProvider(this)[MetadataLibraryViewModel::class.java].state.collectAtStarted(viewLifecycleOwner) {
            current = it
            render()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((440 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.6f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            WindowCompat.getInsetsController(this, decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        binding?.searchQuery?.requestFocus()
    }

    private fun render() {
        val content = binding ?: return
        val query =
            content.searchQuery.text
                .toString()
                .trim()
        val results =
            current.works
                .filter {
                    query.isBlank() || it.title.contains(query, true) || it.originalTitle.contains(query, true)
                }.map {
                    MetadataCard(
                        "${it.mediaKind}:${it.tmdbId}",
                        it.title,
                        "${it.releaseDate.take(4)} · ${if (it.mediaKind == "tv") "剧集 / 番剧" else "电影"}",
                        it.backdrop ?: it.poster
                    )
                }
        if (results.isEmpty() && content.searchResults.hasFocus()) content.searchQuery.requestFocus()
        cards.submitList(results)
        content.searchResults.isVisible = results.isNotEmpty()
        content.empty.isVisible = results.isEmpty()
        content.empty.text = if (query.isBlank()) "媒体库中还没有作品" else "没有找到相关作品"
    }

    override fun onDestroyView() {
        binding?.searchResults?.adapter = null
        binding = null
        super.onDestroyView()
    }
}
