package me.sebastiancheikh.zyonpay.menu;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlotParserTest {

    @Test
    void parsesSingleRangeAndMixedLists() {
        assertArrayEquals(new int[]{5}, SlotParser.parse(5, 54));
        assertArrayEquals(new int[]{3, 4, 5}, SlotParser.parse("3-5", 54));
        assertArrayEquals(new int[]{1, 10, 11, 12, 20}, SlotParser.parse(List.of(1, "10-12", "20"), 54));
    }

    @Test
    void removesDuplicatesKeepingOrder() {
        assertArrayEquals(new int[]{2, 3, 4}, SlotParser.parse(List.of("2-4", 3), 54));
    }

    @Test
    void rejectsInvalidSlots() {
        assertThrows(IllegalArgumentException.class, () -> SlotParser.parse(54, 54));
        assertThrows(IllegalArgumentException.class, () -> SlotParser.parse("5-3", 54));
        assertThrows(IllegalArgumentException.class, () -> SlotParser.parse("a-b", 54));
        assertThrows(IllegalArgumentException.class, () -> SlotParser.parse(-1, 54));
        assertThrows(IllegalArgumentException.class, () -> SlotParser.parse(true, 54));
    }
}
