package emu.grasscutter.scripts;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.world.TestWorld;
import org.junit.jupiter.api.*;
import org.luaj.vm2.LuaTable;

final class GroupTempValuesTest {
    @Test
    void bossWaveCountersAreScopedToSceneAndGroupAndClearedOnUnload() throws Exception {
        ScriptLoader.init();
        boolean scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        try {
            var world = TestWorld.create(new TestWorld.TestPlayer());
            var first = new TestWorld.TestScene(world, 3, SceneType.SCENE_WORLD).getScriptManager();
            var other = new TestWorld.TestScene(world, 1075, SceneType.SCENE_ROOM).getScriptManager();
            assertTrue(first.setGroupTempValue(133301001, "NextWave", 2));
            assertEquals(2, first.getGroupTempValue(133301001, "NextWave"));
            assertEquals(0, first.getGroupTempValue(133301002, "NextWave"));
            assertEquals(0, other.getGroupTempValue(133301001, "NextWave"));
            first.clearGroupTempValues(133301001);
            assertEquals(0, first.getGroupTempValue(133301001, "NextWave"));
        } finally {
            Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
        }
    }

    @Test
    void luaAcceptsTheNativeCurrentGroupNamedGroupAndArrayGroupForms() {
        var current = new LuaTable();
        assertEquals(133301001, ScriptLib.tempValueGroup(current, 133301001));
        var named = new LuaTable();
        named.set("group_id", 133301002);
        assertEquals(133301002, ScriptLib.tempValueGroup(named, 133301001));
        var array = new LuaTable();
        array.set(1, org.luaj.vm2.LuaValue.valueOf(133301003));
        assertEquals(133301003, ScriptLib.tempValueGroup(array, 133301001));
    }
}
