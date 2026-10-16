package cl.mesadoble;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sugerencias en español sin internet: 25.000 palabras ordenadas por frecuencia
 * (lista FrequencyWords, licencia MIT). Completa la palabra, agrega tildes que faltan
 * y propone correcciones para errores de una o dos letras.
 */
public class Dict {

    private static Dict inst;
    private String[] words = new String[0], keys = new String[0];
    private volatile boolean ready;

    public static synchronized Dict get(Context c) {
        if (inst == null) {
            inst = new Dict();
            final Context app = c.getApplicationContext();
            new Thread(() -> inst.load(app), "dict").start();
        }
        return inst;
    }

    private void load(Context c) {
        List<String> w = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("palabras_es.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) { line = line.trim(); if (!line.isEmpty()) w.add(line); }
        } catch (Exception ignored) { }
        String[] ws = w.toArray(new String[0]);
        String[] ks = new String[ws.length];
        for (int i = 0; i < ws.length; i++) ks[i] = plain(ws[i]);
        words = ws; keys = ks; ready = true;
    }

    /** Quita tildes, diéresis y la tilde de la ñ, para comparar sin importar cómo se escribió. */
    static String plain(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toLowerCase().toCharArray()) {
            switch (ch) {
                case 'á': b.append('a'); break;
                case 'é': b.append('e'); break;
                case 'í': b.append('i'); break;
                case 'ó': b.append('o'); break;
                case 'ú': case 'ü': b.append('u'); break;
                case 'ñ': b.append('n'); break;   // "manana" sugiere "mañana"
                default: b.append(ch);
            }
        }
        return b.toString();
    }

    // ---- palabras habituales: las del diccionario personal del teléfono principal y las que se escriben seguido
    private final java.util.Map<String, Integer> learned = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> userWords = new java.util.HashMap<>();
    private android.content.SharedPreferences prefs;

    void attach(Context c) {
        if (prefs != null) return;
        prefs = c.getSharedPreferences("dict", Context.MODE_PRIVATE);
        try {
            org.json.JSONObject o = new org.json.JSONObject(prefs.getString("learned", "{}"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); learned.put(k, o.optInt(k)); }
            org.json.JSONArray u = new org.json.JSONArray(prefs.getString("user", "[]"));
            for (int i = 0; i < u.length(); i++) userWords.put(u.optString(i).toLowerCase(), u.length() - i);
        } catch (Exception ignored) { }
    }

    /** El diccionario personal del principal (llega por la conexión). */
    void setUser(org.json.JSONArray a) {
        userWords.clear();
        for (int i = 0; a != null && i < a.length(); i++) userWords.put(a.optString(i).toLowerCase(), a.length() - i);
        if (prefs != null) prefs.edit().putString("user", a == null ? "[]" : a.toString()).apply();
    }

    /** Una palabra escrita completa: cuenta para las sugerencias. */
    void learn(String w) {
        if (w == null || w.length() < 2) return;
        String k = w.toLowerCase();
        learned.put(k, learned.getOrDefault(k, 0) + 1);
        if (learned.size() > 3000) {   // se olvidan las menos usadas
            String min = null; int mv = Integer.MAX_VALUE;
            for (java.util.Map.Entry<String, Integer> e : learned.entrySet()) if (e.getValue() < mv) { mv = e.getValue(); min = e.getKey(); }
            if (min != null) learned.remove(min);
        }
        if (prefs != null) prefs.edit().putString("learned", new org.json.JSONObject(learned).toString()).apply();
    }

    /** Palabras habituales que empiezan como lo escrito (las más usadas primero). */
    private List<String> habitual(String key, int n) {
        java.util.Map<String, Integer> score = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Integer> e : learned.entrySet())
            if (e.getValue() >= 2 && plain(e.getKey()).startsWith(key) && plain(e.getKey()).length() > key.length()) score.put(e.getKey(), e.getValue() * 10);
        for (java.util.Map.Entry<String, Integer> e : userWords.entrySet())
            if (plain(e.getKey()).startsWith(key) && plain(e.getKey()).length() > key.length()) score.merge(e.getKey(), 5 + Math.min(20, e.getValue()), Integer::sum);
        List<String> l = new ArrayList<>(score.keySet());
        java.util.Collections.sort(l, (x, y) -> score.get(y) - score.get(x));
        return l.size() > n ? l.subList(0, n) : l;
    }

    /** Hasta n sugerencias para la palabra que se está escribiendo. */
    public List<String> suggest(String typed, int n) {
        List<String> out = new ArrayList<>();
        if (typed.isEmpty()) return out;
        if (!ready) { for (String h : habitual(plain(typed), n)) out.add(matchCase(typed, h)); return out; }
        String low = typed.toLowerCase(), key = plain(typed);
        Set<String> res = new LinkedHashSet<>();
        // 0) las palabras habituales del teléfono van primero
        res.addAll(habitual(key, n));
        // 1) misma palabra con tildes correctas ("esta" → "está"), si es más común
        for (int i = 0; i < keys.length; i++)
            if (keys[i].equals(key)) { if (!words[i].equals(low)) res.add(words[i]); break; }
        // 2) palabras que empiezan igual (las más usadas primero)
        for (int i = 0; i < keys.length && res.size() < n; i++)
            if (keys[i].length() > key.length() && keys[i].startsWith(key)) res.add(words[i]);
        // 3) correcciones de tipeo si la palabra no existe
        boolean known = false;
        for (String k : keys) if (k.equals(key)) { known = true; break; }
        if (!known && key.length() >= 3) {
            int max = key.length() >= 6 ? 2 : 1;
            for (int i = 0; i < keys.length && res.size() < n + 1; i++) {
                String k = keys[i];
                if (Math.abs(k.length() - key.length()) > max) continue;
                if (distance(key, k, max) <= max) res.add(words[i]);
            }
        }
        for (String r : res) { out.add(matchCase(typed, r)); if (out.size() >= n) break; }
        return out;
    }

    /** Respeta mayúscula inicial o todo en mayúsculas. */
    private static String matchCase(String typed, String w) {
        if (typed.length() > 1 && typed.equals(typed.toUpperCase())) return w.toUpperCase();
        if (Character.isUpperCase(typed.charAt(0))) return Character.toUpperCase(w.charAt(0)) + w.substring(1);
        return w;
    }

    /** Distancia de edición con corte temprano. */
    private static int distance(String a, String b, int max) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > max) return max + 1;
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
