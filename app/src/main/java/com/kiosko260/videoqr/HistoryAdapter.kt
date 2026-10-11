package com.kiosko260.videoqr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kiosko260.videoqr.databinding.ItemVideoBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Cuadrícula de la pestaña "Procesados": miniatura + número/hora. Al tocar uno se regenera su QR. */
class HistoryAdapter(
    private val scope: CoroutineScope,
    private val thumbnailFor: (String) -> File?,
    private val labelFor: (ProcessedVideo) -> String,
    private val onClick: (ProcessedVideo) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

    private var items: List<ProcessedVideo> = emptyList()
    private val cache = LruCache<String, Bitmap>(60)

    fun submit(videos: List<ProcessedVideo>) {
        items = videos
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val video = items[position]
        holder.binding.label.text = labelFor(video).ifEmpty { video.id }
        holder.binding.root.setOnClickListener { onClick(video) }
        holder.job?.cancel()
        val cached = cache.get(video.id)
        holder.binding.thumbnail.setImageBitmap(cached)
        if (cached == null) {
            holder.job = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    thumbnailFor(video.id)?.let { BitmapFactory.decodeFile(it.absolutePath) }
                }
                if (bitmap != null) {
                    cache.put(video.id, bitmap)
                    holder.binding.thumbnail.setImageBitmap(bitmap)
                }
            }
        }
    }

    class Holder(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root) {
        var job: Job? = null
    }
}
