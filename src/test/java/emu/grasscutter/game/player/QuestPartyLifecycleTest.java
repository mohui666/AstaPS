package emu.grasscutter.game.player;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.avatar.AvatarData;
import emu.grasscutter.data.excels.avatar.AvatarSkillDepotData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.trial.TrialAvatarData;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.avatar.QuestPartyAvatar;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.game.world.TestWorld.TestPlayer;
import emu.grasscutter.utils.JsonUtils;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

@ExtendWith(ServerResourceFixture.class)
@ResourceLock("GameData")
final class QuestPartyLifecycleTest {
    @Test
    void mirrorCopiesIdentityAndHealthWithoutReplacingOwnedRosterOrAvatar() throws Exception {
        var player = new PartyPlayer();
        var avatar = ownedAvatar(player, 10000990);
        var weaponData = JsonUtils.decode("{\"id\":900991,\"itemType\":\"ITEM_WEAPON\","
                + "\"equipType\":\"EQUIP_WEAPON\",\"skillAffix\":[900999],\"weaponProp\":[]}", ItemData.class);
        var weapon = new GameItem(weaponData);
        weapon.setOwner(player);
        weapon.setEquipCharacter(avatar.getAvatarId());
        avatar.getEquips().put(weapon.getEquipSlot(), weapon);
        var team = player.getTeamManager();
        team.getCurrentSinglePlayerTeamInfo().getAvatars().add(avatar.getAvatarId());
        assertTrue(team.lockQuestParty(new QuestPartyLock(6016, QuestPartyLock.Kind.MIRROR_OWNED,
                List.of(avatar.getAvatarId()), 0, 0)));
        var mirror = team.getActivePartyAvatar(avatar.getAvatarId());
        assertInstanceOf(QuestPartyAvatar.class, mirror);
        assertNotEquals(avatar.getGuid(), mirror.getGuid());
        assertNotSame(weapon, mirror.getWeapon());
        assertNotEquals(weapon.getGuid(), mirror.getWeapon().getGuid());
        mirror.getWeapon().getAffixes().clear();
        assertEquals(List.of(900999), weapon.getAffixes());
        assertEquals(avatar.getAvatarId(), weapon.getEquipCharacter());
        assertFalse(mirror.equipItem(weapon, true));
        assertFalse(mirror.upgradeSkill(900999));
        assertFalse(mirror.unlockConstellation());
        assertSame(avatar, player.getAvatars().getAvatarById(avatar.getAvatarId()));
        mirror.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 7f);
        assertEquals(75f, avatar.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        assertEquals(List.of(avatar.getAvatarId()), team.getCurrentSinglePlayerTeamInfo().getAvatars());
        assertSame(mirror, team.getQuestPartyAvatarByGuid(mirror.getGuid()));
        assertTrue(team.unlockQuestParty(6016, 0));
        assertSame(avatar, team.getActivePartyAvatar(avatar.getAvatarId()));
        assertNull(team.getQuestPartyAvatarByGuid(mirror.getGuid()));
        assertFalse(team.isQuestPartyLocked());
    }

    @Test
    void anotherQuestCannotReplaceOrUnlockAnActiveStoryParty() throws Exception {
        var player = new PartyPlayer();
        var avatar = ownedAvatar(player, 10000991);
        var team = player.getTeamManager();
        team.getCurrentSinglePlayerTeamInfo().getAvatars().add(avatar.getAvatarId());
        assertTrue(team.lockQuestParty(new QuestPartyLock(6016, QuestPartyLock.Kind.OWNED,
                List.of(avatar.getAvatarId()), 0, 1333)));
        assertFalse(team.unlockQuestParty(6036, 0));
        assertFalse(team.unlockQuestParty(6016, 1334));
        assertFalse(team.lockQuestParty(new QuestPartyLock(6036, QuestPartyLock.Kind.OWNED,
                List.of(avatar.getAvatarId()), 0, 1333)));
        assertTrue(team.isQuestPartyLocked());
        assertTrue(team.unlockQuestParty(6016, 1333));
    }

    @Test
    void missingTrialResourcesDoNotReplaceTheCurrentParty() throws Exception {
        var player = new PartyPlayer();
        var avatar = ownedAvatar(player, 10000992);
        var team = player.getTeamManager();
        team.getCurrentSinglePlayerTeamInfo().getAvatars().add(avatar.getAvatarId());
        assertTrue(team.lockQuestParty(new QuestPartyLock(6016, QuestPartyLock.Kind.OWNED,
                List.of(avatar.getAvatarId()), 0, 0)));
        assertFalse(team.lockQuestParty(new QuestPartyLock(6016, QuestPartyLock.Kind.TRIAL,
                List.of(Integer.MAX_VALUE), 0, 0)));
        assertEquals(QuestPartyLock.Kind.OWNED, team.getQuestPartyLock().getKind());
        assertEquals(List.of(avatar.getAvatarId()), team.getCurrentTeamInfo().getAvatars());
    }

    @Test
    void removingTheLastLockedTrialRestoresTheOwnedRoster() throws Exception {
        var player = new PartyPlayer();
        var owned = ownedAvatar(player, 10000993);
        var team = player.getTeamManager();
        team.getCurrentSinglePlayerTeamInfo().getAvatars().add(owned.getAvatarId());
        var data = owned.getAvatarData();
        var trial = JsonUtils.decode("{\"trialAvatarId\":900993,\"trialAvatarParamList\":[10000993,35]}",
                TrialAvatarData.class);
        var weapon = JsonUtils.decode("{\"id\":900990,\"itemType\":\"ITEM_WEAPON\","
                + "\"equipType\":\"EQUIP_WEAPON\",\"weaponProp\":[]}", ItemData.class);
        var oldAvatar = GameData.getAvatarDataMap().put(data.getId(), data);
        var oldTrial = GameData.getTrialAvatarDataMap().put(trial.getId(), trial);
        var oldWeapon = GameData.getItemDataMap().put(weapon.getId(), weapon);
        try {
            assertTrue(team.lockQuestParty(new QuestPartyLock(13027, QuestPartyLock.Kind.TRIAL,
                    List.of(trial.getId()), 0, 0)));
            assertNotSame(owned, team.getActivePartyAvatar(owned.getAvatarId()));
            team.removeTrialAvatar(List.of(trial.getId()));
            assertFalse(team.isQuestPartyLocked());
            assertSame(owned, team.getActivePartyAvatar(owned.getAvatarId()));
            assertEquals(List.of(owned.getAvatarId()), team.getCurrentTeamInfo().getAvatars());
        } finally {
            if (oldAvatar == null) GameData.getAvatarDataMap().remove(data.getId());
            else GameData.getAvatarDataMap().put(data.getId(), oldAvatar);
            if (oldTrial == null) GameData.getTrialAvatarDataMap().remove(trial.getId());
            else GameData.getTrialAvatarDataMap().put(trial.getId(), oldTrial);
            if (oldWeapon == null) GameData.getItemDataMap().remove(weapon.getId());
            else GameData.getItemDataMap().put(weapon.getId(), oldWeapon);
        }
    }

    private static Avatar ownedAvatar(Player player, int id) throws Exception {
        var depot = JsonUtils.decode("{\"id\":900990,\"skills\":[],\"talents\":[900999],\"inherentProudSkillOpens\":[]}",
                AvatarSkillDepotData.class);
        depot.onLoad();
        var data = JsonUtils.decode("{\"id\":" + id
                + ",\"hpBase\":100,\"initialWeapon\":900990,\"fetters\":[]}", AvatarData.class);
        Field field = AvatarData.class.getDeclaredField("skillDepot");
        field.setAccessible(true);
        field.set(data, depot);
        var avatar = new Avatar(data);
        avatar.setOwner(player);
        avatar.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 75f);
        avatar.setCurrentHp(75f);
        player.getAvatars().getAvatars().put(id, avatar);
        return avatar;
    }

    private static final class PartyPlayer extends TestPlayer {
        private final TeamManager team = new TeamManager(this);
        @Override public TeamManager getTeamManager() { return team; }
        @Override public void save() {}
    }
}
