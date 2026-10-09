package com.tonisantos.secretario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.time.LocalDateTime;
import org.junit.Test;

/** Referencia: viernes 9 de octubre de 2026, 22:00. */
public class DateParserTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 22, 0);

    private static void check(String text, String expected) {
        DateParser.Result r = DateParser.parse(text, NOW);
        assertNotNull("No detectó nada en: " + text, r);
        assertEquals(text, expected, r.toString());
    }

    private static void none(String text) {
        assertNull("No debería detectar nada en: " + text, DateParser.parse(text, NOW));
    }

    // Los casos que fallaban en el prototipo original
    @Test public void jueves() { check("Quedamos el jueves a las 17:30", "2026-10-15 17:30"); }
    @Test public void mananaALas5() { check("Mañana a las 5", "2026-10-10 17:00 (supuesta)"); }
    @Test public void mesEnLetra() { check("El 15 de octubre a las 10:00", "2026-10-15 10:00"); }
    @Test public void pasadoManana() { check("Pasado mañana a las 18:00", "2026-10-11 18:00"); }
    @Test public void deLaManana() { check("El 20/10 a las 10:00 de la mañana", "2026-10-20 10:00"); }
    @Test public void horaConH() { check("Ensayo a las 19h el 12/10", "2026-10-12 19:00"); }

    // Los del prototipo original que ya funcionaban
    @Test public void manana1730() { check("Mañana a las 17:30 tenemos reunión", "2026-10-10 17:30"); }
    @Test public void hoy9() { check("Hoy a las 9:00 cita", "2026-10-09 09:00 (supuesta)"); }
    @Test public void fechaCompleta() { check("Reunión 25/10/2026 a las 16:15", "2026-10-25 16:15"); }
    @Test public void fechaImposible() {
        DateParser.Result r = DateParser.parse("Reunión 31/02/2026 a las 16:15", NOW);
        assertNull(r.date);
    }
    @Test public void pasaAlAnoSiguiente() { check("Reunión 01/02 a las 11:00", "2027-02-01 11:00"); }

    // Catalán
    @Test public void dema() { check("Demà a les 5 de la tarda", "2026-10-10 17:00"); }
    @Test public void dijousEnLletra() { check("Dijous a les cinc i mitja", "2026-10-15 17:30 (supuesta)"); }
    @Test public void dOctubre() { check("15 d’octubre a les 20:00", "2026-10-15 20:00"); }

    // Más formas habituales
    @Test public void diaSolo() { check("el dia 3 a las 11", "2026-11-03 11:00 (supuesta)"); }
    @Test public void estaTarde() { check("Esta tarde a las 6", "2026-10-09 18:00"); }
    @Test public void estaManana() { check("nos vemos esta mañana a las 10", "2026-10-09 10:00"); }
    @Test public void reunionDeManana() { check("la reunión de mañana es a las 12:30", "2026-10-10 12:30"); }
    @Test public void mananaPorLaManana() { check("Mañana por la mañana a las 9", "2026-10-10 09:00"); }
    @Test public void ceroDelante() { check("a las 07:00 el lunes", "2026-10-12 07:00"); }
    @Test public void diaSemanaYNumero() { check("el jueves 22 a las 20h", "2026-10-22 20:00"); }
    @Test public void mismoDiaSemana() { check("el viernes a las 21:00", "2026-10-16 21:00"); }
    @Test public void sinHora() { check("Sábado 24 de octubre ensayo general", "2026-10-24 --:--"); }
    @Test public void soloHora() { check("a la una y cuarto", "? 13:15 (supuesta)"); }
    @Test public void menosCuarto() { check("mañana a las 3 menos cuarto", "2026-10-10 14:45 (supuesta)"); }
    @Test public void pasadaReciente() {
        DateParser.Result r = DateParser.parse("ayer el 8/10 a las 10:00", NOW);
        assertTrue(r.past);
    }
    @Test public void futuraNoPasada() { assertFalse(DateParser.parse("el 12/10", NOW).past); }

    @Test public void manana9PorLaManana() { check("quedamos mañana por la mañana a las 9", "2026-10-10 09:00"); }
    @Test public void ensayoALas8() { check("ensayo el lunes a las 8 de la tarde", "2026-10-12 20:00"); }
    @Test public void doceMediodia() { check("el martes a las 12", "2026-10-13 12:00"); }

    @Test public void estaMananaNoPuedo() { check("esta mañana no puedo, a las 5", "2026-10-09 17:00 (supuesta)"); }
    @Test public void cincoDeLaManana() { check("mañana a las 5 de la mañana", "2026-10-10 05:00"); }

    // ¿Cita en firme?
    @Test public void pregunta() { assertTrue(DateParser.isQuestion("¿quedamos el jueves a las 17:30?")); }
    @Test public void noPregunta() { assertFalse(DateParser.isQuestion("quedamos el jueves a las 17:30")); }
    @Test public void noPuedo() { assertTrue(DateParser.isNegative("el jueves a las 17:30 no puedo")); }
    @Test public void cancelado() { assertTrue(DateParser.isNegative("Se cancela el ensayo del 15/10")); }
    @Test public void anullat() { assertTrue(DateParser.isNegative("L'assaig de dijous queda anul·lat")); }
    @Test public void ajornat() { assertTrue(DateParser.isNegative("ho ajornem al dilluns a les 18:00")); }
    @Test public void noNegativo() { assertFalse(DateParser.isNegative("nos vemos el jueves a las 17:30 en el Liceu")); }
    @Test public void noNegativoNovela() { assertFalse(DateParser.isNegative("ensayo de Norma el 12/10 a las 10:00")); }
    @Test public void vale() { assertTrue(DateParser.isConfirmation("Vale!")); }
    @Test public void perfecte() { assertTrue(DateParser.isConfirmation("Perfecte, allà seré")); }
    @Test public void pulgar() { assertTrue(DateParser.isConfirmation("👍")); }
    @Test public void siPerfecto() { assertTrue(DateParser.isConfirmation("Sí, perfecto")); }
    @Test public void valeNoPuedo() { assertFalse(DateParser.isConfirmation("vale, pero no puedo")); }
    @Test public void siCondicional() { assertFalse(DateParser.isConfirmation("Si puedes ven el jueves")); }
    @Test public void siSolo() { assertTrue(DateParser.isConfirmation("Sí!")); }
    @Test public void noEsConfirmacion() { assertFalse(DateParser.isConfirmation("Sitges es precioso en octubre, tenemos que ir")); }

    // No debe saltar
    @Test public void saludo() { none("hola qué tal"); }
    @Test public void unaDeLasDos() { none("me quedo con una de las dos"); }
    @Test public void duracion() { none("dura 1h más o menos"); }
    @Test public void precio() { none("son 10.30€ el corte"); }
    @Test public void desDe() { none("vinc a peu des de casa"); }
}
