package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.GetAllSceneGalleryInfoRspOuterClass.GetAllSceneGalleryInfoRsp;
import emu.grasscutter.net.proto.SceneGalleryInfoOuterClass.SceneGalleryInfo;
import emu.grasscutter.scripts.ScriptGalleryManager;

public final class PacketGetAllSceneGalleryInfoRsp extends BasePacket {
    public PacketGetAllSceneGalleryInfoRsp(ScriptGalleryManager.Run gallery) {
        super(PacketOpcodes.GetAllSceneGalleryInfoRsp);
        var response = GetAllSceneGalleryInfoRsp.newBuilder();
        if (gallery != null) {
            response.addGalleryInfoList(SceneGalleryInfo.newBuilder()
                    .setOwnerUid(gallery.player().getUid())
                    .setPlayerCount(1) // The supported MP_BLOCK runtime requires a solo scene.
                    .setStage(2)
                    .setStartTime(gallery.startedAt())
                    .setEndTime(gallery.endTime())
                    .setGalleryId(gallery.config().getId()));
        }
        setData(response);
    }
}
