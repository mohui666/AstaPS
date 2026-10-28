package emu.grasscutter.server.packet.send;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedInputStream;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.excels.GalleryData;
import emu.grasscutter.game.world.TestWorld.TestPlayer;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.scripts.ScriptGalleryManager;
import emu.grasscutter.utils.JsonUtils;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ServerResourceFixture.class)
final class PacketGalleryNativeWireTest {
    @Test
    void startFieldsMatchTheCurrentNativeParser() throws Exception {
        // CMNGOICPEOK.NLGBJEEJDLG RVA 0x10D4CAD0: tags 8, 24, 72, 80, 88.
        var packet = new PacketGalleryStartNotify(19102, 1, 200, 1700, 123);
        assertEquals(20814, packet.getOpcode());
        var wire = CodedInputStream.newInstance(packet.getData());
        assertEquals(8, wire.readTag());
        assertEquals(19102, wire.readUInt32());
        assertEquals(24, wire.readTag());
        assertEquals(1, wire.readUInt32());
        assertEquals(72, wire.readTag());
        assertEquals(1700, wire.readUInt32());
        assertEquals(80, wire.readTag());
        assertEquals(123, wire.readUInt32());
        assertEquals(88, wire.readTag());
        assertEquals(200, wire.readUInt32());
        assertEquals(0, wire.readTag());
    }

    @Test
    void stopFieldsMatchTheCurrentNativeParser() throws Exception {
        // KBLNNKCANDM.NLGBJEEJDLG RVA 0xB41E2E0: tags 64 and 104.
        var packet = new PacketGalleryStopNotify(19102, 3);
        assertEquals(PacketOpcodes.GalleryStopNotify, packet.getOpcode());
        assertEquals(480, packet.getOpcode());
        var wire = CodedInputStream.newInstance(packet.getData());
        assertEquals(64, wire.readTag());
        assertEquals(19102, wire.readUInt32());
        assertEquals(104, wire.readTag());
        assertEquals(3, wire.readUInt32());
        assertEquals(0, wire.readTag());
    }

    @Test
    void activeSnapshotUsesTheNativeCommonGalleryFields() throws Exception {
        var player = new TestPlayer();
        player.setUid(123);
        var config = JsonUtils.decode("{\"id\":19102,\"duration\":1500}", GalleryData.class);
        var run = new ScriptGalleryManager.Run(config, player, 200, null, Set.of(), List.of());
        var packet = new PacketGetAllSceneGalleryInfoRsp(run);
        assertEquals(9640, packet.getOpcode());
        var response = CodedInputStream.newInstance(packet.getData());
        assertEquals(10, response.readTag());
        var gallery = CodedInputStream.newInstance(response.readByteArray());
        // CEGMJNNJOOH.IENGFLPCLNM RVA 0xD1FCE80: common fields 2, 3, 6, 7, 11, 15.
        assertEquals(16, gallery.readTag());
        assertEquals(123, gallery.readUInt32());
        assertEquals(24, gallery.readTag());
        assertEquals(1, gallery.readUInt32());
        assertEquals(48, gallery.readTag());
        assertEquals(2, gallery.readUInt32());
        assertEquals(56, gallery.readTag());
        assertEquals(200, gallery.readUInt32());
        assertEquals(88, gallery.readTag());
        assertEquals(1700, gallery.readUInt32());
        assertEquals(120, gallery.readTag());
        assertEquals(19102, gallery.readUInt32());
        assertEquals(0, gallery.readTag());
        assertEquals(0, response.readTag());
    }

    @Test
    void noActiveGalleryKeepsTheEmptySuccessResponse() {
        assertArrayEquals(new byte[0], new PacketGetAllSceneGalleryInfoRsp(null).getData());
    }
}
