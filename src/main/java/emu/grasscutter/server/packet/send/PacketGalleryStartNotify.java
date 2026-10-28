package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.GalleryStartNotifyOuterClass.GalleryStartNotify;

/** The common start fields consumed by the native 7.1 gallery runtime. */
public final class PacketGalleryStartNotify extends BasePacket {
    public PacketGalleryStartNotify(
            int galleryId, int playerCount, int startedAt, int endTime, int ownerUid) {
        super(PacketOpcodes.GalleryStartNotify);
        setData(GalleryStartNotify.newBuilder()
                .setGalleryId(galleryId)
                .setPlayerCount(playerCount)
                .setStartTime(startedAt)
                .setEndTime(endTime)
                .setOwnerUid(ownerUid));
    }
}
