package emu.grasscutter.game.quest;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.quest.QuestData.QuestExecParam;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ClimateType;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.utils.*;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** One explicit replacement for an original scene-variable binding that has not been recovered. */
public final class QuestSceneVarReconstruction {
    private static final int MAIN = 77160, SOURCE = 7716008, CLEANUP = 7716015;
    private static final int SCENE = 3, WEATHER = 7048;
    private static final String VARIABLE = "Scenevar_GZJR";

    private QuestSceneVarReconstruction() {}

    private static final class Volume {
        float minX, maxX, minY, maxY, minZ, maxZ;

        boolean valid() {
            return Float.isFinite(minX) && Float.isFinite(maxX) && minX < maxX
                    && Float.isFinite(minY) && Float.isFinite(maxY) && minY < maxY
                    && Float.isFinite(minZ) && Float.isFinite(maxZ) && minZ < maxZ;
        }

        boolean contains(Position position) {
            return position.getX() >= minX && position.getX() <= maxX
                    && position.getY() >= minY && position.getY() <= maxY
                    && position.getZ() >= minZ && position.getZ() <= maxZ;
        }
    }

    private static final class Rule {
        boolean reconstructed;
        int version, mainQuest, sourceQuest, cleanupQuest, scene, value, weatherArea;
        String variable, profileName;
        List<Volume> volumes = List.of();
    }

    private static final class Installed {
        static final Rule RULE = read();
    }

    private static Rule read() {
        final Rule rule;
        try {
            rule = JsonUtils.decode(Files.readString(
                    FileUtils.getDataPath("QuestSceneVarReconstruction.json")), Rule.class);
        } catch (IOException error) {
            throw new IllegalStateException("Missing explicit scene-variable reconstruction rule", error);
        }
        if (!rule.reconstructed || rule.version != 1 || rule.mainQuest != MAIN
                || rule.sourceQuest != SOURCE || rule.cleanupQuest != CLEANUP || rule.scene != SCENE
                || rule.value != 0 || rule.weatherArea != WEATHER || !VARIABLE.equals(rule.variable)
                || !"Data/Environment/EnviroSystemProfile/BigWorld/ESP_Zd70_BSH_GZJR02".equals(rule.profileName)
                || rule.volumes.isEmpty() || rule.volumes.stream().anyMatch(volume -> !volume.valid())) {
            throw new IllegalArgumentException("Only the reviewed GZJR battle-environment replacement is supported");
        }
        return rule;
    }

    private static boolean active(GameMainQuest main) {
        if (main == null || main.getParentQuestId() != MAIN || main.isFinished()
                || main.getState() != ParentQuestState.PARENT_QUEST_STATE_NONE) return false;
        var source = main.getChildQuests().get(SOURCE);
        var cleanup = main.getChildQuests().get(CLEANUP);
        return source != null && source.getState() == QuestState.QUEST_STATE_FINISHED
                && cleanup != null && cleanup.getState() != QuestState.QUEST_STATE_FINISHED;
    }

    /** Other reconstructed weather areas retain their existing rules. */
    static boolean mayUseArea(Player owner, int scene, int area, Position position) {
        if (scene != SCENE || area != WEATHER) return true;
        return active(owner.getQuestManager().getMainQuestById(MAIN))
                && Installed.RULE.volumes.stream().anyMatch(volume -> volume.contains(position));
    }

    /** Executes only the actual reviewed finishExec; no numeric scene-variable hash is invented. */
    public static boolean execute(GameQuest quest, QuestExecParam action, String... params) {
        if (quest == null || quest.getMainQuestId() != MAIN || quest.getSubQuestId() != SOURCE
                || quest.getState() != QuestState.QUEST_STATE_FINISHED || action == null
                || action.getType() != QuestExec.QUEST_EXEC_SET_SCENE_VAR
                || !Arrays.equals(params, new String[] {"3", VARIABLE, "0"})
                || !Arrays.equals(action.getParam(), params)
                || quest.getQuestData().getFinishExec().stream().noneMatch(value -> value == action)) return false;
        var owner = quest.getOwner();
        var main = owner.getQuestManager().getMainQuestById(MAIN);
        if (main != quest.getMainQuest() || main.getChildQuests().get(SOURCE) != quest || !active(main)) return false;
        var rule = Installed.RULE;
        var weather = GameData.getWeatherDataMap().get(WEATHER);
        if (weather == null || weather.getSceneID() != SCENE || weather.getWeatherAreaId() != WEATHER
                || weather.getDefaultClimate() != ClimateType.CLIMATE_SNOW
                || !rule.profileName.equals(weather.getProfileName())
                || QuestWeatherAreas.areas(SCENE).stream().noneMatch(area -> area.id == WEATHER
                        && area.reconstructed && area.points.size() >= 3 && area.isUseHeightBorder)) return false;
        return QuestWeatherAreas.change(owner, new int[] {WEATHER}, new int[] {1});
    }
}
