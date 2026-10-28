package emu.grasscutter.server.packet.send;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.TaskVarOuterClass.TaskVar;
import emu.grasscutter.net.proto.TaskVarNotifyOuterClass.TaskVarNotify;
import java.util.*;

public final class PacketTaskVarNotify extends BasePacket {
    public PacketTaskVarNotify(Map<Integer, List<Integer>> variables) {
        super(PacketOpcodes.TaskVarNotify);
        var notify = TaskVarNotify.newBuilder();
        variables.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                notify.addTaskVarList(TaskVar.newBuilder().setKey(entry.getKey()).addAllValueList(entry.getValue())));
        setData(notify.build());
    }
}
