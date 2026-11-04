package com.kiosko260.videoqr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** Interfaces que nunca sirven para que los invitados lleguen a la tablet. */
    private val IGNORED_PREFIXES = listOf(
        "p2p",    // Wi-Fi Direct que deja abierto Quick Share (192.168.49.x): ¡no es la red del evento!
        "aware", "nan", // Wi-Fi Aware
        "rmnet", "ccmni", // datos móviles
        "dummy", "tun", "ifb", "lo",
    )

    /**
     * IP local de la tablet para armar el enlace del QR. No usa internet: solo lee la red del equipo.
     *  - Wi-Fi del evento: la IP que Android asignó a la conexión Wi-Fi (no a Wi-Fi Direct/Quick Share).
     *  - Hotspot ([preferHotspot]): la IP de la red que creó la tablet.
     * Devuelve null si no hay red local.
     */
    fun localIpAddress(context: Context, preferHotspot: Boolean = false): String? {
        if (!preferHotspot) wifiIp(context)?.let { return it }
        return interfaceIp(preferHotspot)
    }

    /** IP de la red Wi-Fi a la que está conectada la tablet, según Android. */
    @Suppress("DEPRECATION") // allNetworks: suficiente y sencillo para listar las redes activas.
    private fun wifiIp(context: Context): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        cm.allNetworks.asSequence()
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .mapNotNull { cm.getLinkProperties(it) }
            .filter { lp -> IGNORED_PREFIXES.none { lp.interfaceName.orEmpty().startsWith(it) } }
            .flatMap { it.linkAddresses.asSequence() }
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { usable(it) }
            ?.hostAddress
    }.getOrNull()

    /** Respaldo: revisa las interfaces de red (necesario para el hotspot, que no es una "red" de Android). */
    private fun interfaceIp(preferHotspot: Boolean): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { nic -> IGNORED_PREFIXES.none { nic.name.startsWith(it) } }
            .flatMap { nic ->
                nic.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { usable(it) }
                    .map { nic.name to it.hostAddress }
            }
            .sortedBy { (name, _) ->
                val hotspot = name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") ||
                    (name.startsWith("wlan") && name != "wlan0")
                when {
                    preferHotspot && hotspot -> 0
                    name == "wlan0" -> 1
                    hotspot -> 2
                    else -> 3
                }
            }
            .firstOrNull()?.second
    }.getOrNull()

    /** Cualquier IPv4 de red local (no solo 192.168/10/172: algunos routers usan otros rangos). */
    private fun usable(address: Inet4Address) =
        !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isAnyLocalAddress
}
