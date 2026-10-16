package cl.mesadoble;

import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.provider.UserDictionary;
import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SpellCheckerSession;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;
import android.view.textservice.TextServicesManager;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * El corrector del propio teléfono (el de Gboard o el de Xiaomi, con lo que ha aprendido) y su diccionario personal.
 * Se usa en el PRINCIPAL: sugerencias para la palabra que se escribe y para la palabra que se toca en la pantalla.
 */
final class Speller {

    private static SpellCheckerSession session;
    private static final Map<Integer, Consumer<List<String>>> waiting = new HashMap<>();
    private static int next = 1;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Speller() { }

    private static SpellCheckerSession session(Context c) {
        if (session != null) return session;
        TextServicesManager tsm = (TextServicesManager) c.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
        if (tsm == null) return null;
        try {
            session = tsm.newSpellCheckerSession(null, null, new SpellCheckerSession.SpellCheckerSessionListener() {
                @Override public void onGetSuggestions(SuggestionsInfo[] results) {
                    for (SuggestionsInfo si : results) deliver(si.getCookie(), collect(si));
                }
                @Override public void onGetSentenceSuggestions(SentenceSuggestionsInfo[] results) {
                    for (SentenceSuggestionsInfo ss : results) {
                        List<String> all = new ArrayList<>();
                        int cookie = -1;
                        for (int i = 0; i < ss.getSuggestionsCount(); i++) {
                            SuggestionsInfo si = ss.getSuggestionsInfoAt(i);
                            if (si == null) continue;
                            cookie = si.getCookie();
                            all.addAll(collect(si));
                        }
                        if (cookie >= 0) deliver(cookie, all);
                    }
                }
            }, true);
        } catch (Exception e) { session = null; }
        return session;
    }

    private static List<String> collect(SuggestionsInfo si) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < si.getSuggestionsCount(); i++) out.add(si.getSuggestionAt(i));
        return out;
    }

    private static void deliver(int cookie, List<String> s) {
        Consumer<List<String>> cb;
        synchronized (waiting) { cb = waiting.remove(cookie); }
        if (cb != null) main.post(() -> cb.accept(new ArrayList<>(new LinkedHashSet<>(s))));
    }

    /** Sugerencias del corrector del teléfono para una palabra (vacío si la da por buena o no hay corrector). */
    static void check(Context c, String word, Consumer<List<String>> out) {
        main.post(() -> {
            SpellCheckerSession s = session(c);
            if (s == null || word == null || word.trim().isEmpty()) { out.accept(new ArrayList<>()); return; }
            int id;
            synchronized (waiting) { id = next++; waiting.put(id, out); }
            try { s.getSentenceSuggestions(new TextInfo[]{new TextInfo(word, id, id)}, 5); }
            catch (Exception e) { deliver(id, new ArrayList<>()); }
            main.postDelayed(() -> deliver(id, new ArrayList<>()), 1500);   // si no contesta, sin sugerencias
        });
    }

    /** Palabras guardadas en el diccionario personal del teléfono (solo se pueden leer siendo el teclado activo). */
    static JSONArray userWords(Context c) {
        JSONArray a = new JSONArray();
        try (Cursor k = c.getContentResolver().query(UserDictionary.Words.CONTENT_URI,
                new String[]{UserDictionary.Words.WORD, UserDictionary.Words.FREQUENCY}, null, null, UserDictionary.Words.FREQUENCY + " DESC")) {
            while (k != null && k.moveToNext() && a.length() < 3000) a.put(k.getString(0));
        } catch (Exception ignored) { }
        return a;
    }
}
