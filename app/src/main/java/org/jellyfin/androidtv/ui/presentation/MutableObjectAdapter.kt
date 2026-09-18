package org.jellyfin.androidtv.ui.presentation

import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A leanback ObjectAdapter using a Kotlin list as backend. Implements Iterable to allow collection
 * operations. And uses generics for strong typing. Uses a MutableList as internal structure.
 */
open class MutableObjectAdapter<T : Any> : ObjectAdapter, Iterable<T> {
	private val data = mutableListOf<T>()
	private var mutationGeneration = 0L

	// Constructors
	constructor(presenterSelector: PresenterSelector) : super(presenterSelector)
	constructor(presenter: Presenter) : super(presenter)
	constructor() : super()

	// ObjectAdapter
	override fun size(): Int = data.size
	override fun get(index: Int): T? = data.getOrNull(index)

	// Iterable
	override fun iterator(): Iterator<T> = data.iterator()

	// Custom
	fun add(element: T) {
		mutationGeneration++
		data.add(element)
		notifyItemRangeInserted(data.size - 1, 1)
	}

	fun addAll(elements: Collection<T>) {
		if (elements.isEmpty()) return

		mutationGeneration++
		val start = data.size
		data.addAll(elements)
		notifyItemRangeInserted(start, elements.size)
	}

	fun add(index: Int, element: T) {
		mutationGeneration++
		data.add(index, element)
		notifyItemRangeInserted(index, 1)
	}

	fun set(index: Int, element: T) {
		mutationGeneration++
		data.set(index, element)
		notifyItemRangeChanged(index, 1)
	}

	fun replaceAll(
		items: List<T>,
		areItemsTheSame: (old: T, new: T) -> Boolean = { old, new -> old == new },
		areContentsTheSame: (old: T, new: T) -> Boolean = { old, new -> old == new },
	) {
		val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
			override fun getOldListSize(): Int = data.size
			override fun getNewListSize(): Int = items.size

			override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
				areItemsTheSame(data[oldItemPosition], items[newItemPosition])

			override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
				areContentsTheSame(data[oldItemPosition], items[newItemPosition])
		})

		mutationGeneration++
		data.clear()
		data.addAll(items)

		diff.dispatchUpdatesTo(object : ListUpdateCallback {
			override fun onInserted(position: Int, count: Int) = notifyItemRangeInserted(position, count)
			override fun onRemoved(position: Int, count: Int) = notifyItemRangeRemoved(position, count)
			override fun onMoved(fromPosition: Int, toPosition: Int) = notifyItemMoved(fromPosition, toPosition)
			override fun onChanged(position: Int, count: Int, payload: Any?) = notifyItemRangeChanged(position, count, payload)
		})
	}

	/**
	 * Calculates an expensive list diff away from the UI thread. The actual
	 * adapter mutation and notifications are still performed on the main
	 * thread, and stale results are discarded if another update won the race.
	 */
	suspend fun replaceAllAsync(
		items: List<T>,
		areItemsTheSame: (old: T, new: T) -> Boolean = { old, new -> old == new },
		areContentsTheSame: (old: T, new: T) -> Boolean = { old, new -> old == new },
	): Boolean {
		val oldItems = data.toList()
		val generation = mutationGeneration
		val diff = withContext(Dispatchers.Default) {
			DiffUtil.calculateDiff(object : DiffUtil.Callback() {
				override fun getOldListSize(): Int = oldItems.size
				override fun getNewListSize(): Int = items.size

				override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
					areItemsTheSame(oldItems[oldItemPosition], items[newItemPosition])

				override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
					areContentsTheSame(oldItems[oldItemPosition], items[newItemPosition])
			})
		}

		return withContext(Dispatchers.Main.immediate) {
			if (generation != mutationGeneration) return@withContext false

			mutationGeneration++
			data.clear()
			data.addAll(items)
			diff.dispatchUpdatesTo(object : ListUpdateCallback {
				override fun onInserted(position: Int, count: Int) = notifyItemRangeInserted(position, count)
				override fun onRemoved(position: Int, count: Int) = notifyItemRangeRemoved(position, count)
				override fun onMoved(fromPosition: Int, toPosition: Int) = notifyItemMoved(fromPosition, toPosition)
				override fun onChanged(position: Int, count: Int, payload: Any?) = notifyItemRangeChanged(position, count, payload)
			})
			true
		}
	}

	fun clear() {
		val size = data.size
		if (size == 0) return

		mutationGeneration++
		notifyItemRangeRemoved(0, size)
		data.clear()
	}

	fun remove(element: T): Boolean {
		val index = indexOf(element)
		if (index == -1) return false
		return removeAt(index, 1)
	}

	fun removeAt(index: Int, length: Int = 1): Boolean {
		if (index < 0 || index >= data.size) return false

		mutationGeneration++
		data.subList(index, index + length).clear()
		notifyItemRangeRemoved(index, length)

		return true
	}

	fun indexOf(item: T) = data.indexOf(item)
}
