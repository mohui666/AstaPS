package emu.grasscutter.game.quest.comedy;

import com.google.gson.*;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;

final class ComedyVoucherCatalog {
    record Stage(int quest,int exhibition,int target,int scene,Position anchor) {}
    private static final Pattern GUIDE = Pattern.compile("\\[(\\d+)_\\d+:([\\d.-]+),([\\d.-]+),([\\d.-]+)]");
    final Map<Integer,Stage> stages = new LinkedHashMap<>();
    final String notice;
    final int itemId, points;
    private ComedyVoucherCatalog() throws IOException {
        var config = JsonParser.parseString(Files.readString(FileUtils.getDataPath("QuestComedyVoucherReconstruction.json"))).getAsJsonObject();
        if (!config.get("reconstructed").getAsBoolean()) throw new IllegalStateException("Comedy workpieces must declare reconstruction");
        notice=config.get("notice").getAsString(); itemId=config.get("earnedItemId").getAsInt(); points=config.get("pointsPerWorkpiece").getAsInt();
        if (itemId != 120400 || points != 100) throw new IllegalStateException("Unsupported comedy reconstruction rules");
        var nativeMain=JsonParser.parseString(Files.readString(FileUtils.getResourcePath("BinOutput/Quest/74025.json"))).getAsJsonObject();
        var children=new HashMap<Integer,JsonObject>();
        for(var value:nativeMain.getAsJsonArray("subQuests")) children.put(value.getAsJsonObject().get("subId").getAsInt(),value.getAsJsonObject());
        for(var value:config.getAsJsonArray("stages")) {
            var row=value.getAsJsonObject(); int quest=row.get("questId").getAsInt(),exhibition=row.get("exhibitionId").getAsInt(),target=row.get("target").getAsInt();
            var nativeQuest=children.get(quest);
            if(nativeQuest==null || nativeQuest.getAsJsonArray("finishCond").size()!=1) throw new IllegalStateException("Missing original comedy objective");
            var condition=nativeQuest.getAsJsonArray("finishCond").get(0).getAsJsonObject();
            if(!condition.get("type").getAsString().equals("QUEST_CONTENT_EXHIBITION_ACCUMULATE_GT_EQ")
                    || condition.getAsJsonArray("param").get(0).getAsInt()!=exhibition || condition.get("count").getAsInt()!=target)
                throw new IllegalStateException("Comedy objective differs from native resource");
            var guide=GUIDE.matcher(nativeQuest.getAsJsonObject("guide").getAsJsonArray("param").get(0).getAsString());
            if(!guide.matches()) throw new IllegalStateException("Missing original comedy guide position");
            var anchor=new Position(Float.parseFloat(guide.group(2)),Float.parseFloat(guide.group(3)),Float.parseFloat(guide.group(4)));
            stages.put(quest,new Stage(quest,exhibition,target,Integer.parseInt(guide.group(1)),anchor));
        }
        if(!stages.keySet().equals(Set.of(7402526,7402529,7402534,7402539))) throw new IllegalStateException("Unsupported comedy stage expansion");
    }
    private static final class Installed {
        static final ComedyVoucherCatalog VALUE=load();
        static ComedyVoucherCatalog load() { try { return new ComedyVoucherCatalog(); } catch(IOException error) { throw new IllegalStateException("Cannot read comedy reconstruction",error); } }
    }
    static ComedyVoucherCatalog installed() { return Installed.VALUE; }
}
