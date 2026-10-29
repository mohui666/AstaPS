package emu.grasscutter.game.activity.leylinechallenge;

import emu.grasscutter.command.CommandOutput;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.dungeons.challenge.WorldChallenge;
import emu.grasscutter.game.dungeons.challenge.trigger.KillMonsterTrigger;
import emu.grasscutter.game.entity.EntityMonster;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.scripts.data.SceneGroup;
import java.util.List;

/**
 * MGC independent battles in the three native scenes. Enemy kinds, levels and time limits are
 * checked against https://wikiwiki.jp/genshinwiki/幽境の激戦 . Stats use the installed client
 * curves; exact official difficulty multipliers and the native three-team protocol remain unknown.
 */
public final class LeyLineChallengeRun {
    public static final int SCHEDULE_ID = 5269012;
    private static final int CONFIG_ID = 9001;
    private static final int[] LEVELS = {40, 70, 90, 100, 105, 110};
    // Selected native variants for MGC's low / menacing / fearless / dire battles.
    // Do not claim this selection reconstructs the unavailable official server scripts.
    private static final int[][] MONSTERS = {
        {25139002, 24090501, 25135202},
        {25139003, 24090502, 25135205},
        {25139004, 24090503, 25135206},
        {25139005, 24090504, 25135207}
    };

    public record BattlePlan(int difficulty, int battle, int dungeonId, int monsterId, int level, int seconds) {}

    public static BattlePlan plan(int difficulty, int battle) {
        if (difficulty < 1 || difficulty > 6 || battle < 1 || battle > 3) {
            throw new IllegalArgumentException("难度须为 1–6，战场须为 1–3。");
        }
        return new BattlePlan(difficulty, battle, 19999 + battle,
                MONSTERS[difficulty <= 3 ? 0 : difficulty - 3][battle - 1],
                LEVELS[difficulty - 1], difficulty <= 3 ? 600 : 120);
    }

    private final Scene scene;
    private final BattlePlan plan;
    private WorldChallenge challenge;
    private boolean ended;

    public LeyLineChallengeRun(Scene scene, BattlePlan plan) {
        this.scene = scene;
        this.plan = plan;
    }

    public void tick() {
        if (ended || scene.getPlayers().isEmpty()) return;
        if (challenge == null) {
            if (scene.getPlayers().stream().anyMatch(p -> p.getSceneLoadState() != Player.SceneLoadState.LOADED)) return;
            start();
        } else if (!challenge.inProgress()) {
            ended = true;
            if (challenge.isSuccess()) {
                scene.getDungeonManager().finishDungeon();
                for (var player : scene.getPlayers()) CommandOutput.sendMessage(player,
                        "幽境独立战场已完成。退出秘境后，可用 /stygian <难度> <战场> 选择其他战场。");
            } else {
                scene.getDungeonManager().failDungeon();
            }
        }
    }

    private void start() {
        int groupId = 200000000 + scene.getId() * 1000 + 1;
        var monster = new EntityMonster(scene, GameData.getMonsterDataMap().get(plan.monsterId()),
                new Position(499.994f, 249.873f, 500.002f), new Position(0, 90, 0), plan.level());
        monster.setGroupId(groupId);
        monster.setConfigId(CONFIG_ID);
        scene.addEntity(monster);
        challenge = new WorldChallenge(scene, SceneGroup.of(groupId), 197, 1,
                List.of(plan.seconds(), groupId, 1), plan.seconds(), 1, List.of(new KillMonsterTrigger(CONFIG_ID)));
        scene.setChallenge(challenge);
        challenge.start();
    }
}
