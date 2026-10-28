package emu.grasscutter.game.tps;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.avatar.AvatarData;
import emu.grasscutter.data.excels.avatar.AvatarSkillDepotData;
import emu.grasscutter.data.excels.tps.TpsWeaponData;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.avatar.AvatarStorage;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.inventory.EquipInventoryTab;
import emu.grasscutter.game.inventory.ItemType;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.QuestPartyLock;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.player.TeamSetupHelper;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.exec.ExecUnlockTpsAvatar;
import emu.grasscutter.game.world.TestWorld.TestPlayer;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.AvatarAddNotifyOuterClass.AvatarAddNotify;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.net.proto.TpsEquipChangeNotifyOuterClass.TpsEquipChangeNotify;
import emu.grasscutter.server.packet.send.PacketAvatarAddNotify;
import emu.grasscutter.utils.JsonUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class QuestTpsAvatarUnlockTest {
    private final Map<Integer, AvatarData> oldAvatars = new HashMap<>();
    private final Map<Integer, AvatarSkillDepotData> oldDepots = new HashMap<>();
    private final Map<Integer, ItemData> oldItems = new HashMap<>();
    private final Map<Integer, TpsWeaponData> oldWeapons = new HashMap<>();

    @BeforeEach
    void resources() throws Exception {
        for (int depotId : List.of(501, 701, 50001)) {
            var depot = JsonUtils.decode("{\"id\":" + depotId
                    + ",\"skills\":[],\"talents\":[0,0,0,0,0,0],\"inherentProudSkillOpens\":[]}", AvatarSkillDepotData.class);
            depot.onLoad();
            oldDepots.put(depotId, GameData.getAvatarSkillDepotDataMap().put(depotId, depot));
        }
        for (int avatarId : List.of(10000005, 10000007, 10000134, 10000135)) {
            var data = JsonUtils.decode("{\"id\":" + avatarId
                    + ",\"initialWeapon\":10012,\"hpBase\":100,\"fetters\":[]}", AvatarData.class);
            var field = AvatarData.class.getDeclaredField("skillDepot");
            field.setAccessible(true);
            field.set(data, GameData.getAvatarSkillDepotDataMap().get(50001));
            oldAvatars.put(avatarId, GameData.getAvatarDataMap().put(avatarId, data));
        }
        var baseWeapon = JsonUtils.decode("{\"id\":10012,\"itemType\":\"ITEM_WEAPON\","
                + "\"equipType\":\"EQUIP_WEAPON\",\"weaponProp\":[]}", ItemData.class);
        oldItems.put(10012, GameData.getItemDataMap().put(10012, baseWeapon));
        for (int id : List.of(224001, 224002)) {
            var item = JsonUtils.decode("{\"id\":" + id + ",\"itemType\":\"ITEM_TPS_WEAPON\"}", ItemData.class);
            oldItems.put(id, GameData.getItemDataMap().put(id, item));
            var weapon = JsonUtils.decode("{\"id\":" + id + ",\"wearSlotType\":1}", TpsWeaponData.class);
            oldWeapons.put(id, GameData.getTpsWeaponDataMap().put(id, weapon));
        }
    }

    @AfterEach
    void restoreResources() {
        restore(GameData.getAvatarDataMap(), oldAvatars);
        restore(GameData.getAvatarSkillDepotDataMap(), oldDepots);
        restore(GameData.getItemDataMap(), oldItems);
        restore(GameData.getTpsWeaponDataMap(), oldWeapons);
    }

    private static <T> void restore(Map<Integer, T> target, Map<Integer, T> values) {
        values.forEach((id, value) -> { if (value == null) target.remove(id); else target.put(id, value); });
    }

    @Test
    void storyUnlockPublishesTheMatchingFormalAvatarAndNeverAddsItToAnOrdinaryTeam() throws Exception {
        for (int traveler : List.of(10000005, 10000007)) {
            var player = new TpsPlayer(traveler);
            var quest = quest(player);
            assertTrue(new ExecUnlockTpsAvatar().execute(quest, null));
            int id = traveler == 10000007 ? 10000135 : 10000134;
            var avatar = player.getAvatars().getAvatarById(id);
            assertNotNull(avatar);
            assertEquals(20, avatar.getLevel());
            assertEquals(1, avatar.getAvatarType());
            assertEquals(0, avatar.getTrialAvatarId());
            assertEquals(List.of(224001), avatar.getTpsWeaponIds());
            assertEquals(List.of(224001), player.getTpsLoadout());
            assertFalse(player.addedToTeam);
            assertTrue(player.getTeamManager().getCurrentTeamInfo().getAvatars().isEmpty());
            var packet = player.sent.stream().filter(p -> p.getOpcode() == PacketOpcodes.AvatarAddNotify)
                    .findFirst().orElseThrow();
            var wire = AvatarAddNotify.parseFrom(packet.getData());
            assertFalse(wire.getIsInTeam());
            assertEquals(id, wire.getAvatar().getAvatarId());
            assertEquals(1, wire.getAvatar().getAvatarType());
            assertEquals(20, wire.getAvatar().getPropMapOrThrow(4001).getVal());
            assertEquals(224001, wire.getAvatar().getTpsWeaponList(0).getItemId());
        }
    }

    @Test
    void replayPreservesProgressAndLoadoutWithoutAnotherGrant() {
        var player = new TpsPlayer(10000005);
        assertTrue(TpsAvatarSystem.unlock(player));
        var avatar = player.getAvatars().getAvatarById(10000134);
        avatar.setLevel(61);
        avatar.getTpsWeaponIds().clear();
        avatar.getTpsWeaponIds().add(224002);
        player.getTpsLoadout().clear();
        player.getTpsLoadout().add(224002);
        int sent = player.sent.size();
        assertTrue(TpsAvatarSystem.unlock(player));
        assertSame(avatar, player.getAvatars().getAvatarById(10000134));
        assertEquals(61, avatar.getLevel());
        assertEquals(List.of(224002), avatar.getTpsWeaponIds());
        assertEquals(List.of(224002), player.getTpsLoadout());
        assertEquals(1, player.avatarGrants);
        assertEquals(1, player.itemGrants);
        assertEquals(1, player.saves);
        assertEquals(sent, player.sent.size());
    }

    @Test
    void anExistingChosenWeaponIsUsedWithoutGrantingAnInitialWeapon() {
        var player = new TpsPlayer(10000007);
        player.ownItem(224002);
        player.getTpsLoadout().add(224002);
        assertTrue(TpsAvatarSystem.unlock(player));
        assertEquals(List.of(224002), player.getAvatars().getAvatarById(10000135).getTpsWeaponIds());
        assertEquals(0, player.itemGrants);
        assertNull(TpsWeaponSystem.findOwnedWeapon(player, 224001));
    }

    @Test
    void missingResourcesOrRejectedInventoryAdmissionCannotReportAnUnlock() {
        var player = new TpsPlayer(10000005);
        var data = GameData.getAvatarDataMap().remove(10000134);
        assertFalse(TpsAvatarSystem.unlock(player));
        GameData.getAvatarDataMap().put(10000134, data);
        player.acceptItems = false;
        assertFalse(TpsAvatarSystem.unlock(player));
        player.acceptItems = true;
        player.getInventory().createInventoryTab(ItemType.ITEM_WEAPON, new EquipInventoryTab(0));
        assertFalse(TpsAvatarSystem.unlock(player));
        assertFalse(new ExecUnlockTpsAvatar().execute(quest(player), null, "unexpected"));
        assertEquals(0, player.avatarGrants);
        assertEquals(0, player.saves);
        assertTrue(player.getAvatars().getAvatars().isEmpty());
        assertTrue(player.getTpsLoadout().isEmpty());
    }

    @Test
    void ordinaryTeamRequestsRejectTheTpsGuidBeforeClearingTheOldRoster() {
        var player = new TpsPlayer(10000005);
        var normal = player.ownAvatar(10000005);
        var tps = player.ownAvatar(10000134);
        var team = player.getTeamManager();
        team.getCurrentSinglePlayerTeamInfo().getAvatars().add(normal.getAvatarId());
        TeamSetupHelper.setupAvatarTeam(player, team.getCurrentTeamId(), List.of(tps.getGuid()));
        team.setupAvatarTeam(team.getCurrentTeamId(), List.of(tps.getGuid()));
        assertFalse(team.addAvatarToCurrentTeam(tps));
        assertEquals(List.of(normal.getAvatarId()), team.getCurrentSinglePlayerTeamInfo().getAvatars());
        assertEquals(0, player.saves);
    }

    @Test
    void characterPageEquipmentChangesReachTheActiveTrialIdentityWithoutAnotherItemGrant() throws Exception {
        var player = new TpsPlayer(10000005);
        var formal = player.ownAvatar(10000134);
        var runtime = new SavedAvatar(GameData.getAvatarDataMap().get(10000134));
        runtime.setOwner(player);
        runtime.setAvatarType(2);
        runtime.setTrialAvatarId(10064);
        runtime.getTpsWeaponIds().add(224001);
        formal.getTpsWeaponIds().add(224001);
        assertTrue(player.getTeamManager().lockQuestParty(new QuestPartyLock(7012,
                QuestPartyLock.Kind.MIRROR_OWNED, List.of(10000134), 0, 2222)));
        var mirror = player.getTeamManager().getQuestPartyAvatars().iterator().next();
        assertEquals(List.of(224001), mirror.getTpsWeaponIds());
        player.getTeamManager().getTrialAvatars().put(10000134, runtime);
        player.getTpsLoadout().add(224001);
        player.ownItem(224001);
        var chosen = player.ownItem(224002);

        assertEquals(Retcode.RET_SUCC_VALUE,
                TpsWeaponSystem.wear(player, formal.getGuid(), List.of(chosen.getGuid())));
        assertEquals(List.of(224002), formal.getTpsWeaponIds());
        assertEquals(List.of(224002), runtime.getTpsWeaponIds());
        assertEquals(List.of(224002), mirror.getTpsWeaponIds());
        assertEquals(List.of(224002), player.getTpsLoadout());
        assertEquals(0, player.itemGrants);
        assertEquals(0, runtime.saves);
        var runtimePacket = player.sent.stream().filter(p -> p.getOpcode() == PacketOpcodes.TpsEquipChangeNotify)
                .map(p -> { try { return TpsEquipChangeNotify.parseFrom(p.getData()); }
                    catch (Exception e) { throw new AssertionError(e); } })
                .filter(p -> p.getAvatarGuid() == runtime.getGuid()).findFirst().orElseThrow();
        assertEquals(224002, runtimePacket.getTpsWeaponList(0).getItemId());

        var first = TpsWeaponSystem.findOwnedWeapon(player, 224001);
        assertEquals(Retcode.RET_SUCC_VALUE,
                TpsWeaponSystem.wear(player, runtime.getGuid(), List.of(first.getGuid())));
        assertEquals(List.of(224001), formal.getTpsWeaponIds());
        assertTrue(formal.saves > 0);
        assertEquals(0, runtime.saves);
        assertEquals(2, player.getInventory().getItems().size());
        runtime.getTpsWeaponIds().clear();
        assertEquals(Retcode.RET_SUCC_VALUE,
                TpsWeaponSystem.wear(player, formal.getGuid(), List.of(first.getGuid())));
        assertEquals(List.of(224001), runtime.getTpsWeaponIds());
        assertEquals(2, player.getInventory().getItems().size());
    }

    private static GameQuest quest(Player player) {
        return new GameQuest() { @Override public Player getOwner() { return player; } };
    }

    private static final class SavedAvatar extends Avatar {
        int saves;
        SavedAvatar(AvatarData data) { super(data); }
        @Override public void save() { saves++; }
    }

    /** In-memory storage/admission boundary; production still calls Player.addAvatar. */
    private static final class TpsPlayer extends TestPlayer {
        final TeamManager team = new TeamManager(this);
        final List<BasePacket> sent = new CopyOnWriteArrayList<>();
        final AvatarStorage storage = new AvatarStorage(this) {
            @Override public Avatar getAvatarByGuid(long guid) {
                return getAvatars().values().stream().filter(a -> a.getGuid() == guid).findFirst().orElse(null);
            }
        };
        final Inventory inventory = new Inventory(this) {
            @Override public boolean addItem(int id, int count, ActionReason reason) {
                if (!acceptItems) return false;
                itemGrants++;
                ownItem(id);
                return true;
            }
        };
        final int traveler;
        boolean acceptItems = true;
        boolean addedToTeam;
        int itemGrants;
        int avatarGrants;
        int saves;

        TpsPlayer(int traveler) { this.traveler = traveler; }
        @Override public int getMainCharacterId() { return traveler; }
        @Override public TeamManager getTeamManager() { return team; }
        @Override public AvatarStorage getAvatars() { return storage; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public boolean hasSentLoginPackets() { return true; }
        @Override public void save() { saves++; }
        @Override public void sendPacket(BasePacket packet) { sent.add(packet); }
        @Override public void addAvatar(Avatar avatar, boolean addToTeam) {
            avatar.setOwner(this);
            storage.getAvatars().put(avatar.getAvatarId(), avatar);
            avatar.recalcStats();
            avatarGrants++;
            addedToTeam = addToTeam;
            sendPacket(new PacketAvatarAddNotify(avatar, addToTeam));
        }

        SavedAvatar ownAvatar(int id) {
            var avatar = new SavedAvatar(GameData.getAvatarDataMap().get(id));
            avatar.setOwner(this);
            storage.getAvatars().put(id, avatar);
            return avatar;
        }

        GameItem ownItem(int id) {
            var item = new GameItem(GameData.getItemDataMap().get(id));
            item.setOwner(this);
            inventory.getItems().put(item.getGuid(), item);
            return item;
        }
    }
}
