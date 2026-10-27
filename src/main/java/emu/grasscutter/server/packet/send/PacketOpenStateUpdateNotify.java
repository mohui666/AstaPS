package emu.grasscutter.server.packet.send;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.OpenStateData;
import emu.grasscutter.game.player.*;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.OpenStateUpdateNotifyOuterClass.OpenStateUpdateNotify;

/*
    Must be sent on login for openStates to work
    Tells the client to update its openStateMap for the keys sent. value is irrelevant
 */
public class PacketOpenStateUpdateNotify extends BasePacket {

    public PacketOpenStateUpdateNotify(Player player) {
        super(PacketOpcodes.OpenStateUpdateNotify);

        OpenStateUpdateNotify.Builder proto = OpenStateUpdateNotify.newBuilder();

        GameData.getOpenStateList().stream().map(OpenStateData::getId).forEach(id -> {
            // If the player has an open state stored in their map, then it would always override any default value
            if (player.getOpenStates().containsKey(id)) {
                proto.putOpenStateMap(id, player.getProgressManager().getOpenState(id));
            }
            // Otherwise, add the state if it is contained in the set of default open states.
            else if (PlayerProgressManager.DEFAULT_OPEN_STATES.contains(id)) {
                proto.putOpenStateMap(id, 1);
            }
        });

        // The 7.1 table omits TOWER (42); its native DungeonEntry requirements are checked by
        // PlayerProgressManager. Include the stored result in the login snapshot as well.
        if (player.getOpenStates().containsKey(42)) {
            proto.putOpenStateMap(42, player.getProgressManager().getOpenState(42));
        }

        // Always unlock OPEN_STATE_RESIN (45). resinUsage only gates consumption — the map/HUD
        // counter must still stack with widgets like the abyss floor/chamber display.
        player.getOpenStates().put(45, 1);
        proto.putOpenStateMap(45, 1);

        // Personal free exploration survives login without forging quest progress.
        if (player.isExplorationUnlocked()) {
            proto.putOpenStateMap(47, player.getProgressManager().getClientOpenState(47));
        }

        this.setData(proto);
    }
}
