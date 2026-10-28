package emu.grasscutter.game.avatar;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.avatar.AvatarData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.inventory.EquipType;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.game.tps.TpsAvatarSystem;
import emu.grasscutter.net.proto.AvatarInfoOuterClass.AvatarInfo;
import emu.grasscutter.net.proto.GrantReasonOuterClass.GrantReason;
import emu.grasscutter.net.proto.MirrorAvatarInfoOuterClass.MirrorAvatarInfo;

/** Quest-only actors have independent GUIDs, equipment copies and health; they cannot write avatars. */
public final class QuestPartyAvatar extends Avatar {
    private final boolean mirror;
    private final int copyFromType;
    private final long copyFromId;

    private QuestPartyAvatar(AvatarData data, boolean mirror, int sourceType, long sourceId) {
        super(data);
        this.mirror = mirror;
        this.copyFromType = sourceType;
        this.copyFromId = sourceId;
    }

    public static QuestPartyAvatar copyOwned(Player owner, Avatar source, int questId) {
        if (source == null || source.getAvatarData() == null || source.getSkillDepot() == null) return null;
        var copy = new QuestPartyAvatar(source.getAvatarData(), true, source.getAvatarType(), source.getGuid());
        copy.setSkillDepotData(source.getSkillDepot());
        copy.setLevel(source.getLevel());
        copy.setExp(source.getExp());
        copy.setPromoteLevel(source.getPromoteLevel());
        copy.setFlyCloak(source.getFlyCloak());
        copy.setCostume(source.getCostume());
        copy.setWeaponSkinId(source.getWeaponSkinId());
        copy.setTraceEffect(source.getTraceEffect());
        copy.setFetterLevel(source.getFetterLevel());
        copy.setFetterExp(source.getFetterExp());
        source.getSkillLevelMap().forEach(copy::setSkillLevel);
        source.getTalentIdList().forEach((int talent) -> copy.unlockConstellation(talent, true));
        source.getEquips().forEach((slot, item) -> copy.getEquips().put(slot, item.copyForGrant()));
        copy.getFightPropOverrides().putAll(source.getFightPropOverrides());
        copy.getExtraAbilityEmbryos().addAll(source.getExtraAbilityEmbryos());
        copy.getTpsWeaponIds().addAll(source.getTpsWeaponIds());
        copy.setOwner(owner);
        copy.setSkillDepotData(source.getSkillDepot());
        copy.setFromParentQuestId(questId);
        copy.setTrialAvatarId(source.getTrialAvatarId());
        copy.setGrantReason(GrantReason.GRANT_REASON_BY_QUEST.getNumber());
        copy.setAvatarType(3);
        copy.recalcStats();
        copy.getExtraAbilityEmbryos().addAll(source.getExtraAbilityEmbryos());
        copy.getFightProperties().putAll(source.getFightProperties());
        copy.setCurrentHp(source.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        copy.setNyxValue(source.getNyxValue());
        if (source.getSkillDepot().getEnergySkillData() != null) {
            var energy = source.getSkillDepot().getElementType().getCurEnergyProp();
            copy.setCurrentEnergy(energy, source.getFightProperty(energy));
        }
        return copy;
    }

    public static QuestPartyAvatar fromTrial(Player owner, int trialId, int questId, boolean mirror) {
        var trial = GameData.getTrialAvatarDataMap().get(trialId);
        if (trial == null || trial.getTrialAvatarParamList() == null
                || trial.getTrialAvatarParamList().size() < 2) return null;
        int avatarId = trial.getTrialAvatarParamList().get(0);
        var data = GameData.getAvatarDataMap().get(avatarId);
        if (data == null) return null;
        var copy = new QuestPartyAvatar(data, mirror, 2, trialId);
        int index = trial.getTrialSkillDepotId();
        var candidates = data.getCandSkillDepotIds();
        // Native Traveler trial selectors are one-based (1..8); the avatar id selects no element.
        if (index == avatarId) index = 1;
        if (index > 0 && (candidates == null || index > candidates.size())) return null;
        var depot = index > 0 ? GameData.getAvatarSkillDepotDataMap().get((int) candidates.get(index - 1))
                : copy.getSkillDepot();
        if (depot == null) return null;
        copy.setSkillDepotData(depot);
        copy.setTrialAvatarId(trialId);
        if (!GameData.getItemDataMap().containsKey(copy.getTrialWeaponId())) return null;
        copy.setTrialAvatarInfo(trial.getTrialAvatarParamList().get(1), trialId,
                GrantReason.GRANT_REASON_BY_QUEST, questId);
        copy.setOwner(owner);
        TpsAvatarSystem.onTrialAvatarCreated(copy);
        if (TpsAvatarSystem.isTpsAvatar(copy) && copy.getTpsWeaponIds().isEmpty()) return null;
        copy.setSkillDepotData(depot);
        copy.setAvatarType(mirror ? 3 : 2);
        copy.recalcStats();
        return copy;
    }

    @Override
    public void save() {
        // A quest actor is not a permanent roster entry, even when a generic combat path saves it.
    }

    @Override
    public boolean equipItem(GameItem item, boolean shouldRecalc) { return false; }

    @Override
    public boolean unequipItem(EquipType slot) { return false; }

    @Override
    public boolean upgradeSkill(int skillId) { return false; }

    @Override
    public boolean unlockConstellation(int talentId, boolean skipPayment) {
        // Construction copies constellation data before an owner is attached; runtime cannot pay.
        return getPlayer() == null && skipPayment && super.unlockConstellation(talentId, true);
    }

    @Override
    public boolean unlockConstellation(boolean skipPayment) { return false; }

    @Override
    public AvatarInfo toProto() {
        var proto = super.toProto().toBuilder();
        if (getTrialAvatarId() > 0) proto.setTrialAvatarInfo(toTrialInfo());
        if (mirror) proto.setMirrorAvatarInfo(MirrorAvatarInfo.newBuilder()
                .setCopyFromAvatarType(copyFromType).setLEEHFFLICIK(copyFromId));
        return proto.build();
    }
}
