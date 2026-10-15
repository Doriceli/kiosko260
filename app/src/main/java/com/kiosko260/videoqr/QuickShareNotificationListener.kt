package com.kiosko260.videoqr

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * Escucha las notificaciones de Quick Share (Samsung y Google) para:
 *  1. Apagar al instante la red del kiosko cuando alguien empieza a enviar un video, así Quick Share
 *     puede usar el Wi-Fi sin preguntar "¿desactivar hotspot?".
 *  2. Pulsar "Aceptar" automáticamente si la notificación trae ese botón ([Config.AUTO_ACCEPT_QUICK_SHARE]).
 * Al terminar la transferencia, QuickShareWatcher detecta el archivo y la app vuelve a encender la red.
 *
 * Requiere que el usuario active "Acceso a notificaciones" para la app (botón en la pantalla).
 */
class QuickShareNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isQuickShare(sbn) || isFinished(sbn)) return
        val app = application as? KioskoApp ?: return
        app.onQuickShareIncoming()
        if (Config.AUTO_ACCEPT_QUICK_SHARE) clickAccept(sbn.notification)
    }

    private fun isQuickShare(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        val extras = sbn.notification.extras
        val text = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).joinToString(" ").lowercase()
        return when {
            QUICK_SHARE_PACKAGES.any { pkg.startsWith(it) } -> true
            // Quick Share de Google vive dentro de Google Play Services, junto a muchas otras notificaciones.
            pkg == "com.google.android.gms" -> KEYWORDS.any { it in text }
            else -> false
        }
    }

    /** "Archivo recibido / guardado": la transferencia ya terminó, no hay que apagar la red. */
    private fun isFinished(sbn: StatusBarNotification): Boolean {
        val extras = sbn.notification.extras
        val text = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT),
        ).joinToString(" ").lowercase()
        return DONE_WORDS.any { it in text }
    }

    /** Pulsa el botón "Aceptar"/"Accept"/"Recibir" de la notificación, si lo tiene. */
    private fun clickAccept(notification: Notification) {
        val action = notification.actions?.firstOrNull { action ->
            val title = action.title?.toString()?.lowercase().orEmpty()
            ACCEPT_WORDS.any { title.startsWith(it) }
        } ?: return
        runCatching { action.actionIntent.send() }
            .onSuccess { Log.i(TAG, "Quick Share aceptado automáticamente") }
            .onFailure { Log.w(TAG, "No se pudo aceptar automáticamente", it) }
    }

    companion object {
        private const val TAG = "QuickShareListener"
        private val QUICK_SHARE_PACKAGES = listOf(
            "com.samsung.android.app.sharelive", // Quick Share de Samsung
            "com.google.android.apps.nearby",
        )
        private val KEYWORDS = listOf("quick share", "nearby share", "compartir con quick share", "recib")
        private val DONE_WORDS = listOf("recibido", "received", "completad", "complete", "guardado", "saved", "listo")
        private val ACCEPT_WORDS = listOf("aceptar", "accept", "recibir", "receive", "permitir", "allow")

        /** ¿El usuario ya dio "Acceso a notificaciones" a la app? */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, QuickShareNotificationListener::class.java).flattenToString()
            return enabled.split(':').any { it == me }
        }
    }
}
