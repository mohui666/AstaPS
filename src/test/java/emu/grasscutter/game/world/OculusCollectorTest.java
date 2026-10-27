package emu.grasscutter.game.world;

import static org.junit.jupiter.api.Assertions.*;
import java.io.StringReader;
import java.util.*;
import org.junit.jupiter.api.Test;

class OculusCollectorTest {
    @Test void generatedIndexLoadsWithTheProjectsGsonVersion() {
        var loaded = OculusCollector.readPoints(new StringReader("""
                [{"sceneId":3,"cityId":1,"itemId":107001,"x":1.25,"y":2,"z":-3,
                  "marks":[{"groupId":101,"configId":1,"gadgetId":70590004,"pointType":4001001},
                           {"groupId":202,"configId":2,"gadgetId":70590004,"pointType":0}]}]
                """));
        assertEquals(new OculusCollector.Point(3,1,107001,1.25f,2,-3,List.of(
                new OculusCollector.Mark(101,1,70590004,4001001),
                new OculusCollector.Mark(202,2,70590004,0))),loaded.get(0));
    }

    @Test void anAlreadyCollectedAliasDoesNotAwardTheSamePhysicalOculusAgain() {
        var first = new OculusCollector.Point(3,1,107001,1,2,3,List.of(
                new OculusCollector.Mark(101,1,70590004,4001001),new OculusCollector.Mark(202,2,70590004,4001001)));
        var second = new OculusCollector.Point(3,1,107001,4,5,6,List.of(new OculusCollector.Mark(303,3,70590004,4001001)));
        assertEquals(List.of(second),OculusCollector.remaining(List.of(first,second),Set.of("202:2")));
        assertTrue(OculusCollector.remaining(List.of(first,second),Set.of("101:1","303:3")).isEmpty());
        assertEquals(2,OculusCollector.remaining(List.of(first,second),Set.of()).size());
    }
}
