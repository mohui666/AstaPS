package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.RewardData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.world.TestWorld.TestPlayer;
import emu.grasscutter.utils.JsonUtils;
import java.util.*;
import org.junit.jupiter.api.*;

final class QuestParentRewardSelectionTest {
    private static final int MAIN = 990071, SUB = 99007101, A = 9900711, B = 9900712;
    private final List<Runnable> restore = new ArrayList<>();

    private static final class RewardPlayer extends TestPlayer {
        final List<ItemParamData> granted = new ArrayList<>();
        private final Inventory inventory = new Inventory(this) {
            @Override public void addItemParamDatas(Collection<ItemParamData> items, ActionReason reason) {
                assertEquals(ActionReason.QuestReward,reason);
                granted.addAll(items);
            }
        };
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class SavedMain extends GameMainQuest {
        int saves;
        SavedMain(RewardPlayer player) { super(player,MAIN); }
        @Override public void save() { saves++; }
    }

    private <T> void install(Map<Integer,T> map, int id, T value) {
        var previous = map.put(id,value);
        restore.add(() -> { if (previous == null) map.remove(id); else map.put(id,previous); });
    }

    @AfterEach void restoreData() { Collections.reverse(restore); restore.forEach(Runnable::run); }

    private SavedMain main(RewardPlayer player, String rewards) {
        install(GameData.getMainQuestDataMap(),MAIN,JsonUtils.decode(
                "{id:" + MAIN + ",rewardIdList:" + rewards + ",subQuests:[{subId:" + SUB + "}]}",MainQuestData.class));
        install(GameData.getQuestDataMap(),SUB,JsonUtils.decode(
                "{mainId:" + MAIN + ",subId:" + SUB + ",finishCond:[],failCond:[]}",QuestData.class));
        install(GameData.getRewardDataMap(),A,JsonUtils.decode(
                "{rewardId:" + A + ",rewardItemList:[{itemId:100001,itemCount:2}],hcoin:5}",RewardData.class));
        install(GameData.getRewardDataMap(),B,JsonUtils.decode(
                "{rewardId:" + B + ",rewardItemList:[{itemId:100002,itemCount:7}],scoin:20,playerExp:30}",RewardData.class));
        return new SavedMain(player);
    }

    @Test void selectedAlternativeIsAppliedSynchronouslyAndGrantsOnlyThatCompleteBundleOnce() {
        var player = new RewardPlayer();
        var main = main(player,"[" + A + "," + B + "]");
        var action = JsonUtils.decode("{type:'QUEST_EXEC_UPDATE_PARENT_QUEST_REWARD_INDEX',param:['1']}",
                QuestData.QuestExecParam.class);
        new QuestSystem(null).triggerExec(main.getChildQuestById(SUB),action,action.getParam());
        assertEquals(1,main.getRewardIndex());
        assertEquals(1,main.saves);
        main.finish();
        assertEquals(List.of(100002,202,102),player.granted.stream().map(ItemParamData::getId).toList());
        assertEquals(List.of(7,20,30),player.granted.stream().map(ItemParamData::getCount).toList());
        main.finish();
        assertEquals(3,player.granted.size());
    }

    @Test void zeroAlternativeDoesNotGrantTheOtherBranchesReward() {
        var player = new RewardPlayer();
        var main = main(player,"[0," + B + "]");
        main.finish();
        assertTrue(player.granted.isEmpty());
    }

    @Test void invalidSelectionCannotChangeTheSavedBranchAndDefaultChoosesFirstOnly() {
        var player = new RewardPlayer();
        var main = main(player,"[" + A + "," + B + "]");
        assertFalse(main.setRewardIndex(-1));
        assertFalse(main.setRewardIndex(2));
        assertFalse(new emu.grasscutter.game.quest.exec.ExecUpdateParentQuestRewardIndex()
                .execute(main.getChildQuestById(SUB),null,"not-an-index"));
        assertEquals(0,main.saves);
        assertEquals(0,main.getRewardIndex());
        main.finish();
        assertEquals(List.of(100001,201),player.granted.stream().map(ItemParamData::getId).toList());
        assertEquals(List.of(2,5),player.granted.stream().map(ItemParamData::getCount).toList());
    }
}
