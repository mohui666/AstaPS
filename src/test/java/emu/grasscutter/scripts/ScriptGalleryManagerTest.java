package emu.grasscutter.scripts;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.AbilityData;
import emu.grasscutter.data.excels.GalleryData;
import emu.grasscutter.game.ability.AbilityManager;
import emu.grasscutter.game.ability.AbilityModifierController;
import emu.grasscutter.game.entity.EntityTeam;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.ScriptArgs;
import emu.grasscutter.utils.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

final class ScriptGalleryManagerTest {
    private boolean scripts;
    private GalleryData previous;
    private Map<String, AbilityData> previousAbilities;
    private GalleryPlayer player;
    private Scene scene;
    private ScriptGalleryManager manager;
    private int now;
    private final List<ScriptArgs> events = new ArrayList<>();

    @BeforeEach
    void prepare() throws Exception {
        ScriptLoader.init();
        scripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        var data = JsonUtils.loadToList(FileUtils.getExcelPath("GalleryExcelConfigData.json"), GalleryData.class)
                .stream().filter(gallery -> gallery.getId() == 19102).findFirst().orElseThrow();
        previous = GameData.getGalleryDataMap().put(19102, data);
        previousAbilities = new HashMap<>(GameData.getAbilityDataMap());
        var config = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "BinOutput/Ability/Temp/ConfigAbility_Activity.json"))).getAsJsonArray();
        for (var entry : config) for (var raw : entry.getAsJsonObject().entrySet()) {
            var ability = raw.getValue().getAsJsonObject();
            String name = ability.get("abilityName").getAsString();
            if (Set.of("Dreamland_HitAndFail", "Dreamland_SpiritNum").contains(name))
                GameData.getAbilityDataMap().put(name, JsonUtils.decode(ability, AbilityData.class));
        }
        player = new GalleryPlayer();
        player.setUid(101);
        var world = TestWorld.create(player);
        scene = new TestWorld.TestScene(world,3,SceneType.SCENE_WORLD) {
            private SceneScriptManager manager;
            @Override public SceneScriptManager getScriptManager() {
                if (manager == null) manager = new SceneScriptManager(this) {
                    @Override public Future<?> callEvent(ScriptArgs args) {
                        events.add(args);
                        return CompletableFuture.completedFuture(null);
                    }
                };
                return manager;
            }
        };
        player.setScene(scene);
        scene.getPlayers().add(player);
        player.team.setEntity(new EntityTeam(player));
        now = 1800000000;
        manager = new ScriptGalleryManager(scene,() -> now);
    }

    @AfterEach
    void restore() {
        if (previous == null) GameData.getGalleryDataMap().remove(19102);
        else GameData.getGalleryDataMap().put(19102,previous);
        GameData.getAbilityDataMap().clear();
        GameData.getAbilityDataMap().putAll(previousAbilities);
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = scripts;
    }

    @Test
    void dreamlandUsesTheNativeAbilitiesAndReleasesOnlyWhatItAdded() {
        player.team.getTeamAbilityEmbryos().add("Existing_Ability");
        player.team.getTeamAbilityEmbryos().add("Dreamland_SpiritNum");
        player.abilities.addAbilityToEntity(player.team.getEntity(),"Dreamland_SpiritNum");
        var existing = player.team.getEntity().getInstancedAbilities().getFirst();
        assertEquals(0,manager.start(133301077,19102));
        assertTrue(manager.isMultiplayerBlocked());
        assertEquals(now,manager.running().startedAt());
        assertEquals(now+1500,manager.running().endTime());
        assertEquals(Set.of("Dreamland_SpiritNum","Dreamland_HitAndFail"),player.team.getEntity().getInstancedAbilities()
                .stream().map(a -> a.getData().abilityName).collect(java.util.stream.Collectors.toSet()));
        var galleryAbility = player.team.getEntity().getInstancedAbilities().stream()
                .filter(ability -> "Dreamland_HitAndFail".equals(ability.getData().abilityName)).findFirst().orElseThrow();
        // A client modifier-change creates this controller after receiving the real ability embryo.
        var modifier = galleryAbility.getData().modifiers.get("UNIQUE_HitAndFailBuff");
        assertNotNull(modifier);
        player.team.getEntity().getInstancedModifiers().put(777,
                new AbilityModifierController(galleryAbility,galleryAbility.getData(),modifier));
        assertEquals(0,manager.start(133301077,19102));
        assertEquals(2,player.team.getEntity().getInstancedAbilities().size());
        assertEquals(0,manager.stop(133301077,19102,false));
        assertFalse(manager.isMultiplayerBlocked());
        assertEquals(List.of(existing),player.team.getEntity().getInstancedAbilities());
        assertTrue(player.team.getEntity().getInstancedModifiers().isEmpty());
        assertEquals(Set.of("Existing_Ability","Dreamland_SpiritNum"),player.team.getTeamAbilityEmbryos());
        assertEquals(List.of(EventType.EVENT_GALLERY_START,EventType.EVENT_GALLERY_STOP),events.stream().map(e -> e.type).toList());
        assertEquals(3,events.getLast().param3);
        assertEquals(0,events.getLast().param2);
        assertEquals(-1,manager.stop(133301077,19102,false));
        assertEquals(2,events.size());
    }

    @Test
    void invalidOwnerOrGroupCannotStartAndTimeoutIsAnActualFailure() {
        assertEquals(-1,manager.start(133301078,19102));
        assertNull(manager.running());
        var guest = new TestWorld.TestPlayer();
        scene.getPlayers().add(guest);
        assertEquals(-1,manager.start(133301077,19102));
        scene.getPlayers().remove(guest);
        assertEquals(0,manager.start(133301077,19102));
        now += 1501;
        manager.onTick();
        assertNull(manager.running());
        assertEquals(1,events.getLast().param3);
        assertEquals(1,events.getLast().param2);
        assertTrue(player.team.getEntity().getInstancedAbilities().isEmpty());
    }

    private static final class GalleryPlayer extends TestWorld.TestPlayer {
        final TeamManager team = new TeamManager(this);
        final AbilityManager abilities = new AbilityManager(this);
        @Override public TeamManager getTeamManager() { return team; }
        @Override public AbilityManager getAbilityManager() { return abilities; }
        @Override public void sendPacket(BasePacket packet) {}
    }
}
