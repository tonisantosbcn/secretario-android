package com.tonisantos.secretario

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

data class CalendarInfo(val id: Long, val name: String, val account: String)

object CalendarHelper {
    const val DURATION_MIN = 60L
    const val REMINDER_MIN = 15

    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Calendarios en los que se puede escribir. */
    fun writable(c: Context): List<CalendarInfo> {
        if (!hasPermission(c)) return emptyList()
        val out = mutableListOf<CalendarInfo>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    out.add(CalendarInfo(cur.getLong(0), cur.getString(1) ?: "(sin nombre)", cur.getString(2) ?: ""))
                }
            }
        }
        return out
    }

    /** El calendario elegido; si no hay ninguno elegido, el que se llame "obsidian". */
    fun selected(c: Context): CalendarInfo? {
        val list = writable(c)
        val id = Store.calendarId(c)
        list.firstOrNull { it.id == id }?.let { return it }
        val auto = list.firstOrNull { it.name.equals("obsidian", ignoreCase = true) } ?: return null
        Store.setCalendarId(c, auto.id)
        return auto
    }

    fun title(item: Item): String {
        val who = if (item.chat == item.sender || item.sender == "Tú") item.chat else "${item.sender} (${item.chat})"
        val snippet = item.text.replace(Regex("\\s+"), " ").trim().take(60)
        return "$who: $snippet"
    }

    fun description(item: Item) = "Detectado por Secretario en ${item.app}\nChat: ${item.chat}\nDe: ${item.sender}\n\n${item.text}"

    /** Inicio y fin en milisegundos; all-day si no hay hora. */
    private fun times(date: LocalDate, time: LocalTime?): Triple<Long, Long, Boolean> =
        if (time == null) {
            val s = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            Triple(s, s + 86_400_000L, true)
        } else {
            val s = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            Triple(s, s + DURATION_MIN * 60_000L, false)
        }

    /** Resultado de crear un evento: eventId si se creó, duplicate si ya existía, error si falló. */
    data class AddResult(val eventId: Long? = null, val duplicate: Boolean = false, val error: String? = null)

    /** Crea el evento directamente en el calendario elegido. Devuelve un mensaje de error o null si fue bien. */
    fun insert(c: Context, item: Item, r: DateParser.Result): String? = add(c, item, r).error

    /** ¿Ya hay en ese calendario un evento a esa hora con este mismo mensaje? (p. ej. creado desde otro móvil) */
    private fun exists(c: Context, calId: Long, start: Long, item: Item): Boolean {
        val needle = item.text.replace(Regex("\\s+"), " ").trim().take(80)
        val proj = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION)
        val sel = "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.DTSTART}=? AND ${CalendarContract.Events.DELETED}=0"
        return try {
            c.contentResolver.query(CalendarContract.Events.CONTENT_URI, proj, sel,
                arrayOf(calId.toString(), start.toString()), null)?.use { cur ->
                while (cur.moveToNext()) {
                    val title = cur.getString(0) ?: ""
                    val desc = (cur.getString(1) ?: "").replace(Regex("\\s+"), " ")
                    if (title == title(item) || (needle.isNotEmpty() && desc.contains(needle))) return true
                }
                false
            } ?: false
        } catch (_: Exception) { false }
    }

    fun add(c: Context, item: Item, r: DateParser.Result): AddResult {
        if (!hasPermission(c)) return AddResult(error = "Falta el permiso de calendario. Ábrelo en Secretario.")
        val cal = selected(c) ?: return AddResult(error = "No hay calendario elegido. Elígelo en Secretario.")
        val date = r.date ?: return AddResult(error = "El mensaje no dice el día: usa «Ajustar».")
        val (start, end, allDay) = times(date, r.time)
        if (exists(c, cal.id, start, item)) return AddResult(duplicate = true)
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal.id)
            put(CalendarContract.Events.TITLE, title(item))
            put(CalendarContract.Events.DESCRIPTION, description(item))
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else ZoneId.systemDefault().id)
        }
        return try {
            val uri = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
                ?: return AddResult(error = "El calendario no aceptó el evento.")
            val eventId = uri.lastPathSegment?.toLongOrNull()
            if (!allDay) {
                if (eventId != null) {
                    val rem = ContentValues().apply {
                        put(CalendarContract.Reminders.EVENT_ID, eventId)
                        put(CalendarContract.Reminders.MINUTES, REMINDER_MIN)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    }
                    try { c.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, rem) } catch (_: Exception) {}
                }
            }
            AddResult(eventId = eventId ?: -1L)
        } catch (e: Exception) {
            AddResult(error = "No se pudo crear: ${e.message}")
        }
    }

    /** Borra un evento creado por Secretario. Devuelve true si se borró. */
    fun delete(c: Context, eventId: Long): Boolean {
        if (eventId <= 0 || !hasPermission(c)) return false
        return try {
            c.contentResolver.delete(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null) > 0
        } catch (_: Exception) { false }
    }

    /** Abre la app de calendario con el evento rellenado, para cambiar lo que haga falta antes de guardar. */
    fun editIntent(item: Item, r: DateParser.Result?): Intent {
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title(item))
            .putExtra(CalendarContract.Events.DESCRIPTION, description(item))
        val date = r?.date ?: LocalDate.now()
        val (start, end, allDay) = times(date, r?.time ?: if (r?.date == null) LocalTime.of(10, 0) else null)
        return i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Evento de prueba mañana a las 10:00, para comprobar que llega a Infomaniak. */
    fun insertTest(c: Context): String? {
        val item = Item("test", "Secretario", "Prueba", "Secretario",
            "Prueba de Secretario: si ves esto en Infomaniak, funciona. Puedes borrarlo.", System.currentTimeMillis(), "new")
        val r = DateParser.parse("mañana a las 10:00", java.time.LocalDateTime.now())!!
        return insert(c, item, r)
    }
}
