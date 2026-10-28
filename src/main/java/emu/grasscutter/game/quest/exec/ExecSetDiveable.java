package emu.grasscutter.game.quest.exec;

import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.props.PlayerProperty;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;

@QuestValueExec(QuestExec.QUEST_EXEC_SET_IS_DIVEABLE)
public final class ExecSetDiveable extends QuestExecHandler {
    @Override public boolean execute(GameQuest quest,QuestData.QuestExecParam condition,String... params) {
        if(params==null||params.length!=1)return false;
        final int enabled;
        try { enabled=Integer.parseInt(params[0]); }catch(NumberFormatException error){return false;}
        if(enabled!=0&&enabled!=1)return false;
        if(!quest.getOwner().setProperty(PlayerProperty.PROP_PLAYER_CAN_DIVE,enabled))return false;
        quest.getOwner().getFieldGames().setDiveExecObserved(true);
        if(enabled==0)emu.grasscutter.game.player.DiveAbilityHelper.forceCleanLand(quest.getOwner());
        quest.getOwner().save();return true;
    }
}
