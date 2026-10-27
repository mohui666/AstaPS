package emu.grasscutter.server.packet.recv;

import emu.grasscutter.data.GameData;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.GetDungeonEntryExploreConditionReqOuterClass.GetDungeonEntryExploreConditionReq;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketDungeonEntryToBeExploreNotify;
import emu.grasscutter.server.packet.send.PacketGetDungeonEntryExploreConditionRsp;

@Opcodes(PacketOpcodes.GetDungeonEntryExploreConditionReq)
public class HandlerGetDungeonEntryExploreConditionReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = GetDungeonEntryExploreConditionReq.parseFrom(payload);

        var entry = GameData.getDungeonEntryDataMap().get(req.getDungeonEntryConfigId());
        if (entry == null || entry.getSceneId() != req.getSceneId()
                || entry.getDungeonEntryId() != req.getDungeonEntryScenePointId()) {
            session.send(new PacketGetDungeonEntryExploreConditionRsp(Retcode.RET_FAIL));
            return;
        }
        var player = session.getPlayer();
        var unmet = entry.getUnmetCondition(player.getLevel(), questId -> {
            var quest = player.getQuestManager().getQuestById(questId);
            return quest != null && quest.getState() == QuestState.QUEST_STATE_FINISHED;
        });
        // A notify does not complete this request. The client needs the matching response even
        // when the level requirement was already met (e.g. AR60 at the AR20 abyss portal).
        session.send(new PacketGetDungeonEntryExploreConditionRsp(unmet));
        if (unmet == null) {
            session.send(new PacketDungeonEntryToBeExploreNotify(
                    entry.getDungeonEntryId(), entry.getSceneId(), entry.getId()));
        }
    }
}
