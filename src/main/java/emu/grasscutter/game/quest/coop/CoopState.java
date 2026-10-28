package emu.grasscutter.game.quest.coop;

import dev.morphia.annotations.Entity;
import emu.grasscutter.net.proto.MainCoopOuterClass.MainCoop;
import java.util.*;
import lombok.Getter;
import lombok.Setter;

/** Player-owned hangout progress. A replay copies a point snapshot; it never shares its maps. */
@Entity
@Getter
public final class CoopState {
    private Set<Integer> acceptedChapters = new HashSet<>();
    private Set<Integer> viewedChapters = new HashSet<>();
    private Set<Integer> takenRewards = new HashSet<>();
    private Map<Integer, Point> points = new HashMap<>();
    private Map<Integer, Map<Integer, Integer>> seenEndings = new HashMap<>();
    @Setter private Run run;

    public Point point(int id) { return points.computeIfAbsent(id, ignored -> new Point()); }

    public Map<Integer, Integer> endings(int chapter) {
        return seenEndings.computeIfAbsent(chapter, ignored -> new HashMap<>());
    }

    @Entity
    @Getter
    @Setter
    public static final class Point {
        private int state;
        private Snapshot start;
    }

    @Entity
    @Getter
    @Setter
    public static final class Run {
        private int chapterId;
        private int pointId;
        private Snapshot current;
        private Map<Integer, Snapshot> questStarts = new HashMap<>();
        private int stageQuestId;
        private int lastCompletedStage;
        private int lastCompletedPoint;
        private boolean auxiliaryStage;
        private boolean phaseCompleted;
        private List<Integer> pendingStageIds = new ArrayList<>();
        private List<Integer> pendingPointIds = new ArrayList<>();
        private Set<Integer> completedAuxiliary = new HashSet<>();
        private Set<Integer> startedObservers = new HashSet<>();
        private Map<String, Integer> stageLuaBaselines = new HashMap<>();

        public Run() {}
        Run(int chapter, int point, Snapshot snapshot) {
            chapterId = chapter;
            pointId = point;
            current = snapshot.copy();
            current.status = 1;
        }
    }

    @Entity
    @Getter
    @Setter
    public static final class Snapshot {
        private int chapterId;
        private int confidence;
        private int status = 1;
        private List<Integer> savePoints = new ArrayList<>();
        private Map<Integer, Integer> normalVars = new HashMap<>();
        private Map<Integer, Integer> tempVars = new HashMap<>();

        public Snapshot() {}
        Snapshot(int chapter, int initialConfidence, Map<Integer, Integer> normal,
                Map<Integer, Integer> temporary) {
            chapterId = chapter;
            confidence = initialConfidence;
            normalVars.putAll(normal);
            tempVars.putAll(temporary);
        }

        public Snapshot copy() {
            var copy = new Snapshot(chapterId, confidence, normalVars, tempVars);
            copy.status = status;
            copy.savePoints.addAll(savePoints);
            return copy;
        }

        public MainCoop toProto(Map<Integer, Integer> endings) {
            return MainCoop.newBuilder().setId(chapterId).setSelfConfidence(confidence)
                    .setStatusValue(status).addAllSavePointIdList(savePoints)
                    .putAllNormalVarMap(normalVars).putAllTempVarMap(tempVars)
                    .putAllSeenEndingMap(endings).build();
        }
    }
}
