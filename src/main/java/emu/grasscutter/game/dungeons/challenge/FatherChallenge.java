package emu.grasscutter.game.dungeons.challenge;

import emu.grasscutter.game.dungeons.challenge.enums.FatherChallengeProperty;
import emu.grasscutter.game.dungeons.challenge.trigger.InTimeTrigger;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** A real aggregate challenge: child results contribute the weights supplied by the native Lua. */
public final class FatherChallenge extends WorldChallenge {
    private final Map<Integer, Child> children = new ConcurrentHashMap<>();
    private int failGoal;
    private int successGoal;
    private int currentSuccess;
    private int currentFail;

    public FatherChallenge(Scene scene, SceneGroup group, int index, int id,
            int duration, int successGoal, int failGoal) {
        super(scene, group, id, index, List.of(duration, successGoal, failGoal), duration,
                successGoal, List.of(new InTimeTrigger()));
        this.failGoal = failGoal;
        this.successGoal = successGoal;
    }

    public synchronized boolean attach(WorldChallenge definition, int successWeight, int failWeight) {
        var existing = children.get(definition.getChallengeIndex());
        if (existing != null && existing.inProgress()) return false;
        var child = new Child(definition, successWeight, failWeight);
        children.put(child.getChallengeIndex(), child);
        if (inProgress()) child.start();
        return true;
    }

    public WorldChallenge child(int index) { return children.get(index); }
    @Override public int getGoal() { return successGoal; }

    @Override public void start() {
        if (inProgress()) return;
        super.start();
        if (inProgress()) children.values().forEach(WorldChallenge::start);
    }

    @Override public void onCheckTimeOut() {
        super.onCheckTimeOut();
        if (inProgress()) children.values().forEach(WorldChallenge::onCheckTimeOut);
    }

    @Override public void onMonsterDeath(EntityMonster monster) {
        if (inProgress()) children.values().forEach(child -> child.onMonsterDeath(monster));
    }

    @Override public void onGadgetDeath(EntityGadget gadget) {
        if (inProgress()) children.values().forEach(child -> child.onGadgetDeath(gadget));
    }

    @Override public void onGadgetDamage(EntityGadget gadget) {
        if (inProgress()) children.values().forEach(child -> child.onGadgetDamage(gadget));
    }

    @Override public void onGroupTriggerDeath(SceneTrigger trigger) {
        if (inProgress()) children.values().forEach(child -> child.onGroupTriggerDeath(trigger));
    }

    @Override public void done() {
        if (!inProgress()) return;
        children.values().stream().filter(WorldChallenge::inProgress).forEach(WorldChallenge::abortQuiet);
        super.done();
    }

    @Override public void fail() {
        if (!inProgress()) return;
        children.values().stream().filter(WorldChallenge::inProgress).forEach(WorldChallenge::abortQuiet);
        super.fail();
    }

    @Override public void abortQuiet() {
        children.values().stream().filter(WorldChallenge::inProgress).forEach(WorldChallenge::abortQuiet);
        super.abortQuiet();
    }

    public synchronized boolean modify(FatherChallengeProperty property, int value) {
        if (!inProgress()) return false;
        // Native ChallengeCondChildSucc/Fail::onModifyProperty use deltas;
        // SUM changes the goal, while CUR changes current points.
        switch (property) {
            case DURATION -> { return addDuration(value, true); }
            case CUR_SUCC -> currentSuccess = (int) Math.max(0, Math.min(successGoal, (long) currentSuccess + value));
            case CUR_FAIL -> currentFail = (int) Math.max(0, Math.min(failGoal, (long) currentFail + value));
            case SUM_SUCC -> successGoal = Math.max(1, Math.addExact(successGoal, value));
            case SUM_FAIL -> failGoal = Math.max(1, Math.addExact(failGoal, value));
        }
        checkResult();
        return true;
    }

    private synchronized void childFinished(Child child, boolean success) {
        if (!inProgress()) return;
        if (success) currentSuccess = (int) Math.min(successGoal, (long) currentSuccess + child.successWeight);
        else currentFail = (int) Math.min(failGoal, (long) currentFail + child.failWeight);
        checkResult();
    }

    private void checkResult() {
        getScore().set(currentSuccess);
        if (failGoal > 0 && currentFail >= failGoal) fail();
        else if (successGoal > 0 && currentSuccess >= successGoal) done();
    }

    private final class Child extends WorldChallenge {
        private final int successWeight;
        private final int failWeight;

        Child(WorldChallenge definition, int successWeight, int failWeight) {
            super(definition.getScene(), definition.getGroup(), definition.getChallengeId(),
                    definition.getChallengeIndex(), definition.getParamList(), definition.getTimeLimit(),
                    definition.getGoal(), definition.getChallengeTriggers());
            this.successWeight = successWeight;
            this.failWeight = failWeight;
            setParticipantUids(definition.getParticipantUids());
            setFatherIndex(FatherChallenge.this.getChallengeIndex());
        }

        @Override public void done() { finishChild(true); }
        @Override public void fail() { finishChild(false); }

        private synchronized void finishChild(boolean success) {
            if (!inProgress()) return;
            if (success) finishSuccessQuiet();
            else abortQuiet();
            getScene().getScriptManager().callEvent(new ScriptArgs(getGroup().id,
                    success ? EventType.EVENT_CHALLENGE_SUCCESS : EventType.EVENT_CHALLENGE_FAIL,
                    getChallengeIndex(), getFinishedTime()).setEventSource(getChallengeIndex()));
            getChallengeTriggers().forEach(trigger -> trigger.onFinish(this));
            childFinished(this, success);
        }
    }
}
