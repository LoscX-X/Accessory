package com.cendreal.accessory.core.configuration.lang;

import java.util.Locale;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Select supported client languages; console and unknown locales default to Simplified Chinese. */
public final class ClientLanguage {
    private static final List<String> TRANSLATIONS = List.of("en_US", "zh_TW", "ja_JP", "ko_KR", "de_DE", "fr_FR", "es_ES");
    private ClientLanguage() {}

    public static boolean english(CommandSender viewer) {
        return viewer instanceof Player player && english(player.locale());
    }

    public static boolean english(Locale locale) {
        return locale != null && "en".equalsIgnoreCase(locale.getLanguage());
    }

    public static String code(CommandSender viewer) {
        return localeCode(viewer instanceof Player player ? player.locale() : null);
    }

    public static List<String> translationCodes() { return TRANSLATIONS; }

    public static String localeCode(Locale locale) {
        if (locale == null) return "zh_CN";
        return switch (locale.getLanguage().toLowerCase(Locale.ROOT)) {
            case "en" -> "en_US";
            case "ja" -> "ja_JP";
            case "ko" -> "ko_KR";
            case "de" -> "de_DE";
            case "fr" -> "fr_FR";
            case "es" -> "es_ES";
            case "zh" -> "Hant".equalsIgnoreCase(locale.getScript())
                || (!"Hans".equalsIgnoreCase(locale.getScript())
                    && List.of("TW", "HK", "MO").contains(locale.getCountry().toUpperCase(Locale.ROOT))) ? "zh_TW" : "zh_CN";
            default -> "zh_CN";
        };
    }

    /** Normalize a template before substituting values. Never apply to player input. */
    public static String punctuation(String template) {
        if (template == null) return null;
        StringBuilder result = new StringBuilder(template.length());
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c >= '\uff01' && c <= '\uff5e') {
                result.append((char) (c - 0xfee0));
                continue;
            }
            result.append(switch (c) {
                case '\u3000' -> " ";
                case '\u3001', '\uff64' -> ",";
                case '\u3002', '\uff61' -> ".";
                case '\u3010', '\u3014', '\u300a', '\u3008', '\u3016' -> "[";
                case '\u3011', '\u3015', '\u300b', '\u3009', '\u3017' -> "]";
                case '\u300c', '\u300d', '\u300e', '\u300f', '\u201c', '\u201d' -> "\"";
                case '\u2018', '\u2019' -> "'";
                case '\u2026' -> "...";
                default -> String.valueOf(c);
            });
        }
        return result.toString();
    }
}
