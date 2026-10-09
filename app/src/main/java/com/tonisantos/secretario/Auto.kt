package com.tonisantos.secretario

import android.content.Context

/** Decide si una cita se añade sola o se pregunta, y la añade. */
object Auto {
    enum class Kind { AUTO, ASK, SKIP }
    data class Decision(val kind: Kind, val reason: String = "")

    private const val CONFIRM_WINDOW_MS = 48 * 3_600_000L

    fun decide(item: Item, r: DateParser.Result): Decision = when {
        r.past -> Decision(Kind.SKIP)
        r.date != null && r.date.isBefore(java.time.LocalDate.now()) -> Decision(Kind.SKIP)
        DateParser.isNegative(item.text) -> Decision(Kind.ASK, "Parece que dice que no, cancela o aplaza")
        DateParser.isQuestion(item.text) -> Decision(Kind.ASK, "Es una pregunta: se añadirá sola si en el chat contestan «vale»")
        r.date == null -> Decision(Kind.ASK, "No dice el día")
        r.time == null -> Decision(Kind.ASK, "No dice la hora")
        r.timeGuessed -> Decision(Kind.ASK, "Hora dudosa")
        else -> Decision(Kind.AUTO)
    }

    /** Lo llama el lector de notificaciones con cada mensaje nuevo. */
    fun onNewMessage(c: Context, item: Item) {
        val r = item.parsed()
        if (r == null) {
            if (DateParser.isConfirmation(item.text)) onConfirmation(c, item)
            return
        }
        val d = decide(item, r)
        when (d.kind) {
            Kind.SKIP -> return
            Kind.AUTO -> if (Store.autoAdd(c)) add(c, item, r) else Notifier.candidate(c, item, r, "")
            Kind.ASK -> {
                if (r.date == null && !Store.notifyTimeOnly(c)) return
                Notifier.candidate(c, item, r, d.reason)
            }
        }
    }

    /** Añade sin preguntar y avisa con «Deshacer». */
    fun add(c: Context, item: Item, r: DateParser.Result) {
        val res = CalendarHelper.add(c, item, r)
        when {
            res.duplicate -> Store.setStatus(c, item.id, "added", note = "ya estaba")
            res.error != null -> Notifier.candidate(c, item, r, res.error)
            else -> {
                Store.setStatus(c, item.id, "added", eventId = res.eventId, note = "auto")
                Notifier.added(c, item, r)
            }
        }
    }

    /** "vale" / "perfecto"…: confirma la última pregunta con fecha de ese chat (últimas 48 h). */
    private fun onConfirmation(c: Context, conf: Item) {
        val q = Store.all(c)
            .filter {
                it.app == conf.app && it.chat == conf.chat && it.status == "new" && it.id != conf.id &&
                    it.ts <= conf.ts && conf.ts - it.ts <= CONFIRM_WINDOW_MS && DateParser.isQuestion(it.text) &&
                    !DateParser.isNegative(it.text)
            }
            .maxByOrNull { it.ts } ?: return
        val r = q.parsed() ?: return
        if (r.date == null || r.past || r.date.isBefore(java.time.LocalDate.now())) return
        if (r.time == null || r.timeGuessed || !Store.autoAdd(c)) {
            Notifier.candidate(c, q, r, "Confirmado en el chat" + if (r.timeGuessed) ", pero la hora es dudosa" else "")
        } else {
            add(c, q, r)
        }
    }
}
