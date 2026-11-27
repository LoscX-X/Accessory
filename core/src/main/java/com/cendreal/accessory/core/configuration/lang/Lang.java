package com.cendreal.accessory.core.configuration.lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public final class Lang {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();
    private static final LegacyComponentSerializer AMPERSAND_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();
    private final JavaPlugin plugin;
    private volatile LangCatalog catalog;
    public Lang(JavaPlugin plugin, String language) { this.plugin = plugin; reload(); }
    public void reload() { catalog = new LangCatalog(plugin); }
    public String text(CommandSender viewer, String key) { return catalog.text(viewer, "messages." + key, "Missing:" + key); }

    public String lang(String key) { return lang(null, key); }
    public String lang(CommandSender viewer, String key) {
        return format(catalog.text(viewer, "messages." + key, "Missing:" + key));
    }
    public List<String> langLines(String key) { return langLines(null, key); }
    public List<String> langLines(CommandSender viewer, String key) {
        return formatLines(catalog.lines(viewer, "messages." + key));
    }
    public Component langComponent(String key) { return langComponent(null, key); }
    public Component langComponent(CommandSender viewer, String key) {
        return formatComponent(catalog.text(viewer, "messages." + key, "Missing:" + key));
    }
    public Component langComponent(String key, Map<String, String> replacements) {
        return langComponent(null, key, replacements);
    }
    public Component langComponent(CommandSender viewer, String key, Map<String, String> replacements) {
        String raw = catalog.text(viewer, "messages." + key, "Missing:" + key);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return formatComponent(raw);
    }

    private String format(String input) {
        return LEGACY_SERIALIZER.serialize(formatComponent(input));
    }

    private List<String> formatLines(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> out = new ArrayList<>(source.size());
        for (String line : source) {
            out.add(format(line));
        }
        return out;
    }

    private Component formatComponent(String input) {
        String raw = input == null ? "" : input;
        try {
            return MINI_MESSAGE.deserialize(raw);
        } catch (Exception ignored) {
            return AMPERSAND_SERIALIZER.deserialize(raw);
        }
    }

    public String template(CommandSender viewer, String template) {
        String normalized = ClientLanguage.punctuation(template);
        if (normalized == null || normalized.isEmpty() || ClientLanguage.code(viewer).equals("zh_CN")) return normalized;
        try {
            String key = "t_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            return catalog.text(viewer, "templates." + key, normalized);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
