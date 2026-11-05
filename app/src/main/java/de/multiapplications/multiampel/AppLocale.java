package de.multiapplications.multiampel;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;
import java.util.Locale;

public final class AppLocale {
    private static final String PREFS = "multiampel_settings";
    private static final String KEY_LANGUAGE = "language";
    private static final String GERMAN = "de";
    private static final String ENGLISH = "en";
    private AppLocale() {
    }

    public static String language(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_LANGUAGE, null);
        if (stored == null) {
            stored = systemLanguage();
            prefs.edit().putString(KEY_LANGUAGE, stored).apply();
        }
        return stored;
    }

    public static void setLanguage(Context context, String language) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE, language)
                .apply();
    }

    public static Context wrap(Context context) {
        Locale locale = new Locale(language(context));
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.setLocales(new LocaleList(locale));
        return context.createConfigurationContext(configuration);
    }

    private static String systemLanguage() {
        String language = Locale.getDefault().getLanguage();
        if (ENGLISH.equals(language)) {
            return ENGLISH;
        }
        if (GERMAN.equals(language)) {
            return GERMAN;
        }
        return ENGLISH;
    }
}