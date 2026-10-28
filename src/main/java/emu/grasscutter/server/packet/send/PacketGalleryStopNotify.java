package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.GalleryStopNotifyOuterClass.GalleryStopNotify;

/** Native 7.1 places gallery_id at field 8 and the stop reason at field 13. */
public final class PacketGalleryStopNotify extends BasePacket {
    public PacketGalleryStopNotify(int galleryId, int reason) {
        super(PacketOpcodes.GalleryStopNotify);
        setData(GalleryStopNotify.newBuilder().setGalleryId(galleryId).setReason(reason));
    }
}
