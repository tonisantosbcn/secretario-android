package com.tonisantos.secretario

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Un mensaje de WhatsApp guardado en el móvil (nunca sale de él). */
data class Item(
    val id: String,
    val app: String,
    val chat: String,
    val sender: String,
    val text: String,
    val ts: Long,
    var status: String, // "new", "added", "dismissed"
    var eventId: Long = -1L, // evento creado en el calendario (para «Deshacer»)
    var note: String = "",   // "auto" si se añadió solo
) {
    fun parsed(): DateParser.Result? {
        val t = textForDates() ?: return null
        return DateParser.parse(t, LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()))
    }

    /** Fotos, vídeos, audios…: solo se mira el pie de foto, si lo hay. */
    fun textForDates(): String? {
        val media = Regex("^\\s*(?:\\uD83D\\uDCF7|\\uD83C\\uDFA5|\\uD83D\\uDCF9|\\uD83C\\uDFA4|\\uD83C\\uDFB5|\\uD83D\\uDCC4|" +
            "\\uD83D\\uDCCE|\\uD83D\\uDDBC\\uFE0F?|\\uD83D\\uDC7E|\\uD83D\\uDCCD|\\uD83D\\uDC64|GIF|Sticker)\\s*")
        val m = media.find(text)
        val rest = (if (m == null) text else text.substring(m.range.last + 1)).trim()
        val placeholder = Regex("^(?:\\d+\\s+)?(?:fotos?|photos?|im[aá]genes?|imatges?|v[ií]deos?|audios?|notas? de voz|" +
            "missatges? de veu|documentos?|documents?|gif|sticker|ubicaci[oó]n|ubicaci[oó]|contactos?|contactes?)\\s*(?:\\(.*\\))?\\s*$",
            RegexOption.IGNORE_CASE)
        return if (rest.isEmpty() || placeholder.matches(rest)) null else rest
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("app", app).put("chat", chat).put("sender", sender)
        .put("text", text).put("ts", ts).put("status", status)
        .put("eventId", eventId).put("note", note)

    companion object {
        fun fromJson(o: JSONObject) = Item(
            o.optString("id"), o.optString("app"), o.optString("chat"), o.optString("sender"),
            o.optString("text"), o.optLong("ts"), o.optString("status", "new"),
            o.optLong("eventId", -1L), o.optString("note", ""),
        )
    }
}

object Store {
    private const val PREFS = "secretario"
    private const val KEY_ITEMS = "items"
    private const val KEEP_DAYS = 14L
    private const val MAX_ITEMS = 1500

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(c: Context): MutableList<Item> {
        val arr = try { JSONArray(prefs(c).getString(KEY_ITEMS, "[]")) } catch (_: Exception) { JSONArray() }
        return MutableList(arr.length()) { Item.fromJson(arr.getJSONObject(it)) }
    }

    @Synchronized
    private fun save(c: Context, items: List<Item>) {
        val limit = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val kept = items.filter { it.ts >= limit }.sortedBy { it.ts }.takeLast(MAX_ITEMS)
        val arr = JSONArray()
        kept.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    /** Añade el mensaje si es nuevo. Devuelve true si no estaba. */
    @Synchronized
    fun addIfNew(c: Context, item: Item): Boolean {
        // Un aviso viejo que siga en la barra no debe volver a entrar cada vez que se borre de la lista.
        if (item.ts < System.currentTimeMillis() - KEEP_DAYS * 86_400_000L) return false
        val items = all(c)
        if (items.any { it.id == item.id }) return false
        // El mismo mensaje puede volver a aparecer con otra hora o con el chat renombrado: lo tratamos como repetido.
        if (items.any {
                it.app == item.app && it.chat == item.chat && it.sender == item.sender && it.text == item.text &&
                    kotlin.math.abs(it.ts - item.ts) < 2 * 86_400_000L
            }) return false
        items.add(item)
        save(c, items)
        return true
    }

    @Synchronized
    fun get(c: Context, id: String): Item? = all(c).firstOrNull { it.id == id }

    @Synchronized
    fun setStatus(c: Context, id: String, status: String, eventId: Long? = null, note: String? = null) {
        val items = all(c)
        items.firstOrNull { it.id == id }?.let {
            it.status = status
            if (eventId != null) it.eventId = eventId
            if (note != null) it.note = note
        }
        save(c, items)
    }

    // ---- ajustes ----
    fun calendarId(c: Context): Long = prefs(c).getLong("calendarId", -1L)
    fun setCalendarId(c: Context, id: Long) = prefs(c).edit().putLong("calendarId", id).apply()
    fun autoAdd(c: Context): Boolean = prefs(c).getBoolean("autoAdd", true)
    fun setAutoAdd(c: Context, v: Boolean) = prefs(c).edit().putBoolean("autoAdd", v).apply()
    fun notifyTimeOnly(c: Context): Boolean = prefs(c).getBoolean("notifyTimeOnly", true)
    fun setNotifyTimeOnly(c: Context, v: Boolean) = prefs(c).edit().putBoolean("notifyTimeOnly", v).apply()
    fun connectedAt(c: Context): Long = prefs(c).getLong("connectedAt", 0L)
    fun setConnectedAt(c: Context, v: Long) = prefs(c).edit().putLong("connectedAt", v).apply()
    fun lastAnyApp(c: Context): String = prefs(c).getString("lastAnyApp", "") ?: ""
    fun setLastAnyApp(c: Context, v: String) = prefs(c).edit().putString("lastAnyApp", v).apply()
    fun lastError(c: Context): String = prefs(c).getString("lastError", "") ?: ""
    fun setLastError(c: Context, v: String) = prefs(c).edit().putString("lastError", v).apply()
    fun lastSeen(c: Context): Long = prefs(c).getLong("lastSeen", 0L)
    fun setLastSeen(c: Context, v: Long) = prefs(c).edit().putLong("lastSeen", v).apply()
}
