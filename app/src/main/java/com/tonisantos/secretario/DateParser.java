package com.tonisantos.secretario;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Busca una fecha y/o una hora en un mensaje escrito en castellano o catalán.
 * Java puro (sin Android) para poder probarlo en cualquier ordenador.
 */
public final class DateParser {

    /** Resultado. date o time pueden ser null, pero nunca los dos. */
    public static final class Result {
        public final LocalDate date;      // null = el mensaje no dice el día
        public final LocalTime time;      // null = el mensaje no dice la hora
        public final boolean timeGuessed; // "a las 5" sin más -> se supone 17:00
        public final boolean past;        // la fecha ya ha pasado

        Result(LocalDate date, LocalTime time, boolean timeGuessed, boolean past) {
            this.date = date; this.time = time; this.timeGuessed = timeGuessed; this.past = past;
        }

        @Override public String toString() {
            return (date == null ? "?" : date.toString()) + " " + (time == null ? "--:--" : time.toString())
                    + (timeGuessed ? " (supuesta)" : "") + (past ? " (pasada)" : "");
        }
    }

    private DateParser() {}

    // ---------- vocabulario ----------
    private static final Map<String, Integer> MONTHS = new HashMap<>();
    private static final Map<String, DayOfWeek> WEEKDAYS = new HashMap<>();
    private static final Map<String, Integer> NUMBERS = new HashMap<>();

    static {
        String[][] months = {
                {"enero", "gener", "ene", "gen"},
                {"febrero", "febrer", "feb", "febr"},
                {"marzo", "marc", "mar"},
                {"abril", "abr"},
                {"mayo", "maig", "may"},
                {"junio", "juny", "jun"},
                {"julio", "juliol", "jul"},
                {"agosto", "agost", "ago"},
                {"septiembre", "setiembre", "setembre", "sept", "sep"},
                {"octubre", "oct"},
                {"noviembre", "novembre", "nov"},
                {"diciembre", "desembre", "dic"}};
        for (int i = 0; i < months.length; i++) for (String m : months[i]) MONTHS.put(m, i + 1);

        String[][] days = {
                {"lunes", "dilluns"}, {"martes", "dimarts"}, {"miercoles", "dimecres"},
                {"jueves", "dijous"}, {"viernes", "divendres"}, {"sabado", "dissabte"},
                {"domingo", "diumenge"}};
        for (int i = 0; i < days.length; i++) for (String d : days[i]) WEEKDAYS.put(d, DayOfWeek.of(i + 1));

        String[][] nums = {
                {"una", "uno", "1"}, {"dos", "dues", "2"}, {"tres", "3"}, {"cuatro", "quatre", "4"},
                {"cinco", "cinc", "5"}, {"seis", "sis", "6"}, {"siete", "set", "7"}, {"ocho", "vuit", "8"},
                {"nueve", "nou", "9"}, {"diez", "deu", "10"}, {"once", "onze", "11"}, {"doce", "dotze", "12"}};
        for (String[] n : nums) {
            int v = Integer.parseInt(n[n.length - 1]);
            for (int i = 0; i < n.length - 1; i++) NUMBERS.put(n[i], v);
        }
    }

    private static String alt(java.util.Set<String> words) {
        // las palabras largas primero, para que "septiembre" gane a "sep"
        return words.stream().sorted((a, b) -> b.length() - a.length())
                .reduce((a, b) -> a + "|" + b).orElse("");
    }

    private static final String MONTH_ALT = alt(MONTHS.keySet());
    private static final String WEEKDAY_ALT = alt(WEEKDAYS.keySet());
    private static final String NUMBER_ALT = alt(NUMBERS.keySet());

    // ---------- patrones de hora ----------
    private static final String FRACTION =
            "(?:\\s+(y\\s+media|y\\s+cuarto|menos\\s+cuarto|i\\s+mitja|i\\s+quart|menys\\s+quart))?";

    /** 17:30 */
    private static final Pattern T_COLON = Pattern.compile("(?<![\\d:])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d])");
    /** a las 5, sobre las 17.30, a les cinc i mitja, a la una */
    private static final Pattern T_PREFIX = Pattern.compile(
            "\\b(a|sobre|hacia|cap\\s+a|vers|desde|de|hasta|fins|entre)\\s+(?:las|la|les)\\s+"
                    + "([01]?\\d|2[0-3]|" + NUMBER_ALT + ")\\b(?:[:.h]([0-5]\\d))?" + FRACTION
                    + "(?:\\s*h\\b|\\s*hs\\b|\\s*horas?\\b|\\s*hores\\b)?");
    /** 19h, 19 h, 19h30, 19 horas */
    private static final Pattern T_H = Pattern.compile(
            "(?<![\\d:/.])([01]?\\d|2[0-3])\\s?(?:h|hs|hrs)([0-5]\\d)?\\b");

    private static final Pattern PM = Pattern.compile(
            "^.{0,25}?\\b(?:de\\s+la\\s+tarde|por\\s+la\\s+tarde|de\\s+la\\s+noche|por\\s+la\\s+noche|"
                    + "de\\s+la\\s+tarda|a\\s+la\\s+tarda|del\\s+vespre|al\\s+vespre|de\\s+la\\s+nit|a\\s+la\\s+nit|pm|p\\.m\\.)");
    private static final Pattern AM = Pattern.compile(
            "^.{0,25}?\\b(?:de\\s+la\\s+manana|por\\s+la\\s+manana|del\\s+mati|al\\s+mati|de\\s+la\\s+madrugada|"
                    + "de\\s+matinada|am|a\\.m\\.)");
    private static final Pattern PM_ANYWHERE = Pattern.compile(
            "\\b(?:esta\\s+tarde|esta\\s+noche|aquesta\\s+tarda|aquest\\s+vespre|aquesta\\s+nit)\\b");

    // ---------- patrones de fecha ----------
    /** 25/10, 25-10-2026, 25/10/26 */
    private static final Pattern D_NUMERIC = Pattern.compile(
            "(?<![\\d/.:-])([0-3]?\\d)[/-]([01]?\\d)(?:[/-](\\d{4}|\\d{2}))?(?![\\d/:-])");
    /** 15 de octubre, 15 d'octubre, 15 oct., 15 de octubre de 2026 */
    private static final Pattern D_MONTH = Pattern.compile(
            "\\b([0-3]?\\d)\\s*(?:de\\s+|d')?(" + MONTH_ALT + ")\\.?(?![a-z])(?:\\s+(?:de|del)\\s+(\\d{4}))?");
    /** el dia 15 / jueves 15 / dijous dia 15 */
    private static final Pattern D_DAY_ONLY = Pattern.compile(
            "\\b(?:dia|(?:" + WEEKDAY_ALT + ")(?:\\s+dia)?)\\s+([0-3]?\\d)\\b(?![:.h/]\\d|\\s*h\\b)");
    private static final Pattern D_AFTER_TOMORROW = Pattern.compile(
            "\\b(?:pasado\\s+manana|passat\\s+dema|dema\\s+passat)\\b");
    private static final Pattern D_TOMORROW = Pattern.compile("\\b(?:manana|dema)\\b");
    private static final Pattern D_NOT_TOMORROW = Pattern.compile(
            "\\b(?:la|las|esta|cada|una|toda)\\s+manana\\b");
    private static final Pattern D_TODAY = Pattern.compile(
            "\\b(?:hoy|avui|esta\\s+tarde|esta\\s+noche|esta\\s+manana|aquesta\\s+tarda|aquest\\s+vespre|"
                    + "aquesta\\s+nit|aquest\\s+mati)\\b");
    private static final Pattern D_WEEKDAY = Pattern.compile("\\b(" + WEEKDAY_ALT + ")\\b");

    // ---------- utilidades ----------
    static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.replace('\u2019', '\'').replace('\u00b7', '.').replaceAll("\\s+", " ");
    }

    private static int toHour(String g) {
        Integer w = NUMBERS.get(g);
        return w != null ? w : Integer.parseInt(g);
    }

    private static final class TimeHit { int pos; int hour; int minute; boolean explicitHour; int end; }

    private static TimeHit findTime(String t) {
        TimeHit best = null;

        Matcher m = T_COLON.matcher(t);
        if (m.find()) {
            best = new TimeHit();
            best.pos = m.start(); best.end = m.end();
            best.hour = Integer.parseInt(m.group(1)); best.minute = Integer.parseInt(m.group(2));
            best.explicitHour = m.group(1).length() == 2 || best.hour >= 13 || best.hour == 0;
        }
        m = T_PREFIX.matcher(t);
        while (m.find()) {
            if (best != null && m.start() >= best.pos) break;
            String prefix = m.group(1);
            String hourText = m.group(2);
            boolean isWord = NUMBERS.containsKey(hourText);
            // "una de las dos", "entre las tres": con número en letra solo valen "a / sobre / hacia..."
            if (isWord && !(prefix.equals("a") || prefix.equals("sobre") || prefix.equals("hacia")
                    || prefix.startsWith("cap") || prefix.equals("vers"))) continue;
            TimeHit h = new TimeHit();
            h.pos = m.start(); h.end = m.end();
            h.hour = toHour(hourText);
            h.minute = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
            h.explicitHour = !isWord && (hourText.length() == 2 || h.hour >= 13 || h.hour == 0);
            String frac = m.group(4);
            if (frac != null) {
                if (frac.contains("media") || frac.contains("mitja")) h.minute = 30;
                else if (frac.startsWith("y") || frac.startsWith("i")) h.minute = 15;
                else { h.hour = (h.hour + 23) % 24; h.minute = 45; }
            }
            if (h.hour > 23) continue;
            best = h;
            break;
        }
        m = T_H.matcher(t);
        while (m.find()) {
            if (best != null && m.start() >= best.pos) break;
            String hh = m.group(1);
            // "1h de duración", "2 h": una cifra sola con h suele ser una duración
            if (hh.length() == 1 && m.group(2) == null) continue;
            TimeHit h = new TimeHit();
            h.pos = m.start(); h.end = m.end();
            h.hour = Integer.parseInt(hh);
            h.minute = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            h.explicitHour = hh.length() == 2 || h.hour >= 13 || h.hour == 0;
            best = h;
            break;
        }
        return best;
    }

    private static LocalDate rollYear(LocalDate d, boolean yearGiven, LocalDate today) {
        if (yearGiven) return d;
        long daysAgo = ChronoUnit.DAYS.between(d, today);
        if (daysAgo > 30) return d.plusYears(1); // "el 3/2" dicho en octubre = febrero del año que viene
        return d;                                  // pasada hace poco: se deja (se marca como pasada)
    }

    private static LocalDate safeDate(int y, int mo, int d) {
        try { return LocalDate.of(y, mo, d); } catch (Exception e) { return null; }
    }

    private static LocalDate findDate(String t, LocalDate today) {
        Matcher m = D_NUMERIC.matcher(t);
        while (m.find()) {
            int d = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2));
            int y = today.getYear();
            if (m.group(3) != null) {
                y = Integer.parseInt(m.group(3));
                if (y < 100) y += 2000;
            }
            LocalDate date = safeDate(y, mo, d);
            if (date != null) return rollYear(date, m.group(3) != null, today);
        }
        m = D_MONTH.matcher(t);
        while (m.find()) {
            int d = Integer.parseInt(m.group(1));
            int mo = MONTHS.get(m.group(2));
            int y = m.group(3) != null ? Integer.parseInt(m.group(3)) : today.getYear();
            LocalDate date = safeDate(y, mo, d);
            if (date != null) return rollYear(date, m.group(3) != null, today);
        }
        m = D_DAY_ONLY.matcher(t);
        while (m.find()) {
            int d = Integer.parseInt(m.group(1));
            if (d < 1 || d > 31) continue;
            LocalDate base = today.withDayOfMonth(1);
            if (d < today.getDayOfMonth()) base = base.plusMonths(1);
            for (int i = 0; i < 3; i++) {
                LocalDate date = safeDate(base.getYear(), base.getMonthValue(), d);
                if (date != null) return date;
                base = base.plusMonths(1);
            }
        }
        if (D_AFTER_TOMORROW.matcher(t).find()) return today.plusDays(2);

        String withoutMorning = D_NOT_TOMORROW.matcher(t).replaceAll(" ");
        if (D_TOMORROW.matcher(withoutMorning).find()) return today.plusDays(1);

        m = D_WEEKDAY.matcher(t);
        if (m.find()) {
            DayOfWeek wd = WEEKDAYS.get(m.group(1));
            int diff = wd.getValue() - today.getDayOfWeek().getValue();
            if (diff <= 0) diff += 7;
            return today.plusDays(diff);
        }
        if (D_TODAY.matcher(t).find()) return today;
        return null;
    }

    /** Analiza el texto. now = momento en que llegó el mensaje. */
    public static Result parse(String text, LocalDateTime now) {
        if (text == null || text.trim().isEmpty()) return null;
        String t = normalize(text);
        LocalDate today = now.toLocalDate();

        LocalDate date = findDate(t, today);
        TimeHit th = findTime(t);
        if (date == null && th == null) return null;

        LocalTime time = null;
        boolean guessed = false;
        if (th != null) {
            int hour = th.hour;
            String after = t.substring(th.end);
            boolean pm = PM.matcher(after).find() || PM_ANYWHERE.matcher(t).find();
            boolean am = AM.matcher(after).find();
            if (pm && hour < 12) hour += 12;
            else if (!am && !pm && !th.explicitHour && hour >= 1 && hour <= 7) { hour += 12; guessed = true; }
            if (hour > 23) return date == null ? null : new Result(date, null, false, date.isBefore(today));
            time = LocalTime.of(hour, th.minute);
        }
        boolean past = date != null && date.isBefore(today);
        return new Result(date, time, guessed, past);
    }
}
