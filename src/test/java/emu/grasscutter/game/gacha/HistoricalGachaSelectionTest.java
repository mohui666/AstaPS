package emu.grasscutter.game.gacha;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.utils.JsonUtils;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoricalGachaSelectionTest {
    @TempDir Path directory;

    @Test
    void keepsExplicitChoiceAcrossRestartAndRejectsUnknownPools() throws Exception {
        var phases = HistoricalGachaSelection.loadHistory();
        var path = directory.resolve("gacha/selection.json");
        var selection = new HistoricalGachaSelection(path, phases);
        assertEquals("1.0.1", selection.current().key());
        assertTrue(selection.select("1.0.2"));
        int schedule = selection.scheduleId();
        String saved = Files.readString(path);
        assertThrows(IllegalArgumentException.class, () -> selection.select("unknown"));
        assertEquals(saved, Files.readString(path));
        var restarted = new HistoricalGachaSelection(path, phases);
        assertEquals("1.0.2", restarted.current().key());
        assertEquals(schedule, restarted.scheduleId());
        assertFalse(restarted.select("1.0.2"));
    }

    @Test
    void hasChronologicalHistoryAndAllFourStaggered13Lineups() {
        var phases = HistoricalGachaSelection.loadHistory();
        assertEquals(108, phases.size());
        assertArrayEquals(new int[] {1022, 11501, 15502}, phases.getFirst().up5());
        var keys = phases.stream().map(HistoricalGachaSelection.Phase::key).toList();
        int xiao = keys.indexOf("1.3.1");
        assertEquals(java.util.List.of("1.3.1", "1.3.2a", "1.3.2b", "1.3.3"), keys.subList(xiao, xiao + 4));
        assertTrue(keys.indexOf("5.8.2") < keys.indexOf("Luna I.1"));
        assertTrue(keys.indexOf("Luna VIII.2") < keys.indexOf("7.0.1"));
        assertEquals("7.1.2", keys.getLast());
        assertArrayEquals(new int[] {4112, 4114, 11517, 13514}, phases.getLast().up5());
    }

    @Test
    void changesOnlyUpsAndScheduleWhileKeepingCostsWeightsAndPityGroup() throws Exception {
        GachaBanner original;
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/defaults/data/Banners.json"), StandardCharsets.UTF_8)) {
            original = JsonUtils.loadToList(reader, GachaBanner.class).getFirst();
        }
        original.onLoad();
        var phase = HistoricalGachaSelection.loadHistory().getFirst();
        var selected = original.forHistoricalSelection(phase, HistoricalGachaSelection.SCHEDULE_BASE);
        assertEquals(6666, original.getScheduleId());
        assertEquals(HistoricalGachaSelection.SCHEDULE_BASE, selected.getScheduleId());
        assertArrayEquals(phase.up5(), selected.getRateUpItems5());
        assertArrayEquals(phase.up4(), selected.getRateUpItems4());
        assertTrue(original.getRateUpItems5().length > selected.getRateUpItems5().length);
        for (int times : new int[] {1, 10}) {
            assertEquals(original.getCost(times).getId(), selected.getCost(times).getId());
            assertEquals(original.getCost(times).getCount(), selected.getCost(times).getCount());
        }
        for (int pity : new int[] {1, 8, 10, 73, 80, 90}) {
            assertEquals(original.getWeight(4, pity), selected.getWeight(4, pity));
            assertEquals(original.getWeight(5, pity), selected.getWeight(5, pity));
        }
        var playerPity = new PlayerGachaInfo();
        assertSame(playerPity.getBannerInfo(original), playerPity.getBannerInfo(selected));
    }

    @Test
    void clearsUnavailableTargetWithoutResettingPullPityOrFeaturedGuarantee() {
        var phase = HistoricalGachaSelection.loadHistory().getFirst();
        var banner = new GachaBanner().forHistoricalSelection(phase, HistoricalGachaSelection.SCHEDULE_BASE);
        var info = new PlayerGachaBannerInfo();
        info.setPity5(70); info.setPity4(8); info.setTotalPulls(170);
        info.setFailedFeaturedItemPulls(5, 1); info.setConsecutiveFeaturedLosses(2);
        info.setWishItemId(1029); info.setFailedChosenItemPulls(1);
        banner.clearUnavailableHistoricalWish(info);
        assertEquals(0, info.getWishItemId()); assertEquals(0, info.getFailedChosenItemPulls());
        assertEquals(70, info.getPity5()); assertEquals(8, info.getPity4()); assertEquals(170, info.getTotalPulls());
        assertEquals(1, info.getFailedFeaturedItemPulls(5)); assertEquals(2, info.getConsecutiveFeaturedLosses());
        info.setWishItemId(1022); info.setFailedChosenItemPulls(1);
        banner.clearUnavailableHistoricalWish(info);
        assertEquals(1022, info.getWishItemId()); assertEquals(1, info.getFailedChosenItemPulls());
    }

    @Test
    void nativeHistoricalTabsSeparateCharactersWeaponsAndTheirPity() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(Path.of("data/GachaEpitomizedPrefabMap.json")));
        GachaEpitomizedPrefabHelper.load();
        var phase = HistoricalGachaSelection.loadHistory().stream()
                .filter(p -> p.key().equals("Luna VIII.1")).findFirst().orElseThrow();
        var tabs = new GachaBanner().forNativeHistoricalSelection(phase, 2_000_000_000);
        assertEquals(java.util.List.of(301,400,302,200),tabs.stream().map(GachaBanner::getGachaType).toList());
        assertArrayEquals(new int[]{4107},tabs.get(0).getRateUpItems5());
        assertArrayEquals(new int[]{4133},tabs.get(1).getRateUpItems5());
        assertArrayEquals(new int[]{12516,14517},tabs.get(2).getRateUpItems5());
        assertTrue(java.util.Arrays.stream(tabs.get(0).getRateUpItems4()).allMatch(id -> id < 10000));
        assertTrue(java.util.Arrays.stream(tabs.get(2).getRateUpItems4()).allMatch(id -> id >= 10000));
        assertEquals(223,tabs.get(0).getCost(1).getId());
        assertEquals(223,tabs.get(2).getCost(1).getId());
        assertEquals(224,tabs.get(3).getCost(1).getId());
        var pity = new PlayerGachaInfo();
        assertSame(pity.getBannerInfo(tabs.get(0)),pity.getBannerInfo(tabs.get(1)));
        assertNotSame(pity.getBannerInfo(tabs.get(0)),pity.getBannerInfo(tabs.get(2)));
        assertNotSame(pity.getBannerInfo(tabs.get(2)),pity.getBannerInfo(tabs.get(3)));
        assertFalse(tabs.get(0).hasEpitomized());
        assertTrue(tabs.get(2).hasEpitomized());
    }

    @Test
    void missing711SkinsUseSupportedTemplatesWithoutChangingActualUpsCostsOrPity() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(Path.of("data/GachaEpitomizedPrefabMap.json")));
        GachaEpitomizedPrefabHelper.load();
        var history = HistoricalGachaSelection.loadHistory();
        var phase = history.stream().filter(p -> p.key().equals("7.1.1")).findFirst().orElseThrow();
        var donorPhase = history.stream().filter(p -> p.key().equals("5.3.1")).findFirst().orElseThrow();
        var tabs = new GachaBanner().forNativeHistoricalSelection(phase, 2_000_000_000);
        var donors = new GachaBanner().forNativeHistoricalSelection(donorPhase, 1_900_000_000);
        assertEquals(java.util.List.of(301, 400, 302, 200), tabs.stream().map(GachaBanner::getGachaType).toList());
        assertArrayEquals(new int[] {4140}, tabs.get(0).getRateUpItems5());
        assertArrayEquals(new int[] {4143}, tabs.get(1).getRateUpItems5());
        assertArrayEquals(new int[] {11522, 14524}, tabs.get(2).getRateUpItems5());
        assertArrayEquals(new int[] {1036, 1039, 1076}, tabs.get(0).getRateUpItems4());
        assertArrayEquals(new int[] {11437, 12401, 13401, 14437, 15437}, tabs.get(2).getRateUpItems4());
        int[] donorItems = {4106, 4107, 12514};
        for (int i = 0; i < 3; i++) {
            var banner = tabs.get(i);
            assertTrue(banner.isPlaceholderDisplay());
            var skin = GachaEpitomizedPrefabHelper.resolve(donorItems[i]);
            assertEquals(skin.getPrefabPath(), banner.getPrefabPath());
            assertEquals(skin.getPreviewPrefabPath(), banner.getPreviewPrefabPath());
            assertEquals(skin.getTitlePath(), banner.getTitlePath());
            assertEquals(223, banner.getCost(1).getId());
            assertEquals(10, banner.getCost(10).getCount());
            for (int pity : new int[] {1, 10, 62, 73, 80, 90}) {
                assertEquals(donors.get(i).getWeight(4, pity), banner.getWeight(4, pity));
                assertEquals(donors.get(i).getWeight(5, pity), banner.getWeight(5, pity));
            }
        }
        assertFalse(tabs.get(0).hasEpitomized());
        assertTrue(tabs.get(2).hasEpitomized());
        assertEquals(1, tabs.get(2).getWishMaxProgress());
        assertTrue(tabs.get(2).isValidEpitomizedWish(11522));
        assertTrue(tabs.get(2).isValidEpitomizedWish(14524));
        assertFalse(tabs.get(2).isValidEpitomizedWish(12514));
        var pity = new PlayerGachaInfo();
        assertSame(pity.getBannerInfo(donors.get(0)), pity.getBannerInfo(tabs.get(0)));
        assertSame(pity.getBannerInfo(donors.get(2)), pity.getBannerInfo(tabs.get(2)));
        assertNotSame(pity.getBannerInfo(tabs.get(0)), pity.getBannerInfo(tabs.get(2)));
        assertFalse(tabs.get(3).isPlaceholderDisplay());
        assertEquals(224, tabs.get(3).getCost(1).getId());
    }

    @Test
    void dedicated531SkinsAreStillUsedWithoutPlaceholderFlags() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(Path.of("data/GachaEpitomizedPrefabMap.json")));
        GachaEpitomizedPrefabHelper.load();
        var phase = HistoricalGachaSelection.loadHistory().stream()
                .filter(p -> p.key().equals("5.3.1")).findFirst().orElseThrow();
        var tabs = new GachaBanner().forNativeHistoricalSelection(phase, 1_800_000_000);
        assertTrue(tabs.stream().noneMatch(GachaBanner::isPlaceholderDisplay));
        assertEquals(GachaEpitomizedPrefabHelper.resolve(4106).getPrefabPath(), tabs.get(0).getPrefabPath());
        assertEquals(GachaEpitomizedPrefabHelper.resolve(4107).getPrefabPath(), tabs.get(1).getPrefabPath());
        assertEquals(GachaEpitomizedPrefabHelper.resolve(12514).getPrefabPath(), tabs.get(2).getPrefabPath());
    }
}
