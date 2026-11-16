package net.zyonmc.zyonpay.menu;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Parses {@code slot: 5}, {@code slots: [1, "10-16"]} or {@code slots: "3-5"}. */
final class SlotParser {

    private SlotParser() {
    }

    /** @throws IllegalArgumentException if a slot is invalid or outside {@code [0, size)} */
    static int[] parse(Object raw, int size) {
        Set<Integer> slots = new LinkedHashSet<>();
        collect(raw, slots);
        for (int slot : slots) {
            if (slot < 0 || slot >= size) {
                throw new IllegalArgumentException("slot " + slot + " is outside the menu (0-" + (size - 1) + ")");
            }
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void collect(Object raw, Set<Integer> out) {
        if (raw instanceof List<?> list) {
            list.forEach(element -> collect(element, out));
        } else if (raw instanceof Number number) {
            out.add(number.intValue());
        } else if (raw instanceof String text) {
            parseText(text.trim(), out);
        } else {
            throw new IllegalArgumentException("invalid slot: " + raw);
        }
    }

    private static void parseText(String text, Set<Integer> out) {
        try {
            int dash = text.indexOf('-', 1);
            if (dash < 0) {
                out.add(Integer.parseInt(text));
                return;
            }
            int from = Integer.parseInt(text.substring(0, dash).trim());
            int to = Integer.parseInt(text.substring(dash + 1).trim());
            if (to < from) {
                throw new IllegalArgumentException("reversed range: " + text);
            }
            for (int slot = from; slot <= to; slot++) {
                out.add(slot);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid slot: " + text);
        }
    }
}
