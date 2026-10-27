package emu.grasscutter.game.quest.content;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class ContentGadgetStateChangeTest {
    @Test
    void storyGadgetMustMatchItsGroupConfigAndNewState() {
        int[] target = {220321008,8036};
        assertTrue(ContentGadgetStateChange.matches(target, "201", 220321008,8036,201));
        assertFalse(ContentGadgetStateChange.matches(target, "201", 220321009,8036,201));
        assertFalse(ContentGadgetStateChange.matches(target, "201", 220321008,8035,201));
        assertFalse(ContentGadgetStateChange.matches(target, "201", 220321008,8036,0));
        assertFalse(ContentGadgetStateChange.matches(target, "bad", 220321008,8036,201));
    }
}
