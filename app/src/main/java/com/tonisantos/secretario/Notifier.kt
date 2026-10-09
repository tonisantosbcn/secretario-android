package com.tonisantos.secretario

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import java.time.format.DateTimeFormatter
import java.util.Locale

object Notifier {
    private const val CHANNEL = "citas"
    const val ACTION_ADD = "com.tonisantos.secretario.ADD"
    const val ACTION_DISMISS = "com.tonisantos.secretario.DISMISS"
    const val EXTRA_ID = "id"

    private val ES = Locale.forLanguageTag("es-ES")
    private val DATE_FMT = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES)

    fun describe(r: DateParser.Result): String {
        val d = r.date?.format(DATE_FMT) ?: "día sin indicar"
        val t = r.time?.let {
            "a las " + it.toString() + when {
                !r.timeGuessed -> ""
                it.hour >= 12 -> " (¿o por la mañana?)"
                else -> " (¿o por la tarde?)"
            }
        } ?: "sin hora"
        return "$d, $t"
    }

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Posibles citas", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Mensajes de WhatsApp que mencionan un día o una hora"
                })
        }
    }

    fun canNotify(c: Context) = Build.VERSION.SDK_INT < 33 ||
        c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun nid(id: String) = id.hashCode()

    fun candidate(c: Context, item: Item, r: DateParser.Result) {
        if (!canNotify(c)) return
        ensureChannel(c)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(c, nid(item.id),
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)
        fun action(a: String) = PendingIntent.getBroadcast(c, nid(item.id) xor a.hashCode(),
            Intent(c, ActionReceiver::class.java).setAction(a).putExtra(EXTRA_ID, item.id), flags)

        val b = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Posible cita · ${item.chat}")
            .setContentText(describe(r))
            .setStyle(Notification.BigTextStyle().bigText(describe(r) + "\n\n" + item.sender + ": " + item.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
        if (r.date != null) {
            b.addAction(Notification.Action.Builder(null, "Añadir", action(ACTION_ADD)).build())
        }
        b.addAction(Notification.Action.Builder(null, "Descartar", action(ACTION_DISMISS)).build())
        c.getSystemService(NotificationManager::class.java).notify(nid(item.id), b.build())
    }

    fun cancel(c: Context, id: String) =
        c.getSystemService(NotificationManager::class.java).cancel(nid(id))
}

/** Botones "Añadir" y "Descartar" de la notificación. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getStringExtra(Notifier.EXTRA_ID) ?: return
        val item = Store.get(c, id) ?: return
        if (item.status != "new") { Notifier.cancel(c, id); return } // ya se añadió o descartó desde la app
        when (intent.action) {
            Notifier.ACTION_ADD -> {
                val r = item.parsed() ?: return
                val err = CalendarHelper.insert(c, item, r)
                if (err == null) {
                    Store.setStatus(c, id, "added")
                    Notifier.cancel(c, id)
                    Toast.makeText(c, "Añadido al calendario", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(c, err, Toast.LENGTH_LONG).show()
                }
            }
            Notifier.ACTION_DISMISS -> {
                Store.setStatus(c, id, "dismissed")
                Notifier.cancel(c, id)
            }
        }
    }
}
