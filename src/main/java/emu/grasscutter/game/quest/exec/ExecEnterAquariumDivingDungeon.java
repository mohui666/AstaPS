package emu.grasscutter.game.quest.exec;

import com.google.gson.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.props.PlayerProperty;
import emu.grasscutter.game.quest.*;
import emu.grasscutter.game.quest.enums.QuestExec;
import emu.grasscutter.game.quest.handlers.QuestExecHandler;
import emu.grasscutter.utils.FileUtils;
import java.nio.file.Files;
import java.io.IOException;

@QuestValueExec(QuestExec.QUEST_EXEC_ENTER_AQUARIUM_DIVING_DUNGEON)
public final class ExecEnterAquariumDivingDungeon extends QuestExecHandler {
    @Override public boolean execute(GameQuest quest,QuestData.QuestExecParam condition,String... params) {
        if(params==null||params.length!=1)return false;
        final int level;
        try {level=Integer.parseInt(params[0]);}catch(NumberFormatException error){return false;}
        var player=quest.getOwner();
        if(player.getScene()==null||player.getSceneId()!=1161||player.getScene().getId()!=1161
                ||player.getWorld()==null||player.getWorld().getHost()!=player||player.isInMultiplayer()
                ||player.getProperty(PlayerProperty.PROP_PLAYER_CAN_DIVE)!=1)return false;
        for(var item:levels()) {
            var row=item.getAsJsonObject();if(row.get("levelID").getAsInt()!=level)continue;
            int dungeon=row.get("dungeonId").getAsInt(),scene=row.get("sceneId").getAsInt();
            var data=GameData.getDungeonDataMap().get(dungeon);
            if(data==null||data.getSceneId()!=scene)return false;
            if(!player.getServer().getDungeonSystem().enterDungeon(player,0,dungeon,true))return false;
            return player.getScene()!=null&&player.getScene().getId()==scene
                    &&player.getScene().getDungeonManager()!=null
                    &&player.getScene().getDungeonManager().getDungeonData().getId()==dungeon;
        }
        return false;
    }
    private static JsonArray levels() {
        try {return JsonParser.parseString(Files.readString(FileUtils.getResourcePath(
                "ExcelBinOutput/AquariumDivingLevelExcelConfigData.json"))).getAsJsonArray();}
        catch(IOException error){throw new IllegalStateException("Missing original aquarium diving levels",error);}
    }
}
