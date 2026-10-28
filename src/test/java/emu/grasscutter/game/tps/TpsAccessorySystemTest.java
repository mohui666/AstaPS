package emu.grasscutter.game.tps;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.tps.TpsWeaponAccessoryData;
import emu.grasscutter.data.excels.tps.TpsWeaponData;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.avatar.AvatarStorage;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.world.TestWorld.TestPlayer;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.StoreItemChangeNotifyOuterClass.StoreItemChangeNotify;
import emu.grasscutter.utils.JsonUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class TpsAccessorySystemTest {
    private final Map<Integer, TpsWeaponAccessoryData> previousAccessories = new HashMap<>();
    private final Map<Integer, TpsWeaponData> previousWeapons = new HashMap<>();

    @BeforeEach
    void loadInstalledMapping() throws Exception {
        for (var row : JsonParser.parseString(Files.readString(Path.of(
                "resources/ExcelBinOutput/TpsWeaponAccessoryExcelConfigData.json"))).getAsJsonArray()) {
            var data = JsonUtils.decode(row.toString(), TpsWeaponAccessoryData.class);
            previousAccessories.put(data.getId(), GameData.getTpsWeaponAccessoryDataMap().put(data.getId(), data));
        }
        for (var row : JsonParser.parseString(Files.readString(Path.of(
                "resources/ExcelBinOutput/TpsWeaponExcelConfigData.json"))).getAsJsonArray()) {
            var data = JsonUtils.decode(row.toString(), TpsWeaponData.class);
            previousWeapons.put(data.getId(), GameData.getTpsWeaponDataMap().put(data.getId(), data));
        }
    }

    @AfterEach
    void restore() {
        previousAccessories.forEach((id, value) -> {
            if (value == null) GameData.getTpsWeaponAccessoryDataMap().remove(id);
            else GameData.getTpsWeaponAccessoryDataMap().put(id, value);
        });
        previousWeapons.forEach((id, value) -> {
            if (value == null) GameData.getTpsWeaponDataMap().remove(id);
            else GameData.getTpsWeaponDataMap().put(id, value);
        });
    }

    @Test
    void actualMaterialUnlocksOnlyItsOwnedWeaponAndUsesOriginalAffix() throws Exception {
        var player = new AccessoryPlayer();
        player.loginSent = true;
        var rifle = player.own(224001, true, 1);
        var other = player.own(224002, true, 1);
        var material = player.own(223112, false, 1);
        assertEquals(1, TpsAccessorySystem.refreshOwnedAccessories(player));
        assertEquals(List.of(224506), rifle.getTpsAccessoryIds());
        assertTrue(other.getTpsAccessoryIds().isEmpty());
        assertEquals(1, material.getCount());
        assertEquals(1, rifle.saves);
        assertEquals(0, material.saves);
        assertEquals(1, TpsWeaponSystem.getAffixLevels(rifle).get(224012));
        var packet = player.sent.stream().filter(p -> p.getOpcode() == PacketOpcodes.StoreItemChangeNotify)
                .findFirst().orElseThrow();
        var update = StoreItemChangeNotify.parseFrom(packet.getData());
        assertEquals(1, update.getItemListCount());
        assertEquals(rifle.getGuid(), update.getItemList(0).getGuid());
        assertEquals(List.of(224506), update.getItemList(0).getTpsWeapon().getAccessoryIdListList());
    }

    @Test
    void materialBeforeWeaponDoesNotInventWeaponAndWillUnlockWhenItArrives() {
        var player = new AccessoryPlayer();
        player.own(223112, false, 1);
        assertEquals(0, TpsAccessorySystem.refreshOwnedAccessories(player));
        assertEquals(1, player.getInventory().getItems().size());
        var weapon = player.own(224001, true, 1);
        TpsAccessorySystem.onItemAdded(player, 224001);
        assertEquals(List.of(224506), weapon.getTpsAccessoryIds());
        assertEquals(2, player.getInventory().getItems().size());
        assertEquals(1, weapon.saves);
    }

    @Test
    void zeroQuantityOrAWeaponWithTheMaterialIdCannotUnlockAnAccessory() {
        var player = new AccessoryPlayer();
        var weapon = player.own(224001, true, 1);
        var material = player.own(223112, false, 0);
        assertEquals(0, TpsAccessorySystem.refreshOwnedAccessories(player));
        player.getInventory().getItems().remove(material.getGuid());
        player.own(223112, true, 1);
        assertEquals(0, TpsAccessorySystem.refreshOwnedAccessories(player));
        assertTrue(weapon.getTpsAccessoryIds().isEmpty());
        assertEquals(0, weapon.saves);
    }

    @Test
    void savedUnlockDoesNotReplayOrDisappearAfterRelogOrMaterialRemoval() {
        var player = new AccessoryPlayer();
        var weapon = player.own(224001, true, 1);
        var material = player.own(223112, false, 1);
        assertEquals(1, TpsAccessorySystem.refreshOwnedAccessories(player));
        // Round-trip the existing persistent accessory field; ItemData is loaded separately.
        var saved = new SavedItem(weapon.getItemData(), 1);
        saved.setTpsAccessoryIds(new ArrayList<>(Arrays.asList(JsonUtils.decode(
                JsonUtils.encode(weapon.getTpsAccessoryIds()), Integer[].class))));
        player.getInventory().getItems().clear();
        saved.setOwner(player);
        player.getInventory().getItems().put(saved.getGuid(), saved);
        assertEquals(0, TpsAccessorySystem.refreshOwnedAccessories(player));
        assertEquals(List.of(224506), saved.getTpsAccessoryIds());
        assertEquals(0, saved.saves);
        assertTrue(player.sent.isEmpty());
        assertEquals(1, material.getCount());
    }

    @Test
    void existingFormalAndDungeonActorsRecalculateOnceWithoutGrantingAnything() {
        var player = new AccessoryPlayer();
        player.own(224001, true, 1);
        player.own(223112, false, 1);
        var formal = new CountedAvatar();
        formal.getTpsWeaponIds().add(224001);
        var trial = new CountedAvatar();
        trial.getTpsWeaponIds().add(224001);
        player.storage.getAvatars().put(10000134, formal);
        player.team.getTrialAvatars().put(10000134, trial);
        assertEquals(1, TpsAccessorySystem.refreshOwnedAccessories(player));
        assertEquals(1, formal.recalculations);
        assertEquals(1, trial.recalculations);
        assertEquals(0, TpsAccessorySystem.refreshOwnedAccessories(player));
        assertEquals(1, formal.recalculations);
        assertEquals(2, player.getInventory().getItems().size());
    }

    private static final class CountedAvatar extends Avatar {
        int recalculations;
        @Override public void recalcStats() { recalculations++; }
    }

    private static final class SavedItem extends GameItem {
        int saves;
        SavedItem() {}
        SavedItem(ItemData data, int count) { super(data, count); }
        @Override public void save() { saves++; }
    }

    private static final class AccessoryPlayer extends TestPlayer {
        final AvatarStorage storage = new AvatarStorage(this);
        final TeamManager team = new TeamManager(this);
        final List<BasePacket> sent = new ArrayList<>();
        final Inventory inventory = new Inventory(this);
        boolean loginSent;
        @Override public AvatarStorage getAvatars() { return storage; }
        @Override public TeamManager getTeamManager() { return team; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public boolean hasSentLoginPackets() { return loginSent; }
        @Override public void sendPacket(BasePacket packet) { sent.add(packet); }
        SavedItem own(int id, boolean weapon, int count) {
            var data = JsonUtils.decode("{\"id\":" + id + ",\"itemType\":\""
                    + (weapon ? "ITEM_TPS_WEAPON" : "ITEM_MATERIAL") + "\"}", ItemData.class);
            var item = new SavedItem(data, count);
            item.setOwner(this);
            inventory.getItems().put(item.getGuid(), item);
            return item;
        }
    }
}
