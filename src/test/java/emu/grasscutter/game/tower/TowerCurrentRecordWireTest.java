package emu.grasscutter.game.tower;

import static org.junit.jupiter.api.Assertions.*;
import com.google.protobuf.UnknownFieldSet;
import emu.grasscutter.net.proto.TowerCurLevelRecordChangeNotifyOuterClass.TowerCurLevelRecordChangeNotify;
import emu.grasscutter.server.packet.send.PacketTowerCurLevelRecordChangeNotify;
import org.junit.jupiter.api.Test;

class TowerCurrentRecordWireTest {
    @Test void clientFloorLookupReceivesTheFloorIdAndChamberReceivesOneThroughThree() throws Exception {
        for (int floor : new int[] {1134, 1135, 1140, 1141}) for (int chamber = 1; chamber <= 3; chamber++) {
            var packet = new PacketTowerCurLevelRecordChangeNotify(floor, chamber, false);
            var outer = UnknownFieldSet.parseFrom(packet.getData());
            var record = UnknownFieldSet.parseFrom(outer.getField(TowerCurLevelRecordChangeNotify.CUR_LEVEL_RECORD_FIELD_NUMBER)
                    .getLengthDelimitedList().getFirst());
            assertEquals((long)floor, record.getField(9).getVarintList().getLast());
            assertEquals((long)chamber, record.getField(10).getVarintList().getLast());
            assertEquals(0L, record.getField(3).getVarintList().getLast(), "lower half remains explicitly encoded");
            assertEquals(0L, record.getField(11).getVarintList().getLast(), "active record remains nonempty");
        }
    }
}
