package emu.grasscutter.game.quest.delivery;

import com.google.gson.*;
import emu.grasscutter.utils.FileUtils;
import java.nio.file.Files;
import java.io.IOException;
import java.util.*;

/** Explicit replacement timing, joined to the original 5006 delivery and watcher tables. */
public final class QuestDeliveryCatalog {
    public record Entry(int quest,int parent,int item,int seconds,Set<String> prohibited){}
    private static final class Installed {static final Map<Integer,Entry> VALUE=load();}
    private QuestDeliveryCatalog(){}
    public static Entry entry(int quest){return Installed.VALUE.get(quest);}
    public static Collection<Entry> entries(){return Installed.VALUE.values();}
    private static Map<Integer,Entry> load(){
        try{
            var rules=JsonParser.parseString(Files.readString(FileUtils.getDataPath("QuestDeliveryReconstruction.json"))).getAsJsonObject();
            if(!rules.get("reconstructed").getAsBoolean()||rules.get("activityId").getAsInt()!=5006)
                throw new IllegalStateException("Delivery rules must identify the reconstructed activity");
            int seconds=rules.get("durationSeconds").getAsInt();if(seconds<=0)throw new IllegalStateException("Invalid delivery duration");
            var allowed=new HashSet<Integer>();for(var id:rules.getAsJsonArray("quests"))allowed.add(id.getAsInt());
            var watchers=new HashMap<Integer,String>();
            for(var row:JsonParser.parseString(Files.readString(FileUtils.getResourcePath("ExcelBinOutput/ActivityDeliveryWatcherDataConfigData.json"))).getAsJsonArray()){
                var raw=row.getAsJsonObject();watchers.put(raw.get("id").getAsInt(),raw.getAsJsonObject("triggerConfig").get("triggerType").getAsString());
            }
            var result=new HashMap<Integer,Entry>();
            for(var day:JsonParser.parseString(Files.readString(FileUtils.getResourcePath("ExcelBinOutput/ActivityDeliveryDailyExcelConfigData.json"))).getAsJsonArray())
                for(var row:day.getAsJsonObject().getAsJsonArray("deliveryQuestConfig")){
                    var raw=row.getAsJsonObject();int id=raw.get("deliveryQuestId").getAsInt();if(!allowed.contains(id))continue;
                    var types=new HashSet<String>();for(var watcher:raw.getAsJsonArray("watcherId")){
                        String type=watchers.get(watcher.getAsInt());if(type==null)throw new IllegalStateException("Missing delivery watcher");types.add(type);
                    }
                    var e=new Entry(id,raw.get("parentQuestId").getAsInt(),raw.get("itemId").getAsInt(),seconds,Set.copyOf(types));
                    if(result.put(id,e)!=null)throw new IllegalStateException("Duplicate delivery "+id);
                }
            if(!result.keySet().equals(allowed))throw new IllegalStateException("Delivery reconstruction references a missing original quest");
            return Map.copyOf(result);
        }catch(IOException error){throw new IllegalStateException("Cannot load delivery reconstruction",error);}
    }
}
