package com.tonisantos.secretario

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private var showAll = false
    private var testText = ""
    private val es = Locale.forLanguageTag("es-ES")
    private val tsFmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", es)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)
        render()
    }

    override fun onResume() { super.onResume(); render() }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results); render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 15f, bold: Boolean = false, top: Int = 0) =
        TextView(this).apply {
            text = s; textSize = size; setPadding(0, dp(top), 0, dp(4))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setTextIsSelectable(false)
        }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply { text = label; isAllCaps = false; setOnClickListener { onClick() } }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun listenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, WhatsAppListener::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
    }

    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun ago(ms: Long): String {
        if (ms <= 0) return "todavía ninguno"
        val min = (System.currentTimeMillis() - ms) / 60_000
        return when {
            min < 1 -> "hace un momento"
            min < 60 -> "hace $min min"
            min < 48 * 60 -> "hace ${min / 60} h"
            else -> "hace ${min / 1440} días ⚠️"
        }
    }

    private fun render() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(32)) }
        setContentView(ScrollView(this).apply { addView(root) })

        // ---------- Estado ----------
        root.addView(text("Estado", 20f, true))
        val okL = listenerEnabled()
        root.addView(text((if (okL) "✅" else "❌") + " Acceso a notificaciones"))
        if (!okL) root.addView(button("Activar acceso a notificaciones") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        val okN = Notifier.canNotify(this)
        root.addView(text((if (okN) "✅" else "❌") + " Avisos de Secretario"))
        if (!okN && Build.VERSION.SDK_INT >= 33) root.addView(button("Permitir avisos") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        })

        val okC = CalendarHelper.hasPermission(this)
        root.addView(text((if (okC) "✅" else "❌") + " Permiso de calendario"))
        if (!okC) root.addView(button("Permitir calendario") {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 2)
        })
        if (okC) {
            val cal = CalendarHelper.selected(this)
            root.addView(text(if (cal != null) "📅 Guarda en: ${cal.name} (${cal.account})" else "❌ No hay calendario elegido"))
            root.addView(row(
                button("Cambiar calendario") { pickCalendar() },
                button("Evento de prueba") {
                    val err = CalendarHelper.insertTest(this)
                    AlertDialog.Builder(this).setMessage(err
                        ?: "Creado mañana a las 10:00 («Prueba de Secretario»).\n\nMira si aparece en Infomaniak (en el ordenador o en la web). Si aparece, todo funciona. Luego bórralo.")
                        .setPositiveButton("OK", null).show()
                }))
        }

        val okB = batteryOk()
        root.addView(text((if (okB) "✅" else "⚠️") + " Batería sin restricciones"))
        if (!okB) root.addView(button("Quitar ahorro de batería a Secretario") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        root.addView(text("Último WhatsApp captado: " + ago(Store.lastSeen(this)), 14f))

        root.addView(CheckBox(this).apply {
            text = "Avisar también si el mensaje solo dice la hora"
            isChecked = Store.notifyTimeOnly(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Store.setNotifyTimeOnly(this@MainActivity, v) }
        })

        // ---------- Probar una frase ----------
        root.addView(text("Probar una frase", 20f, true, top = 16))
        val input = EditText(this).apply {
            hint = "p. ej. el jueves a las 5 en el Liceu"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(testText)
        }
        root.addView(input)
        root.addView(button("Ver qué entiende") {
            testText = input.text.toString()
            val r = DateParser.parse(testText, LocalDateTime.now())
            AlertDialog.Builder(this)
                .setMessage(if (r == null) "No veo ni día ni hora." else Notifier.describe(r))
                .setPositiveButton("OK", null).show()
        })

        // ---------- Mensajes ----------
        root.addView(text("Mensajes", 20f, true, top = 16))
        root.addView(row(
            button((if (!showAll) "● " else "") + "Posibles citas") { showAll = false; render() },
            button((if (showAll) "● " else "") + "Todos (14 días)") { showAll = true; render() }))

        val items = Store.all(this).sortedByDescending { it.ts }
        var shown = 0
        for (item in items) {
            val r = item.parsed()
            if (!showAll && (item.status != "new" || r == null || r.past)) continue
            shown++
            if (shown > 300) break
            addCard(item, r)
        }
        if (shown == 0) root.addView(text(
            if (showAll) "Aún no hay mensajes. Pide a alguien que te mande un WhatsApp."
            else "No hay citas pendientes de revisar.", top = 8))
    }

    private fun addCard(item: Item, r: DateParser.Result?) {
        val time = Instant.ofEpochMilli(item.ts).atZone(ZoneId.systemDefault()).format(tsFmt)
        val who = if (item.sender == item.chat) item.chat else "${item.sender} · ${item.chat}"
        root.addView(text("$time — $who", 13f, true, top = 14))
        root.addView(text(item.text.take(400)))
        if (r != null && !r.past) root.addView(text("➜ " + Notifier.describe(r), 14f, true))
        when (item.status) {
            "added" -> root.addView(text("✔ Añadido al calendario", 13f))
            "dismissed" -> root.addView(text("✖ Descartado", 13f))
        }
        val buttons = mutableListOf<View>()
        val rr: DateParser.Result? = if (item.status == "new" && r != null && r.date != null && !r.past) r else null
        if (rr != null) buttons.add(button("Añadir") {
            val err = CalendarHelper.insert(this, item, rr)
            if (err == null) {
                Store.setStatus(this, item.id, "added"); Notifier.cancel(this, item.id)
                Toast.makeText(this, "Añadido al calendario", Toast.LENGTH_SHORT).show(); render()
            } else AlertDialog.Builder(this).setMessage(err).setPositiveButton("OK", null).show()
        })
        buttons.add(button("Ajustar") {
            try {
                startActivity(CalendarHelper.editIntent(item, r))
                Store.setStatus(this, item.id, "added"); Notifier.cancel(this, item.id)
            } catch (e: Exception) {
                Toast.makeText(this, "No encuentro una app de calendario", Toast.LENGTH_LONG).show()
            }
        })
        if (item.status == "new" && r != null) buttons.add(button("Descartar") {
            Store.setStatus(this, item.id, "dismissed"); Notifier.cancel(this, item.id); render()
        })
        root.addView(row(*buttons.toTypedArray()))
    }

    private fun pickCalendar() {
        val list = CalendarHelper.writable(this)
        if (list.isEmpty()) {
            AlertDialog.Builder(this).setMessage("No encuentro calendarios en los que se pueda escribir. Revisa que kSync tenga activada la sincronización del calendario.")
                .setPositiveButton("OK", null).show()
            return
        }
        val names = list.map { "${it.name}  —  ${it.account}" }.toTypedArray()
        val current = list.indexOfFirst { it.id == Store.calendarId(this) }
        AlertDialog.Builder(this).setTitle("¿Dónde guardo las citas?")
            .setSingleChoiceItems(names, current) { d, which ->
                Store.setCalendarId(this, list[which].id); d.dismiss(); render()
            }.show()
    }
}
