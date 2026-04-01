package com.winlator.cmod.core;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import androidx.preference.PreferenceManager;

import java.util.Locale;

public class LocaleHelper {

    private static final String[] SUPPORTED_LOCALES = {"en_US", "pt_BR", "ru_RU", "ja_JP", "in_ID"};

    public static Context setSystemLocale(Context context) {
        int index = PreferenceManager.getDefaultSharedPreferences(context)
                .getInt("lc_index", -1);

        if (index >= 0 && index < SUPPORTED_LOCALES.length) {
            Locale locale = new Locale(SUPPORTED_LOCALES[index].substring(0, 2));
            Locale.setDefault(locale);

            Configuration config = context.getResources().getConfiguration();
            config.setLocale(locale);
            config.setLayoutDirection(locale);

            return context.createConfigurationContext(config);
        }

        return context;
    }

    public static String[] getSupportedLocaleLabels() {
        return new String[]{
            "English",
            "Português (Brasil)",
            "Русский (Rusia)",
            "日本語 (japan)",
            "Indonesia"
        };
    }

    public static int getLocaleIndex(Context context) {
        LocaleList localeList = context.getResources().getConfiguration().getLocales();
        String locale = !localeList.isEmpty() ? localeList.get(0).toString() : "";

        for (int i = 0; i < SUPPORTED_LOCALES.length; i++) {
            if (locale.startsWith(SUPPORTED_LOCALES[i].substring(0, 2))) {
                return i;
            }
        }

        return 0;
    }
}
