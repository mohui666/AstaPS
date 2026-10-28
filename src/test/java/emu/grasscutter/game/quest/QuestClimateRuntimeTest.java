package emu.grasscutter.game.quest;

import static emu.grasscutter.game.props.PlayerProperty.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.*;
import emu.grasscutter.data.common.PointData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.ability.*;
import emu.grasscutter.game.ability.actions.ActionAddClimateMeter;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.quest.conditions.ConditionCurClimate;
import emu.grasscutter.game.quest.world.QuestSceneOverrides;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.PropValueOuterClass.PropValue;
import emu.grasscutter.net.proto.SetPlayerPropReqOuterClass.SetPlayerPropReq;
import emu.grasscutter.net.proto.SetPlayerPropRspOuterClass.SetPlayerPropRsp;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.recv.HandlerSetPlayerPropReq;
import emu.grasscutter.utils.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("GameData")
final class QuestClimateRuntimeTest {
    private boolean scripts;
    private ClimatePlayer player;
    private TestWorld world;
    private EntityAvatar avatar;
    private long now;
    private final Map<Integer, ScenePointEntry> previousPoints = new HashMap<>();
    private GadgetData previousGadget;

    @BeforeEach void prepare() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        player = new ClimatePlayer(); player.setUid(44001);
        world = TestWorld.create(player); world.transferResult = true;
        player.setScene(new TestWorld.TestScene(world, 3, SceneType.SCENE_WORLD));
        player.getScene().getPlayers().add(player); player.setSceneId(3);
        player.setSceneLoadState(Player.SceneLoadState.LOADED);
        var data = new Avatar() { @Override public Player getPlayer() { return player; } };
        data.setFightProperty(FightProperty.FIGHT_PROP_MAX_HP, 10000);
        data.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 10000);
        avatar = new EntityAvatar(player.getScene(), data);
        player.team.getActiveTeam().add(avatar);
        player.getScene().getEntities().put(avatar.getId(), avatar);
        now = 1_000_000;
        previousGadget = GameData.getGadgetDataMap().put(99004401,
                JsonUtils.decode("{id:99004401,type:'Worktop',jsonName:'ClimateTestPoint',tags:[]}", GadgetData.class));
    }

    @AfterEach void restore() {
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
        for (var entry : previousPoints.entrySet()) {
            if (entry.getValue() == null) GameData.getScenePointEntryMap().remove(entry.getKey());
            else GameData.getScenePointEntryMap().put(entry.getKey().intValue(), entry.getValue());
        }
        if (previousGadget == null) GameData.getGadgetDataMap().remove(99004401);
        else GameData.getGadgetDataMap().put(99004401, previousGadget);
    }

    @Test void coldUsesRealSecondsAndNativeHpLossWhilePauseAndRepeatedFramesDoNotAccumulate() {
        enter(1);
        tick(99);
        assertEquals(9900, player.getProperty(PROP_CUR_CLIMATE_METER));
        assertEquals(10000, hp());
        tick(1);
        assertEquals(10000, player.getProperty(PROP_CUR_CLIMATE_METER));
        assertEquals(9750, hp());
        QuestClimateAreas.update(player, false, now);
        assertEquals(9750, hp());
        player.setPaused(true);
        tick(50);
        assertEquals(9750, hp());
        player.setPaused(false);
        player.setProperty(PROP_IS_GAME_TIME_LOCKED, 1, false);
        tick(50);
        assertEquals(9750, hp());
        player.setProperty(PROP_IS_GAME_TIME_LOCKED, 0, false);
        tick(1);
        assertEquals(9500, hp());
        assertEquals(1, player.getProperty(PROP_CUR_CLIMATE_TYPE));
    }

    @Test void realWarmingGadgetDrainsMeterOnlyWithinItsNativeSearchShape() {
        enter(1); tick(90);
        var point = new EntityGadget(player.getScene(), 99004401, new Position(player.getPosition()),
                new Position(), null, 0, 0, false);
        point.getGlobalAbilityValues().put("IS_WARM_POINT", 1f);
        player.getScene().getEntities().put(point.getId(), point);
        tick(1);
        assertEquals(6500, player.getProperty(PROP_CUR_CLIMATE_METER));
        point.getGlobalAbilityValues().put("IS_WARM_POINT", 0f);
        tick(1);
        assertEquals(6600, player.getProperty(PROP_CUR_CLIMATE_METER));
        point.getGlobalAbilityValues().put("IS_WARM_POINT", 1f);
        point.getPosition().add(new Position(6.51f, 0, 0));
        tick(1);
        assertEquals(6700, player.getProperty(PROP_CUR_CLIMATE_METER));
        assertEquals(10000, hp());
    }

    @Test void actualFoodResistMixinHonorsSourceTrendClimateAndWhetherTheAvatarIsCurrent() throws Exception {
        enter(1);
        var items = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "BinOutput/Ability/Temp/ItemAbilities.json"))).getAsJsonArray();
        var nativeAbility = java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .map(value -> value.getAsJsonObject().getAsJsonObject("Default"))
                .filter(value -> value.get("abilityName").getAsString().equals("Anti_Cold_Food_Buff"))
                .findFirst().orElseThrow();
        var abilityData = JsonUtils.decode(nativeAbility, AbilityData.class);
        var ability = new Ability(abilityData, avatar, player);
        var modifier = abilityData.modifiers.get("Resist_lv2_1");
        assertEquals(AbilityMixinData.Type.ResistClimateMixin, modifier.modifierMixins[0].type);
        avatar.getInstancedModifiers().put(4401, new AbilityModifierController(ability, abilityData, modifier));
        assertEquals(.7f, QuestClimateRuntime.resistance(player, avatar, 1, "Climate", "Rise"), .0001);
        assertEquals(.7f, QuestClimateRuntime.resistance(player, avatar, 3, "Ability", "Rise"), .0001);
        assertEquals(1f, QuestClimateRuntime.resistance(player, avatar, 8, "Climate", "Rise"), .0001);
        assertEquals(1f, QuestClimateRuntime.resistance(player, avatar, 1, "Climate", "Fade"), .0001);
        tick(10);
        assertEquals(700, player.getProperty(PROP_CUR_CLIMATE_METER));
        var otherData = new Avatar() { @Override public Player getPlayer() { return player; } };
        otherData.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 10000);
        var other = new EntityAvatar(player.getScene(), otherData);
        player.team.getActiveTeam().add(other);
        avatar.getInstancedModifiers().clear();
        other.getInstancedModifiers().put(4401, new AbilityModifierController(ability, abilityData, modifier));
        assertEquals(1f, QuestClimateRuntime.resistance(player, avatar, 1, "Climate", "Rise"), .0001);
    }

    @Test void tatariEscalationUsesRealUnprotectedExposureAndElectricFairyResetsIt() {
        avatar.setFightProperty(FightProperty.FIGHT_PROP_MAX_HP, 100000);
        avatar.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 100000);
        enter(10); tick(66);
        assertEquals(62050, hp()); // 30 * (150 + 1%) then (450 + 3%).
        avatar.getGlobalAbilityValues().put("HAS_ELECTRIC_FAIRY_BUFF", 1f);
        tick(2);
        assertEquals(0, player.getProperty(PROP_CUR_CLIMATE_METER));
        assertEquals(62050, hp());
        avatar.getGlobalAbilityValues().put("HAS_ELECTRIC_FAIRY_BUFF", 0f);
        tick(6);
        assertEquals(60900, hp());
    }

    @Test void leavingTheAreaHasDampingThenFadeAndDoesNotEraseSavedMeterOnLogin() {
        enter(1); tick(100);
        player.getPosition().set(10000, 100, 10000);
        tick(5);
        assertEquals(0, player.getProperty(PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE));
        assertEquals(1, player.getProperty(PROP_CUR_CLIMATE_TYPE));
        assertEquals(10000, player.getProperty(PROP_CUR_CLIMATE_METER));
        tick(1);
        assertEquals(9000, player.getProperty(PROP_CUR_CLIMATE_METER));
        float before = hp();
        now += 9_000_000;
        QuestClimateAreas.update(player, true, now);
        assertEquals(9000, player.getProperty(PROP_CUR_CLIMATE_METER));
        assertEquals(before, hp());
        tick(15);
        assertEquals(0, player.getProperty(PROP_CUR_CLIMATE_METER));
        assertEquals(0, player.getProperty(PROP_CUR_CLIMATE_TYPE));
    }

    @Test void fullMistUsesOriginalNearestUnlockedPointAndRealTransferRotation() throws Exception {
        installPoints(91, 127, 128);
        player.getUnlockedScenePoints(3).addAll(List.of(127,128));
        enter(4);
        var expected = List.of(127,128).stream().map(id -> GameData.getScenePointEntryById(3,id).getPointData())
                .min(Comparator.comparingDouble(point -> planeDistance(player.getPosition(), point.getPos())))
                .orElseThrow();
        tick(19);
        assertTrue(world.transfers.isEmpty());
        tick(1);
        assertEquals(1, world.transfers.size());
        var transfer = world.transfers.getFirst();
        assertEquals(3, transfer.getSceneId());
        assertEquals(expected.getTranPos().getX(), transfer.getTeleportTo().getX());
        assertEquals(expected.getTranPos().getZ(), transfer.getTeleportTo().getZ());
        assertEquals(expected.getTranRot().getY(), transfer.getTeleportRot().getY());
        assertEquals(EnterReason.ForceDragBack, transfer.getEnterReason());
        assertEquals(0, player.getProperty(PROP_CUR_CLIMATE_METER));
        assertEquals(0, player.saves);
    }

    @Test void noUnlockedPointUsesTheFirstOriginalPointAndCanceledTransferStartsANewExposureRound() throws Exception {
        installPoints(316);
        world.transferResult = false;
        enter(7); tick(20);
        assertEquals(1, world.transfers.size());
        assertEquals(0, player.getProperty(PROP_CUR_CLIMATE_METER));
        assertEquals(GameData.getScenePointEntryById(3,316).getPointData().getTranPos().getX(),
                world.transfers.getFirst().getTeleportTo().getX());
        world.transferResult = true;
        tick(1);
        assertEquals(1, world.transfers.size());
        assertEquals(500, player.getProperty(PROP_CUR_CLIMATE_METER));
        tick(19);
        assertEquals(2, world.transfers.size());
        assertEquals(0, player.getProperty(PROP_CUR_CLIMATE_METER));
    }

    @Test void closingTheOriginalQuestClimateAreaStopsRisingAndFutureMistTransfers() {
        enter(7); tick(19);
        assertEquals(9500, player.getProperty(PROP_CUR_CLIMATE_METER));
        assertTrue(QuestClimateAreas.change(player, 3, 7, 0));
        QuestClimateAreas.update(player, true, now);
        tick(30);
        assertEquals(0, player.getProperty(PROP_CUR_CLIMATE_METER));
        assertEquals(0, player.getProperty(PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE));
        assertTrue(world.transfers.isEmpty());
        assertEquals(1, player.saves);
    }

    @Test void originalClimateConditionUsesActualAreaAndIgnoresReportedEventValues() {
        enter(7);
        var condition = new ConditionCurClimate();
        var actual = JsonUtils.decode("{type:'QUEST_COND_CUR_CLIMATE',param:[11,7]}",
                QuestData.QuestAcceptCondition.class);
        assertTrue(condition.execute(player, null, actual, "", 8,9999));
        tick(1);
        player.getPosition().set(10000, 100, 10000); tick(1);
        assertEquals(11, player.getProperty(PROP_CUR_CLIMATE_TYPE));
        assertFalse(condition.execute(player, null, actual, "", 11,7));
        var wildcard = JsonUtils.decode("{type:'QUEST_COND_CUR_CLIMATE',param:[0,0]}",
                QuestData.QuestAcceptCondition.class);
        assertTrue(condition.execute(player, null, wildcard, "", 11,7));
    }

    @Test void nativeAddClimateMeterActionUsesItsResourceValueAndCannotTargetAnotherPlayer() {
        enter(1); tick(40);
        var data = JsonUtils.decode("{abilityName:'ClimateTestAbility',modifiers:{}}", AbilityData.class);
        var ability = new Ability(data, avatar, player);
        var action = JsonUtils.decode("{$type:'AddClimateMeter',climateType:'Cold',value:-5}",
                AbilityModifier.AbilityModifierAction.class);
        assertTrue(new ActionAddClimateMeter().execute(ability, action, ByteString.copyFromUtf8("999999"), avatar));
        assertEquals(3500, player.getProperty(PROP_CUR_CLIMATE_METER));
        var stranger = new ClimatePlayer(); stranger.setUid(44002);
        var foreign = new Avatar() { @Override public Player getPlayer() { return stranger; } };
        foreign.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 1000);
        var entity = new EntityAvatar(player.getScene(), foreign);
        assertFalse(new ActionAddClimateMeter().execute(ability, action, ByteString.EMPTY, entity));
        assertFalse(QuestClimateRuntime.addMeter(player, "Unknown", -5));
        assertFalse(QuestClimateRuntime.addMeter(player, "Cold", Float.NaN));
        assertEquals(3500, player.getProperty(PROP_CUR_CLIMATE_METER));
    }

    @Test void installedDamageAndProtectionConstantsMatchTheActualClimateAbilityResource() throws Exception {
        var abilities = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "BinOutput/Ability/Temp/LevelAbilities/ConfigAbility_Climate.json"))).getAsJsonArray();
        var cold = java.util.stream.StreamSupport.stream(abilities.spliterator(), false)
                .map(value -> value.getAsJsonObject().getAsJsonObject("Default"))
                .filter(value -> value.get("abilityName").getAsString().equals("ClimateAbility_Cold"))
                .findFirst().orElseThrow().getAsJsonObject("modifiers");
        var damage = cold.getAsJsonObject("SERVER_ClimateAbility_Cold_Lv2");
        assertEquals(1, damage.get("thinkInterval").getAsInt());
        var action = damage.getAsJsonArray("onThinkInterval").get(0).getAsJsonObject();
        assertEquals(150, action.get("amount").getAsInt());
        assertEquals(.01f, action.get("amountByTargetMaxHPRatio").getAsFloat(), .0001);
        var warm = cold.getAsJsonObject("UNIQUE_WarmBuff");
        assertEquals(.2f, warm.get("thinkInterval").getAsFloat(), .0001);
        assertEquals(-5, warm.getAsJsonArray("onThinkInterval").get(0).getAsJsonObject().get("value").getAsInt());
    }

    @Test void realPropertyRequestsCannotForgeClimateOrPartiallyApplyABatch() throws Exception {
        enter(1); tick(10);
        var replies = new ArrayList<BasePacket>();
        var session = new GameSession(null) {
            @Override public Player getPlayer() { return player; }
            @Override public void send(BasePacket packet) { replies.add(packet); }
        };
        for (var property : List.of(PROP_CUR_CLIMATE_METER, PROP_CUR_CLIMATE_TYPE,
                PROP_CUR_CLIMATE_AREA_ID, PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE)) {
            var request = SetPlayerPropReq.newBuilder().addPropList(PropValue.newBuilder()
                    .setType(PROP_IS_SPRING_AUTO_USE.getId()).setVal(1))
                    .addPropList(PropValue.newBuilder().setType(property.getId()).setVal(9999)).build();
            new HandlerSetPlayerPropReq().handle(session, new byte[0], request.toByteArray());
            assertEquals(1, SetPlayerPropRsp.parseFrom(replies.getLast().getData()).getRetcode());
            assertEquals(0, player.getProperty(PROP_IS_SPRING_AUTO_USE));
            assertEquals(1000, player.getProperty(PROP_CUR_CLIMATE_METER));
            assertEquals(1, player.getProperty(PROP_CUR_CLIMATE_TYPE));
            assertEquals(1, player.getProperty(PROP_CUR_CLIMATE_AREA_ID));
            assertEquals(0, player.saves);
        }
        var ordinary = SetPlayerPropReq.newBuilder().addPropList(PropValue.newBuilder()
                .setType(PROP_IS_SPRING_AUTO_USE.getId()).setVal(1)).build();
        new HandlerSetPlayerPropReq().handle(session, new byte[0], ordinary.toByteArray());
        assertEquals(0, SetPlayerPropRsp.parseFrom(replies.getLast().getData()).getRetcode());
        assertEquals(1, player.getProperty(PROP_IS_SPRING_AUTO_USE));
        assertEquals(1, player.saves);
    }

    private void enter(int areaId) {
        var area = QuestClimateAreas.areas(3).stream().filter(value -> value.id == areaId).findFirst().orElseThrow();
        var point = area.points.getFirst();
        player.getPosition().set(point.x, Math.max(area.bottom, Math.min(200, area.top)), point.y);
        QuestClimateAreas.update(player, true, now);
        assertEquals(areaId, player.getProperty(PROP_CUR_CLIMATE_AREA_ID));
    }

    private void tick(int count) {
        for (int i = 0; i < count; i++) QuestClimateAreas.update(player, false, now += 1000);
    }

    private float hp() { return avatar.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP); }

    private void installPoints(int... ids) throws Exception {
        var points = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "BinOutput/Scene/Point/scene3_point.json"))).getAsJsonObject().getAsJsonObject("points");
        for (int id : ids) {
            var point = JsonUtils.decode(points.get(Integer.toString(id)), PointData.class); point.setId(id);
            previousPoints.put((3 << 16) + id, GameData.getScenePointEntryMap().put((3 << 16) + id,
                    new ScenePointEntry(3, point)));
        }
    }

    private static double planeDistance(Position a, Position b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ(); return dx*dx + dz*dz;
    }

    private static final class ClimatePlayer extends TestWorld.TestPlayer {
        final TeamManager team = new TeamManager(this);
        final QuestSceneOverrides overrides = new QuestSceneOverrides();
        int saves;
        @Override public TeamManager getTeamManager() { return team; }
        @Override public QuestSceneOverrides getQuestSceneOverrides() { return overrides; }
        @Override public void save() { saves++; }
        @Override public void sendPacket(BasePacket packet) {}
        @Override public void onPause() {}
        @Override public void onUnpause() {}
    }
}
