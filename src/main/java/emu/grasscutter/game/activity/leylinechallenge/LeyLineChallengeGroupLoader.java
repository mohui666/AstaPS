package emu.grasscutter.game.activity.leylinechallenge;

import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.data.GameData;
import emu.grasscutter.server.packet.send.*;

/** Native entrance model, activity gate and scene point; native UI handles its own interaction. */
public final class LeyLineChallengeGroupLoader {
    /** No MGC worktop option is installed over the native billboard's activity-page action. */
    public static final int ENTER_OPTION_ID = 0;

    private LeyLineChallengeGroupLoader() {}

    // Native scene3_point.json: 1628 is a LeyLineChallenge DungeonEntry; matching group is 133105039.
    private static final Position ENTRANCE = new Position(980.627f, 260.567f, -194.518f);
    private static final int GADGET = 73051004;

    public static void ensureNearby(Scene scene) {
        if (scene.getLeyLineChallengeRun() != null) scene.getLeyLineChallengeRun().tick();
        if (scene.getId() != 3) return;
        boolean near = scene.getPlayers().stream().anyMatch(player ->
                player.getPosition().computeDistance(ENTRANCE) < 80
                && player.getOpenStates().getOrDefault(4601, 0) > 0
                && player.getUnlockedScenePoints(3).contains(1628));
        if (near) ensureEntrance(scene, false);
    }

    /** Explicit operator unlock: only this activity's open state and entrance point are changed. */
    public static void openEntrance(Player player) {
        if (player == null || !player.isOnline() || player.getScene() == null || player.getScene().getId() != 3)
            throw new IllegalArgumentException("请先进入大世界，再启用幽境入口。");
        if (GameData.getScenePointEntryById(3, 1628) == null || !GameData.getGadgetDataMap().containsKey(GADGET))
            throw new IllegalArgumentException("当前资源缺少幽境入口。");
        player.getOpenStates().put(4601, 1);
        player.getProgressManager().unlockTransPoint(3, 1628, false);
        player.sendPacket(new PacketOpenStateUpdateNotify(player));
        player.sendPacket(new PacketScenePointUnlockNotify(3, 1628));
        ensureEntrance(player.getScene(), true);
        player.save();
    }

    private static void ensureEntrance(Scene scene, boolean refresh) {
        var gadget = scene.getEntities().values().stream().filter(e -> e instanceof EntityGadget g
                && g.getGadgetId() == GADGET && g.getPosition().computeDistance(ENTRANCE) < 8)
                .map(e -> (EntityGadget)e).findFirst().orElse(null);
        boolean created = gadget == null;
        if (created) {
            gadget = new EntityGadget(scene, GADGET, ENTRANCE, new Position());
            gadget.setGroupId(133105039);
            gadget.setConfigId(39001);
            gadget.buildContent();
            gadget.setState(202); // SuperActive in the installed entrance ability.
        }
        boolean changed = !gadget.isInteractEnabled();
        gadget.setInteractEnabled(true);
        if (!created && refresh && gadget.getState() != 202) gadget.updateState(202);
        if (created) scene.addEntity(gadget);
        else if (refresh || changed) scene.broadcastPacket(new PacketSceneEntityAppearNotify(gadget));
    }

    /** Recognize the confirmed native entrance, never a generic domain worktop. */
    public static boolean isEntranceGadget(EntityGadget gadget) {
        return gadget != null && gadget.getGadgetId() == GADGET && gadget.getScene().getId() == 3;
    }
}
