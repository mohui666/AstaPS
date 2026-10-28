package emu.grasscutter.game.quest.reconstruction;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.routes.Route;
import emu.grasscutter.data.binout.routes.RoutePoint;
import emu.grasscutter.data.binout.routes.RouteType;
import emu.grasscutter.game.dungeons.challenge.WorldChallenge;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.GadgetWorktop;
import emu.grasscutter.game.entity.gadget.platform.ConfigRoute;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.proto.MovingPlatformTypeOuterClass.MovingPlatformType;
import emu.grasscutter.net.proto.PlatformInfoOuterClass.PlatformInfo;
import emu.grasscutter.net.proto.EntityMoveInfoOuterClass.EntityMoveInfo;
import emu.grasscutter.net.proto.MotionInfoOuterClass.MotionInfo;
import emu.grasscutter.net.proto.MotionStateOuterClass.MotionState;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.server.packet.send.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Explicitly reconstructed encounters. Quest text, original actors and native completion gates remain authoritative. */
public final class StoryReconstructionRuntime {
    public static final int OPTION = 7;
    private static final Map<Integer, Map<Integer, Run>> RUNS = new ConcurrentHashMap<>();

    private static final class Run {
        final Player player;
        final Scene scene;
        final StoryReconstructionCatalog.Stage stage;
        final StoryReconstructionState.StageProgress saved;
        final Map<Integer, GameEntity> entities = new ConcurrentHashMap<>();
        final Map<Integer, Integer> operatorPoints = new ConcurrentHashMap<>();
        WorldChallenge challenge;
        Route route;
        EntityGadget boat;
        int insideSince;
        boolean inside;
        boolean insideFired;
        boolean restored;
        int challengeScore = -1;

        Run(Player player, StoryReconstructionCatalog.Stage stage) {
            this.player = player;
            this.scene = player.getScene();
            this.stage = stage;
            this.saved = player.getStoryReconstruction().ensure(stage.getQuestId());
            if (saved.getAnchor() == null) {
                saved.setAnchor((stage.isAnchorAtPlayer() ? player.getPosition() : stage.getPosition()).clone());
                player.save();
            }
            String instructions = stage.isAnchorAtPlayer() && stage.getKind().equals("arrival")
                    ? "请先离开这里8米，再返回起点3米以内。"
                    : switch (stage.getKind()) {
                        case "sequence" -> "请按顺序操作周围的装置。";
                        case "collect" -> "请逐一处理周围的目标。";
                        case "travel" -> "登上巡轨船后操作旁边的装置出发，并随船抵达终点。";
                    case "board" -> "登上重建载车的平台并站稳；附近机关不能代替登车。";
                        case "battle" -> "击败这片区域内的全部敌人。";
                        case "enterDungeon" -> "操作入口装置进入剧情秘境。";
                        default -> "请前往目标地点。";
                    };
            String title = stage.getTitle() == null || stage.getTitle().isBlank() ? "重建剧情关卡" : stage.getTitle();
            if(stage.getQuestId()==402415 || stage.getQuestId()==7500509)
                instructions="本关采用普通战斗模型的单阶段替代战，保留原目标的生命值；请击败当前敌人。原版多阶段未恢复。";
            player.sendPacket(new PacketPrivateChatNotify(emu.grasscutter.GameConstants.SERVER_CONSOLE_UID,
                    player.getUid(), "【重建剧情】" + title + "\n" + instructions));
        }
    }

    private StoryReconstructionRuntime() {}

    public static boolean isServerOwnedLuaNotification(String key) {
        return key != null && StoryReconstructionCatalog.installed().ownsKey(key);
    }

    /** Login continues an already entered explicit stage; deliberate quest rewind still resets it. */
    public static boolean preserveOnLogin(emu.grasscutter.game.quest.GameMainQuest main) {
        for (var quest : main.getActiveQuests()) {
            if (StoryReconstructionCatalog.installed().stage(quest.getSubQuestId()) == null) continue;
            var saved = main.getOwner().getStoryReconstruction().get(quest.getSubQuestId());
            if (saved != null && saved.getAnchor() != null) return true;
        }
        return false;
    }

    public static boolean isServerOwnedClientProgress(QuestContent type, int parameter) {
        return type == QuestContent.QUEST_CONTENT_LUA_NOTIFY
                && isServerOwnedLuaNotification(Integer.toUnsignedString(parameter));
    }

    public static boolean ownsLuaCondition(GameQuest quest,
            emu.grasscutter.data.excels.quest.QuestData.QuestContentCondition condition) {
        var stage = StoryReconstructionCatalog.installed().stage(quest.getSubQuestId());
        return stage != null && stage.getNotifications().stream()
                .anyMatch(signal -> signal.getKey().equals(condition.getParamStr()));
    }

    /** Reused native keys must be proved by this stage's actual actions, not another stage's counter. */
    public static boolean matchesLuaNotification(GameQuest quest,
            emu.grasscutter.data.excels.quest.QuestData.QuestContentCondition condition) {
        if (!ownsLuaCondition(quest, condition) || owner(quest.getOwner().getScene()) != quest.getOwner()) return false;
        var stage = StoryReconstructionCatalog.installed().stage(quest.getSubQuestId());
        var saved = quest.getOwner().getStoryReconstruction().get(stage.getQuestId());
        return saved != null && saved.getEmitted().getOrDefault(condition.getParamStr(), 0)
                >= Math.max(1, condition.getCount());
    }

    private static Player owner(Scene scene) {
        if (scene == null || scene.getWorld() == null) return null;
        var owner = scene.getWorld().getHost();
        return owner != null && owner.getScene() == scene && owner.getWorld() == scene.getWorld() ? owner : null;
    }

    private static GameQuest active(Player player, StoryReconstructionCatalog.Stage stage) {
        var quest = player.getQuestManager().getQuestById(stage.getQuestId());
        if (quest == null || quest.getState() != QuestState.QUEST_STATE_UNFINISHED || quest.getQuestData() == null)
            return null;
        if (!emu.grasscutter.game.quest.scenery.QuestSceneryRuntime.mayAccept(player,quest.getQuestData()))
            return null;
        // A configured notification cannot be repurposed to finish dialogue or an unrelated quest.
        for (var signal : stage.getNotifications()) {
            boolean original = quest.getQuestData().getFinishCond().stream().anyMatch(condition ->
                    condition.getType() == QuestContent.QUEST_CONTENT_LUA_NOTIFY
                            && signal.getKey().equals(condition.getParamStr())
                            && Math.max(1, condition.getCount()) == signal.getCount());
            if (!original) return null;
        }
        if (stage.getNotifications().isEmpty() && !stage.getKind().equals("enterDungeon")) {
            for (var effect : stage.getGadgetEffects()) {
                boolean original = quest.getQuestData().getFinishCond().stream().anyMatch(condition ->
                        condition.getType() == QuestContent.QUEST_CONTENT_GADGET_STATE_CHANGE
                                && condition.getParam() != null && condition.getParam().length >= 2
                                && condition.getParam()[0] == effect.getGroupId()
                                && condition.getParam()[1] == effect.getConfigId()
                                && Integer.toString(effect.getState()).equals(condition.getParamStr()));
                if (!original) return null;
            }
        }
        return quest;
    }

    /** Fresh accepts reset only that explicit stage, including a reused key's new round. */
    public static void onQuestStart(GameQuest quest) {
        onQuestStart(quest, StoryReconstructionCatalog.installed());
    }

    static void onQuestStart(GameQuest quest, StoryReconstructionCatalog catalog) {
        var stage = catalog.stage(quest.getSubQuestId());
        if (stage == null) return;
        var player = quest.getOwner();
        var runs = RUNS.get(player.getUid());
        var previous = runs == null ? null : runs.remove(stage.getQuestId());
        if (previous != null) remove(previous);
        player.getStoryReconstruction().reset(stage.getQuestId());
        for (var signal : stage.getNotifications()) player.getPlayerProgress().resetCurrentProgress(signal.getKey());
        player.save();
    }

    public static void onTick(Player player) {
        onTick(player, StoryReconstructionCatalog.installed());
    }

    static void onTick(Player player, StoryReconstructionCatalog catalog) {
        var scene = player.getScene();
        if (owner(scene) != player || player.getSceneLoadState() != Player.SceneLoadState.LOADED) return;
        var runs = RUNS.computeIfAbsent(player.getUid(), ignored -> new ConcurrentHashMap<>());
        // Departed/finished stages keep their saved state, while only their own transient actors retire.
        for (var run : List.copyOf(runs.values())) {
            if (run.player != player || run.scene != scene || active(player, run.stage) == null) {
                snapshot(run);
                remove(run);
                runs.remove(run.stage.getQuestId(), run);
            }
        }
        for (var stage : catalog.getStages()) {
            if (stage.getSceneId() != scene.getId() || active(player, stage) == null) continue;
            var run = runs.computeIfAbsent(stage.getQuestId(), ignored -> new Run(player, stage));
            synchronized (run) {
                if (!restoreSignals(run)) continue;
                if (run.saved.isActionsComplete() && !run.saved.isComplete()) {
                    int full = Math.max(1, run.stage.getNotifications().size());
                    signal(run, full, full);
                }
                if (run.saved.isComplete()) continue;
                switch (stage.getKind()) {
                    case "arrival" -> arrival(run);
                    case "battle" -> battle(run);
                    case "sequence", "collect" -> switches(run);
                    case "travel" -> travel(run);
                    case "board" -> board(run);
                    case "enterDungeon" -> control(run, 99, run.saved.getAnchor());
                    default -> throw new IllegalStateException("Unvalidated reconstruction stage kind");
                }
            }
        }
    }

    private static Position point(Run run, int index) {
        var point = run.stage.getPoints().get(index).clone();
        return run.stage.isAnchorAtPlayer() ? point.add(run.saved.getAnchor()) : point;
    }

    private static void arrival(Run run) {
        double distance = run.player.getPosition().computeDistance(run.saved.getAnchor());
        if (run.stage.isAnchorAtPlayer() && !run.saved.isDepartedAnchor()) {
            // A private fallback anchor under the player is not itself a completed arrival.
            if (distance >= 8) { run.saved.setDepartedAnchor(true); run.player.save(); }
            return;
        }
        boolean inside = distance <= (run.stage.isAnchorAtPlayer() ? Math.min(3, run.stage.getRadius()) : run.stage.getRadius());
        if (!inside) { run.inside = false; run.insideFired = false; return; }
        int now = run.scene.getSceneTime();
        if (!run.inside) { run.inside = true; run.insideSince = now; }
        if (run.insideFired || Integer.toUnsignedLong(now - run.insideSince) < run.stage.getHoldSeconds() * 1000) return;
        run.insideFired = true;
        run.saved.setArrivalActions(run.saved.getArrivalActions() + 1);
        int total = Math.max(run.stage.getNotifications().size(), run.stage.getNotifications().stream()
                .mapToInt(StoryReconstructionCatalog.Notification::getCount).max().orElse(1));
        signal(run, run.saved.getArrivalActions(), total);
    }

    /** The boarding objective is earned by standing above the real platform, never selecting a worktop. */
    private static void board(Run run) {
        if(run.boat==null||run.scene.getEntityById(run.boat.getId())!=run.boat)
            run.boat=gadget(run,80,run.stage.getCarrierGadgetId(),run.saved.getAnchor());
        var player=run.player.getPosition();var platform=run.boat.getPosition();
        double horizontal=Math.hypot(player.getX()-platform.getX(),player.getZ()-platform.getZ());
        float height=player.getY()-platform.getY();
        if(horizontal>run.stage.getRadius()||height<0.2f||height>3f) { run.inside=false;return; }
        int now=run.scene.getSceneTime();
        if(!run.inside) { run.inside=true;run.insideSince=now; }
        if(Integer.toUnsignedLong(now-run.insideSince)>=run.stage.getHoldSeconds()*1000)signal(run,1,1);
    }

    private static void battle(Run run) {
        if (!run.saved.isStarted()) {
            if (run.player.getPosition().computeDistance(run.saved.getAnchor()) > run.stage.getRadius()) return;
            run.saved.setStarted(true);
            run.player.save();
        }
        if (run.challenge == null) {
            if (run.scene.getChallenge() != null && run.scene.getChallenge().inProgress()) return;
            var group = new SceneGroup(); // Per-run descriptor, never the loaded shared template.
            group.id = run.stage.getGroupId();
            run.challenge = new WorldChallenge(run.scene, group, 1, run.stage.getQuestId(),
                    List.of(run.stage.monsterCount()), 0, run.stage.monsterCount(), List.of());
            run.scene.setChallenge(run.challenge);
            run.challenge.start();
        }
        snapshotMonsters(run);
        int offset = 0;
        for (int wave = 0; wave < run.saved.getWave(); wave++) offset += run.stage.getWaves().get(wave).size();
        if (run.saved.getWave() < run.stage.getWaves().size()) {
            var wave = run.stage.getWaves().get(run.saved.getWave());
            boolean dead = true;
            for (int index = 0; index < wave.size(); index++) {
                int slot = offset + index + 1;
                if (run.saved.getMonsterHp().getOrDefault(slot, -1f) == 0) continue;
                dead = false;
                var existing = run.entities.get(slot);
                if (existing != null && run.scene.getEntityById(existing.getId()) == existing) continue;
                var data = GameData.getMonsterDataMap().get(wave.get(index).intValue());
                if (data == null) throw new IllegalStateException("Missing explicitly configured story monster");
                double angle = 2 * Math.PI * index / Math.max(1, wave.size());
                var position = run.saved.getAnchor().clone().addX((float) Math.cos(angle) * 4)
                        .addZ((float) Math.sin(angle) * 4);
                var monster = ReconstructedStoryMonster.create(run.scene, data, position, new Position(), run.stage.getMonsterLevel());
                monster.setGroupId(run.stage.getGroupId());
                monster.setConfigId(run.stage.configId(slot));
                float hp = run.saved.getMonsterHp().getOrDefault(slot,
                        monster.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP));
                monster.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP,
                        Math.min(hp, monster.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP)));
                run.saved.getMonsterHp().put(slot, hp);
                run.entities.put(slot, monster);
                run.scene.addEntity(monster);
            }
            if (dead) { run.saved.setWave(run.saved.getWave() + 1); run.player.save(); }
        }
        int killed = (int) run.saved.getMonsterHp().values().stream().filter(hp -> hp == 0).count();
        if (run.challengeScore != killed) {
            run.challengeScore = killed;
            run.challenge.getScore().set(killed);
            run.scene.broadcastPacket(new PacketChallengeDataNotify(run.challenge, 1, killed));
        }
        signal(run, killed, run.stage.monsterCount());
        if (run.saved.isComplete() && run.scene.getChallenge() == run.challenge) {
            run.challenge.done();
            if (run.scene.getChallenge() == run.challenge) run.scene.setChallenge(null);
        }
    }

    private static void snapshotMonsters(Run run) {
        for (var entry : run.entities.entrySet()) {
            if (entry.getValue() instanceof EntityMonster monster) {
                // Disappearing alive is not a kill, including suite unloads and scene transfers.
                float hp = monster.isDead() ? 0 : Math.max(1, monster.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
                run.saved.getMonsterHp().put(entry.getKey(), hp);
            }
        }
    }

    private static void switches(Run run) {
        for (int index = 0; index < run.stage.getPoints().size(); index++) {
            var position = point(run, index);
            var visual = gadget(run, 20 + index, run.stage.getTargetGadgetId(), position);
            boolean opened = run.saved.getSwitches().contains(index);
            int desired = opened ? 202 : 201;
            if (visual.getState() != desired) visual.updateState(desired);
            var operator = control(run, 50 + index, position.clone().addZ(0.8f), !opened);
            run.operatorPoints.put(operator.getConfigId(), index);
        }
    }

    private static EntityGadget gadget(Run run, int slot, int gadgetId, Position position) {
        var existing = run.entities.get(slot);
        if (existing instanceof EntityGadget gadget && run.scene.getEntityById(gadget.getId()) == gadget) return gadget;
        if (!GameData.getGadgetDataMap().containsKey(gadgetId))
            throw new IllegalStateException("Missing explicitly configured story gadget");
        var gadget = new EntityGadget(run.scene, gadgetId, position.clone(), new Position());
        gadget.setGroupId(run.stage.getGroupId());
        gadget.setConfigId(run.stage.configId(slot));
        run.entities.put(slot, gadget);
        run.scene.addEntity(gadget);
        return gadget;
    }

    @SuppressWarnings("removal")
    private static EntityGadget control(Run run, int slot, Position position) {
        return control(run, slot, position, true);
    }

    @SuppressWarnings("removal")
    private static EntityGadget control(Run run, int slot, Position position, boolean enabled) {
        var gadget = gadget(run, slot, 70350081, position);
        if (!(gadget.getContent() instanceof GadgetWorktop)) gadget.setContent(new GadgetWorktop(gadget));
        var worktop = (GadgetWorktop) gadget.getContent();
        boolean had = worktop.getWorktopOptions().contains(OPTION);
        if (enabled) worktop.addWorktopOptions(new int[] {OPTION});
        else worktop.removeWorktopOption(OPTION);
        if (had != enabled) run.scene.broadcastPacket(new PacketWorktopOptionNotify(gadget));
        return gadget;
    }

    /** Dense route nodes keep the existing platform's authoritative position close to its animation. */
    private static Route buildRoute(Run run) {
        var points = new ArrayList<RoutePoint>();
        for (int segment = 0; segment < run.stage.getPoints().size() - 1; segment++) {
            var from = point(run, segment);
            var to = point(run, segment + 1);
            int steps = Math.max(1, (int) Math.ceil(from.computeDistance(to) / 4));
            for (int i = segment == 0 ? 0 : 1; i <= steps; i++) {
                float fraction = (float) i / steps;
                var node = new RoutePoint();
                node.setPos(new Position(from.getX() + (to.getX() - from.getX()) * fraction,
                        from.getY() + (to.getY() - from.getY()) * fraction,
                        from.getZ() + (to.getZ() - from.getZ()) * fraction));
                node.setTargetVelocity(run.stage.getSpeed());
                points.add(node);
            }
        }
        var route = new Route();
        route.setLocalId(run.stage.configId(98));
        route.setType(RouteType.OneWay);
        route.setPoints(points.toArray(RoutePoint[]::new));
        return route;
    }

    private static final class InlineRoute extends ConfigRoute {
        private final Route route;
        InlineRoute(Route route) { super(new Position(), false, false, route.getLocalId()); this.route = route; }
        @Override public PlatformInfo.Builder toProto() {
            return super.toProto().setRoute(route.toProto()).setRouteId(0)
                    .setMovingPlatformType(MovingPlatformType.MovingPlatformType_MOVING_PLATFORM_ROUTE);
        }
    }

    private static void travel(Run run) {
        if (run.route == null) run.route = buildRoute(run);
        int node = run.saved.getBoatNode();
        if (node < 0 || node >= run.route.getPoints().length)
            throw new IllegalStateException("Saved story boat checkpoint is outside its explicit configured route");
        if (run.boat == null || run.scene.getEntityById(run.boat.getId()) != run.boat) {
            run.boat = gadget(run, 80, run.stage.getCarrierGadgetId(), run.route.getPoints()[node].getPos());
            var config = new InlineRoute(run.route);
            config.setStartIndex(node);
            run.boat.setRouteConfig(config);
            // Initial appear was parked; publish the inline route rather than a nonexistent native route ID.
            run.scene.broadcastPacket(new PacketPlatformChangeRouteNotify(run.boat));
        }
        var operator = control(run, 79, run.boat.getPosition().clone().addY(1));
        var operatorPosition = run.boat.getPosition().clone().addY(1);
        if (operator.getPosition().computeDistance(operatorPosition) > 0.01) {
            operator.getPosition().set(operatorPosition);
            operator.setLastMoveReliableSeq(operator.getLastMoveReliableSeq() + 1);
            run.scene.broadcastPacket(new PacketSceneEntityMoveNotify(EntityMoveInfo.newBuilder()
                    .setEntityId(operator.getId()).setSceneTime(run.scene.getSceneTime())
                    .setReliableSeq(operator.getLastMoveReliableSeq())
                    .setMotionInfo(MotionInfo.newBuilder().setPos(operatorPosition.toProto())
                            .setRot(operator.getRotation().toProto()).setState(MotionState.MotionState_MOTION_NONE))
                    .build()));
        }
        if (!run.saved.isRiding()) return;
        if (run.player.getPosition().computeDistance(run.boat.getPosition()) > 12) {
            run.saved.setRiding(false);
            if (run.boat.getRouteConfig().isStarted()) run.boat.stopPlatform();
            run.player.save();
            return;
        }
        var config = (ConfigRoute) run.boat.getRouteConfig();
        run.saved.setBoatNode(config.getStartIndex());
        if (config.getStartIndex() == run.route.getPoints().length - 1
                && run.player.getPosition().computeDistance(run.boat.getPosition()) <= run.stage.getRadius())
            signal(run, run.route.getPoints().length - 1, run.route.getPoints().length - 1);
    }

    /** Scene-local override for ConfigRouteRun; it never changes GameData's shared route map. */
    public static Route route(Scene scene, int routeId) {
        var player = owner(scene);
        var runs = player == null ? null : RUNS.get(player.getUid());
        if (runs != null) for (var run : runs.values())
            if (run.scene == scene && run.route != null && run.route.getLocalId() == routeId) return run.route;
        return null;
    }

    /** True only for our own dynamic worktops, including denied requests; caller supplies the actual session player. */
    public static boolean tryInteract(Player player, GameEntity entity, int option) {
        var runs = RUNS.get(player.getUid());
        if (runs != null) for (var run : runs.values()) {
            if (run.entities.values().stream().anyMatch(value -> value == entity)) {
                interact(run, player, entity, option);
                return true;
            }
        }
        // A guest must not fall through to a host-owned runtime actor's Lua callback.
        var host = owner(player.getScene());
        var hostRuns = host == null ? null : RUNS.get(host.getUid());
        return hostRuns != null && hostRuns.values().stream()
                .anyMatch(run -> run.entities.values().stream().anyMatch(value -> value == entity));
    }

    private static int interact(Run run, Player player, GameEntity entity, int option) {
        synchronized (run) {
            if (run.player != player || owner(run.scene) != player || player.getScene() != run.scene
                    || player.getSceneLoadState() != Player.SceneLoadState.LOADED
                    || active(player, run.stage) == null || run.saved.isComplete() || option != OPTION
                    || !(entity instanceof EntityGadget gadget) || run.scene.getEntityById(entity.getId()) != entity
                    || !(gadget.getContent() instanceof GadgetWorktop worktop)
                    || !worktop.getWorktopOptions().contains(option)
                    || player.getPosition().computeDistance(gadget.getPosition()) > 4) return -1;
            switch (run.stage.getKind()) {
                case "sequence", "collect" -> {
                    var point = run.operatorPoints.get(entity.getConfigId());
                    if (point == null || run.saved.getSwitches().contains(point)
                            || run.stage.getKind().equals("sequence") && point != run.saved.getSwitches().size()) return -1;
                    run.saved.getSwitches().add(point);
                    worktop.removeWorktopOption(option);
                    var visual = run.entities.get(20 + point);
                    if (visual instanceof EntityGadget light) light.updateState(202);
                    run.scene.broadcastPacket(new PacketWorktopOptionNotify(gadget));
                    signal(run, run.saved.getSwitches().size(), run.stage.getPoints().size());
                    player.save(); // Includes intermediate switches whose notification waterline is still zero.
                    return 0;
                }
                case "travel" -> {
                    if (run.boat == null || player.getPosition().computeDistance(run.boat.getPosition()) > 6) return -1;
                    run.saved.setRiding(true);
                    run.saved.setStarted(true);
                    if (run.saved.getBoatNode() < run.route.getPoints().length - 1 && !run.boat.startPlatform()) {
                        run.saved.setRiding(false);
                        return -1;
                    }
                    player.save();
                    return 0;
                }
                case "enterDungeon" -> {
                    if (!emu.grasscutter.game.dungeons.DomainDungeonHelper.canEnterDungeonFromOpenWorld(
                            player, run.stage.getPointId(), run.stage.getDungeonId())) return -1;
                    return player.getServer().getDungeonSystem().enterDungeon(player,
                            run.stage.getPointId(), run.stage.getDungeonId(), true) ? 0 : -1;
                }
                default -> { return -1; }
            }
        }
    }

    /** Native group worktop callback: it can start an actual stage, never directly complete one. */
    public static int interact(SceneScriptManager scripts, int uid, int groupId, int configId, int option) {
        var player = owner(scripts.getScene());
        var group = StoryReconstructionCatalog.installed().group(scripts.getScene().getId(), groupId);
        var entity = scripts.getScene().getEntityByConfigId(configId, groupId);
        if (player == null || uid != player.getUid() || group == null || configId != group.getControllerConfigId()
                || option != OPTION || !(entity instanceof EntityGadget gadget)
                || !(gadget.getContent() instanceof GadgetWorktop worktop)
                || !worktop.getWorktopOptions().contains(option)
                || player.getPosition().computeDistance(entity.getPosition()) > 4)
            return -1;
        onTick(player);
        return 0;
    }

    public static int groupLoaded(SceneScriptManager scripts, int groupId) {
        var group = StoryReconstructionCatalog.installed().group(scripts.getScene().getId(), groupId);
        if (group == null) return -1;
        var entity = scripts.getScene().getEntityByConfigId(group.getControllerConfigId(), groupId);
        if (entity != null) onEntityCreated(entity);
        return 0;
    }

    public static int monsterDied(SceneScriptManager scripts, int groupId, int configId) {
        var player = owner(scripts.getScene());
        var runs = player == null ? null : RUNS.get(player.getUid());
        if (runs == null) return -1;
        boolean actual = runs.values().stream().filter(run -> run.scene == scripts.getScene())
                .flatMap(run -> run.entities.values().stream()).anyMatch(entity -> entity instanceof EntityMonster
                        && entity.getGroupId() == groupId && entity.getConfigId() == configId && entity.isDead());
        if (!actual) return -1;
        onTick(player);
        return 0;
    }

    @SuppressWarnings("removal")
    public static void onEntityCreated(GameEntity entity) {
        if (!(entity instanceof EntityGadget gadget)) return;
        var group = StoryReconstructionCatalog.installed().group(entity.getScene().getId(), entity.getGroupId());
        if (group == null || entity.getConfigId() != group.getControllerConfigId()) return;
        if (!(gadget.getContent() instanceof GadgetWorktop)) gadget.setContent(new GadgetWorktop(gadget));
        ((GadgetWorktop) gadget.getContent()).addWorktopOptions(new int[] {OPTION});
    }

    /** Independent keys own consecutive action sections; one operation never fabricates every key. */
    static Map<String, Integer> waterline(StoryReconstructionCatalog.Stage stage, int actions, int total) {
        var result = new LinkedHashMap<String, Integer>();
        int count = stage.getNotifications().size();
        if (total < count) throw new IllegalArgumentException("Independent story keys require independent actions");
        for (int index = 0; index < count; index++) {
            var notification = stage.getNotifications().get(index);
            int from = index * total / count;
            int to = (index + 1) * total / count;
            int done = Math.max(0, Math.min(actions, to) - from);
            result.put(notification.getKey(), (int) ((long) done * notification.getCount() / (to - from)));
        }
        return result;
    }

    private static void signal(Run run, int actions, int total) {
        if (active(run.player, run.stage) == null) return;
        if (actions >= total) {
            run.saved.setActionsComplete(true);
            if (!applyGadgetEffects(run, false)) {
                run.player.save();
                return;
            }
        }
        boolean changed = false;
        for (var entry : waterline(run.stage, actions, total).entrySet()) {
            int previous = run.saved.getEmitted().getOrDefault(entry.getKey(), 0);
            for (int value = previous; value < entry.getValue(); value++) {
                run.saved.getEmitted().put(entry.getKey(), value + 1);
                run.player.getPlayerProgress().addToCurrentProgress(entry.getKey(), 1);
                run.player.getQuestManager().queueEvent(QuestCond.QUEST_COND_LUA_NOTIFY, entry.getKey(), 0);
                run.player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_LUA_NOTIFY, entry.getKey(), 0);
                changed = true;
            }
        }
        if (actions >= total) run.saved.setComplete(true);
        if (changed || actions >= total) run.player.save();
    }

    /** Apply only explicit effects to real scene actors after the required actions were earned. */
    private static boolean applyGadgetEffects(Run run, boolean replay) {
        var targets = new ArrayList<EntityGadget>();
        for (var effect : run.stage.getGadgetEffects()) {
            var entity = run.scene.getEntityByConfigId(effect.getConfigId(), effect.getGroupId());
            if (entity == null) {
                var scripts = run.scene.getScriptManager();
                if (!scripts.isInit() || scripts.isDestroyed()) return false;
                if (scripts.getGroupById(effect.getGroupId()) == null) return false;
                entity = run.scene.getEntityByConfigId(effect.getConfigId(), effect.getGroupId());
            }
            if (!(entity instanceof EntityGadget target)) return false;
            targets.add(target);
        }
        for (int index = 0; index < targets.size(); index++) {
            var target = targets.get(index);
            var effect = run.stage.getGadgetEffects().get(index);
            boolean changed = target.getState() != effect.getState();
            target.updateState(effect.getState());
            if (replay && !changed) run.player.getQuestManager().queueEvent(
                    QuestContent.QUEST_CONTENT_GADGET_STATE_CHANGE,
                    target.getGroupId(), target.getConfigId(), target.getState());
        }
        return true;
    }

    private static boolean restoreSignals(Run run) {
        if (run.restored) return true;
        if ((run.saved.isComplete() || run.saved.isActionsComplete()) && !applyGadgetEffects(run, true)) return false;
        run.restored = true;
        for (var entry : run.saved.getEmitted().entrySet()) {
            int existing = Math.max(0, run.player.getPlayerProgress().getCurrentProgress(entry.getKey()));
            if (existing < entry.getValue()) {
                run.player.getPlayerProgress().addToCurrentProgress(entry.getKey(), entry.getValue() - existing);
            }
            // A save can precede dispatch of its event. Re-evaluate that actual saved action once,
            // even if its global counter was already persisted before the disconnect.
            run.player.getQuestManager().queueEvent(QuestContent.QUEST_CONTENT_LUA_NOTIFY, entry.getKey(), 0);
        }
        return true;
    }

    private static void snapshot(Run run) {
        synchronized (run) {
            snapshotMonsters(run);
            if (run.boat != null && run.boat.getRouteConfig() instanceof ConfigRoute config) {
                run.saved.setBoatNode(config.getStartIndex());
                run.saved.setRiding(false);
            }
            run.player.save();
        }
    }

    public static void capture(Player player) {
        var runs = RUNS.remove(player.getUid());
        if (runs == null) return;
        for (var run : runs.values()) { snapshot(run); remove(run); }
    }

    private static void remove(Run run) {
        if (run.boat != null && run.boat.getRouteConfig() != null && run.boat.getRouteConfig().isStarted())
            run.boat.stopPlatform();
        if (run.challenge != null && run.scene.getChallenge() == run.challenge) {
            if (run.challenge.inProgress()) run.challenge.abortQuiet();
            run.scene.setChallenge(null);
        }
        for (var entity : List.copyOf(run.entities.values()))
            if (run.scene.getEntityById(entity.getId()) == entity) run.scene.removeEntity(entity);
        run.entities.clear();
    }
}
