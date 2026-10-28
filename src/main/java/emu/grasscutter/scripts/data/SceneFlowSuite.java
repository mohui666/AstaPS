package emu.grasscutter.scripts.data;

import java.util.*;
import lombok.Setter;

/** Native io_type=1 suites carry entity properties, rather than only config IDs. */
@Setter
public final class SceneFlowSuite {
    @Setter public static final class Gadget {
        public int config_id;
        public int state;
        public Platform platform_info;
    }
    @Setter public static final class Platform {
        public int point_id;
        public int move_type;
        public int route_id;
        public int route_index;
        public boolean is_started;
    }
    @Setter public static final class Monster { public int config_id; public int pose_id; }
    @Setter public static final class Variable { public String name; public int value; }
    public List<Gadget> gadgets = List.of();
    public List<Monster> monsters = List.of();
    public List<Integer> regions = List.of();
    public List<Integer> npcs = List.of();
    public List<String> triggers = List.of();
    public List<Variable> variables = List.of();

    public SceneSuite toSuite() {
        var suite = new SceneSuite();
        suite.flow = this;
        suite.gadgets = gadgets.stream().map(gadget -> gadget.config_id).toList();
        suite.monsters = monsters.stream().map(monster -> monster.config_id).toList();
        suite.regions = regions;
        suite.npcs = npcs;
        suite.triggers = triggers;
        return suite;
    }

    public int gadgetState(SceneGadget template) {
        return gadgets.stream().filter(gadget -> gadget.config_id == template.config_id)
                .map(gadget -> gadget.state).findFirst().orElse(template.state);
    }

    public int monsterPose(SceneMonster template) {
        return monsters.stream().filter(monster -> monster.config_id == template.config_id)
                .map(monster -> monster.pose_id).findFirst().orElse(template.pose_id);
    }
}
