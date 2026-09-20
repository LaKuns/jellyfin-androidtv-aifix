package org.jellyfin.androidtv.ui.base

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import org.jellyfin.androidtv.util.PerformanceProfile

/**
 * `CompositingStrategy.Offscreen` renders the text into its own offscreen buffer before it is
 * composited. Text is a leaf node, so it never needs that for correct output, but the cost is an
 * extra layer allocation and render pass per text node - plus a re-allocation every time the text
 * animates. Screens routinely contain dozens of Text nodes, which makes this very expensive on
 * low-end TV GPUs, so it is skipped there.
 *
 * Kept as a single instance to avoid re-allocating the modifier for every Text call.
 */
private val offscreenTextLayer = Modifier.graphicsLayer {
	compositingStrategy = CompositingStrategy.Offscreen
}

@Composable
private fun textLayerModifier(): Modifier {
	val context = LocalContext.current
	val useOffscreenLayer = remember(context) {
		!PerformanceProfile.isLowPerformanceDevice(context)
	}

	return if (useOffscreenLayer) offscreenTextLayer else Modifier
}

@Composable
fun Text(
	text: String,
	modifier: Modifier = Modifier,
	color: Color = Color.Unspecified,
	fontSize: TextUnit = TextUnit.Unspecified,
	fontStyle: FontStyle? = null,
	fontWeight: FontWeight? = null,
	fontFamily: FontFamily? = null,
	letterSpacing: TextUnit = TextUnit.Unspecified,
	textDecoration: TextDecoration? = null,
	textAlign: TextAlign? = null,
	lineHeight: TextUnit = TextUnit.Unspecified,
	overflow: TextOverflow = TextOverflow.Clip,
	softWrap: Boolean = true,
	maxLines: Int = Int.MAX_VALUE,
	minLines: Int = 1,
	onTextLayout: (TextLayoutResult) -> Unit = {},
	style: TextStyle = LocalTextStyle.current
) {
	val textColor = color.takeOrElse { style.color.takeOrElse { Color.Black } }

	BasicText(
		text = text,
		modifier = modifier.then(textLayerModifier()),
		style = style.merge(
			color = textColor,
			fontSize = fontSize,
			fontWeight = fontWeight,
			textAlign = textAlign ?: TextAlign.Unspecified,
			lineHeight = lineHeight,
			fontFamily = fontFamily,
			textDecoration = textDecoration,
			fontStyle = fontStyle,
			letterSpacing = letterSpacing
		),
		onTextLayout = onTextLayout,
		overflow = overflow,
		softWrap = softWrap,
		maxLines = maxLines,
		minLines = minLines
	)
}

@Composable
fun Text(
	text: AnnotatedString,
	modifier: Modifier = Modifier,
	color: Color = Color.Unspecified,
	fontSize: TextUnit = TextUnit.Unspecified,
	fontStyle: FontStyle? = null,
	fontWeight: FontWeight? = null,
	fontFamily: FontFamily? = null,
	letterSpacing: TextUnit = TextUnit.Unspecified,
	textDecoration: TextDecoration? = null,
	textAlign: TextAlign? = null,
	lineHeight: TextUnit = TextUnit.Unspecified,
	overflow: TextOverflow = TextOverflow.Clip,
	softWrap: Boolean = true,
	maxLines: Int = Int.MAX_VALUE,
	minLines: Int = 1,
	inlineContent: Map<String, InlineTextContent> = mapOf(),
	onTextLayout: (TextLayoutResult) -> Unit = {},
	style: TextStyle = LocalTextStyle.current
) {
	val textColor = color.takeOrElse { style.color.takeOrElse { Color.Black } }

	BasicText(
		text = text,
		modifier = modifier.then(textLayerModifier()),
		style =
			style.merge(
				color = textColor,
				fontSize = fontSize,
				fontWeight = fontWeight,
				textAlign = textAlign ?: TextAlign.Unspecified,
				lineHeight = lineHeight,
				fontFamily = fontFamily,
				textDecoration = textDecoration,
				fontStyle = fontStyle,
				letterSpacing = letterSpacing
			),
		onTextLayout = onTextLayout,
		overflow = overflow,
		softWrap = softWrap,
		maxLines = maxLines,
		minLines = minLines,
		inlineContent = inlineContent
	)
}

val LocalTextStyle = compositionLocalOf(structuralEqualityPolicy()) { TypographyDefaults.Default }

@Composable
fun ProvideTextStyle(value: TextStyle, content: @Composable () -> Unit) {
	val mergedStyle = LocalTextStyle.current.merge(value)
	CompositionLocalProvider(LocalTextStyle provides mergedStyle, content = content)
}
