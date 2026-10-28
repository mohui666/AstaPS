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
    void vehicleFeatureTagsComeFromTheNativeGroupAndRidersAreDeduplicatedByUid() throws Exception {
        var vehicleData = JsonUtils.loadToList(emu.grasscutter.utils.FileUtils.getExcelPath(
                "VehicleExcelConfigData.json"), emu.grasscutter.data.excels.avatar.VehicleData.class)
                .stream().filter(data -> data.getId() == 45003041).findFirst().orElseThrow();
        var features = JsonUtils.loadToList(emu.grasscutter.utils.FileUtils.getExcelPath(
                "FeatureTagGroupExcelConfigData.json"), emu.grasscutter.data.excels.FeatureTagGroupData.class)
                .stream().filter(data -> data.getId() == vehicleData.getFeatureTagGroupID()).findFirst().orElseThrow();
        var oldVehicle = GameData.getVehicleDataMap().put(vehicleData.getId(), vehicleData);
        var oldFeatures = GameData.getFeatureTagGroupDataMap().put(features.getId(), features);
        ScriptLoader.init();
        boolean scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        try {
            assertTrue(QuestVehicles.featureTags(45003041).contains(30014));
            var owner = new TestWorld.TestPlayer();
            var world = TestWorld.create(owner);
            var scene = new TestWorld.TestScene(world,3,SceneType.SCENE_WORLD);
            var vehicle = new emu.grasscutter.game.entity.EntityVehicle(scene,owner,45003041,0,new Position(),new Position());
            var first = emu.grasscutter.net.proto.VehicleMemberOuterClass.VehicleMember.newBuilder()
                    .setUid(101).setAvatarGuid(1).build();
            var changedAvatar = first.toBuilder().setAvatarGuid(2).build();
            assertTrue(QuestVehicles.board(vehicle,first));
            assertFalse(QuestVehicles.board(vehicle,changedAvatar));
            assertEquals(1,vehicle.getVehicleMembers().size());
            assertTrue(QuestVehicles.dismount(vehicle,changedAvatar));
            assertFalse(QuestVehicles.dismount(vehicle,first));
            assertTrue(vehicle.getVehicleMembers().isEmpty());
        } finally {
            if (oldVehicle == null) GameData.getVehicleDataMap().remove(vehicleData.getId());
            else GameData.getVehicleDataMap().put(vehicleData.getId(), oldVehicle);
            if (oldFeatures == null) GameData.getFeatureTagGroupDataMap().remove(features.getId());
            else GameData.getFeatureTagGroupDataMap().put(features.getId(), oldFeatures);
            Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
        }
    }

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
