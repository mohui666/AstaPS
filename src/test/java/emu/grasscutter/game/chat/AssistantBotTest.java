package emu.grasscutter.game.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.DefaultPermissionHandler;
import emu.grasscutter.command.PermissionHandler;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.ItemCatalog.Entry;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.game.Account;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.props.FightProperty;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssistantBotTest {
    private final RecordingChat chat = new RecordingChat();
    private final AssistantBot bot = new AssistantBot(chat);
    private ItemData previousPrimogem;
    private PermissionHandler previousPermissions;

    @BeforeEach
    void setUp() {
        previousPrimogem = GameData.getItemDataMap().put(201, new Gson().fromJson(
                "{\"id\":201,\"itemType\":\"ITEM_VIRTUAL\",\"stackLimit\":2147483647}", ItemData.class));
        previousPermissions = Grasscutter.getPermissionHandler();
        Grasscutter.setPermissionHandler(new DefaultPermissionHandler());
    }

    @AfterEach
    void cleanUp() {
        if (previousPrimogem == null) GameData.getItemDataMap().remove(201);
        else GameData.getItemDataMap().put(201, previousPrimogem);
        Grasscutter.setPermissionHandler(previousPermissions);
    }

    @Test
    void parsesChineseAndMultiwordNamesAndValidatesTheEntireQuantity() {
        assertEquals(new AssistantBot.Request("原石", 10000, null, null), AssistantBot.Request.parse("原石 10000"));
        assertEquals(new AssistantBot.Request("Mistsplitter Reforged", 2, 90, 5), AssistantBot.Request.parse("Mistsplitter Reforged 2 90 5"));
        assertEquals(new AssistantBot.Request("201", null, null, null), AssistantBot.Request.parse("201"));
        for (String input : List.of("原石 0", "原石 -1", "原石 1.5", "原石 2147483648", "原石 1 90 6", "原石 1 2 3 4"))
            assertThrows(IllegalArgumentException.class, () -> AssistantBot.Request.parse(input), input);
    }

    @Test
    void exactNamesBeatSubstringMatchesButAmbiguousNamesStayAmbiguous() {
        var exact = new Entry(201, "原石", "Primogem", "currency", 5, true);
        var longer = new Entry(300, "奇怪的原石", "Odd Primogem", "material", 5, true);
        assertEquals(List.of(exact), AssistantBot.matches(List.of(longer, exact), "原石"));
        assertEquals(List.of(exact), AssistantBot.matches(List.of(longer, exact), "201"));
        assertEquals(2, AssistantBot.matches(List.of(longer, exact), "primog").size());
    }

    @Test
    void grantsAreBoundToTheSenderAndSearchAloneNeverGrants() {
        var first = new RecordingPlayer(10001);
        var second = new RecordingPlayer(10002);
        bot.handle(first, "搜索 原石");
        assertEquals(0, first.inventory.calls);
        bot.handle(first, "领取 1 25");
        assertEquals(1, first.inventory.calls, () -> chat.messages.toString());
        assertEquals(25, first.inventory.received.getFirst().getCount());
        assertEquals(0, second.inventory.calls);
        assertTrue(chat.last(first).contains("已领取"));

        bot.handle(second, "领取 1");
        assertEquals(0, second.inventory.calls);
        assertTrue(chat.last(second).contains("没有找到"));
    }

    @Test
    void refusesMissingPermissionBeforeChangingTheInventory() {
        var player = new RecordingPlayer(10001);
        player.account.clearPermission();
        bot.handle(player, "领取 原石 10");
        assertEquals(0, player.inventory.calls);
        assertTrue(chat.last(player).contains("没有此功能权限"), () -> chat.messages.toString());
    }

    @Test
    void aRejectedGrantIsNeverReportedAsDelivered() {
        var player = new RecordingPlayer(10001);
        player.inventory.reject = true;
        bot.handle(player, "领取 原石 10");
        assertEquals(1, player.inventory.calls, () -> chat.messages.toString());
        assertTrue(chat.last(player).contains("实际到账 0 / 10"));
        assertTrue(chat.last(player).contains("背包空位不足"));
        assertFalse(chat.last(player).startsWith("已领取"));
    }

    @Test
    void menuAndLogoutDiscardOldNumberSelections() {
        var player = new RecordingPlayer(10001);
        bot.handle(player, "搜索 原石");
        bot.handle(player, "菜单");
        bot.handle(player, "领取 1");
        assertEquals(0, player.inventory.calls);
        bot.handle(player, "搜索 原石");
        bot.clear(player.getUid());
        bot.handle(player, "领取 1");
        assertEquals(0, player.inventory.calls);
    }

    @Test
    void plainSupplementsCannotBeGivenEquipmentOptions() {
        var material = GameData.getItemDataMap().get(201);
        assertThrows(IllegalArgumentException.class, () -> AssistantBot.prepareItems(material, new AssistantBot.Request("201", 1, 90, null)));
        assertThrows(IllegalArgumentException.class, () -> AssistantBot.prepareItems(material, new AssistantBot.Request("201", 1, null, 5)));
        var weapon = new Gson().fromJson("{\"id\":11509,\"itemType\":\"ITEM_WEAPON\",\"rankLevel\":5}", ItemData.class);
        var items = AssistantBot.prepareItems(weapon, new AssistantBot.Request("11509", 2, 90, 5));
        assertEquals(2, items.size());
        assertNotSame(items.get(0), items.get(1));
        assertEquals(90, items.getFirst().getLevel());
        assertEquals(6, items.getFirst().getPromoteLevel());
        assertEquals(4, items.getFirst().getRefinement());
    }

    @Test
    void shortcutsRetainNativeCommandsAndRejectTargetOrCommandTextInValues() {
        assertEquals("barrier off", AssistantBot.shortcut("自由探索", "开"));
        assertEquals("barrier on", AssistantBot.shortcut("自由探索", "关"));
        assertEquals("prop unlimitedstamina on", AssistantBot.shortcut("无限体力", "开"));
        assertEquals("prop godmode toggle", AssistantBot.shortcut("无敌", ""));
        assertEquals("teleport 7 3", AssistantBot.shortcut("传送点", "7 3"));
        assertThrows(IllegalArgumentException.class, () -> AssistantBot.shortcut("世界等级", "8 @10002"));
        assertThrows(IllegalArgumentException.class, () -> AssistantBot.shortcut("传送点", "7; give all"));
    }

    @Test
    void savedLocationsKeepFiniteCoordinatesAndSeparatePlayerFiles() {
        var position = new AssistantTeleports.Destination(3, 123.5f, 200, -10);
        assertEquals(position, AssistantTeleports.Destination.read(position.json()));
        assertNotEquals(AssistantTeleports.path(10001), AssistantTeleports.path(10002));
        assertThrows(IllegalArgumentException.class, () -> new AssistantTeleports.Destination(3, Float.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AssistantTeleports.Destination(3, 0, Float.POSITIVE_INFINITY, 0));
        assertFalse(AssistantBot.isCoordinateInput(new String[] {"我的", "璃月", "家"}));
        assertFalse(AssistantBot.isCoordinateInput(new String[] {"my", "liyue", "home", "point"}));
        assertTrue(AssistantBot.isCoordinateInput(new String[] {"123.5", "200", "-10", "3"}));
    }

    @Test
    void elementalMasteryKeepsAbsolutePointsWhileCriticalRateUsesPercent() {
        assertEquals(1000f, AssistantBot.statValue(FightProperty.FIGHT_PROP_ELEMENT_MASTERY, 1000));
        assertEquals(10000f, AssistantBot.statValue(FightProperty.FIGHT_PROP_CUR_ATTACK, 10000));
        assertEquals(1f, AssistantBot.statValue(FightProperty.FIGHT_PROP_CRITICAL, 100));
        assertEquals(2f, AssistantBot.statValue(FightProperty.FIGHT_PROP_CRITICAL_HURT, 200));
    }

    private static final class RecordingChat extends ChatSystem {
        final Map<Integer, List<String>> messages = new HashMap<>();
        RecordingChat() { super(null); }
        @Override public void sendPrivateMessageFromServer(int uid, String text) {
            messages.computeIfAbsent(uid, ignored -> new ArrayList<>()).add(text);
        }
        String last(Player player) { return messages.get(player.getUid()).getLast(); }
    }

    private static final class RecordingPlayer extends Player {
        final Account account = new Account();
        final RecordingInventory inventory = new RecordingInventory(this);
        RecordingPlayer(int uid) { setUid(uid); account.addPermission("player.give"); }
        @Override public boolean isOnline() { return true; }
        @Override public Account getAccount() { return account; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public void dropMessage(Object text) { }
    }

    private static final class RecordingInventory extends Inventory {
        int calls;
        boolean reject;
        List<GameItem> received = List.of();
        RecordingInventory(Player player) { super(player); }
        @Override public synchronized InventoryAddResult addItems(Collection<GameItem> items, ActionReason reason, InventoryAddPolicy policy) {
            calls++;
            assertEquals(InventoryAddPolicy.ALL_OR_NOTHING, policy);
            if (reject) return InventoryAddResult.rejected(items, InventoryAddResult.Status.CAPACITY_FULL);
            received = List.copyOf(items);
            return new InventoryAddResult(items.stream().map(i -> new InventoryAddResult.Entry(i.getItemId(), i.getCount(), i.getCount(), InventoryAddResult.Status.ADDED)).toList());
        }
    }
}
