package emu.grasscutter.game.dungeons;

import dev.morphia.annotations.Entity;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import lombok.Getter;

/** The actual manager fields of one player-owned story dungeon, never a completion inference. */
@Entity(useDiscriminator = false)
@Getter
public final class PersistentDungeonProgress {
    private int ownerUid;
    private int sceneId;
    private int dungeonId;
    private int passConfigId;
    private int[] finishedConditions = new int[0];
    private Set<Integer> activeWayPoints = new HashSet<>();
    private int newestWayPoint;
    private int elapsedSeconds;
    private boolean started;
    private boolean ended;
    private String claimRunToken;
    private Set<Integer> rewardedPlayers = new HashSet<>();
    private int originSceneId;
    private int originPointId;
    private int killedMonsterCount;

    public PersistentDungeonProgress() {}

    PersistentDungeonProgress(int ownerUid, int sceneId, int dungeonId, int passConfigId,
            int[] finishedConditions, Set<Integer> activeWayPoints, int newestWayPoint,
            int elapsedSeconds, boolean started, boolean ended, String claimRunToken,
            Set<Integer> rewardedPlayers, int originSceneId, int originPointId, int killedMonsterCount) {
        this.ownerUid = ownerUid;
        this.sceneId = sceneId;
        this.dungeonId = dungeonId;
        this.passConfigId = passConfigId;
        this.finishedConditions = finishedConditions.clone();
        this.activeWayPoints = new HashSet<>(activeWayPoints);
        this.newestWayPoint = newestWayPoint;
        this.elapsedSeconds = elapsedSeconds;
        this.started = started;
        this.ended = ended;
        this.claimRunToken = claimRunToken;
        this.rewardedPlayers = new HashSet<>(rewardedPlayers);
        this.originSceneId = originSceneId;
        this.originPointId = originPointId;
        this.killedMonsterCount = killedMonsterCount;
    }

    public int[] getFinishedConditions() { return finishedConditions.clone(); }
    public Set<Integer> getActiveWayPoints() { return Set.copyOf(activeWayPoints); }
    public Set<Integer> getRewardedPlayers() { return Set.copyOf(rewardedPlayers); }

    public boolean matches(int ownerUid, int sceneId, DungeonData data, int conditionCount) {
        return ownerUid > 0 && this.ownerUid == ownerUid && this.sceneId == sceneId
                && data != null && data.getSceneId() == sceneId && data.getId() == dungeonId
                && data.getPassCond() == passConfigId && finishedConditions != null
                && finishedConditions.length == conditionCount
                && Arrays.stream(finishedConditions).allMatch(value -> value == 0 || value == 1)
                && activeWayPoints != null && activeWayPoints.stream().allMatch(id -> id > 0)
                && (newestWayPoint == 0 || activeWayPoints.contains(newestWayPoint))
                && elapsedSeconds >= 0 && killedMonsterCount >= 0
                && rewardedPlayers != null && rewardedPlayers.stream().allMatch(uid -> uid > 0)
                && originSceneId > 0 && originPointId >= 0
                && DomainClaimRunIdentity.validToken(claimRunToken);
    }

    public boolean sameProgress(PersistentDungeonProgress other) {
        return other != null && ownerUid == other.ownerUid && sceneId == other.sceneId
                && dungeonId == other.dungeonId && passConfigId == other.passConfigId
                && Arrays.equals(finishedConditions, other.finishedConditions)
                && activeWayPoints.equals(other.activeWayPoints) && newestWayPoint == other.newestWayPoint
                && elapsedSeconds == other.elapsedSeconds && started == other.started && ended == other.ended
                && Objects.equals(claimRunToken, other.claimRunToken) && rewardedPlayers.equals(other.rewardedPlayers)
                && originSceneId == other.originSceneId && originPointId == other.originPointId
                && killedMonsterCount == other.killedMonsterCount;
    }
}
