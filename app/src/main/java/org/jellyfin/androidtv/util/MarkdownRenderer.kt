package org.jellyfin.androidtv.util

import android.content.Context
import android.text.Spanned
import android.util.LruCache
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin

class MarkdownRenderer(context: Context) {
	private val markwon = Markwon.builder(context)
		.usePlugin(HtmlPlugin.create())
		.build()
	private val cache = LruCache<String, Spanned>(16)

	/**
	 * Convert string with markdown and HTML to a [Spanned].
	 */
	fun toMarkdownSpanned(input: String): Spanned =
		cache.get(input) ?: markwon.toMarkdown(input).also { cache.put(input, it) }
}
