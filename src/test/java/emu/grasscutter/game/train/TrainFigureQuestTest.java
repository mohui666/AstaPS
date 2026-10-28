package emu.grasscutter.game.train;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.world.TestWorld;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.ServerGlobalValueChangeNotifyOuterClass.ServerGlobalValueChangeNotify;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import emu.grasscutter.utils.Utils;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class TrainFigureQuestTest {
    private static final AtomicInteger UID = new AtomicInteger(87000);
    private GadgetData previous;

    @BeforeEach
    void gadget() {
        previous = GameData.getGadgetDataMap().put(SnezhnayaTrainSystem.PLAYER_TRAIN_GADGET,
                JsonUtils.decode("{id:73094085,jsonName:'TestQuestTrain'}", GadgetData.class));
    }

    @AfterEach
    void restore() {
        if (previous == null) GameData.getGadgetDataMap().remove(SnezhnayaTrainSystem.PLAYER_TRAIN_GADGET);
        else GameData.getGadgetDataMap().put(SnezhnayaTrainSystem.PLAYER_TRAIN_GADGET, previous);
    }

    @Test
    void allExplicitReconstructedMappingsControlRealNativeVisibleTransforms() throws Exception {
        var catalog = TrainFigureCatalog.installed();
        assertEquals(14, catalog.figures().size());
        assertEquals("SGV_TrainCollection_02", catalog.figure(2).sgv());
        assertEquals("SGV_CarriageWallpaper_06", catalog.figure(14).sgv());
        var rows = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "BinOutput/Ability/Temp/GadgetAbilities/ConfigAbility_Scene_V7_0_SnezhnayaTrain.json")))
                .getAsJsonArray();
        var ability = java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .map(value -> value.getAsJsonObject().getAsJsonObject("Default"))
                .filter(value -> "SceneObj_Area_Zd_Property_SnezhnayaTrain_Interior_Head_01"
                        .equals(value.get("abilityName").getAsString())).findFirst().orElseThrow();
        var modifiers = ability.getAsJsonObject("modifiers");
        var mixins = modifiers.getAsJsonObject("SnezhnayaTrain_CollectionManager").getAsJsonArray("modifierMixins");
        for (var figure : catalog.figures()) {
            var mixin = java.util.stream.StreamSupport.stream(mixins.spliterator(), false)
                    .map(value -> value.getAsJsonObject()).filter(value -> figure.sgv()
                            .equals(value.get("globalValueKey").getAsString())).findFirst().orElseThrow();
            assertEquals("AttachModifierToGlobalValueMixin", mixin.get("$type").getAsString());
            var enabled = mixin.getAsJsonArray("modifierNameSteps").get(1).getAsString();
            var action = modifiers.getAsJsonObject(enabled).getAsJsonArray("onAdded").get(0).getAsJsonObject();
            assertEquals("EnableSceneTransformByName", action.get("$type").getAsString());
            assertTrue(action.get("setEnable").getAsBoolean());
            assertEquals(figure.transform(), action.getAsJsonArray("transformNames").get(0).getAsString());
        }
    }

    @Test
    void actualMainQuest700917RegistersExec138AndChangesItsOwnTrain() throws Exception {
        assertEquals(138, QuestExec.QUEST_EXEC_CHANGE_PLAYER_TRAIN_FIGURE_SGV.getValue());
        var player = player();
        var quest = quest(player, 700917);
        var action = quest.getQuestData().getFinishExec().stream()
                .filter(value -> value.getType() == QuestExec.QUEST_EXEC_CHANGE_PLAYER_TRAIN_FIGURE_SGV)
                .findFirst().orElseThrow();
        assertArrayEquals(new String[] {"3", "2", "1"}, action.getParam());
        new QuestSystem(null).triggerExec(quest, action, action.getParam());
        QuestManager.eventExecutor.submit(() -> {}).get(3, TimeUnit.SECONDS);
        assertEquals(1, player.getTrainFigures().get(2, 0));
        assertEquals(1f, train(player).getGlobalAbilityValues().get("SGV_TrainCollection_02"));
        assertEquals(1, player.saves);
    }

    @Test
    void theSevenUsedIdsHaveVisibleChangesAndReplayDoesNotDuplicateSavedRewards() throws Exception {
        var player = player();
        SnezhnayaTrainSystem.onEnterSceneDone(player);
        var train = train(player);
        assertEquals(0f, train.getGlobalAbilityValues().get("SGV_TrainCollection_02"));
        for (int id : new int[] {1, 2, 3, 4, 5, 6, 14}) {
            assertTrue(SnezhnayaTrainSystem.setFigure(player, 3, id, 1));
            var figure = TrainFigureCatalog.installed().figure(id);
            assertEquals(1f, train.getGlobalAbilityValues().get(figure.sgv()));
            var packet = ((TrainScene) player.getScene()).sent.getLast();
            assertEquals(PacketOpcodes.ServerGlobalValueChangeNotify, packet.getOpcode());
            var wire = ServerGlobalValueChangeNotify.parseFrom(packet.getData());
            assertEquals(train.getId(), wire.getEntityId());
            assertEquals(Utils.abilityHash(figure.sgv()), wire.getKeyHash());
            assertEquals(1f, wire.getValue());
        }
        assertEquals(7, player.saves);
        assertTrue(SnezhnayaTrainSystem.setFigure(player, 3, 2, 1));
        assertEquals(7, player.saves);
        assertTrue(SnezhnayaTrainSystem.setFigure(player, 3, 2, 0));
        assertEquals(0f, train.getGlobalAbilityValues().get("SGV_TrainCollection_02"));
    }

    @Test
    void rewardsCannotAffectOtherPlayersTrainsOrRunWithoutTheRealSceneAndOwnership() {
        var first = player();
        var other = player();
        other.setWorld(first.getWorld());
        other.setScene(first.getScene());
        SnezhnayaTrainSystem.onEnterSceneDone(first);
        SnezhnayaTrainSystem.onEnterSceneDone(other);
        var trains = first.getScene().getEntities().values().stream().map(entity -> (EntityGadget) entity).toList();
        assertEquals(2, trains.size());
        assertTrue(SnezhnayaTrainSystem.setFigure(first, 3, 2, 1));
        assertEquals(1, trains.stream().filter(entity -> entity.getGlobalAbilityValues()
                .get("SGV_TrainCollection_02") == 1f).count());
        assertEquals(0, other.getTrainFigures().get(2, 0));
        assertFalse(SnezhnayaTrainSystem.setFigure(first, 4, 2, 1));
        assertFalse(SnezhnayaTrainSystem.setFigure(first, 3, 999, 1));
        assertFalse(SnezhnayaTrainSystem.setFigure(first, 3, 2, 2));
        first.ownsWidget = false;
        assertFalse(SnezhnayaTrainSystem.setFigure(first, 3, 2, 0));
        first.ownsWidget = true;
        first.setScene(new TrainScene((TestWorld) first.getWorld(), 4));
        assertFalse(SnezhnayaTrainSystem.setFigure(first, 3, 2, 0));
    }

    @Test
    void persistedFiguresReappearInInitialEntityAbilityInfoAfterRelogAndSceneRefresh() {
        var player = player();
        assertTrue(SnezhnayaTrainSystem.setFigure(player, 3, 2, 1));
        assertTrue(SnezhnayaTrainSystem.setFigure(player, 3, 14, 1));
        var restored = player();
        restored.figures = JsonUtils.decode(JsonUtils.encode(player.getTrainFigures()), TrainFigureState.class);
        SnezhnayaTrainSystem.onEnterSceneDone(restored);
        var train = train(restored);
        assertEquals(1f, train.getGlobalAbilityValues().get("SGV_TrainCollection_02"));
        assertEquals(1f, train.getGlobalAbilityValues().get("SGV_CarriageWallpaper_06"));
        var values = train.toProto().getEntityAuthorityInfo().getAbilityInfo().getSgvDynamicValueMapList();
        for (String name : List.of("SGV_TrainCollection_02", "SGV_CarriageWallpaper_06")) {
            var matches = values.stream().filter(value -> name.equals(value.getKey().getStr())
                    || value.getKey().getHash() == Utils.abilityHash(name)).toList();
            assertEquals(1, matches.size(), "saved state must replace the captured baseline exactly once");
            assertEquals(1f, matches.getFirst().getFloatValue());
        }
        assertEquals(0, restored.saves);
    }

    @Test
    void actualWorldQuest7767601ChangesTheConfiguredWallpaper() throws Exception {
        var player = player();
        var quest = quest(player, 7767601);
        var action = quest.getQuestData().getFinishExec().getFirst();
        assertArrayEquals(new String[] {"3", "14", "1"}, action.getParam());
        assertTrue(new emu.grasscutter.game.quest.exec.ExecChangePlayerTrainFigureSgv()
                .execute(quest, action, action.getParam()));
        assertEquals(1f, train(player).getGlobalAbilityValues().get("SGV_CarriageWallpaper_06"));
    }

    private static GameQuest quest(Player player, int id) throws Exception {
        var parent = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "BinOutput/Quest/" + id / 100 + ".json"))).getAsJsonObject();
        JsonObject row = java.util.stream.StreamSupport.stream(parent.getAsJsonArray("subQuests").spliterator(), false)
                .map(value -> value.getAsJsonObject()).filter(value -> value.get("subId").getAsInt() == id)
                .findFirst().orElseThrow();
        var data = JsonUtils.decode(row, QuestData.class);
        return new GameQuest() {
            @Override public Player getOwner() { return player; }
            @Override public QuestData getQuestData() { return data; }
        };
    }

    private static FigurePlayer player() {
        var player = new FigurePlayer();
        player.setUid(UID.incrementAndGet());
        player.setScene(new TrainScene(TestWorld.create(player), 3));
        return player;
    }

    private static EntityGadget train(Player player) {
        return player.getScene().getEntities().values().stream().filter(entity -> entity instanceof EntityGadget)
                .map(entity -> (EntityGadget) entity).filter(entity -> entity.getCurveMoveInfo() != null)
                .findFirst().orElseThrow();
    }

    private static final class TrainScene extends TestWorld.TestScene {
        final List<BasePacket> sent = new ArrayList<>();
        TrainScene(TestWorld world, int id) { super(world, id, SceneType.SCENE_ROOM); }
        @Override public synchronized void addEntity(GameEntity entity) { getEntities().put(entity.getId(), entity); }
        @Override public void broadcastPacket(BasePacket packet) { sent.add(packet); }
    }

    private static final class FigurePlayer extends Player {
        private TrainFigureState figures = new TrainFigureState();
        final Inventory inventory = new Inventory(this) {
            @Override public int getItemCountById(int id) {
                return id == SnezhnayaTrainSystem.GREY_EYES_WIDGET && ownsWidget ? 1 : 0;
            }
        };
        boolean ownsWidget = true;
        int saves;
        @Override public Inventory getInventory() { return inventory; }
        @Override public TrainFigureState getTrainFigures() { return figures; }
        @Override public void save() { saves++; }
        @Override public void sendPacket(BasePacket packet) {}
    }
}
