package emu.grasscutter.game.quest.scenery;

import com.google.gson.*;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** Native primary/sub lists, combined with explicitly authored lifecycle and group-scene bindings. */
public final class QuestSceneryCatalog {
    public record Primary(int id, int initialSub, Set<Integer> parents, Set<Integer> dungeons,
            Set<Integer> completionQuests, String label) {}
    public record Sub(int id, int primary, Map<Integer,Integer> suites, Set<Integer> hiddenTags) {}
    private final Map<Integer,Primary> primaries = new TreeMap<>();
    private final Map<Integer,Sub> subs = new TreeMap<>();
    private final Map<Integer,Integer> groupScenes = new HashMap<>();
    private final Map<Integer,Integer> runningSceneGates = new HashMap<>();
    private static final class Installed { static final QuestSceneryCatalog VALUE = read(); }
    public static QuestSceneryCatalog installed() { return Installed.VALUE; }
    public Collection<Primary> primaries() { return primaries.values(); }
    public Primary primary(int id) { return primaries.get(id); }
    public Sub sub(int id) { return subs.get(id); }
    public int groupScene(int group) { return groupScenes.getOrDefault(group,0); }
    public int runningSceneGate(int quest) { return runningSceneGates.getOrDefault(quest,0); }
    public Set<Integer> groups(int primary) {
        var result = new LinkedHashSet<Integer>();
        subs.values().stream().filter(sub -> sub.primary()==primary).forEach(sub -> result.addAll(sub.suites().keySet()));
        return result;
    }
    private static JsonElement json(java.nio.file.Path path) {
        try { return JsonParser.parseString(Files.readString(path)); }
        catch(IOException error) { throw new IllegalStateException("Cannot read scenery resource "+path,error); }
    }
    private static Set<Integer> ints(JsonArray values) {
        var result = new LinkedHashSet<Integer>();
        if(values!=null) for(var value:values) result.add(value.getAsInt());
        return Collections.unmodifiableSet(result);
    }
    private static QuestSceneryCatalog read() {
        var catalog = new QuestSceneryCatalog();
        var rules = json(FileUtils.getDataPath("QuestSceneryRules.json")).getAsJsonObject();
        if(!rules.get("reconstructed").getAsBoolean()||rules.get("version").getAsInt()!=1)
            throw new IllegalArgumentException("Scenery lifecycle must declare its reconstruction rules");
        var custom = new HashMap<Integer,JsonObject>();
        for(var value:rules.getAsJsonArray("primaries")) {
            var rule=value.getAsJsonObject(); custom.put(rule.get("id").getAsInt(),rule);
        }
        for(var item:json(FileUtils.getResourcePath("ExcelBinOutput/QuestPrimarySceneryExcelConfigData.json")).getAsJsonArray()) {
            var row=item.getAsJsonObject(); int id=row.get("AEFJEHFOHOB").getAsInt();
            var rule=custom.get(id); if(rule==null) throw new IllegalArgumentException("Missing explicit scenery lifecycle "+id);
            catalog.primaries.put(id,new Primary(id,row.get("CPOAFBMGKLG").getAsInt(),
                    ints(row.getAsJsonArray("EHKFNDKIDDE")),ints(row.getAsJsonArray("dungeonIdList")),
                    ints(rule.getAsJsonArray("completionQuests")),rule.get("label").getAsString()));
        }
        for(var item:rules.getAsJsonArray("groupScenes")) {
            var pair=item.getAsJsonArray(); catalog.groupScenes.put(pair.get(0).getAsInt(),pair.get(1).getAsInt());
        }
        for(var item:rules.getAsJsonArray("runningSceneGates")) {
            var pair=item.getAsJsonArray(); catalog.runningSceneGates.put(pair.get(0).getAsInt(),pair.get(1).getAsInt());
        }
        for(var item:json(FileUtils.getResourcePath("ExcelBinOutput/QuestSubSceneryExcelConfigData.json")).getAsJsonArray()) {
            var row=item.getAsJsonObject(); int id=row.get("EDMGJECHGIB").getAsInt();
            int primary=row.get("AEFJEHFOHOB").getAsInt(); var suites=new LinkedHashMap<Integer,Integer>();
            for(String value:row.get("JOELOPMEIAF").getAsString().split(";")) {
                var pair=value.split(","); int group=Integer.parseInt(pair[0]),suite=Integer.parseInt(pair[1]);
                if(catalog.groupScene(group)<=0||suite<=0||suites.put(group,suite)!=null)
                    throw new IllegalArgumentException("Invalid scenery group binding "+id);
            }
            var hidden=new LinkedHashSet<Integer>();
            // The explicit authored rule uses this native list to hide conflicting event scenery.
            if(row.has("MBEHHFOAPCA")) for(String value:row.get("MBEHHFOAPCA").getAsString().split(","))
                hidden.add(Integer.parseInt(value));
            if(!catalog.primaries.containsKey(primary)||catalog.subs.put(id,new Sub(id,primary,
                    Collections.unmodifiableMap(suites),Collections.unmodifiableSet(hidden)))!=null)
                throw new IllegalArgumentException("Invalid scenery variant "+id);
        }
        for(var primary:catalog.primaries.values()) if(!catalog.subs.containsKey(primary.initialSub())
                ||primary.completionQuests().isEmpty()) throw new IllegalArgumentException("Missing scenery initial/completion binding");
        return catalog;
    }
}
