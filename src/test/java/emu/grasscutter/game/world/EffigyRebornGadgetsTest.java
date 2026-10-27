package emu.grasscutter.game.world;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.data.binout.AbilityMixinData;
import emu.grasscutter.data.binout.AbilityModifier;
import emu.grasscutter.data.binout.config.ConfigEntityGadget;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.ability.AbilityModifierController;
import emu.grasscutter.game.ability.actions.ActionCreateGadget;
import emu.grasscutter.game.entity.EntityClientGadget;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.entity.EntityMonster;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.managers.blossom.BlossomManager;
import emu.grasscutter.game.props.EntityIdType;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.net.proto.AbilityActionCreateGadgetOuterClass.AbilityActionCreateGadget;
import emu.grasscutter.net.proto.EvtCreateGadgetNotifyOuterClass.EvtCreateGadgetNotify;
import emu.grasscutter.net.proto.VectorOuterClass.Vector;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.server.game.GameServer;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

final class EffigyRebornGadgetsTest {
    private SceneStub scene;
    private MonsterStub boss;
    private final Map<Integer, GadgetData> oldGadgets = new HashMap<>();
    private ConfigEntityGadget oldConfig;

    @BeforeEach
    void setup() throws Exception {
        oldConfig = GameData.getGadgetConfigData().get("Effigy_RebornPart");
        for (int id : new int[] {42004010, 42004016, 42004017, 42004018, 42004201, 42004212,
                42004120, 42004706, 42004604, 42004623, 42004504, 42004507, 42004508, 42004512, 42906123}) {
            oldGadgets.put(id, GameData.getGadgetDataMap().get(id));
            String type = id == 42004507 || id == 42004512 ? "Bullet" : "Gadget";
            GameData.getGadgetDataMap().put(id, new Gson().fromJson(
                    "{\"id\":" + id + ",\"jsonName\":\"Effigy_RebornPart\",\"type\":\"" + type + "\"}", GadgetData.class));
        }
        GameData.getGadgetConfigData().put("Effigy_RebornPart", new Gson().fromJson(
                "{\"abilities\":[]}", ConfigEntityGadget.class));
        scene = allocate(SceneStub.class);
        scene.entities = new ConcurrentHashMap<>();
        scene.removed = new ArrayList<>();
        scene.world = allocate(WorldStub.class);
        scene.world.nextId = 0x800100;
        boss = boss(0x400100);
    }

    @AfterEach
    void restore() {
        oldGadgets.forEach((id, old) -> {
            if (old == null) GameData.getGadgetDataMap().remove((int) id);
            else GameData.getGadgetDataMap().put((int) id, old);
        });
        if (oldConfig == null) GameData.getGadgetConfigData().remove("Effigy_RebornPart");
        else GameData.getGadgetConfigData().put("Effigy_RebornPart", oldConfig);
    }

    @Test
    void actualCreateActionAcceptsPositionedRequestAndKeepsAllThreePrisms() throws Exception {
        var ability = allocate(Ability.class);
        field(ability, Ability.class, "owner", boss);
        var action = action();
        for (float x : new float[] {10, 14, 18}) {
            assertTrue(new ActionCreateGadget().execute(ability, action, request(x), boss));
        }
        var prisms = prisms();
        assertEquals(3, prisms.size());
        assertTrue(prisms.stream().allMatch(prism -> prism.getOwner() == boss));
        assertEquals(List.of(10f, 14f, 18f), prisms.stream().map(p -> p.getPosition().getX()).sorted().toList());
    }

    @Test
    void syntheticReplayDoesNotCreateAPrismAtTheBossOrigin() {
        assertTrue(EffigyRebornGadgets.createFromClientRequest(boss, action(), ByteString.EMPTY));
        assertTrue(prisms().isEmpty());
    }

    @Test
    void duplicateRequestsAndAFourthNormalPrismAreIgnored() {
        for (int i = 0; i < 4; i++) {
            assertTrue(EffigyRebornGadgets.createFromClientRequest(boss, action(), request(10)));
        }
        assertEquals(1, prisms().size());
        for (float x : new float[] {14, 18, 22}) {
            assertTrue(EffigyRebornGadgets.createFromClientRequest(boss, action(), request(x)));
        }
        assertEquals(3, prisms().size());
    }

    @Test
    void localPrismBeforeTheRequestPreventsTheMatchingServerCopy() {
        var local = client(0x900100, boss, 10);
        scene.entities.put(local.getId(), local);
        assertTrue(EffigyRebornGadgets.createFromClientRequest(boss, action(), request(10)));
        assertTrue(prisms().isEmpty());
        assertSame(local, scene.entities.get(local.getId()));
    }

    @Test
    void lateLocalPrismReplacesOnlyItsServerCounterpartAndNotAnotherBoss() throws Exception {
        for (float x : new float[] {10, 14, 18}) {
            EffigyRebornGadgets.createFromClientRequest(boss, action(), request(x));
        }
        var other = boss(0x400200);
        EffigyRebornGadgets.createFromClientRequest(other, action(), request(10));
        var local = client(0x900100, boss, 10);
        EffigyRebornGadgets.onClientGadgetCreated(local);

        assertEquals(1, scene.removed.size());
        assertSame(boss, ((EntityGadget) scene.removed.get(0)).getOwner());
        assertEquals(2, prisms().stream().filter(p -> p.getOwner() == boss).count());
        assertEquals(1, prisms().stream().filter(p -> p.getOwner() == other).count());
    }

    @Test
    void serverPrismsRetireWhenTheirOwnerUnloads() {
        EffigyRebornGadgets.createFromClientRequest(boss, action(), request(10));
        var prism = prisms().get(0);
        scene.entities.remove(boss.getId());
        prism.onTick(100);
        assertFalse(scene.entities.containsKey(prism.getId()));
        assertEquals(List.of(prism), scene.removed);
    }

    @Test
    void aNearbyBossRespawnDoesNotPurgeAnotherLivingBossPrisms() throws Exception {
        EffigyRebornGadgets.createFromClientRequest(boss, action(), request(10));
        var other = boss(0x400200);
        EffigyCombatHelper.purgeStaleRebornGadgets(other);
        assertEquals(1, prisms().size());
        assertTrue(scene.removed.isEmpty());
    }

    @Test
    void invalidRequestsDoNotCreateAnEntity() {
        assertFalse(EffigyRebornGadgets.createFromClientRequest(boss, action(), request(Float.NaN)));
        assertFalse(EffigyRebornGadgets.createFromClientRequest(boss, action(), ByteString.copyFrom(new byte[] {(byte) 0xFF})));
        assertTrue(prisms().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"42004201,4", "42004212,3", "42004120,4", "42004706,3", "42004604,4",
            "42004623,2", "42004504,3", "42004507,3", "42004512,3"})
    void nativeMultiPointMonsterMechanicsKeepEveryPosition(int id, int count) throws Exception {
        var ability = ability(boss);
        for (int i = 0; i < count; i++) {
            assertTrue(new ActionCreateGadget().execute(ability, action(id), request(10 + i * 4), boss));
        }
        assertEquals(count, gadgets(id).size());
        assertTrue(gadgets(id).stream().allMatch(g -> g.getOwner() == boss));
        assertTrue(new ActionCreateGadget().execute(ability, action(id), request(10), boss));
        assertEquals(count, gadgets(id).size(), "A duplicate request must not replace the other points");
    }

    @ParameterizedTest
    @CsvSource({"42004016,3", "42004017,2", "42004018,1"})
    void elitePrismsHonorTheirNativeMaximumWithoutLosingEarlierPoints(int id, int maximum) throws Exception {
        for (int i = 0; i <= maximum; i++) {
            assertTrue(new ActionCreateGadget().execute(ability(boss), action(id), request(10 + i * 4), boss));
        }
        assertEquals(maximum, gadgets(id).size());
    }

    @ParameterizedTest
    @ValueSource(ints = {42004006, 42004012})
    void pureEffigyPositionMarkersStayLocalEvenWithAPosition(int id) throws Exception {
        assertTrue(new ActionCreateGadget().execute(ability(boss), action(id), request(10), boss));
        assertTrue(prisms().isEmpty());
    }

    @Test
    void syntheticMultiPointMonsterReplayDoesNotAddAnOriginCopy() throws Exception {
        assertTrue(new ActionCreateGadget().execute(ability(boss), action(42004212), ByteString.EMPTY, boss));
        assertTrue(prisms().isEmpty());
    }

    @Test
    void nestedMonsterControllerKeepsItsChildrenAndAcceptsDirectOrRootLocalOwnership() throws Exception {
        new ActionCreateGadget().execute(ability(boss), action(42004507), request(10), boss);
        var controller = gadgets(42004507).get(0);
        for (float x : new float[] {14, 18, 22}) {
            assertTrue(new ActionCreateGadget().execute(ability(controller), action(42004508), request(x), controller));
        }
        assertEquals(3, controller.getChildren().size());
        assertSame(boss, MonsterSkillGadgets.rootMonster(controller));
        var direct = client(0x900100, controller, 14, 42004508);
        var rootOwned = client(0x900101, boss, 18, 42004508);
        assertSame(boss, MonsterSkillGadgets.rootMonster(direct));
        MonsterSkillGadgets.onClientGadgetCreated(direct);
        MonsterSkillGadgets.onClientGadgetCreated(rootOwned);
        assertEquals(1, gadgets(42004508).size());
        assertEquals(22f, gadgets(42004508).get(0).getPosition().getX());
        assertSame(controller, gadgets(42004508).get(0).getOwner());
    }

    @Test
    void sakuraSafezoneUsesTheModifierTargetBossAfterItsChargeControllerRetires() throws Exception {
        var parent = client(0x900100, boss, 0, 42004504);
        scene.entities.put(parent.getId(), parent);
        var controller = client(0x900101, parent, 0, 42004504);
        scene.entities.put(controller.getId(), controller);
        scene.entities.remove(parent.getId());
        assertNull(MonsterSkillGadgets.rootMonster(controller));
        var action = action(42906123);
        action.lifeByOwnerIsAlive = false;
        assertTrue(new ActionCreateGadget().execute(ability(controller), action, request(10), boss));
        var shield = gadgets(42906123).get(0);
        assertSame(boss, shield.getOwner());
        scene.entities.remove(controller.getId());
        shield.onTick(100);
        assertTrue(scene.entities.containsKey(shield.getId()));
    }

    @Test
    void independentMechanicsAreNotRemovedByAnUnrequestedOwnerLifetime() {
        var action = action(42004623);
        action.lifeByOwnerIsAlive = false;
        action.lifeByOwnerV2 = false;
        assertTrue(MonsterSkillGadgets.createFromClientRequest(boss, action, request(10)));
        var gadget = gadgets(42004623).get(0);
        scene.entities.remove(boss.getId());
        gadget.onTick(100);
        assertTrue(scene.entities.containsKey(gadget.getId()));
    }

    @Test
    void avatarCreationRetainsItsExistingPerOwnerReplacement() throws Exception {
        var avatar = allocate(AvatarStub.class);
        avatar.scene = scene;
        avatar.setId(0x200100);
        scene.entities.put(avatar.getId(), avatar);
        assertNull(MonsterSkillGadgets.rootMonster(avatar));
        var ability = ability(avatar);
        for (float x : new float[] {10, 14}) {
            assertTrue(new ActionCreateGadget().execute(ability, action(42004212), request(x), avatar));
        }
        assertEquals(1, gadgets(42004212).size());
        assertSame(avatar, gadgets(42004212).get(0).getOwner());
        assertEquals(14f, gadgets(42004212).get(0).getPosition().getX());
        assertEquals(1, scene.removed.size());
    }

    @ParameterizedTest
    @CsvSource({"1,true", "2,false", "3,true"})
    void hydroNativeCoreStatesAgreeAcrossGlobalModifierAndTickPaths(float value, boolean protectedState) {
        boss.data = new Gson().fromJson("{\"id\":20040401}", MonsterData.class);
        boss.getGlobalAbilityValues().put("_STATE_", value);
        EffigyCombatHelper.onGlobalValue(boss, "_STATE_", value);
        assertEquals(protectedState, boss.isLockHP());
        EffigyCombatHelper.onModifiersChanged(boss);
        assertEquals(protectedState, boss.isLockHP());
        EffigyCombatHelper.onMonsterTick(boss);
        assertEquals(protectedState, boss.isLockHP());
    }

    @ParameterizedTest
    @CsvSource({"0,true", "1,false"})
    void otherHypostasisCoreStatesKeepTheirOriginalMeaning(float value, boolean protectedState) {
        boss.getGlobalAbilityValues().put("_CORESTATE_", value);
        EffigyCombatHelper.onGlobalValue(boss, "_CORESTATE_", value);
        assertEquals(protectedState, boss.isLockHP());
        EffigyCombatHelper.onModifiersChanged(boss);
        assertEquals(protectedState, boss.isLockHP());
        EffigyCombatHelper.onMonsterTick(boss);
        assertEquals(protectedState, boss.isLockHP());
    }

    @Test
    void hydroShieldProtectionStillWinsOverTheExposedCoreState() {
        boss.data = new Gson().fromJson("{\"id\":20040401}", MonsterData.class);
        boss.getGlobalAbilityValues().put("_STATE_", 2f);
        var modifier = new AbilityModifier();
        var shield = new AbilityMixinData();
        shield.type = AbilityMixinData.Type.ShieldBarMixin;
        modifier.modifierMixins = new AbilityMixinData[] {shield};
        boss.getInstancedModifiers().put(1, new AbilityModifierController(null, null, modifier));
        EffigyCombatHelper.onGlobalValue(boss, "_STATE_", 2f);
        assertTrue(boss.isLockHP());
        EffigyCombatHelper.onModifiersChanged(boss);
        assertTrue(boss.isLockHP());
        EffigyCombatHelper.onMonsterTick(boss);
        assertTrue(boss.isLockHP());
    }

    private Ability ability(GameEntity owner) throws Exception {
        var ability = allocate(Ability.class);
        field(ability, Ability.class, "owner", owner);
        return ability;
    }

    private List<EntityGadget> gadgets(int id) {
        return prisms().stream().filter(g -> g.getGadgetId() == id).toList();
    }

    private MonsterStub boss(int id) throws Exception {
        var monster = allocate(MonsterStub.class);
        monster.scene = scene;
        monster.data = new Gson().fromJson("{\"id\":20040101}", MonsterData.class);
        field(monster, EntityMonster.class, "fightProperties", new Int2FloatOpenHashMap());
        field(monster, GameEntity.class, "globalAbilityValues", new ConcurrentHashMap<String, Float>());
        field(monster, GameEntity.class, "instancedModifiers", new ConcurrentHashMap<>());
        monster.setFightProperty(FightProperty.FIGHT_PROP_MAX_HP, 100f);
        monster.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 4f);
        monster.setId(id);
        scene.entities.put(id, monster);
        return monster;
    }

    private EntityClientGadget client(int id, GameEntity owner, float x) {
        return client(id, owner, x, 42004010);
    }

    private EntityClientGadget client(int id, GameEntity owner, float x, int gadgetId) {
        return new EntityClientGadget(scene, null, EvtCreateGadgetNotify.newBuilder()
                .setEntityId(id).setConfigId(gadgetId).setOwnerEntityId(owner.getId())
                .setInitPos(Vector.newBuilder().setX(x).setY(3).setZ(2)).build());
    }

    private List<EntityGadget> prisms() {
        return scene.entities.values().stream().filter(e -> e instanceof EntityGadget)
                .map(e -> (EntityGadget) e).toList();
    }

    private static AbilityModifierAction action() {
        return action(42004010);
    }

    private static AbilityModifierAction action(int id) {
        var action = new AbilityModifierAction();
        action.gadgetID = id;
        action.campID = 4001;
        action.lifeByOwnerIsAlive = true;
        action.lifeByOwnerV2 = true;
        return action;
    }

    private static ByteString request(float x) {
        return AbilityActionCreateGadget.newBuilder()
                .setPos(Vector.newBuilder().setX(x).setY(3).setZ(2)).build().toByteString();
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void field(Object target, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class MonsterStub extends EntityMonster {
        SceneStub scene;
        MonsterData data;
        private MonsterStub() { super(null, null, null, null, 1); }
        @Override public Scene getScene() { return scene; }
        @Override public MonsterData getMonsterData() { return data; }
        @Override public Position getPosition() { return new Position(); }
        @Override public Position getRotation() { return new Position(); }
    }

    private static final class AvatarStub extends EntityAvatar {
        SceneStub scene;
        private AvatarStub() { super((Scene) null, null); }
        @Override public Scene getScene() { return scene; }
        @Override public Avatar getAvatar() { return null; }
        @Override public Position getPosition() { return new Position(); }
        @Override public Position getRotation() { return new Position(); }
    }

    private static final class WorldStub extends World {
        int nextId;
        private WorldStub() { super((GameServer) null, null); }
        @Override public int getNextEntityId(EntityIdType type) { return nextId++; }
    }

    private static final class SceneStub extends Scene {
        Map<Integer, GameEntity> entities;
        List<GameEntity> removed;
        WorldStub world;
        private SceneStub() { super(null, null); }
        @Override public World getWorld() { return world; }
        @Override public Map<Integer, GameEntity> getEntities() { return entities; }
        @Override public GameEntity getEntityById(int id) { return entities.get(id); }
        @Override public BlossomManager getBlossomManager() { return null; }
        @Override public void addEntity(GameEntity entity) { entities.put(entity.getId(), entity); }
        @Override public void removeEntity(GameEntity entity) { removeEntity(entity, VisionType.VisionType_VISION_DIE); }
        @Override public void removeEntity(GameEntity entity, VisionType vision) {
            entities.remove(entity.getId());
            removed.add(entity);
        }
    }
}
