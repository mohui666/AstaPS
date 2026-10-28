package emu.grasscutter.game.quest;

import static emu.grasscutter.game.props.PlayerProperty.*;

import com.google.gson.JsonParser;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.AbilityMixinData;
import emu.grasscutter.data.common.DynamicFloat;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.data.TeleportProperties;
import emu.grasscutter.net.proto.EnterTypeOuterClass.EnterType;
import emu.grasscutter.server.event.player.PlayerTeleportEvent.TeleportType;
import emu.grasscutter.server.packet.send.PacketPlayerPropNotify;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** Native climate lifecycle with explicitly reconstructed missing ConfigClimate numeric settings. */
public final class QuestClimateRuntime {
    private static final Map<Player, State> states = Collections.synchronizedMap(new WeakHashMap<>());
    private static final class State {
        long second = -1;
        int sceneId;
        int damping = -1;
        int fullTicks;
        int tatariHits;
    }
    private record Rules(float length, float thresholdRatio, int damping, float fade,
            Map<Integer, Float> rise) {}
    private static final class Installed { static final Rules RULES = load(); }
    private QuestClimateRuntime() {}

    private static Rules load() {
        try {
            var json = JsonParser.parseString(Files.readString(FileUtils.getDataPath(
                    "QuestClimateRules.json"))).getAsJsonObject();
            float length = json.get("length").getAsFloat();
            float threshold = json.get("thresholdRatio").getAsFloat();
            int damping = json.get("dampingSeconds").getAsInt();
            float fade = json.get("fadePerSecond").getAsFloat();
            if (!json.get("reconstructed").getAsBoolean() || !Float.isFinite(length) || length <= 0
                    || length > Integer.MAX_VALUE / 100f || !Float.isFinite(threshold)
                    || threshold <= 0 || threshold > 1 || damping < 0
                    || !Float.isFinite(fade) || fade <= 0)
                throw new IllegalStateException("Invalid reconstructed climate meter settings");
            var rise = new HashMap<Integer, Float>();
            for (var entry : json.getAsJsonObject("risePerSecond").entrySet()) {
                int type = QuestClimateAreas.typeId(entry.getKey());
                float speed = entry.getValue().getAsFloat();
                if (type <= 0 || !Float.isFinite(speed) || speed <= 0)
                    throw new IllegalStateException("Invalid reconstructed climate speed " + entry.getKey());
                rise.put(type, speed);
            }
            return new Rules(length, threshold, damping, fade, Map.copyOf(rise));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read reconstructed climate meter settings", error);
        }
    }

    static void update(Player player, QuestClimateAreas.Area area, int areaType,
            boolean onEnter, long nowMs, Set<PlayerProperty> changed) {
        var state = states.computeIfAbsent(player, ignored -> new State());
        synchronized (state) {
            int currentType = player.getProperty(PROP_CUR_CLIMATE_TYPE);
            if (area != null && area.isForceClearDifferentClimateMeter && currentType != areaType) {
                setMeter(player, 0, changed);
                state.fullTicks = state.tatariHits = 0;
            }
            if (player.getProperty(PROP_CUR_CLIMATE_METER) == 0 || areaType != 0)
                QuestClimateAreas.set(player, PROP_CUR_CLIMATE_TYPE, areaType, changed);
            long second = nowMs / 1000;
            if (onEnter || state.second < 0 || state.sceneId != player.getSceneId()) {
                state.sceneId = player.getSceneId();
                state.second = second;
                state.damping = -1;
                return;
            }
            if (second <= state.second) return;
            // Native updateClimateMeter advances one heartbeat, never offline/lag catch-up damage.
            state.second = second;
            var avatar = player.getTeamManager() == null ? null
                    : player.getTeamManager().getCurrentAvatarEntity();
            if (player.isPaused() || player.getWorld().isPaused()
                    || player.getProperty(PROP_IS_GAME_TIME_LOCKED) != 0 || avatar == null
                    || avatar.getScene() != player.getScene() || avatar.isDead()) return;
            var rules = Installed.RULES;
            currentType = player.getProperty(PROP_CUR_CLIMATE_TYPE);
            float meter = player.getProperty(PROP_CUR_CLIMATE_METER) / 100f;
            float drain = protectionDrain(player, avatar, areaType);
            if (areaType != 0) {
                state.damping = -1;
                float speed = rules.rise.getOrDefault(areaType, 0f);
                meter = drain > 0 ? meter - drain
                        : meter + speed * resistance(player, avatar, areaType, "Climate", "Rise");
            } else if (meter > 0) {
                if (state.damping < 0) state.damping = rules.damping;
                if (state.damping > 0) state.damping--;
                else meter -= rules.fade * resistance(player, avatar, currentType, "Climate", "Fade");
            }
            setMeter(player, meter, changed);
            meter = player.getProperty(PROP_CUR_CLIMATE_METER) / 100f;
            if (areaType == 0 && meter == 0) QuestClimateAreas.set(player, PROP_CUR_CLIMATE_TYPE, 0, changed);
            if (meter < rules.length * rules.thresholdRatio || drain > 0) {
                state.fullTicks = state.tatariHits = 0;
                return;
            }
            if (isTransferClimate(areaType) && meter >= rules.length) {
                // Native checkTransferPlayerOnTransClimateFull consumes the full meter before
                // jumping. A canceled transfer starts a new exposure round, not an every-tick retry.
                setMeter(player, 0, changed);
                QuestClimateAreas.set(player, PROP_CUR_CLIMATE_TYPE, 0, changed);
                state.fullTicks = state.tatariHits = 0;
                if (!transfer(player, area)) Grasscutter.getLogger().debug(
                        "Climate transfer did not occur: uid={} scene={} area={}",
                        player.getUid(), player.getSceneId(), area == null ? 0 : area.id);
                return;
            }
            state.fullTicks++;
            // ConfigAbility_Climate: the cold modifier ticks at 1s, Tatari at 2s.
            int hazardType = player.getProperty(PROP_CUR_CLIMATE_TYPE);
            if (hazardType == 1) avatar.loseHpByAbility(150f + maxHp(avatar) * .01f, true);
            else if (hazardType == 8 && state.fullTicks % 2 == 0) {
                if (marked(avatar, "HAS_ELECTRIC_FAIRY_BUFF")
                        || marked(player.getTeamManager().getEntity(), "HAS_ELECTRIC_FAIRY_BUFF")) {
                    state.tatariHits = 0;
                } else {
                    state.tatariHits++;
                    boolean escalated = state.tatariHits > 30;
                    avatar.loseHpByAbility((escalated ? 450f : 150f)
                            + maxHp(avatar) * (escalated ? .03f : .01f), true);
                }
            } else if ((hazardType == 3 || hazardType == 4) && state.fullTicks > 0) {
                String prefix = hazardType == 3 ? "_MONSTER_LASIGNORA" : "_MONSTER_LASIGNORA_HARBINGER";
                boolean quest = marked(avatar, prefix + "_CLIMATE_QUESTADJUST");
                boolean story = marked(avatar, prefix + "_STORYADJUST");
                avatar.loseHpByAbility(quest && story ? 100f
                        : 150f + maxHp(avatar) * (quest ? .01f : .03f), true);
            }
        }
    }

    private static float maxHp(EntityAvatar avatar) {
        return Math.max(0f, avatar.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP));
    }

    private static void setMeter(Player player, float value, Set<PlayerProperty> changed) {
        int encoded = Math.round(Math.max(0, Math.min(Installed.RULES.length, value)) * 100f);
        QuestClimateAreas.set(player, PROP_CUR_CLIMATE_METER, encoded, changed);
    }

    /** AddClimateMeter's amount is a real configured effect, not a report of quest progress. */
    public static boolean addMeter(Player player, String climateName, float delta) {
        int type = QuestClimateAreas.typeId(climateName);
        if (!Float.isFinite(delta) || type <= 0 || !Installed.RULES.rise.containsKey(type)
                || player.getScene() == null || player.getWorld() != player.getScene().getWorld()
                || player.getSceneLoadState() != Player.SceneLoadState.LOADED
                || player.isPaused() || player.getWorld().isPaused()
                || player.getProperty(PROP_IS_GAME_TIME_LOCKED) != 0) return false;
        var avatar = player.getTeamManager().getCurrentAvatarEntity();
        if (avatar == null || avatar.getScene() != player.getScene()) return false;
        var state = states.computeIfAbsent(player, ignored -> new State());
        synchronized (state) {
            int oldType = player.getProperty(PROP_CUR_CLIMATE_TYPE);
            if (delta <= 0 && oldType != type) return true;
            float ratio = resistance(player, avatar, type, "Ability", delta > 0 ? "Rise" : "Fade");
            if (ratio <= 0) return true;
            var changed = new LinkedHashSet<PlayerProperty>();
            float oldMeter = oldType == type ? player.getProperty(PROP_CUR_CLIMATE_METER) / 100f : 0f;
            setMeter(player, oldMeter + delta * ratio, changed);
            QuestClimateAreas.set(player, PROP_CUR_CLIMATE_TYPE,
                    player.getProperty(PROP_CUR_CLIMATE_METER) == 0 ? 0 : type, changed);
            if (!changed.isEmpty()) player.sendPacket(new PacketPlayerPropNotify(player, changed));
            return true;
        }
    }

    private static boolean isTransferClimate(int type) {
        return type == 9 || type == 11 || type == 13;
    }

    private static boolean transfer(Player player, QuestClimateAreas.Area area) {
        if (area == null) return false;
        emu.grasscutter.data.common.PointData first = null, target = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int id : area.transPoints) {
            var entry = GameData.getScenePointEntryById(player.getSceneId(), id);
            if (entry == null || !finite(entry.getPointData().getPos()) || !finite(entry.getPointData().getTranPos())
                    || !finite(entry.getPointData().getTranRot())) continue;
            var point = entry.getPointData();
            if (first == null) first = point;
            if (!player.getWorld().getHost().getUnlockedScenePoints(player.getSceneId()).contains(id)) continue;
            double dx = player.getPosition().getX() - point.getPos().getX();
            double dz = player.getPosition().getZ() - point.getPos().getZ();
            double nextDistance = dx * dx + dz * dz;
            if (nextDistance < distance) { target = point; distance = nextDistance; }
        }
        if (target == null) target = first;
        if (target == null) return false;
        return player.getWorld().transferPlayerToScene(player, TeleportProperties.builder()
                .sceneId(player.getSceneId()).teleportType(TeleportType.INTERNAL)
                .enterReason(EnterReason.ForceDragBack).enterType(EnterType.EnterType_ENTER_GOTO)
                .teleportTo(new Position(target.getTranPos()))
                .teleportRot(new Position(target.getTranRot())).build());
    }

    private static boolean finite(Position point) {
        return point != null && Float.isFinite(point.getX()) && Float.isFinite(point.getY())
                && Float.isFinite(point.getZ());
    }

    private static boolean marked(GameEntity entity, String key) {
        if (entity == null) return false;
        if (entity.getGlobalAbilityValues().containsKey(key))
            return entity.getGlobalAbilityValues().get(key) == 1f;
        if (entity instanceof EntityGadget gadget && gadget.getConfigGadget() != null) {
            var global = gadget.getConfigGadget().getGlobalValue();
            return global != null && global.getInitServerGlobalValues() != null
                    && global.getInitServerGlobalValues().getOrDefault(key, 0f) == 1f;
        }
        return false;
    }

    private static float protectionDrain(Player player, EntityAvatar avatar, int type) {
        String marker;
        float radius, height, drain;
        switch (type) {
            case 1 -> { marker = "IS_WARM_POINT"; radius = 6.5f; height = 5; drain = 25; }
            case 8 -> { marker = "IS_TATAERIREGION_POINT"; radius = 5; height = 10; drain = 250; }
            case 13 -> { marker = "IS_TSURUMIMIST_POINT"; radius = 12; height = 10; drain = 125; }
            case 5 -> { marker = "IS_SEAMIST_POINT"; radius = 6.5f; height = 5; drain = 25; }
            case 3 -> { marker = "IS_HOTMONSTER_POINT"; radius = 3; height = 5; drain = 20; }
            case 4 -> { marker = "IS_COLDMONSTER_POINT"; radius = 3; height = 5; drain = 20; }
            default -> { return 0; }
        }
        if (type == 8 && (marked(avatar, "HAS_ELECTRIC_FAIRY_BUFF")
                || marked(player.getTeamManager().getEntity(), "HAS_ELECTRIC_FAIRY_BUFF"))) return drain;
        var position = player.getPosition();
        for (var entity : player.getScene().getEntities().values()) {
            if (entity == null || entity == avatar || entity.getScene() != player.getScene()
                    || !marked(entity, marker)) continue;
            var other = entity.getPosition();
            double dx = position.getX() - other.getX(), dz = position.getZ() - other.getZ();
            if (Math.abs(position.getY() - other.getY()) <= height / 2f
                    && dx * dx + dz * dz <= radius * radius) return drain;
        }
        return 0;
    }

    static float resistance(Player player, EntityAvatar current, int type, String source, String trend) {
        float resist = 0;
        var entities = new LinkedHashSet<GameEntity>(new ArrayList<>(player.getTeamManager().getActiveTeam()));
        entities.add(current);
        if (player.getTeamManager().getEntity() != null) entities.add(player.getTeamManager().getEntity());
        for (var entity : entities) {
            if (entity == null) continue;
            for (var controller : entity.getInstancedModifiers().values()) {
                var mixins = controller.getModifierData().modifierMixins;
                if (mixins == null) continue;
                for (var mixin : mixins) {
                    if (mixin == null || mixin.type != AbilityMixinData.Type.ResistClimateMixin
                            || !filter(mixin.climateSource, source) || !filter(mixin.climateTrend, trend)
                            || mixin.climateTypes != null && !mixin.climateTypes.isEmpty()
                                && mixin.climateTypes.stream().noneMatch(name -> QuestClimateAreas.typeId(name) == type))
                        continue;
                    if ("Current".equals(mixin.climateAvatarStage) && entity != current
                            && entity != player.getTeamManager().getEntity()) continue;
                    if ("Back".equals(mixin.climateAvatarStage) && entity == current) continue;
                    float ratio = mixin.ratio == DynamicFloat.ONE ? 0 : mixin.ratio.get(controller.getAbility());
                    if (Float.isFinite(ratio)) resist += ratio;
                }
            }
        }
        return Math.max(0f, 1f + resist);
    }

    private static boolean filter(String configured, String expected) {
        return configured == null || "All".equals(configured) || expected.equals(configured);
    }
}
