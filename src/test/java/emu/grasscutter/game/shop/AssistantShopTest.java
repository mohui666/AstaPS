package emu.grasscutter.game.shop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.DefaultPermissionHandler;
import emu.grasscutter.command.PermissionHandler;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.avatar.*;
import emu.grasscutter.game.Account;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.avatar.AvatarStorage;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import java.util.*;
import org.junit.jupiter.api.*;

class AssistantShopTest {
    private static final Gson GSON = new Gson();
    private final List<Runnable> restore = new ArrayList<>();
    private PermissionHandler previousPermissions;

    @BeforeEach void tables() {
        previousPermissions = Grasscutter.getPermissionHandler();
        Grasscutter.setPermissionHandler(new DefaultPermissionHandler());
        item("{\"id\":201,\"itemType\":\"ITEM_VIRTUAL\",\"stackLimit\":2147483647}");
        item("{\"id\":202,\"itemType\":\"ITEM_VIRTUAL\",\"stackLimit\":2147483647}");
        item("{\"id\":11509,\"itemType\":\"ITEM_WEAPON\",\"rankLevel\":5}");
        item("{\"id\":4133,\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_AVATAR\",\"useOnGain\":true,\"itemUse\":[{\"useOp\":\"ITEM_USE_GAIN_AVATAR\",\"useParam\":[\"10000133\"]}]}");
        item("{\"id\":104003,\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_EXP_FRUIT\",\"stackLimit\":99999,\"itemUse\":[{\"useOp\":\"ITEM_USE_ADD_EXP\",\"useParam\":[\"20000\"]}]}");
        put(GameData.getAvatarDataMap(), 10000133, GSON.fromJson("{\"id\":10000133,\"avatarPromoteId\":9867,\"skillDepotId\":9867}", AvatarData.class));
        put(GameData.getAvatarSkillDepotDataMap(), 9867, GSON.fromJson("{\"id\":9867,\"skills\":[],\"energySkill\":0}", AvatarSkillDepotData.class));
        int[] caps = {20,40,50,60,70,80,90};
        for (int stage=0;stage<=6;stage++) {
            var row=GSON.fromJson("{\"avatarPromoteId\":9867,\"promoteLevel\":"+stage+",\"unlockMaxLevel\":"+caps[stage]+",\"scoinCost\":100,\"costItems\":[]}",AvatarPromoteData.class);
            put(GameData.getAvatarPromoteDataMap(),row.getId(),row);
        }
        for(int level=1;level<90;level++)put(GameData.getAvatarLevelDataMap(),level,GSON.fromJson("{\"level\":"+level+",\"exp\":1000}",AvatarLevelData.class));
    }

    @AfterEach void cleanup() {
        Collections.reverse(restore);
        restore.forEach(Runnable::run);
        Grasscutter.setPermissionHandler(previousPermissions);
    }

    private void item(String json) { var row=GSON.fromJson(json,ItemData.class);row.onLoad();put(GameData.getItemDataMap(),row.getId(),row); }
    private <T> void put(Map<Integer,T> rows,int id,T row) {
        T previous=rows.put(id,row);restore.add(()->{if(previous==null)rows.remove(id);else rows.put(id,previous);});
    }

    @Test void nativeGoodsAreFreeUnlimitedAndUseRealNewAvatarCards() {
        var player=new Buyer();
        var goods=AssistantShop.goods(player,AssistantShop.SHOP_TYPE);
        var card=goods.stream().filter(g->g.getGoodsItem().getId()==4133).findFirst().orElseThrow();
        assertEquals(10000133,AssistantShop.avatarId(GameData.getItemDataMap().get(4133)));
        assertEquals(card,goods.getFirst());
        var wire=ShopGoodsBuilder.fromShopInfo(card,Integer.MAX_VALUE,0).build();
        assertEquals(0,wire.getOCFMGIPGLDK());
        assertEquals(0,wire.getSingleLimit());
        assertEquals(0,wire.getELPGDNACFOA());
        assertEquals(0,wire.getNextRefreshTime());
        assertEquals(0,wire.getCostItemListCount());
        assertEquals(0,wire.getScoin()+wire.getHcoin()+wire.getMcoin());
        assertNull(AssistantShop.goods(player,1004));
    }

    @Test void permissionsAndShopIdentityAreCheckedBeforeAnyGrant() {
        var player=new Buyer();
        player.account.clearPermission();
        assertNull(AssistantShop.goods(player,AssistantShop.SHOP_TYPE));
        assertThrows(IllegalArgumentException.class,()->AssistantShop.buy(player,902,300000201,1));
        player.account.addPermission("player.give");
        assertThrows(IllegalArgumentException.class,()->AssistantShop.buy(player,1004,300000201,1));
        assertThrows(IllegalArgumentException.class,()->AssistantShop.buy(player,902,399999999,1));
        assertThrows(IllegalArgumentException.class,()->AssistantShop.buy(player,902,300000201,0));
        assertThrows(IllegalArgumentException.class,()->AssistantShop.buy(player,902,300000201,-1));
        assertEquals(0,player.inventory.calls);
    }

    @Test void repeatPurchasesDoNotAccumulateAStockLimitAndOverflowCannotGrant() {
        var player=new Buyer();
        for(int i=0;i<3;i++)assertTrue(AssistantShop.buy(player,902,300000201,7).accepted());
        assertEquals(3,player.inventory.calls);
        assertEquals(70000,player.inventory.rewards.getFirst().getCount());
        assertTrue(player.getShopLimit().isEmpty());
        assertThrows(ArithmeticException.class,()->AssistantShop.buy(player,902,300000201,Integer.MAX_VALUE));
        assertEquals(3,player.inventory.calls);
    }

    @Test void equipmentPurchasesProduceIndependentItemsAndRejectedBagsReportFailure() {
        var player=new Buyer();
        assertTrue(AssistantShop.buy(player,902,300011509,3).accepted());
        assertEquals(3,player.inventory.rewards.size());
        assertNotSame(player.inventory.rewards.get(0),player.inventory.rewards.get(1));
        assertTrue(player.inventory.rewards.stream().allMatch(i->i.getCount()==1));
        assertTrue(player.inventory.rewards.stream().allMatch(i->i.getLevel()==100 && i.getPromoteLevel()==6 && i.getRefinement()==4));
        player.inventory.reject=true;
        var receipt=AssistantShop.buy(player,902,300011509,3);
        assertFalse(receipt.accepted());
        assertTrue(receipt.bagFull());
        assertTrue(receipt.message().contains("未完整领取"));
        assertTrue(player.getShopLimit().isEmpty());
    }

    @Test void oneNativeCharacterCardAndTheWholePackShareInventoryAdmission() {
        var player=new Buyer();
        var receipt=AssistantShop.buy(player,902,300004133,2);
        assertTrue(receipt.accepted());
        assertEquals(1,player.inventory.calls);
        assertEquals(1,player.inventory.rewards.stream().filter(i->i.getItemId()==4133).count());
        assertEquals(1,player.inventory.rewards.stream().filter(i->i.getItemId()==4133).findFirst().orElseThrow().getCount());
        assertEquals(14,player.inventory.rewards.stream().filter(i->i.getItemId()==104003).findFirst().orElseThrow().getCount());
        assertTrue(receipt.message().contains("角色与培养材料"));
        assertEquals(100,player.storage.owned.getLevel());
        assertEquals(6,player.storage.owned.getPromoteLevel());
        player.inventory.reject=true;
        player.storage.owned=null;
        assertFalse(AssistantShop.buy(player,902,300004133,1).accepted());
    }

    @Test void ownedCharactersReceiveOnlyMaterialsAndTheirConstellationsAreUntouched() {
        var player=new Buyer();
        var owned=new OwnedAvatar();
        player.storage.owned=owned;
        var receipt=AssistantShop.buy(player,902,300004133,1);
        assertTrue(receipt.accepted());
        assertFalse(player.inventory.rewards.stream().anyMatch(i->i.getItemId()==4133));
        assertEquals(0,owned.getCoreProudSkillLevel());
        assertEquals(100,owned.getLevel());
        assertEquals(6,owned.getPromoteLevel());
    }

    private static final class OwnedAvatar extends Avatar {
        OwnedAvatar(){setLevel(45);setPromoteLevel(2);}
        @Override public int getAvatarId(){return 10000133;}
        @Override public int getSkillDepotId(){return 9867;}
        @Override public Map<Integer,Integer> getSkillLevelMap(){return Map.of();}
        @Override public int getCoreProudSkillLevel(){return 0;}
        @Override public void recalcStats(boolean force){ }
        @Override public void save(){ }
    }
    private static final class Buyer extends Player {
        final Account account=new Account();
        final RecordingInventory inventory=new RecordingInventory(this);
        final OwnedStorage storage=new OwnedStorage(this);
        Buyer(){setUid(99881);account.addPermission("player.give");}
        @Override public boolean isOnline(){return true;}
        @Override public Account getAccount(){return account;}
        @Override public Inventory getInventory(){return inventory;}
        @Override public AvatarStorage getAvatars(){return storage;}
        @Override public void dropMessage(Object message){}
        @Override public void sendPacket(emu.grasscutter.net.packet.BasePacket packet){}
    }
    private static final class OwnedStorage extends AvatarStorage {
        Avatar owned;
        OwnedStorage(Player player){super(player);}
        @Override public Avatar getAvatarById(int id){return owned!=null&&owned.getAvatarId()==id?owned:null;}
    }
    private static final class RecordingInventory extends Inventory {
        int calls;boolean reject;List<GameItem> rewards=List.of();
        RecordingInventory(Player player){super(player);}
        @Override public synchronized InventoryAddResult addItems(Collection<GameItem> items,ActionReason reason,InventoryAddPolicy policy){
            calls++;assertEquals(InventoryAddPolicy.ALL_OR_NOTHING,policy);assertEquals(ActionReason.Shop,reason);
            rewards=List.copyOf(items);
            if(reject)return InventoryAddResult.rejected(items,InventoryAddResult.Status.CAPACITY_FULL);
            if(items.stream().anyMatch(i->i.getItemId()==4133))((Buyer)getPlayer()).storage.owned=new OwnedAvatar();
            return new InventoryAddResult(items.stream().map(i->new InventoryAddResult.Entry(i.getItemId(),i.getCount(),i.getCount(),InventoryAddResult.Status.ADDED)).toList());
        }
    }
}
