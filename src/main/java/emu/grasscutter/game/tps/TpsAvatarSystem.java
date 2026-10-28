package emu.grasscutter.game.tps;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.scene.SceneData;
import emu.grasscutter.data.excels.trial.TrialAvatarData;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.dungeons.DungeonTrialTeam;
import emu.grasscutter.game.inventory.ItemType;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.proto.GrantReasonOuterClass.GrantReason;
import java.util.*;
import javax.annotation.Nullable;

/**
 * The dedicated TPS traveler. The TPS dungeons (Emerged Grey Field, the shooting range) list
 * avatars 10000134/10000135 as their only allowed avatars ({@code SceneExcelConfigData
 * .specifiedAvatarList}); the player enters as the one matching their traveler, a level 20 trial
 * avatar (TrialAvatarExcelConfigData 10064/10065), wearing the TPS weapons they picked last time.
 */
public final class TpsAvatarSystem {
    /** CONST_VALUE_TPS_AVATAR_CONFIG_ID_MALE. */
    public static final int TPS_AVATAR_MALE = 10000134;

    /** CONST_VALUE_TPS_AVATAR_CONFIG_ID_FEMALE. */
    public static final int TPS_AVATAR_FEMALE = 10000135;

    /** CONST_VALUE_INIT_TPS_WEAPON_ID: what the TPS traveler carries before any choice is made. */
    public static final int INIT_TPS_WEAPON_ID = 224001;

    /** CONST_VALUE_TPS_AVATAR_INIT_LEVEL (native ConstValue id 7030). */
    public static final int INIT_TPS_AVATAR_LEVEL = 20;

    private static final int TRAVELER_FEMALE = 10000007;

    private TpsAvatarSystem() {}

    public static boolean isTpsAvatar(int avatarId) {
        return avatarId == TPS_AVATAR_MALE || avatarId == TPS_AVATAR_FEMALE;
    }

    public static boolean isTpsAvatar(@Nullable Avatar avatar) {
        return avatar != null && isTpsAvatar(avatar.getAvatarId());
    }

    public static int getTpsAvatarId(Player player) {
        return player.getMainCharacterId() == TRAVELER_FEMALE ? TPS_AVATAR_FEMALE : TPS_AVATAR_MALE;
    }

    /**
     * Makes the dedicated TPS traveler available without adding it to an ordinary party.
     *
     * <p>The 7.1 character page resolves this config id through the avatar-data collection and
     * explicitly requires AvatarInfo.avatar_type == 1, so a trial/mirror notification does not
     * unlock it. The persistent initialization here is inferred from that native lookup, quest
     * 701215's sole unlock action, and the native initial-level/weapon constants; the server-side
     * implementation of that quest action is not part of the client binary.
     */
    public static boolean unlock(Player player) {
        var storage = player.getAvatars();
        synchronized (player.getInventory()) {
            int avatarId = getTpsAvatarId(player);
            var existing = storage.getAvatarById(avatarId);
            if (existing != null) return existing.getAvatarType() == 1 && existing.getTrialAvatarId() == 0;

            var data = GameData.getAvatarDataMap().get(avatarId);
            if (data == null || data.getSkillDepot() == null) return false;
            var startingWeapon = GameData.getItemDataMap().get(data.getInitialWeapon());
            if (startingWeapon == null || startingWeapon.getItemType() != ItemType.ITEM_WEAPON) return false;
            // Player.addAvatar grants the normal base weapon as well as publishing the avatar.
            // Keep its inventory slot available until that standard grant has finished.
            var weaponTab = player.getInventory().getInventoryTab(ItemType.ITEM_WEAPON);
            if (weaponTab == null || weaponTab.getSize() >= weaponTab.getMaxCapacity()) return false;
            if (!prepareLoadout(player)) return false;

            var avatar = new Avatar(data);
            avatar.setLevel(INIT_TPS_AVATAR_LEVEL);
            avatar.recalcStats();
            avatar.setCurrentHp(avatar.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP));
            avatar.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, avatar.getCurrentHp());
            avatar.getTpsWeaponIds().addAll(player.getTpsLoadout());
            // Standard storage, starting weapon, persistence and AvatarAddNotify/AvatarDataNotify.
            player.addAvatar(avatar, false);
            if (storage.getAvatarById(avatarId) != avatar) return false;
            player.save();
            return true;
        }
    }

    public static boolean isTpsScene(@Nullable SceneData sceneData) {
        if (sceneData == null || sceneData.getSpecifiedAvatarList() == null) return false;
        return sceneData.getSpecifiedAvatarList().stream().anyMatch(TpsAvatarSystem::isTpsAvatar);
    }

    /** The trial avatar entry for the player's TPS traveler; the lowest id wins. */
    public static int getTrialAvatarId(Player player) {
        int avatarId = getTpsAvatarId(player);
        return GameData.getTrialAvatarDataMap().values().stream()
                .filter(data -> data.getTrialAvatarParamList() != null)
                .filter(data -> !data.getTrialAvatarParamList().isEmpty())
                .filter(data -> data.getTrialAvatarParamList().get(0) == avatarId)
                .mapToInt(TrialAvatarData::getTrialAvatarId)
                .filter(id -> !player.getTeamManager().getTrialAvatarParam(id).isEmpty())
                .min()
                .orElse(0);
    }

    /** The team a TPS scene puts the player in, or null when the scene is not a TPS scene. */
    @Nullable public static DungeonTrialTeam getTrialTeam(Player player, @Nullable Scene scene) {
        if (scene == null || !isTpsScene(scene.getSceneData())) return null;
        int trialAvatarId = getTrialAvatarId(player);
        if (trialAvatarId == 0) return null;
        return new DungeonTrialTeam(
                new ArrayList<>(List.of(trialAvatarId)), GrantReason.GRANT_REASON_BY_TRIAL_AVATAR_ACTIVITY);
    }

    /**
     * Dresses a freshly made TPS traveler trial avatar in the player's TPS loadout, handing out the
     * initial weapon when there is none yet. Called before its stats are first calculated.
     */
    public static void onTrialAvatarCreated(Avatar avatar) {
        if (!isTpsAvatar(avatar) || avatar.getPlayer() == null) return;
        var player = avatar.getPlayer();
        prepareLoadout(player);

        avatar.getTpsWeaponIds().clear();
        avatar.getTpsWeaponIds().addAll(player.getTpsLoadout());
    }

    private static boolean prepareLoadout(Player player) {
        var loadout = player.getTpsLoadout();
        loadout.removeIf(itemId -> TpsWeaponSystem.findOwnedWeapon(player, itemId) == null);
        if (loadout.isEmpty()) {
            var initialWeapon = GameData.getItemDataMap().get(INIT_TPS_WEAPON_ID);
            if (initialWeapon == null || initialWeapon.getItemType() != ItemType.ITEM_TPS_WEAPON)
                return false;
            if (TpsWeaponSystem.findOwnedWeapon(player, INIT_TPS_WEAPON_ID) == null
                    && !player.getInventory().addItem(INIT_TPS_WEAPON_ID)) return false;
            if (TpsWeaponSystem.findOwnedWeapon(player, INIT_TPS_WEAPON_ID) == null) return false;
            loadout.add(INIT_TPS_WEAPON_ID);
        }
        return true;
    }

    /** The permanent character-page GUID and dungeon actors share the player's TPS equipment. */
    static void synchronizeLoadout(Player player, Avatar updated) {
        Set<Avatar> actors = Collections.newSetFromMap(new IdentityHashMap<>());
        player.getAvatars().forEach(actors::add);
        actors.addAll(player.getTeamManager().getTrialAvatars().values());
        actors.addAll(player.getTeamManager().getQuestPartyAvatars());
        player.getTeamManager().getActiveTeam().forEach(entity -> actors.add(entity.getAvatar()));
        for (var actor : actors) {
            if (actor == updated || !isTpsAvatar(actor)
                    || actor.getTpsWeaponIds().equals(player.getTpsLoadout())) continue;
            actor.getTpsWeaponIds().clear();
            actor.getTpsWeaponIds().addAll(player.getTpsLoadout());
            if (actor.getTrialAvatarId() == 0) actor.save();
            actor.recalcStats();
            TpsWeaponSystem.sendEquipChange(actor);
        }
    }
}
