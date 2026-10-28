package emu.grasscutter.scripts;

import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.GalleryData;
import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.entity.EntityTeam;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.server.packet.send.PacketAbilityChangeNotify;
import emu.grasscutter.server.packet.send.PacketGalleryStartNotify;
import emu.grasscutter.server.packet.send.PacketGalleryStopNotify;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.ScriptArgs;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.Utils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.function.IntSupplier;

/** MP_BLOCK galleries own the native Dreamland abilities and their multiplayer restriction. */
public final class ScriptGalleryManager {
    private final Scene scene;
    private final IntSupplier clock;
    private Run running;
    private static Map<String, List<String>> abilityGroups;

    public ScriptGalleryManager(Scene scene) {
        this(scene, Utils::getCurrentSeconds);
    }

    ScriptGalleryManager(Scene scene, IntSupplier clock) {
        this.scene = scene;
        this.clock = clock;
    }

    public record Run(GalleryData config, Player player, int startedAt,
            EntityTeam team, Set<String> addedEmbryos, List<Ability> addedAbilities) {
        public int endTime() { return startedAt + config.getDuration(); }
    }

    public Run running() {
        synchronized (scene) { return running; }
    }

    public boolean isMultiplayerBlocked() {
        synchronized (scene) { return running != null; }
    }

    public int start(int groupId, int galleryId) {
        synchronized (scene) {
            var config = GameData.getGalleryDataMap().get(galleryId);
            if (config == null || !"GALLERY_TYPE_MP_BLOCK".equals(config.getType())
                    || config.getSceneId() != scene.getId() || config.getControlGroupId() != groupId
                    || scene.getPlayerCount() != 1) return -1;
            if (running != null) return running.config().getId() == galleryId ? 0 : -1;
            var player = scene.getPlayers().getFirst();
            var team = player.getTeamManager().getEntity();
            if (team == null) return -1;
            var names = new LinkedHashSet<String>();
            for (String group : config.getTeamAbilityGroupList()) {
                var abilities = abilityGroups().get(group);
                if (abilities == null) throw new IllegalStateException("Missing gallery ability group " + group);
                names.addAll(abilities);
            }
            for (String name : names) if (GameData.getAbilityData(name) == null)
                throw new IllegalStateException("Missing gallery ability " + name);
            var addedEmbryos = new HashSet<String>();
            var before = Set.copyOf(team.getInstancedAbilities());
            for (String name : names) {
                if (player.getTeamManager().getTeamAbilityEmbryos().add(name)) addedEmbryos.add(name);
                if (team.getInstancedAbilities().stream().noneMatch(ability -> name.equals(ability.getData().abilityName)))
                    attachAbility(player, team, name);
            }
            var added = team.getInstancedAbilities().stream().filter(ability -> !before.contains(ability)).toList();
            running = new Run(config, player, clock.getAsInt(), team, Set.copyOf(addedEmbryos), added);
            player.sendPacket(new PacketAbilityChangeNotify(team.getId(), player.getTeamManager().getAbilityControlBlock()));
            player.sendPacket(new PacketGalleryStartNotify(galleryId, 1, running.startedAt(), running.endTime(), player.getUid()));
            scene.getScriptManager().callEvent(new ScriptArgs(groupId, EventType.EVENT_GALLERY_START, galleryId).setUid(player.getUid()));
            return 0;
        }
    }

    public int stop(int groupId, int galleryId, boolean failed) {
        return stop(groupId, galleryId, failed ? 4 : 3);
    }

    private int stop(int groupId, int galleryId, int reason) {
        synchronized (scene) {
            if (running == null) return -1;
            if (running.config().getId() != galleryId || running.config().getControlGroupId() != groupId) return -1;
            var ended = running;
            running = null;
            var player = ended.player();
            var team = ended.team();
            for (var ability : ended.addedAbilities()) {
                var modifiers = team.getInstancedModifiers().values().stream()
                        .filter(modifier -> modifier.getAbility() == ability).toList();
                for (var modifier : modifiers) if (modifier.getModifierData().onRemoved != null)
                    for (var action : modifier.getModifierData().onRemoved)
                        player.getAbilityManager().executeActionNow(ability, action, ByteString.EMPTY, team);
                if (ability.getData().onRemoved != null) for (var action : ability.getData().onRemoved)
                    player.getAbilityManager().executeActionNow(ability, action, ByteString.EMPTY, team);
                team.getInstancedModifiers().entrySet().removeIf(entry -> entry.getValue().getAbility() == ability);
                ability.getModifiers().clear();
                team.getInstancedAbilities().remove(ability);
            }
            player.getTeamManager().getTeamAbilityEmbryos().removeAll(ended.addedEmbryos());
            player.sendPacket(new PacketAbilityChangeNotify(team.getId(), player.getTeamManager().getAbilityControlBlock()));
            player.sendPacket(new PacketGalleryStopNotify(galleryId, reason));
            boolean failed = reason != 3 && reason != 9;
            scene.getScriptManager().callEvent(new ScriptArgs(groupId, EventType.EVENT_GALLERY_STOP, galleryId, failed ? 1 : 0)
                    .setParam3(reason).setUid(player.getUid()));
            if (failed && scene.getChallenge() != null && scene.getChallenge().inProgress()
                    && scene.getChallenge().getGroup().id == groupId) scene.getChallenge().fail();
            return 0;
        }
    }

    public void onTick() {
        synchronized (scene) {
            if (running != null && clock.getAsInt() >= running.endTime())
                stop(running.config().getControlGroupId(), running.config().getId(), 1);
        }
    }

    public void onPlayerLeave(Player player) {
        synchronized (scene) {
            if (running != null && running.player() == player)
                stop(running.config().getControlGroupId(), running.config().getId(), 5);
        }
    }

    private static void attachAbility(Player player, EntityTeam team, String name) {
        var data = GameData.getAbilityData(name);
        var ability = new Ability(data, team, player);
        team.getInstancedAbilities().add(ability);
        // Gallery teardown may run immediately after start; its own attach/remove actions must
        // finish in order so a queued attachment cannot recreate a modifier after teardown.
        if (data.onAdded != null) for (var action : data.onAdded)
            if (action.type != null) player.getAbilityManager().executeActionNow(ability, action, ByteString.EMPTY, team);
        var defaultModifier = data.modifiers.get("Default");
        if (defaultModifier != null && defaultModifier.onAdded != null) for (var action : defaultModifier.onAdded)
            if (action.type != null) player.getAbilityManager().executeActionNow(ability, action, ByteString.EMPTY, team);
    }

    private static synchronized Map<String, List<String>> abilityGroups() {
        if (abilityGroups != null) return abilityGroups;
        try {
            var json = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                    "BinOutput/AbilityGroup/AbilityGroup.json"))).getAsJsonObject();
            var result = new HashMap<String, List<String>>();
            for (var entry : json.entrySet()) {
                var list = entry.getValue().getAsJsonObject().getAsJsonArray("targetAbilities");
                if (list == null) continue;
                var names = new ArrayList<String>();
                for (var ability : list) names.add(ability.getAsJsonObject().get("abilityName").getAsString());
                result.put(entry.getKey(), List.copyOf(names));
            }
            abilityGroups = Map.copyOf(result);
            return abilityGroups;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read gallery ability groups", error);
        }
    }
}
