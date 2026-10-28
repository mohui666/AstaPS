package emu.grasscutter.server.packet.recv;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.MusicGameStartReqOuterClass;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketMusicGameStartRsp;
import emu.grasscutter.game.props.ActivityType;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;

@Opcodes(PacketOpcodes.MusicGameStartReq)
public class HandlerMusicGameStartReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = MusicGameStartReqOuterClass.MusicGameStartReq.parseFrom(payload);

        if (session.getPlayer().getActivityManager()
                .getActivityHandler(ActivityType.NEW_ACTIVITY_MUSIC_GAME).isEmpty()) {
            session.send(new PacketMusicGameStartRsp(
                    req.getMusicBasicId(), req.getUgcGuid(), Retcode.RET_ACTIVITY_CLOSE_VALUE));
            return;
        }

        session.send(new PacketMusicGameStartRsp(req.getMusicBasicId(), req.getUgcGuid()));
    }
}
