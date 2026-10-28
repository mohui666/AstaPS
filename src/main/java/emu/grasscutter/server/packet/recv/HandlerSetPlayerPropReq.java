package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.props.PlayerProperty;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.SetPlayerPropReqOuterClass.SetPlayerPropReq;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketSetPlayerPropRsp;

@Opcodes(PacketOpcodes.SetPlayerPropReq)
public class HandlerSetPlayerPropReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = SetPlayerPropReq.parseFrom(payload);
        var player = session.getPlayer();

        // Climate and diving unlocks come from server state. Validate the entire batch before
        // changing any property so a forged climate value cannot leave a partially applied request.
        for (var p : req.getPropListList()) {
            switch (PlayerProperty.getPropById(p.getType())) {
                case PROP_CUR_CLIMATE_METER, PROP_CUR_CLIMATE_TYPE,
                        PROP_CUR_CLIMATE_AREA_ID, PROP_CUR_CLIMATE_AREA_CLIMATE_TYPE,
                        PROP_PLAYER_CAN_DIVE -> {
                    session.send(new PacketSetPlayerPropRsp(1));
                    return;
                }
            }
        }

        for (var p : req.getPropListList()) {
            var prop = PlayerProperty.getPropById(p.getType());
            switch (prop) {
                default -> player.setProperty(prop, (int) p.getVal(), true);
                case PROP_IS_MP_MODE_AVAILABLE -> {
                    if (!player.setProperty(prop, (int) p.getVal(), false)) {
                        session.send(new PacketSetPlayerPropRsp(1));
                        return;
                    }
                }
            }
        }

        player.save();
        session.send(new PacketSetPlayerPropRsp(0));
    }
}
