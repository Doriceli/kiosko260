package com.kiosko260.videoqr

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Nombre y contraseña de la red Wi-Fi creada por el kiosko. */
data class HotspotInfo(val ssid: String, val password: String) {
    /** Texto del QR de Wi-Fi: la cámara de Android/iPhone se conecta sola al escanearlo. */
    fun wifiQrText(): String = "WIFI:T:WPA;S:${escape(ssid)};P:${escape(password)};;"

    private fun escape(text: String) = text.replace(Regex("""([\;,:"])"""), """\\$1""")
}

/**
 * Red Wi-Fi propia del kiosko, SIN internet (LocalOnlyHotspot de Android).
 * Los invitados se conectan a ella (QR de Wi-Fi) y descargan el video desde el servidor local.
 * No necesita ninguna red externa ni datos móviles. Android elige nombre y contraseña.
 */
class KioskHotspot(context: Context, private val onChange: (HotspotInfo?, String?) -> Unit) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var starting = false

    val isActive get() = reservation != null

    @SuppressLint("MissingPermission") // Los permisos se piden en MainActivity antes de llamar.
    fun start() {
        if (reservation != null || starting) return
        starting = true
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    starting = false
                    reservation = res
                    onChange(readInfo(res), null)
                }

                override fun onStopped() {
                    starting = false
                    reservation = null
                    onChange(null, null) // p. ej. Android la apagó para que Quick Share use el Wi-Fi
                }

                override fun onFailed(reason: Int) {
                    starting = false
                    reservation = null
                    val why = when (reason) {
                        ERROR_INCOMPATIBLE_MODE -> "apaga el hotspot normal del celular"
                        ERROR_TETHERING_DISALLOWED -> "el equipo no permite compartir red"
                        else -> "activa el Wi-Fi y la ubicación del celular"
                    }
                    onChange(null, "No se pudo crear la red del kiosko: $why")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            starting = false
            Log.e("KioskHotspot", "startLocalOnlyHotspot", e)
            onChange(null, "No se pudo crear la red del kiosko: ${e.message}")
        }
    }

    fun stop() {
        reservation?.close()
        reservation = null
        onChange(null, null)
    }

    @Suppress("DEPRECATION")
    private fun readInfo(res: WifiManager.LocalOnlyHotspotReservation): HotspotInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = res.softApConfiguration
            config.ssid?.let { HotspotInfo(it.removeSurrounding("\""), config.passphrase.orEmpty()) }
        } else {
            res.wifiConfiguration?.let { HotspotInfo(it.SSID.removeSurrounding("\""), it.preSharedKey.orEmpty().removeSurrounding("\"")) }
        }
}
