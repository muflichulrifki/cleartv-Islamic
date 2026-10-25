package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.chrono.HijrahDate;
import java.time.temporal.ChronoField;
import java.util.Calendar;
import java.util.Locale;

/**
 * Prayer times for the zone a theme names, from one of two sources:
 * <ul>
 * <li>Malaysia: JAKIM's e-Solat, for a JAKIM zone ("prayer_zone": "TRG04").</li>
 * <li>Indonesia: Bimas Islam Kemenag RI through equran.id, for a province and regency or city
 * ("prayer_zone": "ID:Jawa Timur:Kab. Sidoarjo", the names exactly as equran.id lists them).</li>
 * </ul>
 * The whole year is fetched once and kept on disk, so the row under the clock needs the network only
 * when a new year starts. Every failure leaves the row as it was; a failed fetch is tried again an
 * hour later.
 */
final class PrayerTimes {
    private static final String TAG = "TvHome.Solat";
    private static final long RETRY_MS = 60L * 60 * 1000;
    /** JAKIM's keys, in the order the row shows them. Kemenag's days are stored under the same keys. */
    private static final String[] KEYS = {"fajr", "dhuhr", "asr", "maghrib", "isha"};
    static final String[] NAMES = {"Subuh", "Zohor", "Asar", "Maghrib", "Isyak"};
    /** The same five prayers as Indonesians write them. */
    static final String[] NAMES_ID = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    /** Month abbreviations as e-Solat writes them in its dates ("21-Okt-2026"). */
    private static final String[] MONTHS = {"jan", "feb", "mac", "apr", "mei", "jun", "jul", "ogo", "sep", "okt", "nov", "dis"};
    private static final String[] EN_MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final String[] HIJRI_MONTHS = {"Muharram", "Safar", "Rabiulawal", "Rabiulakhir", "Jamadilawal",
            "Jamadilakhir", "Rejab", "Syaaban", "Ramadan", "Syawal", "Zulkaedah", "Zulhijjah"};
    private static final String[] HIJRI_MONTHS_ID = {"Muharram", "Safar", "Rabiul Awal", "Rabiul Akhir", "Jumadil Awal",
            "Jumadil Akhir", "Rajab", "Syakban", "Ramadan", "Syawal", "Zulkaidah", "Zulhijah"};
    /** equran.id's monthly schedule (POST provinsi, kabkota, bulan, tahun); data from Bimas Islam Kemenag RI. */
    private static final String EQURAN = "https://equran.id/api/v2/shalat";

    /** True for an Indonesian zone, "ID:<provinsi>:<kabkota>". */
    static boolean indonesia(String zone) {
        return zone != null && zone.regionMatches(true, 0, "ID:", 0, 3);
    }

    /** The five names the row, the screensaver and the azan show for this zone. */
    static String[] names(String zone) {
        return indonesia(zone) ? NAMES_ID : NAMES;
    }

    /** "ID:Jawa Timur:Kab. Sidoarjo" to {"Jawa Timur", "Kab. Sidoarjo"}; null when it is not that form. */
    static String[] region(String zone) {
        if (!indonesia(zone)) return null;
        int c = zone.indexOf(':', 3);
        if (c < 0) return null;
        String prov = zone.substring(3, c).trim(), kab = zone.substring(c + 1).trim();
        return prov.isEmpty() || kab.isEmpty() ? null : new String[]{prov, kab};
    }

    /** The cache file's name: "solat-TRG04.json", "solat-ID_Jawa_Timur_Kab_Sidoarjo.json". */
    static String fileName(String zone) {
        return "solat-" + zone.replaceAll("[^A-Za-z0-9]+", "_") + ".json";
    }

    /** "2026-10-25" to "25-Oct-2026", the form {@link #dateKey} reads; "" when it is not a date. */
    static String isoToDate(String ymd) {
        try {
            LocalDate d = LocalDate.parse(ymd.trim());
            return String.format(Locale.ROOT, "%02d-%s-%04d", d.getDayOfMonth(), EN_MONTHS[d.getMonthValue() - 1], d.getYear());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * "2026-10-25" to its Hijri date as "1448-05-14" (Umm al-Qura calendar, plus {@code offsetDays});
     * "" when it is not a date. Kemenag's own Hijri date can be a day apart; a theme corrects that
     * with "hijri_offset".
     */
    static String isoToHijri(String ymd, int offsetDays) {
        try {
            HijrahDate h = HijrahDate.from(LocalDate.parse(ymd.trim()).plusDays(offsetDays));
            return String.format(Locale.ROOT, "%04d-%02d-%02d", h.get(ChronoField.YEAR),
                    h.get(ChronoField.MONTH_OF_YEAR), h.get(ChronoField.DAY_OF_MONTH));
        } catch (Exception e) {
            return "";
        }
    }

    /** One day's times, in minutes after midnight, and its Hijri date ("10 Jamadilawal 1448"). */
    static final class Day {
        final int[] minutes;
        final String hijri;

        Day(int[] minutes, String hijri) {
            this.minutes = minutes;
            this.hijri = hijri;
        }
    }

    /** Days added to the computed Hijri date of an Indonesian zone (theme key "hijri_offset", -2..2). Set by Config. */
    static volatile int hijriOffset;

    private PrayerTimes() {
    }

    private static File file(Context ctx, String zone) {
        File base = Config.dir(ctx);
        return base != null ? new File(base, fileName(zone)) : null;
    }

    /** The day of the cached year whose date is {@code key} ("21-10-2026"), or null. */
    static Day day(Context ctx, String zone, String key) {
        JSONArray days = read(file(ctx, zone));
        for (int i = 0; days != null && i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d == null || !key.equals(dateKey(d.optString("date")))) continue;
            int[] m = new int[KEYS.length];
            for (int k = 0; k < KEYS.length; k++) {
                m[k] = minutes(d.optString(KEYS[k]));
                if (m[k] < 0) return null;
            }
            if (indonesia(zone)) {
                // "21-10-2026" back to "2026-10-21"; the Hijri date is worked out here so a new offset applies at once.
                String iso = key.substring(6) + "-" + key.substring(3, 5) + "-" + key.substring(0, 2);
                return new Day(m, hijri(isoToHijri(iso, hijriOffset), HIJRI_MONTHS_ID));
            }
            return new Day(m, hijri(d.optString("hijri")));
        }
        return null;
    }

    /** True when the cache has no entry for today and no fetch failed in the last hour. */
    static boolean due(Context ctx, String zone, String todayKey) {
        if (day(ctx, zone, todayKey) != null) return false;
        return System.currentTimeMillis() - prefs(ctx).getLong("tried_" + zone, 0) >= RETRY_MS;
    }

    /** Downloads the year's times for the zone. Blocking: call from a background thread. */
    static boolean refresh(Context ctx, String zone) {
        prefs(ctx).edit().putLong("tried_" + zone, System.currentTimeMillis()).apply();
        File out = file(ctx, zone);
        if (out == null) return false;
        try {
            JSONArray days = indonesia(zone) ? fetchKemenag(zone) : fetchJakim(zone);
            if (days == null || days.length() == 0) throw new IllegalStateException("no prayer times");
            File tmp = new File(out.getPath() + ".new");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(days.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(out)) throw new IllegalStateException("rename failed");
            Log.i(TAG, "saved " + days.length() + " days for " + zone);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "fetch failed for " + zone + ": " + e.getMessage());
            return false;
        }
    }

    /** The year's days from JAKIM e-Solat, as it sends them. */
    private static JSONArray fetchJakim(String zone) throws Exception {
        byte[] body = get("https://www.e-solat.gov.my/index.php?r=esolatApi/TakwimSolat&period=year&zone="
                + java.net.URLEncoder.encode(zone, "UTF-8"), 512 * 1024);
        return new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONArray("prayerTime");
    }

    /**
     * This year's days from Kemenag via equran.id, one month per request, plus next January so the
     * screensaver knows the first Subuh of the new year. Each day is stored in e-Solat's shape
     * ({"date": "25-Oct-2026", "fajr": "03:46", ...}) so {@link #day} reads both sources alike.
     */
    private static JSONArray fetchKemenag(String zone) throws Exception {
        String[] r = region(zone);
        if (r == null) throw new IllegalStateException("prayer_zone must be ID:<provinsi>:<kabkota>");
        int year = Calendar.getInstance().get(Calendar.YEAR);
        JSONArray days = new JSONArray();
        for (int i = 0; i < 13; i++) {
            int y = year + i / 12, mon = i % 12 + 1;
            JSONArray jadwal;
            try {
                String req = new JSONObject().put("provinsi", r[0]).put("kabkota", r[1])
                        .put("bulan", mon).put("tahun", y).toString();
                JSONObject res = new JSONObject(new String(post(EQURAN, req, 256 * 1024), StandardCharsets.UTF_8));
                JSONObject data = res.optJSONObject("data");
                jadwal = data != null ? data.optJSONArray("jadwal") : null;
                if (jadwal == null || jadwal.length() == 0) throw new IllegalStateException("no jadwal for " + mon + "/" + y);
            } catch (Exception e) {
                if (i == 12) break; // next year may not be published yet; this year is enough
                throw e;
            }
            for (int k = 0; k < jadwal.length(); k++) {
                JSONObject d = jadwal.optJSONObject(k);
                String date = d != null ? isoToDate(d.optString("tanggal_lengkap")) : "";
                if (date.isEmpty()) continue;
                days.put(new JSONObject().put("date", date)
                        .put("fajr", d.optString("subuh")).put("dhuhr", d.optString("dzuhur"))
                        .put("asr", d.optString("ashar")).put("maghrib", d.optString("maghrib"))
                        .put("isha", d.optString("isya")));
            }
        }
        return days;
    }

    /** "21-10-2026" for a calendar day, the key {@link #dateKey} gives e-Solat's dates. */
    static String key(Calendar c) {
        return String.format(Locale.ROOT, "%02d-%02d-%04d", c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR));
    }

    /** "21-Okt-2026" (or "21-Oct-2026") to "21-10-2026"; "" when it is not a date. */
    static String dateKey(String date) {
        String[] p = date.trim().split("-");
        if (p.length != 3 || p[1].length() < 3) return "";
        String mon = p[1].substring(0, 3).toLowerCase(Locale.ROOT);
        int m = -1;
        for (int i = 0; i < MONTHS.length; i++) if (MONTHS[i].equals(mon)) m = i;
        // English spellings where they differ from Malay.
        if (m < 0) m = "mar".equals(mon) ? 2 : "may".equals(mon) ? 4 : "aug".equals(mon) ? 7
                : "oct".equals(mon) ? 9 : "dec".equals(mon) ? 11 : -1;
        if (m < 0) return "";
        try {
            return String.format(Locale.ROOT, "%02d-%02d-%04d", Integer.parseInt(p[0]), m + 1, Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** "13:01:00" to 781; -1 when it is not a time. */
    static int minutes(String hms) {
        String[] p = hms.trim().split(":");
        if (p.length < 2) return -1;
        try {
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            return h < 0 || h > 23 || m < 0 || m > 59 ? -1 : h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "1448-05-10" to "10 Jamadilawal 1448"; "" when it is not a date. */
    static String hijri(String ymd) {
        return hijri(ymd, HIJRI_MONTHS);
    }

    /** "1448-05-14" to "14 Jumadil Awal 1448" with Indonesian month names. */
    static String hijriId(String ymd) {
        return hijri(ymd, HIJRI_MONTHS_ID);
    }

    private static String hijri(String ymd, String[] months) {
        String[] p = ymd.trim().split("-");
        if (p.length != 3) return "";
        try {
            int m = Integer.parseInt(p[1]);
            if (m < 1 || m > 12) return "";
            return Integer.parseInt(p[2]) + " " + months[m - 1] + " " + Integer.parseInt(p[0]);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** The index of the next prayer after {@code now} minutes, or -1 when Isyak has passed. */
    static int next(int[] minutes, int now) {
        for (int i = 0; i < minutes.length; i++) if (minutes[i] > now) return i;
        return -1;
    }

    /** "dalam 12 min", "dalam 1 j 5 min", "dalam 2 j". */
    static String countdown(int minutesLeft) {
        int h = minutesLeft / 60, m = minutesLeft % 60;
        if (h == 0) return "dalam " + Math.max(1, m) + " min";
        return m == 0 ? "dalam " + h + " j" : "dalam " + h + " j " + m + " min";
    }

    /** "Maghrib 44 menit lagi" / "Isya 1 jam 5 menit lagi" for Indonesia, "Maghrib dalam 44 min" otherwise. */
    static String countdown(String zone, String name, int minutesLeft) {
        if (!indonesia(zone)) return name + " " + countdown(minutesLeft);
        int h = minutesLeft / 60, m = minutesLeft % 60;
        if (h == 0) return name + " " + Math.max(1, m) + " menit lagi";
        return name + " " + h + " jam" + (m == 0 ? "" : " " + m + " menit") + " lagi";
    }

    /** "05:49" in 24-hour time, "5:49" in 12-hour time (the clock beside it says AM or PM). */
    static String format(int minutes, boolean is24) {
        int h = minutes / 60, m = minutes % 60;
        if (is24) return String.format(Locale.ROOT, "%02d:%02d", h, m);
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.ROOT, "%d:%02d", h12, m);
    }

    private static JSONArray read(File f) {
        if (f == null || !f.isFile() || f.length() > 512 * 1024) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new JSONArray(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] get(String url, int maxBytes) throws Exception {
        return request(url, null, maxBytes);
    }

    private static byte[] post(String url, String json, int maxBytes) throws Exception {
        return request(url, json, maxBytes);
    }

    /** A GET, or a POST of {@code json} when it is not null; the body when the answer is 200. */
    private static byte[] request(String url, String json, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (json != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > maxBytes) throw new IllegalStateException("response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("prayer_times", Context.MODE_PRIVATE);
    }
}
