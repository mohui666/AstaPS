package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.utils.JsonUtils;
import org.junit.jupiter.api.Test;

final class QuestVehiclesTest {
    @Test
    void nativeAndClientCreationReuseOnlyTheSameOwnersVehicleAtTheSamePoint() throws Exception {
        ScriptLoader.init();
        boolean scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        var previous = GameData.getGadgetDataMap().put(45001001,
                JsonUtils.decode("{id:45001001,type:'Vehicle',jsonName:'Skiff_Normal_01'}", GadgetData.class));
        try {
            var owner = new TestWorld.TestPlayer();
            var friend = new TestWorld.TestPlayer();
            var world = TestWorld.create(owner);
            var scene = new TestWorld.TestScene(world,3,SceneType.SCENE_WORLD) {
                @Override public synchronized void addEntity(GameEntity entity) {
                    getEntities().put(entity.getId(), entity);
                }
            };
            var first = QuestVehicles.create(owner,scene,45001001,new Position(1,2,3),new Position());
            assertNotNull(first);
            assertSame(first, QuestVehicles.create(owner,scene,45001001,new Position(1,2,3),new Position()));
            var friends = QuestVehicles.create(friend,scene,45001001,new Position(1,2,3),new Position());
            assertNotSame(first, friends);
            QuestVehicles.create(owner,scene,45001001,new Position(10,2,3),new Position());
            assertEquals(3, scene.getEntities().size());
            assertEquals(2, QuestVehicles.owned(owner,scene,45001001).size());
            assertEquals(1, QuestVehicles.owned(friend,scene,45001001).size());
            assertNull(QuestVehicles.create(owner,scene,999999999,new Position(),new Position()));
        } finally {
            if (previous == null) GameData.getGadgetDataMap().remove(45001001);
            else GameData.getGadgetDataMap().put(45001001, previous);
            Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
        }
    }
}
