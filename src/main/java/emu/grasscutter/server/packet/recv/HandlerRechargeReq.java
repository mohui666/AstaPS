package emu.grasscutter.server.packet.recv;

import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.Opcodes;
import emu.grasscutter.net.packet.PacketHandler;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.game.shop.LocalRecharge;
import emu.grasscutter.net.proto.PacketHeadOuterClass.PacketHead;

/** Acknowledge the local server's simulated recharge without contacting a payment service. */
@Opcodes(PacketOpcodes.RechargeReq)
public final class HandlerRechargeReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var product = LocalRecharge.productFromPayload(payload);
        int sequence = PacketHead.parseFrom(header).getClientSequenceId();
        if (sequence <= 0) throw new IllegalArgumentException("Recharge request has no order sequence");
        var receipt = LocalRecharge.purchase(session.getPlayer(),product,"native:"+session.hashCode()+":"+sequence);
        emu.grasscutter.Grasscutter.getLogger().info("Local checkout completed: uid={}, product={}, duplicate={}",
                session.getPlayer().getUid(), receipt.get("configId").getAsInt(), receipt.get("duplicate").getAsBoolean());
        // An empty protobuf response has the default retcode 0 (success). The 7.1
        // export lacks this message's field layout, so do not invent wire fields.
        session.send(new BasePacket(PacketOpcodes.RechargeRsp, sequence));
    }
}
