package emu.grasscutter.game.quest;

import dev.morphia.annotations.*;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.*;
import emu.grasscutter.data.binout.MainQuestData.*;
import emu.grasscutter.data.excels.RewardData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.database.DatabaseHelper;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.quest.enums.*;
import emu.grasscutter.game.quest.content.ContentServerState;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.net.proto.ChildQuestOuterClass.ChildQuest;
import emu.grasscutter.net.proto.ParentQuestOuterClass.ParentQuest;
import emu.grasscutter.server.packet.send.*;
import emu.grasscutter.utils.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.*;
import org.bson.types.ObjectId;

@Entity(value = "quests", useDiscriminator = false)
public class GameMainQuest {
    @Id private ObjectId id;
    @Indexed @Getter private int ownerUid;
    @Transient @Getter private Player owner;
    @Transient @Getter private QuestManager questManager;
    @Getter private Map<Integer, GameQuest> childQuests;
    @Getter private int parentQuestId;
    @Getter private int[] questVars;
    @Getter private long[] timeVar;
    /** New native timers use Unix seconds; existing timeVar entries keep their saved game-minute units. */
    @Getter private Map<Integer, Long> timeVarUnixSeconds = new HashMap<>();
    @Getter private ParentQuestState state;
    @Getter private boolean isFinished;
    @Getter private int rewardIndex;
    // Completion evidence for MQ1000 rescue plot reports that arrive before acceptance.
    @Getter @Setter(AccessLevel.PACKAGE) private boolean liyueRescuePlotReported;
    @Getter @Setter(AccessLevel.PACKAGE) private int liyueAmberSuite;
    /**
     * Added with /quest add. Such a quest line is sent to the client and tracked even while general
     * questing is off, since someone asked for it explicitly.
     */
    @Getter @Setter private boolean addedByCommand;
    @Getter List<QuestGroupSuite> questGroupSuites;

    @Getter int[] suggestTrackMainQuestList;
    @Getter private Map<Integer, TalkData> talks;

    @Deprecated // Morphia only. Do not use.
    public GameMainQuest() {}

    public GameMainQuest(Player player, int parentQuestId) {
        this.owner = player;
        this.ownerUid = player.getUid();
        this.questManager = player.getQuestManager();
        this.parentQuestId = parentQuestId;
        this.childQuests = new HashMap<>();
        this.talks = new HashMap<>();
        // official server always has a list of 5 questVars, with default value 0
        this.questVars = new int[] {0, 0, 0, 0, 0};
        this.timeVar =
                new long[] {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1}; // theoretically max is 10 here
        this.state = ParentQuestState.PARENT_QUEST_STATE_NONE;
        this.questGroupSuites = new ArrayList<>();
        addAllChildQuests();
    }

    private void addAllChildQuests() {
        List<Integer> subQuestIds =
                Arrays.stream(GameData.getMainQuestDataMap().get(this.parentQuestId).getSubQuests())
                        .map(SubQuestData::getSubId)
                        .toList();
        for (var subQuestId : subQuestIds) {
            QuestData questConfig = GameData.getQuestDataMap().get((int) subQuestId);
            if (questConfig == null) {
                Grasscutter.getLogger()
                        .error(
                                "Quest {} not found in QuestData. Please check MainQuestData and QuestData.",
                                subQuestId);
                continue;
            }

            this.childQuests.put(subQuestId, new GameQuest(this, questConfig));
        }
    }

    public Collection<GameQuest> getActiveQuests() {
        return childQuests.values().stream()
                .filter(q -> q.getState().getValue() == QuestState.QUEST_STATE_UNFINISHED.getValue())
                .toList();
    }

    public void setOwner(Player player) {
        if (player.getUid() != this.getOwnerUid()) return;
        this.owner = player;
    }

    public int getQuestVar(int i) {
        return questVars[i];
    }

    public void setQuestVar(int i, int value) {
        int previousValue = this.questVars[i];
        this.questVars[i] = value;
        Grasscutter.getLogger()
                .debug("questVar {} value changed from {} to {}", i, previousValue, value);

        this.triggerQuestVarAction(i, this.questVars[i]);
    }

    public void incQuestVar(int i, int inc) {
        int previousValue = this.questVars[i];
        this.questVars[i] += inc;
        Grasscutter.getLogger()
                .debug(
                        "questVar {} value incremented from {} to {}", i, previousValue, previousValue + inc);

        this.triggerQuestVarAction(i, this.questVars[i]);
    }

    public void decQuestVar(int i, int dec) {
        int previousValue = this.questVars[i];
        this.questVars[i] -= dec;
        Grasscutter.getLogger()
                .debug(
                        "questVar {} value decremented from {} to {}", i, previousValue, previousValue - dec);

        this.triggerQuestVarAction(i, this.questVars[i]);
    }

    public void randomQuestVar(int i, int low, int high) {
        int previousValue = this.questVars[i];
        this.questVars[i] = Utils.random.nextInt(low, high);
        Grasscutter.getLogger()
                .debug("questVar {} value randomized from {} to {}", i, previousValue, this.questVars[i]);

        this.triggerQuestVarAction(i, this.questVars[i]);
    }

    public void triggerQuestVarAction(int index, int value) {
        var questManager = this.getQuestManager();
        questManager.queueEvent(QuestCond.QUEST_COND_QUEST_VAR_EQUAL, index, value);
        questManager.queueEvent(QuestCond.QUEST_COND_QUEST_VAR_GREATER, index, value);
        questManager.queueEvent(QuestCond.QUEST_COND_QUEST_VAR_LESS, index, value);
        questManager.queueEvent(QuestContent.QUEST_CONTENT_QUEST_VAR_EQUAL, index, value);
        questManager.queueEvent(QuestContent.QUEST_CONTENT_QUEST_VAR_GREATER, index, value);
        questManager.queueEvent(QuestContent.QUEST_CONTENT_QUEST_VAR_LESS, index, value);
        questManager.queueEvent(QuestContent.QUEST_CONTENT_QUEST_VAR_NOT_EQUAL, index, value);

        this.getOwner()
                .sendPacket(new PacketQuestUpdateQuestVarNotify(this.getParentQuestId(), this.questVars));
    }

    public GameQuest getChildQuestById(int id) {
        return this.getChildQuests().get(id);
    }

    public GameQuest getChildQuestByOrder(int order) {
        return this.getChildQuests().values().stream()
                .filter(p -> p.getQuestData().getOrder() == order)
                .toList()
                .get(0);
    }

    /** Native parent rewards are alternatives, selected by a zero-based quest action. */
    public boolean setRewardIndex(int index) {
        var data = GameData.getMainQuestDataMap().get(this.parentQuestId);
        if (data == null || data.getRewardIdList() == null
                || index < 0 || index >= data.getRewardIdList().length) return false;
        if (this.rewardIndex != index) {
            this.rewardIndex = index;
            save();
        }
        return true;
    }

    public void finish() {
        // Avoid recursion from child finish() in GameQuest
        // when auto finishing all child quests with QUEST_STATE_UNFINISHED (below)
        synchronized (this) {
            if (this.isFinished || this.state == ParentQuestState.PARENT_QUEST_STATE_FINISHED) {
                Grasscutter.getLogger()
                        .debug(
                                "Skip main quest {} finishing because it's already finished",
                                this.getParentQuestId());
                return;
            }

            this.isFinished = true;
            this.state = ParentQuestState.PARENT_QUEST_STATE_FINISHED;
        }

        this.getOwner().sendPacket(new PacketFinishedParentQuestUpdateNotify(this));
        this.getOwner().sendPacket(new PacketCodexDataUpdateNotify(this));

        this.save();

        // Add rewards
        MainQuestData mainQuestData = GameData.getMainQuestDataMap().get(this.getParentQuestId());
        var rewards = mainQuestData.getRewardIdList();
        if (rewards != null && rewardIndex >= 0 && rewardIndex < rewards.length) {
            RewardData rewardData = GameData.getRewardDataMap().get(rewards[rewardIndex]);
            if (rewards[rewardIndex] != 0 && rewardData != null)
                this.getOwner().getInventory()
                        .addItemParamDatas(rewardData.getAllRewardItems(), ActionReason.QuestReward);
        }

        this.tryStartFollowingQuests();
        this.getQuestManager().queueEvent(
                QuestContent.QUEST_CONTENT_PARENT_QUEST_STATE_EQUAL,
                this.getParentQuestId(), this.getState().getValue());
    }

    /** Retries only successor startup after this parent has already been persisted as finished. */
    void tryStartFollowingQuests() {
        if (!this.isFinished && this.state != ParentQuestState.PARENT_QUEST_STATE_FINISHED) return;

        var mainQuestData = GameData.getMainQuestDataMap().get(this.parentQuestId);
        if (mainQuestData == null || mainQuestData.getSuggestTrackMainQuestList() == null) return;

        for (int next : mainQuestData.getSuggestTrackMainQuestList()) {
            this.getOwner().getQuestManager().startMainQuestIfUnlinked(next);
        }
    }
    // TODO
    public void fail() {}

    public void cancel() {}

    public List<Position> rewindTo(GameQuest targetQuest, boolean notifyDelete) {
        if (targetQuest == null || !targetQuest.rewind(notifyDelete)) {
            return null;
        }

        // if(rewindPositions.isEmpty()){
        //     this.addRewindPoints();
        // }

        List<Position> posAndRot = new ArrayList<>();
        if (hasRewindPosition(targetQuest.getSubQuestId(), posAndRot)) {
            return posAndRot;
        }

        List<GameQuest> rewindQuests =
                getChildQuests().values().stream()
                        .filter(
                                p ->
                                        (p.getState() == QuestState.QUEST_STATE_UNFINISHED
                                                        || p.getState() == QuestState.QUEST_STATE_FINISHED)
                                                && p.getQuestData() != null
                                                && p.getQuestData().isRewind())
                        .toList();

        for (GameQuest quest : rewindQuests) {
            if (hasRewindPosition(quest.getSubQuestId(), posAndRot)) {
                return posAndRot;
            }
        }

        return null;
    }

    /** Resume reviewed parallel stages without replaying completed talks or resetting counters. */
    public List<Position> resumeOnLogin() {
        if (this.questManager == null) this.questManager = getOwner().getQuestManager();
        if (emu.grasscutter.game.quest.coop.CoopSystem.preserveOnLogin(this)) return null;
        if (emu.grasscutter.game.quest.check.QuestCheckRuntime.preserveOnLogin(this)) return null;
        if (emu.grasscutter.game.gcg.story.StoryCardRuntime.preserveOnLogin(this)) return null;
        if (emu.grasscutter.game.quest.comedy.ComedyVoucherRuntime.preserveOnLogin(this)) return null;
        if (emu.grasscutter.game.quest.rename.QuestMiscRenameService.preserveOnLogin(this)) return null;
        if (emu.grasscutter.game.quest.reconstruction.QuestActivityRuntime.preserveOnLogin(this)) return null;
        if (emu.grasscutter.game.quest.scenery.QuestSceneryRuntime.preserveOnLogin(this)) return null;
        if (emu.grasscutter.game.quest.field.QuestFieldRuntime.preserveOnLogin(this)) return null;
        if (emu.grasscutter.game.quest.delivery.QuestDeliveryRuntime.preserveOnLogin(this)) return null;
        if (emu.grasscutter.game.quest.war.ReconstructedWarRuntime.preserveOnLogin(this)) return null;
        if (QuestInventoryProgress.preserveOnLogin(this)) return null;
        // Replaying this waiting stage's begin exec can restart its deadline on every login.
        if (QuestTimeConditions.preserveOnLogin(this)
                || emu.grasscutter.game.quest.reconstruction.StoryReconstructionRuntime.preserveOnLogin(this)
                || getActiveQuests().stream().anyMatch(emu.grasscutter.game.quest.reconstruction
                        .CalibertQuestReconstruction::shouldPreserveOnLogin)) return null;
        if (QuestManager.isQuestingActive()) {
            var jadePosition = LiyueJadeChamberRecovery.resumeOnLogin(this);
            if (jadePosition != null) return jadePosition;
        }
        if (AmberFlightExamSupport.preserveOnLogin(this)) return null;
        if (QuestManager.isQuestingActive() && RazorWolfQuestSupport.isActiveBattle(this)) return null;
        if (MondstadtStoryQuestSupport.restoreCompletedAmberDungeon(this)) return null;
        if (QuestManager.isQuestingActive() && MondstadtStoryQuestSupport.isAmberSecondSegment(this)) return null;
        if (MondstadtStoryQuestSupport.canContinueAmberDungeon(
                getChildQuestById(48510), GameData.getDungeonDataMap().get(2007))) return null;
        if (QuestManager.isQuestingActive() && LiyueActFourEntrySupport.resumeOnLogin(this))
            return null;
        if (QuestManager.isQuestingActive() && LiyueGoldenHouseEntrySupport.resumeOnLogin(this))
            return null;
        if (QuestManager.isQuestingActive() && LiyueChargePotRecovery.resumeOnLogin(this))
            return null;
        if (QuestManager.isQuestingActive() && LiyueCloudDungeonEntryRecovery.resumeOnLogin(this))
            return null;
        if (QuestManager.isQuestingActive() && (LiyueCloudRecipeRecovery.resumeOnLogin(this)
                || LiyueMountainQuestResume.resumeOnLogin(this))) return null;
        if (QuestManager.isQuestingActive() && LiyueCityQuestResume.resumeOnLogin(this)) {
            return null;
        }
        if (QuestManager.isQuestingActive() && LiyueWangshuItemRecovery.resumeOnLogin(this)) {
            return null;
        }
        if (QuestManager.isQuestingActive() && LiyueRescuePlotRecovery.resumeOnLogin(this)) {
            return null;
        }
        var recovery = QuestManager.isQuestingActive()
                ? LiyueParallelQuestResume.selectPhase(this) : null;
        if (recovery == null) return rewind();

        for (var quest : recovery.quests()) {
            if (quest.getState() == QuestState.QUEST_STATE_UNSTARTED) quest.start();
        }

        // Older order-based rewinds may have erased counters while keeping completed talks.
        // Restore only their missing contribution, so repeated logins never add it twice.
        int completedTalks = recovery.completedTalks();
        for (int counterId : recovery.phase().counters()) {
            int saved = Math.max(0,
                    getOwner().getPlayerProgress().getCurrentProgress(String.valueOf(counterId)));
            if (completedTalks > saved) {
                getOwner().getProgressManager().addQuestProgress(counterId, completedTalks - saved);
            }
            // Recheck persisted counts even if no new talk event occurred this session.
            getOwner().getQuestManager().queueEvent(
                    QuestContent.QUEST_CONTENT_ADD_QUEST_PROGRESS, counterId);
        }
        var counter = getChildQuestById(recovery.phase().completedCounter());
        if (counter.getState() == QuestState.QUEST_STATE_FINISHED) counter.triggerStateEvents();
        return null; // Keep the saved login position.
    }

    // Rewinds to the last finished/unfinished rewind quest, and returns the avatar rewind position
    // (if it exists)
    public List<Position> rewind() {
        if (this.questManager == null) {
            this.questManager = getOwner().getQuestManager();
        }
        var activeQuests = getActiveQuests();
        var highestActiveQuest =
                activeQuests.stream()
                        .filter(q -> q.getQuestData() != null)
                        .max(Comparator.comparing(q -> q.getQuestData().getOrder()))
                        .orElse(null);

        if (highestActiveQuest == null) {
            var firstUnstarted =
                    getChildQuests().values().stream()
                            .filter(
                                    q ->
                                            q.getQuestData() != null
                                                    && q.getState().getValue() != QuestState.FINISHED.getValue())
                            .min(Comparator.comparingInt(a -> a.getQuestData().getOrder()));
            if (firstUnstarted.isEmpty()) {
                // all quests are probably finished, do don't rewind and maybe also set the mainquest to
                // finished?
                return null;
            }
            highestActiveQuest = firstUnstarted.get();
            // todo maybe try to accept quests if there is no active quest and no rewind target?
            // tryAcceptSubQuests(QuestTrigger.QUEST_COND_NONE, "", 0);
        }

        var highestOrder = highestActiveQuest.getQuestData().getOrder();
        var rewindTarget =
                getChildQuests().values().stream()
                        .filter(q -> q.getQuestData() != null)
                        .filter(q -> q.getQuestData().isRewind() && q.getQuestData().getOrder() <= highestOrder)
                        .max(Comparator.comparingInt(a -> a.getQuestData().getOrder()))
                        .orElse(null);

        return rewindTo(rewindTarget != null ? rewindTarget : highestActiveQuest, false);
    }

    public boolean hasRewindPosition(int subId, List<Position> posAndRot) {
        RewindData questRewind = GameData.getRewindDataMap().get(subId);
        if (questRewind == null) return false;

        RewindData.AvatarData avatarData = questRewind.getAvatar();
        if (avatarData == null) return false;

        String avatarPos = avatarData.getPos();
        QuestData.Guide guide = GameData.getQuestDataMap().get(subId).getGuide();
        if (guide == null) return false;

        int sceneId = guide.getGuideScene();
        var position = QuestSceneConditions.dummyPoint(sceneId, avatarPos);
        var rotation = QuestSceneConditions.dummyRotation(sceneId, avatarPos);
        if (position == null || rotation == null) return false;
        posAndRot.add(0, position);
        posAndRot.add(1, rotation);
        Grasscutter.getLogger().debug("Successfully loaded rewind data for quest {}.", subId);
        return true;
    }

    /**
     * Checks if the quest has a teleport position. Returns true if it does and adds the target
     * position and rotation to the list.
     *
     * @param subId The sub-quest ID.
     * @param posAndRot A list which will contain the position and rotation if the quest has a
     *     teleport.
     * @return True if the quest has a teleport position. False otherwise.
     */
    public boolean hasTeleportPosition(int subId, List<Position> posAndRot) {
        TeleportData questTransmit = GameData.getTeleportDataMap().get(subId);
        if (questTransmit == null) return false;

        TeleportData.TransmitPoint transmitPoint =
                !questTransmit.getTransmit_points().isEmpty()
                        ? questTransmit.getTransmit_points().get(0)
                        : null;
        if (transmitPoint == null) return false;

        String transmitPos = transmitPoint.getPos();
        int sceneId = transmitPoint.getScene_id();
        var position = QuestSceneConditions.dummyPoint(sceneId, transmitPos);
        var rotation = QuestSceneConditions.dummyRotation(sceneId, transmitPos);
        if (position == null || rotation == null) return false;
        posAndRot.add(0, position);
        posAndRot.add(1, rotation);
        Grasscutter.getLogger().debug("Successfully loaded teleport data for sub-quest {}.", subId);
        return true;
    }

    public void checkProgress() {
        for (var quest : getChildQuests().values()) {
            if (quest.getState() == QuestState.QUEST_STATE_UNFINISHED) {
                questManager.checkQuestAlreadyFulfilled(quest);
            }
        }
    }

    public void tryFailSubQuests(QuestContent condType, String paramStr, int... params) {
        try {
            List<GameQuest> subQuestsWithCond =
                    getChildQuests().values().stream()
                            .filter(p -> p.getState() == QuestState.QUEST_STATE_UNFINISHED)
                            .filter(
                                    p ->
                                            p.getQuestData().getFailCond().stream()
                                                    .anyMatch(q -> q.getType() == condType))
                            .toList();

            for (GameQuest subQuestWithCond : subQuestsWithCond) {
                val failCond = subQuestWithCond.getQuestData().getFailCond();
                boolean progressChanged = false;

                for (int i = 0; i < failCond.size(); i++) {
                    val condition = failCond.get(i);
                    if (condition.getType() == condType || condition.getType().isServerStatePredicate()) {
                        boolean result =
                                condition.getType().isServerStatePredicate()
                                        ? ContentServerState.matchesCurrent(subQuestWithCond, condition)
                                        : this.getOwner()
                                        .getServer()
                                        .getQuestSystem()
                                        .triggerContent(subQuestWithCond, condition, paramStr, params);
                        if (QuestProgress.recordContent(
                                subQuestWithCond.getFailProgressList(), i, result, condition.getType())) {
                            progressChanged = true;
                            getOwner().sendPacket(new PacketQuestProgressUpdateNotify(subQuestWithCond));
                        }
                    }
                }

                boolean shouldFail =
                        LogicType.calculate(
                                subQuestWithCond.getQuestData().getFailCondComb(),
                                subQuestWithCond.getFailProgressList());

                if (shouldFail) subQuestWithCond.fail();
                else if (progressChanged) subQuestWithCond.save();
            }

        } catch (Exception e) {
            Grasscutter.getLogger().error("An error occurred while trying to fail quest.", e);
        }
    }

    /** Accept conditions determine how a quest starts, not whether it can finish. */
    static boolean shouldHandleFinishEvent(GameQuest quest, QuestContent event) {
        if (quest == null || event == null || quest.getState() != QuestState.QUEST_STATE_UNFINISHED
                || quest.getQuestData() == null) return false;
        var conditions = quest.getQuestData().getFinishCond();
        return conditions != null && conditions.stream()
                .anyMatch(condition -> condition != null && condition.getType() == event);
    }

    public void tryFinishSubQuests(QuestContent condType, String paramStr, int... params) {
        try {
            List<GameQuest> subQuestsWithCond =
                    getChildQuests().values().stream()
                            .filter(p -> shouldHandleFinishEvent(p, condType))
                            .toList();

            for (GameQuest subQuestWithCond : subQuestsWithCond) {
                val finishCond = subQuestWithCond.getQuestData().getFinishCond();
                boolean progressChanged = false;

                for (int i = 0; i < finishCond.size(); i++) {
                    val condition = finishCond.get(i);
                    if (condition.getType() == condType || condition.getType().isServerStatePredicate()) {
                        boolean result =
                                condition.getType().isServerStatePredicate()
                                        ? ContentServerState.matchesCurrent(subQuestWithCond, condition)
                                        : this.getOwner()
                                        .getServer()
                                        .getQuestSystem()
                                        .triggerContent(subQuestWithCond, condition, paramStr, params);
                        if (QuestProgress.recordContent(
                                subQuestWithCond.getFinishProgressList(), i, result, condition.getType())) {
                            progressChanged = true;
                            getOwner().sendPacket(new PacketQuestProgressUpdateNotify(subQuestWithCond));
                        }
                    }
                }

                boolean shouldFinish =
                        LogicType.calculate(
                                subQuestWithCond.getQuestData().getFinishCondComb(),
                                subQuestWithCond.getFinishProgressList());

                var questManager = this.getQuestManager();
                if (questManager != null
                        && questManager.getLoggedQuests().contains(subQuestWithCond.getSubQuestId())) {
                    Grasscutter.getLogger()
                            .debug(
                                    ">>> Quest {} will be {} as a result of content trigger {} ({}, {}).",
                                    subQuestWithCond.getSubQuestId(),
                                    shouldFinish ? "finished" : "not finished",
                                    condType.name(),
                                    paramStr,
                                    Arrays.stream(params)
                                            .mapToObj(String::valueOf)
                                            .collect(Collectors.joining(", ")));
                }

                if (shouldFinish) subQuestWithCond.finish();
                else if (progressChanged) subQuestWithCond.save();
            }
        } catch (Exception e) {
            Grasscutter.getLogger().debug("An error occurred while trying to finish quest.", e);
        }
    }

    public void save() {
        DatabaseHelper.saveQuest(this);
    }

    public void delete() {
        DatabaseHelper.deleteQuest(this);
    }

    public ParentQuest toProto(boolean withChildQuests) {
        var proto =
                ParentQuest.newBuilder()
                        .setParentQuestId(getParentQuestId())
                        .setIsFinished(isFinished())
                        .setParentQuestState(getState().getValue());
        // 7.1: ParentQuest no longer has the uint64 video_key field

        if (withChildQuests) {
            for (var quest : this.getChildQuests().values()) {
                if (quest.getState() != QuestState.QUEST_STATE_UNSTARTED) {
                    var childQuest =
                            ChildQuest.newBuilder()
                                    .setQuestId(quest.getSubQuestId())
                                    .setState(quest.getState().getValue())
                                    .build();

                    proto.addChildQuestList(childQuest);
                }
            }
        }

        for (int i : getQuestVars()) {
            proto.addQuestVar(i);
        }

        return proto.build();
    }

    public synchronized boolean initTimeVar(int index) {
        if (index < 0 || index >= 10) {
            Grasscutter.getLogger()
                    .error(
                            "Trying to init out of bounds time var {} for quest {}", index, this.parentQuestId);
            return false;
        }
        // The old unit cannot be converted into an original wall-clock start. Keep a running
        // legacy slot until its actual CLEAR exec instead of resetting its current wait.
        if (legacyTimeVar(index) < 0) {
            if (this.timeVarUnixSeconds == null) this.timeVarUnixSeconds = new HashMap<>();
            this.timeVarUnixSeconds.put(index, timeVarNowSeconds());
            this.save();
        }
        synchronized (owner.getActiveQuestTimers()) {
            owner.getActiveQuestTimers().add(this.parentQuestId);
        }
        owner.getQuestManager().queueTimeVarChecks(this.parentQuestId);
        return true;
    }

    public synchronized boolean clearTimeVar(int index) {
        if (index < 0 || index >= 10) {
            Grasscutter.getLogger()
                    .error(
                            "Trying to clear out of bounds time var {} for quest {}", index, this.parentQuestId);
            return false;
        }
        boolean changed = legacyTimeVar(index) >= 0;
        if (this.timeVar != null && index < this.timeVar.length) this.timeVar[index] = -1;
        if (this.timeVarUnixSeconds != null) changed |= this.timeVarUnixSeconds.remove(index) != null;
        if (!checkActiveTimers()) {
            synchronized (owner.getActiveQuestTimers()) {
                owner.getActiveQuestTimers().remove(this.parentQuestId);
            }
        }
        if (changed) this.save();
        owner.getQuestManager().queueTimeVarChecks(this.parentQuestId);
        return true;
    }

    public synchronized boolean checkActiveTimers() {
        return this.timeVar != null && Arrays.stream(timeVar).anyMatch(value -> value >= 0)
                || this.timeVarUnixSeconds != null && !this.timeVarUnixSeconds.isEmpty();
    }

    public synchronized boolean isTimeVarActive(int index) {
        if (index < 0 || index >= 10) return false;
        return legacyTimeVar(index) >= 0 || this.timeVarUnixSeconds != null
                && this.timeVarUnixSeconds.getOrDefault(index, 0L) > 0;
    }

    private long legacyTimeVar(int index) {
        return this.timeVar != null && index >= 0 && index < this.timeVar.length ? this.timeVar[index] : -1;
    }

    /** A narrow clock seam lets elapsed-time tests advance time without waiting or changing the OS clock. */
    protected long timeVarNowSeconds() {
        return java.time.Instant.now().getEpochSecond();
    }

    public synchronized long getSecondsSinceTimeVar(int index) {
        if (index < 0 || index >= 10) return -1;
        var stamp = this.timeVarUnixSeconds == null ? null : this.timeVarUnixSeconds.get(index);
        if (stamp != null) {
            long now = timeVarNowSeconds();
            return stamp > 0 && now >= stamp ? now - stamp : -1;
        }
        long legacy = legacyTimeVar(index);
        if (legacy < 0 || owner == null || owner.getWorld() == null) return -1;
        long minutes = owner.getWorld().getTotalGameTimeMinutes() - legacy;
        return minutes >= 0 ? minutes * 60 : -1;
    }

    public synchronized long getDaysSinceTimeVar(int index) {
        if (index < 0 || index >= 10) {
            Grasscutter.getLogger()
                    .error(
                            "Trying to get days for out of bounds time var {} for quest {}",
                            index,
                            this.parentQuestId);
            return -1;
        }
        var stamp = this.timeVarUnixSeconds == null ? null : this.timeVarUnixSeconds.get(index);
        if (stamp != null) {
            long now = timeVarNowSeconds();
            if (stamp <= 0 || now < stamp) return -1;
            return QuestTimeConditions.dayIndex(now) - QuestTimeConditions.dayIndex(stamp);
        }
        long legacy = legacyTimeVar(index);
        if (legacy < 0 || owner == null || owner.getWorld() == null) return -1;
        if (owner.getWorld().getTotalGameTimeMinutes() < legacy) return -1;
        return owner.getWorld().getTotalGameTimeDays() - ConversionUtils.gameTimeToDays(legacy);
    }

    public synchronized long getHoursSinceTimeVar(int index) {
        if (index < 0 || index >= 10) {
            Grasscutter.getLogger()
                    .error(
                            "Trying to get hours for out of bounds time var {} for quest {}",
                            index,
                            this.parentQuestId);
            return -1;
        }
        long elapsed = getSecondsSinceTimeVar(index);
        return elapsed < 0 ? -1 : elapsed / 3600;
    }
}
