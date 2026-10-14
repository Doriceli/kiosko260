package com.kiosko260.videoqr

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * IP local del kiosko en la red Wi-Fi (wlan0) o en su propio hotspot (ap0/swlan0/wlan1...).
     * Con [preferHotspot] se elige primero la red creada por el kiosko. Devuelve null si no hay red local.
     * No usa internet: solo lee las interfaces de red del propio celular.
     */
    fun localIpAddress(preferHotspot: Boolean = false): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { nic.name to it.hostAddress }
            }
            .sortedBy { (name, _) ->
                val hotspot = name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") ||
                    (name.startsWith("wlan") && name != "wlan0")
                when {
                    name.startsWith("rmnet") || name.startsWith("ccmni") -> 9 // datos móviles: no sirve
                    preferHotspot && hotspot -> 0
                    name == "wlan0" -> 1
                    hotspot -> 2
                    else -> 3
                }
            }
            .firstOrNull()?.second
    }.getOrNull()
}
