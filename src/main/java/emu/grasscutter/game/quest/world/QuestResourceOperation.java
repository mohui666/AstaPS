package emu.grasscutter.game.quest.world;

/** Named native enum CBNLANDIGMF; absent fields retain the server's permanent-resource policy. */
public enum QuestResourceOperation {
    PERMANENT, TEMPORARY, TEMPORARY_TO_PERMANENT;

    public static QuestResourceOperation parse(String raw) {
        if (raw == null || raw.isBlank()) return PERMANENT;
        return switch (raw.trim()) {
            case "0", "QUEST_EXEC_RES_OP_PERMANENT" -> PERMANENT;
            case "1", "QUEST_EXEC_RES_OP_TEMP" -> TEMPORARY;
            case "2", "QUEST_EXEC_RES_OP_TEMP_TO_PERMANENT" -> TEMPORARY_TO_PERMANENT;
            default -> throw new IllegalArgumentException("Unknown native resource operation " + raw);
        };
    }
}
