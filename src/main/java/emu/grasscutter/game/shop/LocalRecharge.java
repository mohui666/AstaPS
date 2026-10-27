package emu.grasscutter.game.shop;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.UnknownFieldSet;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.inventory.InventoryAddPolicy;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.proto.ShopOuterClass.Shop;
import emu.grasscutter.net.proto.ShopMcoinProductOuterClass.ShopMcoinProduct;
import emu.grasscutter.net.proto.ShopCardProductOuterClass.ShopCardProduct;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.*;

/** Free local checkout. Product amounts come from the installed resource tables. */
public final class LocalRecharge {
    public record Product(int configId, String id, String name, String priceTier,
                          int crystals, int firstBonus, int repeatBonus, int days) {}

    private static final class Catalog {
        static final List<Product> PRODUCTS = loadProducts();
        static final Map<String, Product> ALIASES = loadAliases();
    }

    private LocalRecharge() {}

    private static List<JsonObject> rows(String file) {
        try {
            var value = JsonParser.parseString(Files.readString(FileUtils.getResourcePath("ExcelBinOutput/" + file)));
            var rows = new ArrayList<JsonObject>();
            for (var row : value.getAsJsonArray()) rows.add(row.getAsJsonObject());
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read recharge product data", e);
        }
    }

    private static int number(JsonObject row, String name) {
        return row.has(name) ? row.get(name).getAsInt() : 0;
    }

    private static List<Product> loadProducts() {
        var products = new ArrayList<Product>();
        for (var row : rows("ProductMcoinDetailConfigData.json")) {
            int id = number(row, "configId"), base = number(row, "mcoinNum");
            products.add(new Product(id, Integer.toString(id), base + " 创世结晶",
                    row.get("priceTier").getAsString(), base, number(row,"mcoinFirst"), number(row,"mcoinNonFirst"), 0));
        }
        for (var row : rows("ProductCardDetailConfigData.json")) {
            if (number(row,"configId") != 101) continue;
            products.add(new Product(101, "ys_chn_blessofmoon_tier5", "空月祝福 · 30 天",
                    row.get("priceTier").getAsString(), number(row,"mcoinBase"), 0, 0, number(row,"days")));
        }
        for (var row : rows("ProductPlayDetailConfigData.json")) {
            int id = number(row,"configId");
            if (id < 201 || id > 205) continue;
            String label = switch (id) {
                case 201 -> "珍珠纪行"; case 202 -> "珍珠之歌"; case 203 -> "纪行升级包";
                case 204 -> "珍珠纪行（优惠档）"; default -> "珍珠之歌（优惠档）";
            };
            products.add(new Product(id, Integer.toString(id), label, row.get("priceTier").getAsString(), 0,0,0,0));
        }
        return List.copyOf(products);
    }

    private static Map<String, Product> loadAliases() {
        var result = new HashMap<String,Product>();
        for (var product : Catalog.PRODUCTS) {
            result.put(product.id(), product);
            result.put(Integer.toString(product.configId()), product);
        }
        for (var row : rows("ProductIdConfigData.json")) {
            var product = result.get(Integer.toString(number(row,"configId")));
            if (product != null) result.put(row.get("productId").getAsString(), product);
        }
        for (String region : List.of("chn", "glb")) {
            for (var product : Catalog.PRODUCTS) {
                if (product.configId() < 100) result.put("ys_"+region+"_primogem"+product.crystals()+"_"+product.priceTier().toLowerCase(java.util.Locale.ROOT).replace("_",""),product);
            }
            result.put("ys_"+region+"_blessofmoon_tier5", result.get("101"));
            result.put("ys_"+region+"_bp_normal_tier10", result.get("201"));
            result.put("ys_"+region+"_bp_extra_tier20", result.get("202"));
            result.put("ys_"+region+"_bp_upgrade_tier12", result.get("203"));
        }
        result.put("cloudys_chn_blessofmoon_tier5",result.get("101"));
        result.put("cloudysglbblessofmoontier5",result.get("101"));
        result.put("cloudys_chn_bp_normal_tier10",result.get("201"));
        result.put("cloudys_chn_bp_extra_tier20",result.get("202"));
        result.put("cloudys_chn_bp_upgrade_tier12",result.get("203"));
        return Map.copyOf(result);
    }

    public static List<Product> products() { return Catalog.PRODUCTS; }

    public static Product product(String id) {
        var product = Catalog.ALIASES.get(id);
        if (product == null) throw new IllegalArgumentException("不支持的本地充值商品：" + id);
        return product;
    }

    /** Select only an exact known SKU; never guess a product from an ambiguous price tier. */
    public static String productFromPayload(byte[] payload) throws IOException {
        if (payload.length > 16_384) throw new IllegalArgumentException("充值请求过大。");
        var products = new LinkedHashSet<String>();
        for (var field : UnknownFieldSet.parseFrom(payload).asMap().values()) {
            for (var bytes : field.getLengthDelimitedList()) {
                var product = bytes.isValidUtf8() ? Catalog.ALIASES.get(bytes.toStringUtf8()) : null;
                if (product != null) {
                    products.add(product.id());
                } else if (!bytes.isEmpty() && bytes.byteAt(0) == 10) {
                    // Captured 7.1 RechargeReq: card_product is field 12 and its SKU
                    // is nested field 1. Mcoin/play products use the same SKU slot.
                    var nested = UnknownFieldSet.parseFrom(bytes).getField(1);
                    for (var sku : nested.getLengthDelimitedList()) {
                        if (!sku.isValidUtf8()) continue;
                        var known = Catalog.ALIASES.get(sku.toStringUtf8());
                        if (known != null) products.add(known.id());
                    }
                }
            }
        }
        if (products.size() != 1) throw new IllegalArgumentException("充值请求中没有唯一有效商品，尚未发放。");
        return products.iterator().next();
    }

    public static int crystalAmount(Product product, int bought) {
        return Math.addExact(product.crystals(), bought == 0 ? product.firstBonus() : product.repeatBonus());
    }

    /** Called on the game logic thread; a repeated order returns its stored receipt. */
    public static JsonObject purchase(Player player, String productId, String orderId) {
        if (!GAME_OPTIONS.localRecharge) throw new IllegalArgumentException("本地模拟充值未开启。");
        if (player == null || !player.isOnline()) throw new IllegalArgumentException("请先进入游戏。");
        if (orderId == null || !orderId.matches("[A-Za-z0-9:_-]{8,128}")) throw new IllegalArgumentException("充值订单 ID 无效。");
        var product = product(productId);
        var stored = player.getLocalRechargeReceipts().get(orderId);
        if (stored != null) {
            var receipt = JsonParser.parseString(stored).getAsJsonObject();
            if (receipt.get("configId").getAsInt() != product.configId()) throw new IllegalArgumentException("该订单已用于其他商品。");
            receipt.addProperty("duplicate",true);
            receipt.addProperty("crystals",player.getCrystals());
            receipt.addProperty("moonCardDays",player.inMoonCard() ? Math.max(0,player.getMoonCardRemainDays()) : 0);
            return receipt;
        }
        String countKey = Integer.toString(product.configId());
        int amount = 0;
        if (product.configId() < 100) {
            amount = crystalAmount(product, player.getLocalRechargeCounts().getOrDefault(countKey,0));
            int balance = Math.addExact(player.getCrystals(), amount);
            if (!player.setCrystals(balance)) throw new IllegalArgumentException("创世结晶余额已达上限。");
        } else if (product.configId() == 101) {
            Math.addExact(player.getCrystals(),product.crystals());
            if (!player.rechargeMoonCard()) throw new IllegalArgumentException("空月祝福已达 180 天上限。");
            amount = product.crystals();
        } else {
            var pass = player.getBattlePassManager();
            int bpType = switch (product.configId()) { case 204 -> 201; case 205 -> 202; default -> product.configId(); };
            int schedule = pass.getScheduleProto().getScheduleId();
            String extraKey = "bp:" + schedule + ":extra";
            countKey = "bp:" + schedule + ":" + product.configId();
            if (bpType == 201 && pass.isPaid()) throw new IllegalArgumentException("本期珍珠纪行已解锁。");
            if (bpType == 202 && pass.isPaid()) throw new IllegalArgumentException("已有珍珠纪行，请选择纪行升级包。");
            if (bpType == 203 && !pass.isPaid()) throw new IllegalArgumentException("请先解锁本期珍珠纪行。");
            if (bpType != 201 && player.getLocalRechargeCounts().containsKey(extraKey)) throw new IllegalArgumentException("本期珍珠之歌奖励已领取。");
            int points = 0;
            if (bpType != 201) {
                var config = rows("BattlePassScheduleExcelConfigData.json").stream()
                        .filter(row -> number(row,"id") == schedule).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("本期纪行缺少商品奖励配置。"));
                var reward = GameData.getRewardDataMap().get(number(config,"extraPaidRewardId"));
                if (reward == null) throw new IllegalArgumentException("本期珍珠之歌奖励资料缺失。");
                var items = reward.getAllRewardItems().stream()
                        .map(item -> new GameItem(item.getItemId(),item.getItemCount())).toList();
                if (!player.getInventory().addItems(items, null, InventoryAddPolicy.ALL_OR_NOTHING).allAccepted())
                    throw new IllegalArgumentException("背包无法接收纪行商品奖励，尚未解锁。");
                points = number(config,"extraPaidAddPoint");
                player.getLocalRechargeCounts().put(extraKey,1);
            }
            pass.grantProductLevels(points);
        }
        player.getLocalRechargeCounts().merge(countKey,1,Integer::sum);
        var receipt = new JsonObject();
        receipt.addProperty("retcode",0);
        receipt.addProperty("message","本地模拟充值成功，商品已到账");
        receipt.addProperty("orderId",orderId);
        receipt.addProperty("uid",player.getUid());
        receipt.addProperty("configId",product.configId());
        receipt.addProperty("product",product.name());
        receipt.addProperty("crystalsAdded",amount);
        receipt.addProperty("crystals",player.getCrystals());
        receipt.addProperty("moonCardDays",player.inMoonCard() ? Math.max(0,player.getMoonCardRemainDays()) : 0);
        receipt.addProperty("duplicate",false);
        player.getLocalRechargeReceipts().put(orderId,JsonUtils.encode(receipt));
        player.save();
        return receipt;
    }

    public static void addShopProducts(Shop.Builder shop, Player player, int shopType) {
        if (!GAME_OPTIONS.localRecharge) return;
        for (var product : products()) {
            if (shopType == 903 && product.configId() < 100) {
                shop.addMcoinProductList(ShopMcoinProduct.newBuilder().setProductId(product.id()).setPriceTier(product.priceTier())
                        .setMcoinBase(product.crystals()).setMcoinFirst(product.firstBonus()).setMcoinNonFirst(product.repeatBonus())
                        .setBoughtNum(player.getLocalRechargeCounts().getOrDefault(Integer.toString(product.configId()),0)));
            } else if (shopType == 900 && product.configId() == 101) {
                shop.addCardProductList(ShopCardProduct.newBuilder().setProductId(product.id()).setPriceTier(product.priceTier())
                        .setMcoinBase(product.crystals()).setHcoinPerDay(90).setDays(product.days()).setCardProductType(1)
                        .setRemainRewardDays(player.inMoonCard() ? Math.max(0,player.getMoonCardRemainDays()) : 0));
            }
        }
    }
}
