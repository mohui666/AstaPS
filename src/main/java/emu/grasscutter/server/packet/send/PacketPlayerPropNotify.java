package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.PlayerProperty;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.PlayerPropNotifyOuterClass.PlayerPropNotify;
import emu.grasscutter.utils.helpers.ProtoHelper;
import java.util.Collection;
import java.util.List;

public class PacketPlayerPropNotify extends BasePacket {

    public PacketPlayerPropNotify(Player player, PlayerProperty prop) {
        this(player, List.of(prop));
    }

    /** Native scene changes publish their related properties together. */
    public PacketPlayerPropNotify(Player player, Collection<PlayerProperty> properties) {
        super(PacketOpcodes.PlayerPropNotify);

        this.buildHeader(0);

        var builder = PlayerPropNotify.newBuilder();
        for (var prop : properties)
            builder.putPropMap(prop.getId(), ProtoHelper.newPropValue(prop, player.getProperty(prop)));

        this.setData(builder.build());
    }
}
