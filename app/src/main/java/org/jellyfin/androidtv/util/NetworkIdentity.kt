package org.jellyfin.androidtv.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Identifies the connection a measurement was taken on.
 *
 * A measurement of the connection is only valid for the connection it was taken
 * on. A device that is moved from wifi to a cable keeps the speed of the wifi
 * connection otherwise, and the requested bitrate would stay at the value of
 * the slower connection.
 *
 * The identity consists of the transport of the connection, the name of the
 * network interface and the address of the connection. The address is part of
 * it because a device that connects to another access point keeps the same
 * interface but is on a different network.
 */
object NetworkIdentity {
	/**
	 * The identity of the connection that is currently used.
	 *
	 * @param context Used to read the state of the connections
	 * @return The identity or an empty string when there is no connection
	 */
	fun current(context: Context): String {
		val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return ""
		val network = connectivityManager.activeNetwork ?: return ""
		val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return ""

		val linkProperties = connectivityManager.getLinkProperties(network)
		val address = linkProperties?.linkAddresses?.firstOrNull()?.address?.hostAddress

		return buildString {
			append(transport(capabilities))
			append(':')
			append(linkProperties?.interfaceName ?: "unknown")

			if (!address.isNullOrEmpty()) {
				append(':')
				append(address)
			}
		}
	}

	/**
	 * The kind of the connection.
	 */
	private fun transport(capabilities: NetworkCapabilities): String = when {
		capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
		capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
		capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
		capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
		else -> "OTHER"
	}
}
