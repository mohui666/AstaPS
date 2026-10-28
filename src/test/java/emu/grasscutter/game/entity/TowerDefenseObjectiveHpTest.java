package emu.grasscutter.game.entity;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.config.ConfigEntityGadget;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.monster.MonsterCurveData;
import emu.grasscutter.game.dungeons.DungeonManager;
import emu.grasscutter.game.dungeons.challenge.WorldChallenge;
import emu.grasscutter.game.dungeons.challenge.trigger.GuardTrigger;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ElementType;
import emu.grasscutter.game.props.EntityIdType;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.game.world.World;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.ChallengeDataNotifyOuterClass.ChallengeDataNotify;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.scripts.data.ScriptArgs;
import emu.grasscutter.utils.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

final class TowerDefenseObjectiveHpTest {
    private static final String MONOLITH = "SceneObj_Gear_Operator_Mamolu_Entity";
    private static final String OTHER = "Other_Invincible_Gadget";
    private final Map<Integer, GadgetData> previousGadgets = new HashMap<>();
    private final Map<String, ConfigEntityGadget> previousConfigs = new HashMap<>();
    private MonsterCurveData previousCurve;
    private RecordingScene scene;

    @BeforeEach
    void setup() throws Exception {
        for (var entry : Map.of(70350450, MONOLITH, 70360010, OTHER).entrySet()) {
            previousGadgets.put(entry.getKey(), GameData.getGadgetDataMap().get(entry.getKey().intValue()));
            previousConfigs.put(entry.getValue(), GameData.getGadgetConfigData().get(entry.getValue()));
            GameData.getGadgetDataMap().put(entry.getKey().intValue(), JsonUtils.decode(
                    "{\"id\":" + entry.getKey() + ",\"jsonName\":\"" + entry.getValue() + "\"}", GadgetData.class));
            GameData.getGadgetConfigData().put(entry.getValue(), JsonUtils.decode(
                    "{\"combat\":{\"property\":{\"HP\":5000,\"isInvincible\":true}}}", ConfigEntityGadget.class));
        }
        previousCurve = GameData.getMonsterCurveDataMap().get(11);
        var curve = JsonUtils.decode("""
                {"level":11,"curveInfos":[{"type":"GROW_CURVE_HP_ENVIRONMENT","value":4}]}
                """, MonsterCurveData.class);
        curve.onLoad();
        GameData.getMonsterCurveDataMap().put(11, curve);
        scene = allocate(RecordingScene.class);
        scene.world = allocate(RecordingWorld.class);
        scene.scripts = allocate(QuietScripts.class);
        scene.packets = new ArrayList<>();
    }

    @AfterEach
    void restoreTables() {
        previousGadgets.forEach((id, value) -> {
            if (value == null) GameData.getGadgetDataMap().remove(id.intValue());
            else GameData.getGadgetDataMap().put(id.intValue(), value);
        });
        previousConfigs.forEach((name, value) -> {
            if (value == null) GameData.getGadgetConfigData().remove(name);
            else GameData.getGadgetConfigData().put(name, value);
        });
        if (previousCurve == null) GameData.getMonsterCurveDataMap().remove(11);
        else GameData.getMonsterCurveDataMap().put(11, previousCurve);
    }

    @Test
    void monolithHasFiniteScaledHpAndRealDamageUpdatesTheGuardHudAndFailsAtZero() throws Exception {
        var gadget = gadget(70350450);
        scene.objective = gadget;
        var group = SceneGroup.of(gadget.getGroupId());
        var challenge = new WorldChallenge(scene, group, 188, 1, List.of(4, 1001), 0, 4,
                List.of(new GuardTrigger(1001)));
        scene.challenge = challenge;
        assertEquals(20_000f, gadget.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP));
        assertEquals(20_000f, gadget.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        challenge.start();
        assertEquals(100, challenge.getGuardEntityHpPercent());
        gadget.damage(8_000f, 0, ElementType.None);
        assertEquals(12_000f, gadget.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        assertEquals(60, challenge.getGuardEntityHpPercent());
        assertEquals(60, latestGuardPercent());
        assertTrue(challenge.inProgress());
        gadget.damage(12_000f, 0, ElementType.None);
        assertEquals(0, challenge.getGuardEntityHpPercent());
        assertEquals(0, latestGuardPercent());
        assertFalse(challenge.inProgress());
        assertFalse(challenge.isSuccess());
        assertTrue(gadget.isDead());
    }

    @Test
    void unrelatedInvincibleGadgetKeepsItsExistingProtection() {
        var gadget = gadget(70360010);
        assertEquals(Float.POSITIVE_INFINITY, gadget.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        gadget.damage(99_999f, 0, ElementType.None);
        assertEquals(Float.POSITIVE_INFINITY, gadget.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        assertFalse(gadget.isDead());
    }

    @Test
    void guardPercentageNeverOverflowsOrLeavesTheValidRange() {
        var gadget = gadget(70350450);
        var challenge = new WorldChallenge(scene, SceneGroup.of(gadget.getGroupId()), 188, 1,
                List.of(), 0, 1, List.of());
        challenge.setGuardEntity(gadget);
        gadget.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, Float.POSITIVE_INFINITY);
        assertEquals(100, challenge.getGuardEntityHpPercent());
        gadget.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 40_000f);
        assertEquals(100, challenge.getGuardEntityHpPercent());
        gadget.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, -1f);
        assertEquals(0, challenge.getGuardEntityHpPercent());
    }

    private EntityGadget gadget(int id) {
        var result = new EntityGadget(scene, id, new Position(), new Position(), null, 0, 0, false);
        result.setGroupId(233899001);
        result.setConfigId(1001);
        return result;
    }

    private int latestGuardPercent() throws Exception {
        for (int i = scene.packets.size() - 1; i >= 0; i--) {
            var packet = scene.packets.get(i);
            if (packet.getOpcode() != PacketOpcodes.ChallengeDataNotify) continue;
            var data = ChallengeDataNotify.parseFrom(packet.getData());
            if (data.getParamIndex() == 2) return data.getValue();
        }
        throw new AssertionError("No guard HP update was sent");
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static class RecordingWorld extends World {
        RecordingWorld() { super((Player) null); }
        @Override public int getNextEntityId(EntityIdType type) { return 0x400001; }
    }

    private static class RecordingScene extends Scene {
        RecordingWorld world;
        QuietScripts scripts;
        EntityGadget objective;
        WorldChallenge challenge;
        List<BasePacket> packets;
        RecordingScene() { super(null, null); }
        @Override public World getWorld() { return world; }
        @Override public List<Player> getPlayers() { return List.of(); }
        @Override public WorldChallenge getChallenge() { return challenge; }
        @Override public SceneScriptManager getScriptManager() { return scripts; }
        @Override public DungeonManager getDungeonManager() { return null; }
        @Override public int getSceneTimeSeconds() { return 0; }
        @Override public GameEntity getEntityById(int id) { return null; }
        @Override public GameEntity getEntityByConfigId(int configId, int groupId) { return objective; }
        @Override public void broadcastPacket(BasePacket packet) { packets.add(packet); }
        @Override public void killEntity(GameEntity entity, int attackerId) {}
    }

    private static class QuietScripts extends SceneScriptManager {
        QuietScripts() { super(null); }
        @Override public Future<?> callEvent(ScriptArgs args) { return CompletableFuture.completedFuture(null); }
        @Override public void removeMonstersInGroup(SceneGroup group) {}
    }
}
