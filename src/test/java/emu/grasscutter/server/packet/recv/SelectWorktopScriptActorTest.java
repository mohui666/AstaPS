package emu.grasscutter.server.packet.recv;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.SelectWorktopOptionReqOuterClass.SelectWorktopOptionReq;
import emu.grasscutter.scripts.*;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.ScriptArgs;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.utils.JsonUtils;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

final class SelectWorktopScriptActorTest {
    private static final class ScriptScene extends TestWorld.TestScene {
        CapturedScripts captured;
        ScriptScene(World world) { super(world,990072,SceneType.SCENE_WORLD); }
        @Override public SceneScriptManager getScriptManager() {
            if (captured == null) captured = new CapturedScripts(this);
            return captured;
        }
        @Override public void selectWorktopOptionWith(SelectWorktopOptionReq request) {}
    }

    private static final class CapturedScripts extends SceneScriptManager {
        ScriptArgs selected;
        CapturedScripts(Scene scene) { super(scene); }
        @Override public Future<?> callEvent(ScriptArgs args) {
            if (args.type == EventType.EVENT_SELECT_OPTION) selected = args;
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test void realRequestPassesTheActingPlayerIntoLuaInsteadOfAnAnonymousUid() throws Exception {
        boolean oldScripts = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        var previousGadget = GameData.getGadgetDataMap().put(70360001,JsonUtils.decode(
                "{id:70360001,type:'Worktop',jsonName:'Story_Test_Worktop',tags:[]}",GadgetData.class));
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        try {
            ScriptLoader.init();
            var player = new TestWorld.TestPlayer();
            player.setUid(4201);
            var world = TestWorld.create(player);
            var scene = new ScriptScene(world);
            player.setScene(scene);
            player.setSceneId(scene.getId());
            var gadget = new EntityGadget(scene,70360001,new Position(1,2,3));
            gadget.setGroupId(199007201);
            gadget.setConfigId(9001);
            scene.getEntities().put(gadget.getId(),gadget);
            var session = new GameSession(null) {
                @Override public Player getPlayer() { return player; }
                @Override public void send(BasePacket packet) {}
            };
            var request = SelectWorktopOptionReq.newBuilder().setGadgetEntityId(gadget.getId()).setOptionId(7).build();
            new HandlerSelectWorktopOptionReq().handle(session,new byte[0],request.toByteArray());
            assertNotNull(scene.captured.selected);
            assertEquals(player.getUid(),scene.captured.selected.uid);
            assertEquals(199007201,scene.captured.selected.group_id);
            assertEquals(9001,scene.captured.selected.param1);
            assertEquals(7,scene.captured.selected.param2);
            var lib = new ScriptLib();
            lib.setSceneScriptManager(scene.getScriptManager());
            var anonymous = new ScriptArgs(199007201,EventType.EVENT_SELECT_OPTION,9001,7);
            lib.setCurrentCallParams(anonymous);
            assertEquals(0,anonymous.uid);
        } finally {
            if (previousGadget == null) GameData.getGadgetDataMap().remove(70360001);
            else GameData.getGadgetDataMap().put(70360001,previousGadget);
            Grasscutter.getConfig().server.game.enableScriptInBigWorld = oldScripts;
        }
    }
}
