package emu.grasscutter.game.quest.aranara;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.server.packet.send.PacketAddAranaraCollectionNotify;
import emu.grasscutter.server.packet.send.PacketAranaraCollectionDataNotify;

/** Native quest actions and the actual server mailbox are the only mutation entry points. */
public final class AranaraCollectionRuntime {
    private AranaraCollectionRuntime() {}

    public static boolean modify(GameQuest quest, int type, int pageId, int targetState) {
        var player = quest.getOwner();
        var catalog = AranaraCollectionCatalog.installed();
        if (player.getWorld() == null || player.getWorld().getHost() != player
                || !catalog.hasPage(type, pageId) || !AranaraCollectionCatalog.validState(targetState)) return false;
        synchronized (player.getAranaraCollection()) {
            var change = player.getAranaraCollection().modify(pageId, targetState);
            if (change != null) {
                player.save();
                player.sendPacket(new PacketAddAranaraCollectionNotify(type, change));
            }
        }
        return true;
    }

    public static int getCount(Scene scene, int type, int state) {
        var player = owner(scene);
        var catalog = AranaraCollectionCatalog.installed();
        if (player == null || !catalog.hasType(type) || !AranaraCollectionCatalog.validState(state)) return -1;
        synchronized (player.getAranaraCollection()) {
            return (int) catalog.pageIds().stream()
                    .filter(id -> player.getAranaraCollection().getState(id) == state).count();
        }
    }

    public static int receiveAll(Scene scene, int groupId, int type) {
        var player = owner(scene);
        var catalog = AranaraCollectionCatalog.installed();
        if (player == null || !catalog.hasType(type)
                || scene.getId() != AranaraCollectionCatalog.MAILBOX_SCENE
                || groupId != AranaraCollectionCatalog.MAILBOX_GROUP
                || scene.getScriptManager().getGroupInstanceById(groupId) == null) return -1;
        synchronized (player.getAranaraCollection()) {
            var changes = player.getAranaraCollection().receiveAll(catalog.pageIds());
            if (changes.isEmpty()) return 0;
            player.save();
            changes.forEach(change -> player.sendPacket(new PacketAddAranaraCollectionNotify(type, change)));
        }
        return 0;
    }

    public static void onLogin(Player player) {
        synchronized (player.getAranaraCollection()) {
            player.sendPacket(new PacketAranaraCollectionDataNotify(player));
        }
    }

    private static Player owner(Scene scene) {
        if (scene == null || scene.getWorld() == null) return null;
        var player = scene.getWorld().getHost();
        return player != null && player.getWorld() == scene.getWorld() && player.getScene() == scene
                ? player : null;
    }
}
