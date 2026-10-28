package emu.grasscutter.game.player;

import dev.morphia.annotations.Entity;
import emu.grasscutter.GameConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;
import lombok.Getter;

/** Persist only the quest's roster description; temporary avatar and item identities never persist. */
@Entity
@Getter
public final class QuestPartyLock {
    public enum Kind { OWNED, TRIAL, MIRROR_OWNED, MIRROR_TRIAL }

    private int questMainId;
    private Kind kind;
    private List<Integer> avatarIds = new ArrayList<>();
    private int previousCharacterIndex;
    private int dungeonId;

    public QuestPartyLock() {}

    public QuestPartyLock(int questMainId, Kind kind, List<Integer> avatarIds,
            int previousCharacterIndex, int dungeonId) {
        if (questMainId <= 0 || kind == null || avatarIds.isEmpty()
                || avatarIds.stream().anyMatch(id -> id <= 0))
            throw new IllegalArgumentException("A quest party requires a quest and valid avatars");
        this.questMainId = questMainId;
        this.kind = kind;
        this.avatarIds = new ArrayList<>(avatarIds);
        this.previousCharacterIndex = Math.max(0, previousCharacterIndex);
        this.dungeonId = dungeonId;
    }

    public boolean isTrial() { return kind == Kind.TRIAL || kind == Kind.MIRROR_TRIAL; }
    public boolean isMirror() { return kind == Kind.MIRROR_OWNED || kind == Kind.MIRROR_TRIAL; }

    /** Native semicolon alternatives differ by Traveler gender, not by array position. */
    public static List<Integer> selectRoster(String parameter, int travelerId,
            IntUnaryOperator avatarIdForEntry) {
        if (parameter == null || parameter.isBlank())
            throw new IllegalArgumentException("Missing quest party roster");
        String[] alternatives = parameter.split(";", -1);
        List<List<Integer>> rosters = new ArrayList<>();
        for (String alternative : alternatives) {
            List<Integer> roster = new ArrayList<>();
            for (String value : alternative.split(",", -1)) {
                int id = Integer.parseInt(value.trim());
                if (id <= 0 || roster.contains(id))
                    throw new IllegalArgumentException("Invalid or duplicate quest party entry");
                roster.add(id);
            }
            rosters.add(roster);
        }
        if (rosters.size() == 1) return rosters.getFirst();
        List<Integer> chosen = null;
        for (var roster : rosters) {
            if (roster.stream().mapToInt(avatarIdForEntry::applyAsInt).anyMatch(id -> id == travelerId)) {
                if (chosen != null) throw new IllegalArgumentException("Ambiguous Traveler party alternatives");
                chosen = roster;
            }
        }
        if (chosen == null) throw new IllegalArgumentException("No party matches this Traveler");
        return chosen;
    }

    public static int ownedAvatarId(int requestedId, int travelerId) {
        return requestedId == GameConstants.MAIN_CHARACTER_MALE
                || requestedId == GameConstants.MAIN_CHARACTER_FEMALE ? travelerId : requestedId;
    }
}
