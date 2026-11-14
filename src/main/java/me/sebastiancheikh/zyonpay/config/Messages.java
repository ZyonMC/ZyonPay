package me.sebastiancheikh.zyonpay.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** messages.yml in MiniMessage format. Missing keys fall back to the file bundled in the jar. */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private volatile YamlConfiguration yaml = new YamlConfiguration();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream bundled = plugin.getResource("messages.yml")) {
            if (bundled != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            plugin.getSLF4JLogger().warn("Could not read the default messages", e);
        }
        this.yaml = loaded;
    }

    /** Unparsed text (lists are joined with newlines). */
    public String raw(String key) {
        Object value = yaml.get(key);
        if (value instanceof List<?> lines) {
            return String.join("\n", lines.stream().map(String::valueOf).toList());
        }
        return value == null ? "<red>Message not found: " + key : value.toString();
    }

    public Component get(String key, TagResolver... resolvers) {
        TagResolver all = TagResolver.builder()
                .resolver(Placeholder.parsed("prefix", yamlPrefix()))
                .resolvers(resolvers)
                .build();
        return MINI.deserialize(raw(key), all);
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(get(key, resolvers));
    }

    /** Plain-text placeholder: players cannot inject MiniMessage tags. */
    public static TagResolver tag(String name, String value) {
        return Placeholder.unparsed(name, value);
    }

    /** Placeholder parsed as MiniMessage (only for the plugin's own values, such as a color). */
    public static TagResolver parsedTag(String name, String value) {
        return Placeholder.parsed(name, value);
    }

    private String yamlPrefix() {
        return yaml.getString("prefix", "");
    }
}
