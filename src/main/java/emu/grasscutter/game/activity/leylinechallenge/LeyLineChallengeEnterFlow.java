package emu.grasscutter.game.activity.leylinechallenge;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.command.CommandOutput;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.props.SceneType;

/**
 * Entry for MGC independent battles through /stygian. Native activity-page entry and the
 * overworld worktop remain unregistered until their client identifiers are confirmed.
 */
public final class LeyLineChallengeEnterFlow {

    private LeyLineChallengeEnterFlow() {}

    /**
     * Enter the challenge.
     *
     * @param player the entering player
     * @param difficulty 1-based difficulty tier chosen on the activity page
     * @param fromWorktop {@code true} when this is the private-server worktop fallback rather than
     *     the official activity-page {@code EnterReq}
     */
    public static void enter(Player player, int difficulty, boolean fromWorktop) {
        enterBattle(player, difficulty, 1);
    }

    public static void enterBattle(Player player, int difficulty, int battle) {
        if (player == null || !player.isOnline() || player.getScene() == null) {
            throw new IllegalArgumentException("幽境挑战需要在线玩家。");
        }
        var plan = LeyLineChallengeRun.plan(difficulty, battle);
        var schedule = LeyLineChallengeCatalog.currentSchedule();
        if (schedule == null || schedule.getScheduleId() != LeyLineChallengeRun.SCHEDULE_ID) {
            throw new IllegalArgumentException("当前没有已配置的幽境战斗期数。");
        }
        if (player.getWorld().getPlayers().size() != 1) {
            throw new IllegalArgumentException("当前 MGC 独立战场入口仅支持单人，请先退出联机。");
        }
        if (player.getScene().getSceneType() != SceneType.SCENE_WORLD) {
            throw new IllegalArgumentException("请先退出当前秘境，再进入幽境战场。");
        }
        if (GameData.getMonsterDataMap().get(plan.monsterId()) == null) {
            throw new IllegalArgumentException("本机资源缺少对应的幽境敌人。");
        }
        if (!player.getServer().getDungeonSystem().enterDungeon(player, 0, plan.dungeonId(), true)) {
            throw new IllegalArgumentException("进入幽境副本失败。");
        }
        player.getScene().setLeyLineChallengeRun(new LeyLineChallengeRun(player.getScene(), plan));
        CommandOutput.sendMessage(player, "MGC 幽境独立战场：难度 " + difficulty + "，战场 " + battle
                + "。本入口不生成官方三队战绩或发放活动奖励。");
    }
}
