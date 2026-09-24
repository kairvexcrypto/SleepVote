package ru.warndev.sleepvote;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorldRuleTest {
    @ParameterizedTest
    @CsvSource({"50,1,1", "50,3,2", "50,4,2", "100,5,5", "1,101,2", "33,3,1"})
    void roundsRequiredVotesUp(int percentage, int players, int expected) {
        var rule = new WorldRule("world", percentage, 1, 1, 5, true, true, true, 12542, 23459, 0);
        assertEquals(expected, rule.required(players));
    }

    @Test
    void respectsNightBoundariesAndMinimumVotes() {
        var rule = new WorldRule("world", 50, 1, 3, 5, true, true, true, 12542, 23459, 0);
        assertEquals(3, rule.required(2));
        assertFalse(rule.night(12541));
        assertTrue(rule.night(12542));
        assertTrue(rule.night(23459));
        assertFalse(rule.night(23460));
        assertTrue(rule.night(24000 + 12542));
    }

    @Test
    void rejectsInvalidPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new WorldRule("world", 0, 1, 1, 5, true, true, true, 12542, 23459, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorldRule("world", 50, 1, 1, 0, true, true, true, 12542, 23459, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorldRule("world", 50, 1, 1, 5, true, true, true, 12542, 23459, 13000));
    }
}
