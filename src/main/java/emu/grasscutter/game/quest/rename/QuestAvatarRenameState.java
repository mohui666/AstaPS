package emu.grasscutter.game.quest.rename;

import dev.morphia.annotations.Entity;
import java.util.HashMap;
import java.util.Map;

/** Player-owned names, keyed by the native AvatarRenameExcel rename ID. */
@Entity(useDiscriminator = false)
public final class QuestAvatarRenameState {
    private Map<Integer, String> names = new HashMap<>();

    public synchronized String getName(int renameId) { return names.get(renameId); }
    public synchronized boolean hasName(int renameId) { return names.containsKey(renameId); }
    public synchronized Map<Integer, String> getNames() { return Map.copyOf(names); }
    public synchronized void setName(int renameId, String name) {
        if (renameId <= 0 || name == null || name.isBlank())
            throw new IllegalArgumentException("Invalid avatar rename");
        names.put(renameId, name);
    }
}
