package emu.grasscutter.game.quest.field;

import com.google.gson.*;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.utils.*;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import lombok.Getter;

/** Explicit replacement mechanics retain the real fish, capture and widget resource identities. */
public final class QuestFieldCatalog {
    public record Fish(int id,int monster,int item,int pose,boolean furniture) {}
    @Getter public static final class Fishing {
        private int id,pool,fish;
        private String label;
        private List<Integer> quests=List.of();
        private Position bank,water;
        private boolean bankAtPlayer;
    }
    @Getter public static final class Capture {
        private int quest,animal,avatar,trial,material,drop,reward;
        private boolean anchorAtPlayer;
        private Position position;
    }
    @Getter private List<Fishing> fishing=List.of();
    @Getter private List<Capture> capture=List.of();
    private transient Map<Integer,Fish> fishes;
    private transient Set<Integer> quests;
    private static final class Installed {static final QuestFieldCatalog VALUE=read();}
    public static QuestFieldCatalog installed(){return Installed.VALUE;}
    public Fish fish(int id){return fishes.get(id);}
    public Fishing fishing(int id){return fishing.stream().filter(f->f.id==id).findFirst().orElse(null);}
    public Capture capture(int id){return capture.stream().filter(c->c.quest==id).findFirst().orElse(null);}
    public boolean ownsQuest(int id){return quests.contains(id);}
    private static JsonArray resource(String file) {
        try{return JsonParser.parseString(Files.readString(FileUtils.getResourcePath(file))).getAsJsonArray();}
        catch(IOException error){throw new IllegalStateException("Missing original field-game resource "+file,error);}
    }
    private static QuestFieldCatalog read() {
        final JsonObject rules;
        try{rules=JsonParser.parseString(Files.readString(FileUtils.getDataPath("QuestFieldGames.json"))).getAsJsonObject();}
        catch(IOException error){throw new IllegalStateException("Missing explicit field-game reconstruction rules",error);}
        if(!rules.get("reconstructed").getAsBoolean()||rules.get("version").getAsInt()!=1)
            throw new IllegalArgumentException("Field games require explicit reconstruction rules");
        var result=JsonUtils.decode(rules,QuestFieldCatalog.class);
        result.fishes=new HashMap<>();result.quests=new HashSet<>(Set.of(99809));
        for(var item:resource("ExcelBinOutput/FishExcelConfigData.json")) {
            var row=item.getAsJsonObject();int id=row.get("id").getAsInt();
            result.fishes.put(id,new Fish(id,row.get("monsterId").getAsInt(),row.get("itemId").getAsInt(),
                    row.has("initPose")?row.get("initPose").getAsInt():0,row.has("fishCateId")&&row.get("fishCateId").getAsInt()!=0));
        }
        var poolIds=new HashSet<Integer>();for(var item:resource("ExcelBinOutput/FishPoolExcelConfigData.json"))
            poolIds.add(item.getAsJsonObject().get("id").getAsInt());
        var ids=new HashSet<Integer>();
        for(var f:result.fishing) {
            if(f.id<=0||!ids.add(f.id)||!result.fishes.containsKey(f.fish)||!poolIds.contains(f.pool)
                    ||f.quests.isEmpty()||f.bank==null||f.water==null)throw new IllegalArgumentException("Invalid field fishing binding");
            result.quests.addAll(f.quests);
        }
        var drops=new HashMap<Integer,JsonObject>();for(var item:resource("Server/DropTableExcelConfigData.json")) {
            var row=item.getAsJsonObject();drops.put(row.get("id").getAsInt(),row);
        }
        var originalCaptures=new HashMap<Integer,JsonObject>();for(var item:resource("ExcelBinOutput/CaptureExcelConfigData.json")) {
            var row=item.getAsJsonObject();originalCaptures.put(row.get("monsterID").getAsInt(),row);
        }
        for(var c:result.capture) {
            if(c.quest<=0||c.animal<=0||c.position==null||!result.quests.add(c.quest)
                    ||!originalCaptures.containsKey(c.animal)||c.avatar==0&&c.material==0)
                throw new IllegalArgumentException("Invalid field capture binding");
            if(c.avatar>0&&c.trial<=0)throw new IllegalArgumentException("Survey needs its explicit real trial avatar");
            if(c.material>0) {
                var drop=drops.get(c.drop);
                boolean nativeCapture=false;
                for(var value:originalCaptures.get(c.animal).getAsJsonArray("captureTagConfig")) {
                    var tag=value.getAsJsonObject();
                    nativeCapture|=tag.has("captureType")&&tag.get("captureType").getAsInt()==1001
                            &&tag.has("dropID")&&tag.get("dropID").getAsInt()==c.drop;
                }
                var rewards=drop==null?null:drop.getAsJsonArray("dropVec");
                if(!nativeCapture||rewards==null||rewards.size()!=1)throw new IllegalArgumentException("Missing exact native capture reward");
                var item=rewards.get(0).getAsJsonObject();
                if(item.get("itemId").getAsInt()!=c.reward||!item.get("countRange").getAsString().equals("1")
                        ||item.get("weight").getAsInt()!=10000)throw new IllegalArgumentException("Capture reward is not the original guaranteed leaf");
            }
        }
        return result;
    }
}
