package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketAddQuestContentProgressRsp;

@Opcodes(PacketOpcodes.AddQuestContentProgressReq)
public class HandlerAddQuestContentProgressReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = AddQuestContentProgressReq.parseFrom(payload);

        // Find all conditions in quest that are the same as the given one
        var type = QuestContent.getContentTriggerByValue(req.getContentType());
        if (type != null && !type.isServerStatePredicate()
                && type != QuestContent.QUEST_CONTENT_ENTER_VEHICLE
                && type != QuestContent.QUEST_CONTENT_ENTER_FEATURE_TAG_VEHICLE
                && type != QuestContent.QUEST_CONTENT_LEAVE_FEATURE_TAG_VEHICLE
                && type != QuestContent.QUEST_CONTENT_LEAVE_VEHICLE
                && type != QuestContent.QUEST_CONTENT_CALL_PLAYER_TRAIN_IN_RANGE
                && type != QuestContent.QUEST_CONTENT_FINISH_ANY_FOOD_COOK
                && !emu.grasscutter.game.quest.reconstruction.CalibertQuestReconstruction.isServerOwnedClientProgress(type,req.getParam())
                && !emu.grasscutter.game.quest.reconstruction.StoryReconstructionRuntime.isServerOwnedClientProgress(type,req.getParam())
                && !(type == QuestContent.QUEST_CONTENT_LUA_NOTIFY
                        && emu.grasscutter.game.quest.war.ReconstructedWarRuntime.isServerOwnedLuaNotification(
                                Integer.toUnsignedString(req.getParam())))) {
            session.getPlayer().getQuestManager().queueEvent(type, req.getParam());
        }

        session.send(new PacketAddQuestContentProgressRsp(req.getContentType()));
    }
}
