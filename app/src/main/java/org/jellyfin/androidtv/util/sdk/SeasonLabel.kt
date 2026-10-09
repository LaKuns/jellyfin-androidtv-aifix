package org.jellyfin.androidtv.util.sdk

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.BaseItemDto

private val CHINESE_SEASON_DIGITS = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")

/** Use the season number instead of a server name that may repeat the series title. */
fun episodeSeasonLabel(context: Context, season: BaseItemDto, seasonItems: List<BaseItemDto> = emptyList()): String {
	if (season.indexNumber == 0) return season.name.orEmpty()
	val number = season.indexNumber?.takeIf { it > 0 }
		?: seasonItems.filter { it.indexNumber != 0 }.indexOfFirst { it.id == season.id }
			.takeIf { it >= 0 }?.plus(1)
		?: return season.name.orEmpty()
	val language = context.resources.configuration.locale.language
	val value = if ((language == "zh" || language == "yue") && number in 1..99) {
		val tens = number / 10
		val ones = number % 10
		when {
			number < 10 -> CHINESE_SEASON_DIGITS[number]
			tens == 1 -> "十" + if (ones == 0) "" else CHINESE_SEASON_DIGITS[ones]
			else -> CHINESE_SEASON_DIGITS[tens] + "十" + if (ones == 0) "" else CHINESE_SEASON_DIGITS[ones]
		}
	} else number.toString()
	return context.getString(R.string.lbl_episode_picker_season_number, value)
}
