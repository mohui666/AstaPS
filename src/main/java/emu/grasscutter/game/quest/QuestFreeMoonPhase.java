package emu.grasscutter.game.quest;

import com.google.gson.JsonParser;
import emu.grasscutter.GameConstants;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.server.packet.send.PacketPrivateChatNotify;
import emu.grasscutter.server.packet.send.PacketServerGlobalValueChangeNotify;
import emu.grasscutter.server.packet.send.PacketTeamMoonPhaseChangeNotify;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;

/**
 * Explicit AstaPS replacement for the missing native refresh policy 200001: a real quest
 * activation grants the installed Damselette phase until the next server quest-day boundary.
 * The phase ID, fixed level and SGV names retain their original installed resource values.
 */
public final class QuestFreeMoonPhase {
    public record Phase(int type, int level) {}
    private record Settings(int teamType, int freeType, int freeLevel, String typeSgv, String levelSgv,
            String notice) {}

    private static final class Installed { static final Settings SETTINGS = load(); }

    private QuestFreeMoonPhase() {}

    private static Settings load() {
        try {
            var custom = JsonParser.parseString(Files.readString(FileUtils.getDataPath(
                    "QuestFreeMoonPhaseReconstruction.json"))).getAsJsonObject();
            if (!custom.get("reconstructed").getAsBoolean()
                    || !custom.get("expiryRule").getAsString().equals("NEXT_SERVER_QUEST_DAY")
                    || custom.get("missingNativeRefreshPolicyId").getAsInt() != 200001)
                throw new IllegalStateException("Unsupported free moon reconstruction rule");
            int teamType = 0, freeType = 0, refreshId = 0;
            String typeSgv = null, levelSgv = null;
            var constants = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                    "ExcelBinOutput/MoonPhaseConstValueExcelConfigData.json"))).getAsJsonArray();
            for (var value : constants) {
                var row = value.getAsJsonObject();
                if (!row.has("name")) continue;
                String text = row.getAsJsonArray("value").get(0).getAsString();
                switch (row.get("name").getAsString()) {
                    case "MOON_PHASE_CONST_VALUE_MOON_PHASE_TEAM_ID" -> teamType = Integer.parseInt(text);
                    case "MOON_PHASE_CONST_VALUE_MOON_PHASE_DAMSELETTE_ID" -> freeType = Integer.parseInt(text);
                    case "MOON_PHASE_CONST_VALUE_MOON_PHASE_DAMSELETTE_REFRESH_ID" -> refreshId = Integer.parseInt(text);
                    case "MOON_PHASE_CONST_VALUE_MOON_PHASE_TYPE_SGV_KEY" -> typeSgv = text;
                    case "MOON_PHASE_CONST_VALUE_MOON_PHASE_LEVEL_SGV_KEY" -> levelSgv = text;
                    default -> { }
                }
            }
            int fixedLevel = 0;
            var types = JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                    "ExcelBinOutput/MoonPhaseTypeExcelConfigData.json"))).getAsJsonArray();
            for (var value : types) {
                var row = value.getAsJsonObject();
                if (row.get("JDHOOEMGANE").getAsInt() == freeType
                        && row.has("GNBDGGPIKGI") && row.get("GNBDGGPIKGI").getAsString()
                                .equals("MOON_PHASE_EXTRA_LEVEL_TYPE_FIXED"))
                    fixedLevel = row.get("JGBDDOAPICP").getAsInt();
            }
            if (teamType <= 0 || freeType <= 0 || teamType == freeType || refreshId != 200001
                    || fixedLevel <= 0 || !"SGV_MoonPhaseType".equals(typeSgv)
                    || !"SGV_MoonPhaseLevel".equals(levelSgv))
                throw new IllegalStateException("Missing native free moon phase resource");
            return new Settings(teamType, freeType, fixedLevel, typeSgv, levelSgv,
                    custom.get("notice").getAsString());
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read free moon phase resources", error);
        }
    }

    public static Phase current(Player player) { return current(player, Instant.now().getEpochSecond()); }

    static Phase current(Player player, long now) {
        var settings = Installed.SETTINGS;
        var state = player.getQuestFreeMoonPhase();
        synchronized (state) {
            boolean active = state.getActivatedAt() > 0 && state.getQuestId() > 0
                    && now >= state.getActivatedAt()
                    && QuestTimeConditions.dayIndex(now) == QuestTimeConditions.dayIndex(state.getActivatedAt());
            return active ? new Phase(settings.freeType, settings.freeLevel)
                    : new Phase(settings.teamType, player.getTeamManager().getMoonsignLevel());
        }
    }

    public static boolean activate(GameQuest quest) {
        return activate(quest.getOwner(), quest.getSubQuestId(), Instant.now().getEpochSecond());
    }

    static boolean activate(Player player, int questId, long now) {
        if (questId <= 0 || now <= 0) return false;
        var state = player.getQuestFreeMoonPhase();
        synchronized (state) {
            state.activate(now, questId);
            player.save();
        }
        sendState(player, true, now);
        player.sendPacket(new PacketPrivateChatNotify(GameConstants.SERVER_CONSOLE_UID, player.getUid(),
                Installed.SETTINGS.notice));
        return true;
    }

    /** Restores current state without replaying the one-time activation animation. */
    public static void sendState(Player player, boolean activation) {
        sendState(player, activation, Instant.now().getEpochSecond());
    }

    static void sendState(Player player, boolean activation, long now) {
        var phase = current(player, now);
        var settings = Installed.SETTINGS;
        var entity = player.getTeamManager().getEntity();
        if (entity != null) {
            entity.getGlobalAbilityValues().put(settings.typeSgv, (float) phase.type);
            entity.getGlobalAbilityValues().put(settings.levelSgv, (float) phase.level);
            player.sendPacket(new PacketServerGlobalValueChangeNotify(entity, settings.typeSgv, phase.type));
            player.sendPacket(new PacketServerGlobalValueChangeNotify(entity, settings.levelSgv, phase.level));
        }
        player.sendPacket(new PacketTeamMoonPhaseChangeNotify(phase.type, phase.level, activation));
    }

    public static void onTick(Player player) { expire(player, Instant.now().getEpochSecond()); }

    static void expire(Player player, long now) {
        var state = player.getQuestFreeMoonPhase();
        synchronized (state) {
            if (state.getActivatedAt() == 0) return;
            if (current(player, now).type == Installed.SETTINGS.freeType) return;
            state.clear();
            player.save();
        }
        sendState(player, false, now);
    }
}
