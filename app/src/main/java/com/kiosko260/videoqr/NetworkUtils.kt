package com.kiosko260.videoqr

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * IP local del kiosko en la red Wi-Fi (wlan0) o en su propio hotspot (ap0/swlan0...).
     * Devuelve null si no hay red local.
     */
    fun localIpAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { nic.name to it.hostAddress }
            }
            .sortedBy { (name, _) ->
                when {
                    name == "wlan0" -> 0
                    name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") -> 1
                    name.startsWith("rmnet") || name.startsWith("ccmni") -> 3 // datos móviles: no sirve
                    else -> 2
                }
            }
            .firstOrNull()?.second
    }.getOrNull()
}
