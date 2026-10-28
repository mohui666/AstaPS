package emu.grasscutter.server.packet.recv;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.game.tower.TowerAbyssFix;
import emu.grasscutter.net.proto.TowerTeamOuterClass;
import emu.grasscutter.net.proto.TowerTeamSelectReqOuterClass.TowerTeamSelectReq;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketTowerTeamSelectRsp;

@Opcodes(PacketOpcodes.TowerTeamSelectReq)
public class HandlerTowerTeamSelectReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        TowerTeamSelectReq req = TowerTeamSelectReq.parseFrom(payload);

        var towerTeams =
                req.getTowerTeamListList().stream()
                        .map(TowerTeamOuterClass.TowerTeam::getAvatarGuidListList)
                        .toList();

        var player = session.getPlayer();
        var tower = session.getServer().getTowerSystem();
        if (!tower.getAllFloors().contains(req.getFloorId()) || !tower.isFloorPlayable(req.getFloorId())) {
            emu.grasscutter.command.CommandHandler.sendMessage(
                    player, "当期深境螺旋战斗资源不完整或楼层已失效，暂无法进入。");
            session.send(new PacketTowerTeamSelectRsp(Retcode.RET_SVR_ERROR_VALUE));
            return;
        }
        TowerAbyssFix.rememberEntry(player);
        synchronized (player.getTowerManager()) {
            player.getTowerManager().teamSelect(req.getFloorId(), towerTeams);
        }

        session.send(new PacketTowerTeamSelectRsp());
    }
}
