package net.zyonmc.zyonpay.command;

import net.zyonmc.zyonpay.config.Settings.TabSettings;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Tab-completion helpers. */
public final class Tabs {

    /** Names connected on any server of the network (filled by the Redis sync). */
    private static volatile Set<String> network = Set.of();
    private static volatile TabSettings settings = new TabSettings(0, false, 3);

    private Tabs() {
    }

    public static void configure(TabSettings tab) {
        settings = tab;
    }

    public static Set<String> network() {
        return network;
    }

    public static void setNetwork(Set<String> names) {
        network = Set.copyOf(names);
    }

    /** Players on this server and (unless {@code tab.online-only}) on the network, matching the prefix. */
    static List<String> players(String prefix) {
        if (prefix.length() < settings.completeChars()) {
            return List.of();
        }
        String lower = prefix.toLowerCase(Locale.ROOT);
        Set<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Bukkit.getOnlinePlayers().forEach(p -> all.add(p.getName()));
        if (!settings.onlineOnly()) {
            all.addAll(network);
        }
        return all.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
