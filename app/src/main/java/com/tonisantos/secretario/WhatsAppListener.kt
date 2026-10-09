package com.tonisantos.secretario

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.security.MessageDigest

/**
 * Lee las notificaciones de WhatsApp (normal y Business) que llegan al móvil.
 * Solo guarda el texto en el propio teléfono; la app no tiene permiso de Internet.
 */
class WhatsAppListener : NotificationListenerService() {

    companion object {
        val PACKAGES = mapOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Si el sistema nos desconecta (ahorro de batería), pedimos volver.
        requestRebind(ComponentName(this, WhatsAppListener::class.java))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Al conectar, recogemos lo que ya esté en la barra de notificaciones.
        try { activeNotifications?.forEach { handle(it) } } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { handle(sbn) } catch (_: Exception) {}
    }

    private fun handle(sbn: StatusBarNotification) {
        val appName = PACKAGES[sbn.packageName] ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return // "5 mensajes de 3 chats"
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return  // llamada en curso, "WhatsApp Web activo"…
        if (n.category == Notification.CATEGORY_CALL) return           // llamadas entrantes
        val extras = n.extras ?: return
        Store.setLastSeen(this, System.currentTimeMillis())

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val chat = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() } ?: title

        val messages = readMessagingStyle(extras)
        if (messages.isNotEmpty()) {
            for ((sender, text, time) in messages) {
                save(appName, chat, sender ?: "Tú", text, if (time > 0) time else sbn.postTime)
            }
        } else {
            val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (text.isNotBlank()) save(appName, chat, title, text, sbn.postTime)
        }
    }

    /** Mensajes individuales de una notificación de conversación (MessagingStyle). */
    @Suppress("DEPRECATION")
    private fun readMessagingStyle(extras: Bundle): List<Triple<String?, String, Long>> {
        val arr = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        val out = mutableListOf<Triple<String?, String, Long>>()
        for (p in arr) {
            val b = p as? Bundle ?: continue
            val text = b.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() } ?: continue
            val time = b.getLong("time", 0L)
            var sender: String? = b.getCharSequence("sender")?.toString()
            if (sender.isNullOrBlank() && Build.VERSION.SDK_INT >= 28) {
                sender = (b.getParcelable<Person>("sender_person"))?.name?.toString()
            }
            out.add(Triple(sender?.takeIf { it.isNotBlank() }, text, time))
        }
        return out
    }

    private fun save(app: String, chat: String, sender: String, text: String, ts: Long) {
        val id = sha256("$app|$chat|$sender|$text|$ts")
        val item = Item(id, app, chat, sender, text, ts, "new")
        if (!Store.addIfNew(this, item)) return
        val r = item.parsed() ?: return
        if (r.past) return
        if (r.date == null && !Store.notifyTimeOnly(this)) return
        Notifier.candidate(this, item, r)
    }

    private fun sha256(v: String): String =
        MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)
}
