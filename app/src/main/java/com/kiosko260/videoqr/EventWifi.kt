package com.kiosko260.videoqr

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings

/** Cómo se conectan los invitados con el kiosko. */
enum class NetworkMode {
    /** Tablet e invitados en la red Wi-Fi del evento (LAN sin internet). Quick Share y QR funcionan a la vez. */
    EVENT_WIFI,

    /** La tablet crea su propia red (hotspot) y la apaga/enciende para convivir con Quick Share. */
    HOTSPOT,
}

/**
 * Conexión de la tablet a la red Wi-Fi del evento ([Config.EVENT_WIFI_SSID]).
 * No necesita internet: es una red local donde también están los celulares de los invitados.
 */
object EventWifi {

    val info get() = HotspotInfo(Config.EVENT_WIFI_SSID, Config.EVENT_WIFI_PASSWORD)

    private fun suggestion(): WifiNetworkSuggestion =
        WifiNetworkSuggestion.Builder()
            .setSsid(Config.EVENT_WIFI_SSID)
            .setWpa2Passphrase(Config.EVENT_WIFI_PASSWORD)
            .build()

    /**
     * Intent para guardar la red en la tablet (Android 11+: ventana del sistema "¿Guardar red @ETU26?").
     * Una vez guardada, Android se conecta solo cada vez que la red esté disponible.
     */
    fun addNetworkIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(
                Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion()),
            )
        } else {
            // Android 10: se registra como "sugerencia" (aparece una notificación para permitirla)
            // y se abre el panel de Wi-Fi.
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            runCatching { wifi.addNetworkSuggestions(listOf(suggestion())) }
            Intent(Settings.Panel.ACTION_WIFI)
        }

    /** Panel rápido de Wi-Fi (por si el Wi-Fi de la tablet está apagado). */
    fun wifiPanelIntent(): Intent = Intent(Settings.Panel.ACTION_WIFI)

    fun isWifiEnabled(context: Context): Boolean =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
}
