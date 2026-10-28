package emu.grasscutter.game.player;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import dev.morphia.annotations.*;
import emu.grasscutter.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.config.ConfigLevelEntity;
import emu.grasscutter.data.binout.config.fields.ConfigAbilityData;
import emu.grasscutter.data.excels.avatar.AvatarSkillDepotData;
import emu.grasscutter.game.ability.PartyReviveHelper;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.avatar.QuestPartyAvatar;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.tps.TpsAvatarSystem;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.*;
import emu.grasscutter.net.proto.EnterTypeOuterClass.EnterType;
import emu.grasscutter.net.proto.MotionStateOuterClass.MotionState;
import emu.grasscutter.net.proto.PlayerDieTypeOuterClass.PlayerDieType;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.net.proto.GrantReasonOuterClass.GrantReason;
import emu.grasscutter.server.event.entity.EntityCreationEvent;
import emu.grasscutter.server.event.player.*;
import emu.grasscutter.server.packet.send.*;
import emu.grasscutter.utils.Utils;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.*;
import java.util.stream.Stream;
import lombok.*;

@Entity
public final class TeamManager extends BasePlayerDataManager {
    /**
     * Global value key for the party's Verdant Dew. The client owns the running total and
     * mirrors its updates back; the server keeps a copy because ability conditions read it as
     * ByTargetGlobalValue(MoonOvergrowPoint_All, Team) and would otherwise evaluate zero.
     */
    public static final String VERDANT_DEW = "MoonOvergrowPoint_All";

    @Transient private final List<EntityAvatar> avatars;
    @Transient @Getter private final Set<EntityBaseGadget> gadgets;
    @Transient @Getter private final IntSet teamResonances;
    @Transient @Getter private final IntSet teamResonancesConfig;
    @Transient @Getter @Setter private Set<String> teamAbilityEmbryos;

    @Getter private LinkedHashMap<Integer, TeamInfo> teams;
    private int currentTeamIndex;
    @Getter @Setter private int currentCharacterIndex;
    @Transient @Getter @Setter private TeamInfo mpTeam;
    @Transient @Getter @Setter private EntityTeam entity;

    @Transient private int useTemporarilyTeamIndex = -1;
    @Transient private List<TeamInfo> temporaryTeam;
    @Transient @Getter @Setter private boolean usingTrialTeam;
    @Transient @Getter @Setter private TeamInfo trialAvatarTeam;

    @Transient @Getter @Setter private Map<Integer, Avatar> trialAvatars;
    @Getter private QuestPartyLock questPartyLock;
    @Transient private TeamInfo questParty;
    @Transient private final Map<Integer, Avatar> questPartyAvatars = new LinkedHashMap<>();
    @Transient private Scene questPartyScene;

    @Transient @Getter @Setter
    private int previousIndex = -1;

    public TeamManager() {
        this.mpTeam = new TeamInfo();
        this.avatars = Collections.synchronizedList(new ArrayList<>());
        this.gadgets = new HashSet<>();
        this.teamResonances = new IntOpenHashSet();
        this.teamResonancesConfig = new IntOpenHashSet();
        this.teamAbilityEmbryos = new HashSet<>();
        this.trialAvatars = new HashMap<>();
        this.trialAvatarTeam = new TeamInfo();
    }

    public TeamManager(Player player) {
        this();
        this.setPlayer(player);

        this.teams = new LinkedHashMap<>();
        this.currentTeamIndex = 1;
        for (int i = 1; i <= GameConstants.DEFAULT_TEAMS; i++) {
            this.teams.put(i, new TeamInfo());
        }
    }

    public AbilityControlBlockOuterClass.AbilityControlBlock getAbilityControlBlock() {
        AbilityControlBlockOuterClass.AbilityControlBlock.Builder abilityControlBlock =
            AbilityControlBlockOuterClass.AbilityControlBlock.newBuilder();
        int embryoId = 0;

        if (Arrays.stream(GameConstants.DEFAULT_TEAM_ABILITY_STRINGS).count() > 0) {
            boolean inNatlan = player.isInNatlan();
            List<String> teamAbilties =
                Arrays.stream(GameConstants.DEFAULT_TEAM_ABILITY_STRINGS).toList();
            for (String skill : teamAbilties) {
                if ("DynamicAbility_Phlogiston".equals(skill) && !inNatlan) continue;
                AbilityEmbryoOuterClass.AbilityEmbryo emb =
                    AbilityEmbryoOuterClass.AbilityEmbryo.newBuilder()
                        .setAbilityId(++embryoId)
                        .setAbilityNameHash(Utils.abilityHash(skill))
                        .setAbilityOverrideNameHash(GameConstants.DEFAULT_ABILITY_NAME)
                        .build();
                abilityControlBlock.addAbilityEmbryoList(emb);
            }
        }

        var scene = player.getScene();
        if (scene != null) {
            String configName = scene.getSceneData().getLevelEntityConfig();
            if (configName != null && !configName.isEmpty()) {
                ConfigLevelEntity levelConfig = GameData.getConfigLevelEntityDataMap().get(configName);
                if (levelConfig != null && levelConfig.getTeamAbilities() != null) {
                    for (ConfigAbilityData ability : levelConfig.getTeamAbilities()) {
                        String skill = ability.getAbilityName();
                        if (skill == null || skill.isEmpty()) {
                            Grasscutter.getLogger().warn("Empty ability name in {}", configName);
                            continue;
                        }
                        AbilityEmbryoOuterClass.AbilityEmbryo emb =
                            AbilityEmbryoOuterClass.AbilityEmbryo.newBuilder()
                                .setAbilityId(++embryoId)
                                .setAbilityNameHash(Utils.abilityHash(skill))
                                .setAbilityOverrideNameHash(GameConstants.DEFAULT_ABILITY_NAME)
                                .build();
                        abilityControlBlock.addAbilityEmbryoList(emb);
                    }
                }
            }
        }

        // Widget follower pets (Endora / Mini Seelie) and other dynamic team abilities
        if (this.teamAbilityEmbryos != null) {
            for (String skill : this.teamAbilityEmbryos) {
                if (skill == null || skill.isEmpty()) {
                    continue;
                }
                // Never emit Dive_Team on land - a leftover embryo after diving would replay ripples.
                if (GameConstants.DIVE_TEAM_ABILITY.equals(skill)
                        && !emu.grasscutter.game.player.DiveAbilityHelper.isAttached(player)) {
                    continue;
                }
                AbilityEmbryoOuterClass.AbilityEmbryo emb =
                    AbilityEmbryoOuterClass.AbilityEmbryo.newBuilder()
                        .setAbilityId(++embryoId)
                        .setAbilityNameHash(Utils.abilityHash(skill))
                        .setAbilityOverrideNameHash(GameConstants.DEFAULT_ABILITY_NAME)
                        .build();
                abilityControlBlock.addAbilityEmbryoList(emb);
            }
        }

        return abilityControlBlock.build();
    }

    public World getWorld() {
        return this.getPlayer().getWorld();
    }

    public boolean isQuestPartyLocked() {
        return this.questPartyLock != null;
    }

    /** Actor identity takes precedence over owned avatar id, including a mirror of an owned actor. */
    public Avatar getActivePartyAvatar(int avatarId) {
        var avatar = this.questPartyAvatars.get(avatarId);
        if (avatar == null) avatar = this.trialAvatars.get(avatarId);
        return avatar != null ? avatar : this.getPlayer().getAvatars().getAvatarById(avatarId);
    }

    public Collection<Avatar> getQuestPartyAvatars() {
        return this.questPartyAvatars.values().stream()
                .filter(QuestPartyAvatar.class::isInstance).toList();
    }

    public Avatar getQuestPartyAvatarByGuid(long guid) {
        return this.questPartyAvatars.values().stream()
                .filter(avatar -> avatar.getGuid() == guid).findFirst().orElse(null);
    }

    private boolean questPartySceneReady() {
        var player = this.getPlayer();
        return player.getScene() != null && player.getWorld() != null
                && player.getWorld().getHost() == player && this.getEntity() != null
                && player.hasSentLoginPackets()
                && player.getSceneLoadState() == Player.SceneLoadState.LOADED;
    }

    private Map<Integer, Avatar> createQuestPartyActors(QuestPartyLock lock) {
        Map<Integer, Avatar> actors = new LinkedHashMap<>();
        for (int id : lock.getAvatarIds()) {
            Avatar actor;
            if (lock.isTrial()) {
                actor = QuestPartyAvatar.fromTrial(this.getPlayer(), id, lock.getQuestMainId(), lock.isMirror());
            } else {
                actor = this.trialAvatars.get(id);
                if (actor == null) actor = this.getPlayer().getAvatars().getAvatarById(id);
                if (lock.isMirror()) actor = QuestPartyAvatar.copyOwned(this.getPlayer(), actor, lock.getQuestMainId());
            }
            if (actor == null || actors.putIfAbsent(actor.getAvatarId(), actor) != null) return null;
            if (actor instanceof QuestPartyAvatar) {
                for (var item : actor.getEquips().values()) {
                    item.setEquipCharacter(actor.getAvatarId());
                    item.setOwner(this.getPlayer());
                }
            }
        }
        return actors;
    }

    /** Called before scene entity creation and login avatar data; does not depend on loaded quests. */
    public synchronized boolean prepareQuestPartyActors() {
        if (!isQuestPartyLocked() || this.questParty != null) return true;
        var actors = createQuestPartyActors(this.questPartyLock);
        if (actors == null) {
            Grasscutter.getLogger().error("Cannot restore quest {} party: missing or duplicate avatar resources",
                    this.questPartyLock.getQuestMainId());
            return false;
        }
        this.questPartyAvatars.putAll(actors);
        this.questParty = new TeamInfo(new ArrayList<>(actors.keySet()));
        this.currentCharacterIndex = Math.min(this.currentCharacterIndex, actors.size() - 1);
        return true;
    }

    public synchronized boolean lockQuestParty(QuestPartyLock requested) {
        var player = this.getPlayer();
        if (requested == null || player.isInMultiplayer() || this.useTemporarilyTeamIndex >= 0) return false;
        if (this.questPartyLock != null && this.questPartyLock.getQuestMainId() != requested.getQuestMainId()) {
            var owner = player.getQuestManager().getMainQuestById(this.questPartyLock.getQuestMainId());
            if (owner == null || !owner.isFinished()) return false;
            if (!unlockQuestParty(this.questPartyLock.getQuestMainId(), 0)) return false;
        }
        // Another quest or an activity owns its own trial roster and must retire it itself.
        if (this.trialAvatars.values().stream().anyMatch(avatar ->
                avatar.getGrantReason() != GrantReason.GRANT_REASON_BY_QUEST.getNumber()
                || avatar.getFromParentQuestId() != requested.getQuestMainId())) return false;
        if (this.questPartyLock != null && this.questPartyLock.getKind() == requested.getKind()
                && this.questPartyLock.getAvatarIds().equals(requested.getAvatarIds()))
            return resumeQuestPartyLock();
        var actors = createQuestPartyActors(requested);
        if (actors == null) return false;
        var oldActors = getQuestPartyAvatars();
        int restoreIndex = this.questPartyLock == null ? requested.getPreviousCharacterIndex()
                : this.questPartyLock.getPreviousCharacterIndex();
        var stored = new QuestPartyLock(requested.getQuestMainId(), requested.getKind(), requested.getAvatarIds(),
                restoreIndex, requested.getDungeonId());
        if (questPartySceneReady()) removeActiveQuestPartyEntities();
        this.questPartyLock = stored;
        this.questPartyAvatars.clear();
        this.questPartyAvatars.putAll(actors);
        this.questParty = new TeamInfo(new ArrayList<>(actors.keySet()));
        this.currentCharacterIndex = 0;
        this.questPartyScene = null;
        if (player.hasSentLoginPackets() && !oldActors.isEmpty())
            player.sendPacket(new PacketAvatarDelNotify(oldActors.stream().map(Avatar::getGuid).toList()));
        player.save();
        return !questPartySceneReady() || resumeQuestPartyLock();
    }

    private void removeActiveQuestPartyEntities() {
        var scene = this.getPlayer().getScene();
        if (scene != null) this.getActiveTeam().forEach(actor -> scene.removeEntity(
                actor, VisionTypeOuterClass.VisionType.VisionType_VISION_REMOVE));
        this.getActiveTeam().clear();
    }

    /** Rebind scene entities after transfer/relogin while retaining the mirror's combat state. */
    public synchronized boolean resumeQuestPartyLock() {
        if (!isQuestPartyLocked()) return true;
        if (!prepareQuestPartyActors()) return false;
        if (!questPartySceneReady()) return true;
        var player = this.getPlayer();
        var owner = player.getQuestManager().getMainQuestById(this.questPartyLock.getQuestMainId());
        if (owner != null && owner.isFinished())
            return unlockQuestParty(this.questPartyLock.getQuestMainId(), 0);
        if (this.questPartyLock.getDungeonId() == 0 && player.getScene().getDungeonManager() != null) {
            var previous = this.questPartyLock;
            this.questPartyLock = new QuestPartyLock(previous.getQuestMainId(), previous.getKind(),
                    previous.getAvatarIds(), previous.getPreviousCharacterIndex(),
                    player.getScene().getDungeonManager().getDungeonData().getId());
            player.save();
        }
        if (this.questPartyScene == player.getScene() && this.getActiveTeam().size() == this.questParty.size()
                && this.getActiveTeam().stream().allMatch(entity ->
                    this.questPartyAvatars.get(entity.getAvatar().getAvatarId()) == entity.getAvatar())) return true;
        removeActiveQuestPartyEntities();
        for (var avatar : this.questPartyAvatars.values()) {
            var entity = EntityCreationEvent.call(EntityAvatar.class,
                    new Class<?>[] {Scene.class, Avatar.class}, new Object[] {player.getScene(), avatar});
            if (entity == null) throw new IllegalStateException("Quest party entity creation was cancelled");
            this.getActiveTeam().add(entity);
            if (avatar instanceof QuestPartyAvatar) player.sendPacket(new PacketAvatarAddNotify(avatar, true));
        }
        this.questPartyScene = player.getScene();
        this.currentCharacterIndex = Math.max(0, Math.min(this.currentCharacterIndex, this.questParty.size() - 1));
        this.updateTeamProperties();
        player.getScene().addEntity(this.getCurrentAvatarEntity());
        player.sendPacket(new PacketQuestPartyUpdateNotify(player));
        return true;
    }

    /** Only the owning quest (or a successor after it finishes) may release the party. */
    public synchronized boolean unlockQuestParty(int requestingMainId, int dungeonId) {
        if (requestingMainId <= 0) return false;
        var player = this.getPlayer();
        var lock = this.questPartyLock;
        if (lock != null) {
            if (dungeonId != 0 && lock.getDungeonId() != dungeonId) return false;
            if (lock.getQuestMainId() != requestingMainId) {
                var owner = player.getQuestManager().getMainQuestById(lock.getQuestMainId());
                if (owner == null || !owner.isFinished()) return false;
            }
            boolean ready = questPartySceneReady();
            if (player.getScene() != null) removeActiveQuestPartyEntities();
            var temporary = getQuestPartyAvatars();
            this.questParty = null;
            this.questPartyLock = null;
            this.questPartyAvatars.clear();
            this.questPartyScene = null;
            this.currentCharacterIndex = lock.getPreviousCharacterIndex();
            if (player.hasSentLoginPackets() && !temporary.isEmpty())
                player.sendPacket(new PacketAvatarDelNotify(temporary.stream().map(Avatar::getGuid).toList()));
            var trials = questTrialIds(lock.getQuestMainId());
            if (!trials.isEmpty()) {
                if (player.hasSentLoginPackets()) player.sendPacket(new PacketAvatarDelNotify(
                        trials.stream().map(this::getTrialAvatarGuid).toList()));
                this.retireTrialAvatarRoster(trials);
                if (!this.isUsingTrialTeam()) this.setPreviousIndex(-1);
            }
            if (player.getScene() != null && this.getActiveTeam().isEmpty()) {
                for (int avatarId : this.getCurrentTeamInfo().getAvatars()) {
                    var avatar = getActivePartyAvatar(avatarId);
                    if (avatar == null) continue;
                    var entity = EntityCreationEvent.call(EntityAvatar.class,
                            new Class<?>[] {Scene.class, Avatar.class}, new Object[] {player.getScene(), avatar});
                    if (entity != null) this.getActiveTeam().add(entity);
                }
            }
            if (ready) {
                this.updateTeamProperties();
                var current = this.getCurrentAvatarEntity();
                if (current != null) player.getScene().addEntity(current);
            }
            if (player.hasSentLoginPackets()) player.sendPacket(new PacketAvatarTeamUpdateNotify(player));
            player.save();
            return true;
        }
        if (dungeonId != 0) return true;
        this.removeTrialAvatar(questTrialIds(requestingMainId));
        return true;
    }

    private List<Integer> questTrialIds(int mainId) {
        return this.trialAvatars.values().stream()
                .filter(avatar -> avatar.getGrantReason() == GrantReason.GRANT_REASON_BY_QUEST.getNumber()
                        && avatar.getFromParentQuestId() == mainId)
                .map(Avatar::getTrialAvatarId).toList();
    }

    public int getTeamId(TeamInfo team) {
        for (int i = 1; i <= this.teams.size(); i++) {
            if (this.teams.get(i).equals(team)) {
                return i;
            }
        }
        return -1;
    }

    public int getCurrentTeamId() {

        return currentTeamIndex;
    }

    private void setCurrentTeamId(int currentTeamIndex) {
        this.currentTeamIndex = currentTeamIndex;
    }

    public long getCurrentCharacterGuid() {
        var currentAvatarEntity = this.getCurrentAvatarEntity();
        if (currentAvatarEntity == null){

            Avatar mainCharacter = new Avatar(this.getPlayer().getMainCharacterId());
            return mainCharacter.getGuid();
        }

        return currentAvatarEntity.getAvatar().getGuid();
    }

    public TeamInfo getCurrentTeamInfo() {
        if (questParty != null) return questParty;
        if (useTemporarilyTeamIndex >= 0 && useTemporarilyTeamIndex < temporaryTeam.size()) {
            return temporaryTeam.get(useTemporarilyTeamIndex);
        }
        if (this.getPlayer().isInMultiplayer()) {
            return this.getMpTeam();
        }
        return this.getTeams().get(this.currentTeamIndex);
    }

    /**
     * Drops unowned and dedicated TPS avatar ids from ordinary saved teams. A trial avatar is added to
     * the current team while it is in use (see {@link #addAvatarToTrialTeam}); one left behind, by
     * leaving through a path that did not remove it or by logging out mid-dungeon, gets saved, and
     * the next team rebuild has no avatar for it.
     *
     * @return whether anything was removed.
     */
    public boolean removeUnownedAvatarsFromTeams() {
        var avatars = this.getPlayer().getAvatars();
        boolean changed = false;
        for (TeamInfo team : this.getTeams().values()) {
            changed |= team.getAvatars().removeIf(
                    id -> avatars.getAvatarById(id) == null || TpsAvatarSystem.isTpsAvatar(id));
            if (team.getAvatars().isEmpty() && team == this.getCurrentSinglePlayerTeamInfo()) {
                var main = avatars.getAvatarById(this.getPlayer().getMainCharacterId());
                if (main != null) team.getAvatars().add(main.getAvatarId());
            }
        }
        return changed;
    }

    public TeamInfo getCurrentSinglePlayerTeamInfo() {
        return this.getTeams().get(this.currentTeamIndex);
    }

    public List<EntityAvatar> getActiveTeam() {
        return avatars;
    }

    public List<EntityAvatar> getActiveTeam(boolean fix) {
        if (!fix) return this.getActiveTeam();

        var avatars = this.getActiveTeam();
        var avatarIds = new HashSet<Long>();
        for (var entityAvatar : new ArrayList<>(avatars)) {
            if (avatarIds.contains(entityAvatar.getAvatar().getGuid())) {
                avatars.remove(entityAvatar);
            } else {
                avatarIds.add(entityAvatar.getAvatar().getGuid());
            }
        }

        return avatars;
    }

    public EntityAvatar getCurrentAvatarEntity() {

        if (this.getActiveTeam().isEmpty()) {
            this.currentCharacterIndex = 0;
            // An empty team gets the player's own main character. This used to add the entity of a
            // fresh, unowned Avatar, which has none: the team then held a null that broke the scene
            // info, ability creation and everything else that walks the team.
            var mainCharacter = this.questParty != null && !this.questParty.getAvatars().isEmpty()
                    ? getActivePartyAvatar(this.questParty.getAvatars().getFirst())
                    : this.player.getAvatars().getAvatarById(this.player.getMainCharacterId());
            var scene = this.player.getScene();
            if (mainCharacter == null || scene == null) return null;
            var entity =
                    EntityCreationEvent.call(
                            EntityAvatar.class,
                            new Class<?>[] {Scene.class, Avatar.class},
                            new Object[] {scene, mainCharacter});
            if (entity != null) this.avatars.add(entity);
            return entity;
        }

        if (this.currentCharacterIndex >= this.getActiveTeam().size()) {
            this.currentCharacterIndex = 0;
        }

        EntityAvatar currentCharacter;

        try {
            currentCharacter = this.getActiveTeam().get(this.currentCharacterIndex);
        } catch (Exception e) {
            currentCharacter = this.getActiveTeam().get(0);
        }

        return currentCharacter;
    }

    public boolean isSpawned() {
        return this.getPlayer().getScene() != null
            && this.getPlayer()
            .getScene()
            .getEntities()
            .containsKey(this.getCurrentAvatarEntity().getId());
    }

    public int getMaxTeamSize() {
        if (this.getPlayer().isInMultiplayer()) {
            int max = GAME_OPTIONS.avatarLimits.multiplayerTeam;
            if (this.getPlayer().getWorld().getHost() == this.getPlayer()) {
                return Math.max(1, (int) Math.ceil(max / (double) this.getWorld().getPlayerCount()));
            }
            return Math.max(1, (int) Math.floor(max / (double) this.getWorld().getPlayerCount()));
        }

        return GAME_OPTIONS.avatarLimits.singlePlayerTeam;
    }

    public boolean canAddAvatarsToTeam(TeamInfo team, int avatars) {
        if (team == this.questParty) return false;
        return team.size() + avatars <= this.getMaxTeamSize();
    }

    public boolean canAddAvatarToTeam(TeamInfo team) {
        return this.canAddAvatarsToTeam(team, 1);
    }

    public boolean canAddAvatarsToCurrentTeam(int avatars) {
        if (isQuestPartyLocked() || this.useTemporarilyTeamIndex != -1) {
            return false;
        }
        return this.canAddAvatarsToTeam(this.getCurrentTeamInfo(), avatars);
    }

    public boolean canAddAvatarToCurrentTeam() {
        return this.canAddAvatarsToCurrentTeam(1);
    }

    public boolean addAvatarsToTeam(TeamInfo team, Collection<Avatar> avatars) {
        if (avatars.stream().anyMatch(TpsAvatarSystem::isTpsAvatar)
                || !this.canAddAvatarsToTeam(team, avatars.size())) {
            return false;
        }

        team.getAvatars().addAll(avatars.stream().map(a -> a.getAvatarId()).toList());

        if (this.getPlayer().isInMultiplayer()) {
            if (team.equals(this.getMpTeam())) {

                this.updateTeamEntities(new PacketChangeMpTeamAvatarRsp(this.getPlayer(), team));
            }
        } else {

            this.getPlayer().sendPacket(new PacketAvatarTeamUpdateNotify(this.getPlayer()));

            int teamId = this.getTeamId(team);
            if (teamId != -1) {

                if (teamId == this.getCurrentTeamId()) {
                    this.updateTeamEntities(new PacketSetUpAvatarTeamRsp(this.getPlayer(), teamId, team));
                } else {
                    this.getPlayer().sendPacket(new PacketSetUpAvatarTeamRsp(this.getPlayer(), teamId, team));
                }
            }
        }

        return true;
    }

    public boolean addAvatarToTeam(TeamInfo team, Avatar avatar) {
        return this.addAvatarsToTeam(team, Collections.singleton(avatar));
    }

    public boolean addAvatarsToCurrentTeam(Collection<Avatar> avatars) {
        if (isQuestPartyLocked() || this.useTemporarilyTeamIndex != -1) {
            return false;
        }
        return this.addAvatarsToTeam(this.getCurrentTeamInfo(), avatars);
    }

    public boolean addAvatarToCurrentTeam(Avatar avatar) {
        return this.addAvatarsToCurrentTeam(Collections.singleton(avatar));
    }

    private void updateTeamResonances() {
        this.getTeamResonances().clear();
        this.getTeamResonancesConfig().clear();

        if (this.avatars.size() < 4) return;

        var elementCounts = new Object2IntOpenHashMap<ElementType>();
        this.getActiveTeam().stream()
            .map(EntityAvatar::getAvatar)
            .filter(Objects::nonNull)
            .map(Avatar::getSkillDepot)
            .filter(Objects::nonNull)
            .map(AvatarSkillDepotData::getElementType)
            .filter(Objects::nonNull)
            .forEach(elementType -> elementCounts.addTo(elementType, 1));

        elementCounts.object2IntEntrySet().stream()
            .filter(e -> e.getIntValue() >= 2)
            .map(e -> e.getKey())
            .filter(elementType -> elementType.getTeamResonanceId() != 0)
            .forEach(
                elementType -> {
                    this.teamResonances.add(elementType.getTeamResonanceId());
                    this.teamResonancesConfig.add(elementType.getConfigHash());
                });

        if (elementCounts.size() >= 4) {
            this.teamResonances.add(ElementType.Default.getTeamResonanceId());
            this.teamResonancesConfig.add(ElementType.Default.getConfigHash());
        }
    }

    public void updateTeamProperties() {
        this.updateTeamResonances();
        this.getWorld()
            .broadcastPacket(
                new PacketSceneTeamUpdateNotify(
                    this.getPlayer()));

        this.getActiveTeam().stream()
            .map(EntityAvatar::getAvatar)
            .forEach(Avatar::sendSkillExtraChargeMap);

        this.sendMoonsignState();

        long hexenzirkelCount = this.getActiveTeam().stream()
            .filter(e -> PacketPlayerEnterSceneInfoNotify.getHexenzirkelIds().contains(e.getAvatar().getAvatarId()))
            .count();
        this.getPlayer().sendPacket(new PacketServerGlobalValueChangeNotify(
            this.getEntity().getId(), "SGV_HexenzirkelLevel", (float) hexenzirkelCount));
        this.getPlayer().sendPacket(new PacketTeamHexenzirkelChangeNotify((int) hexenzirkelCount));
    }

    /** How many Moonsign characters the active team is fielding - the client's Moonsign level. */
    public int getMoonsignLevel() {
        return (int) this.getActiveTeam().stream()
            .filter(e -> PacketPlayerEnterSceneInfoNotify.getMoonphaseIds().contains(e.getAvatar().getAvatarId()))
            .count();
    }

    /**
     * Pushes the Moonsign level at the client. Sent on every team change, and again whenever a
     * Moonsign ability finishes loading: the client only wires the level up once its ability
     * exists, so a level sent before that is dropped on the floor.
     */
    public void sendMoonsignState() {
        int moonsignLevel = emu.grasscutter.game.quest.QuestFreeMoonPhase.current(this.getPlayer()).level();
        emu.grasscutter.game.quest.QuestFreeMoonPhase.sendState(this.getPlayer(), false);

        // Seeds the party's Verdant Dew. The client owns the value from here on and mirrors its
        // own updates back through AbilityManager, which is why this is a seed and not a refresh.
        if (moonsignLevel > 0) {
            this.getPlayer().sendPacket(new PacketServerGlobalValueChangeNotify(
                this.getEntity().getId(), VERDANT_DEW, 100f));
        }

    }

    public void updateTeamEntities(BasePacket responsePacket) {

        if (this.getCurrentTeamInfo().getAvatars().size() <= 0) {
            return;
        }

        try {
            // Scrub Dive_Team leftovers before ability rebuild so land team-swap cannot replay ripples.
            emu.grasscutter.game.player.DiveAbilityHelper.onTeamOrSceneRebuild(this.getPlayer());
        } catch (Throwable ignored) {
        }

        var currentEntity = this.getCurrentAvatarEntity();
        var existingAvatars = new Int2ObjectOpenHashMap<EntityAvatar>();
        var prevSelectedAvatarIndex = -1;

        for (EntityAvatar entity : this.getActiveTeam()) {
            if (entity != null) existingAvatars.put(entity.getAvatar().getAvatarId(), entity);
        }

        this.getActiveTeam().clear();

        for (int i = 0; i < this.getCurrentTeamInfo().getAvatars().size(); i++) {
            var avatarId = (int) this.getCurrentTeamInfo().getAvatars().get(i);
            EntityAvatar entity;
            if (existingAvatars.containsKey(avatarId)) {
                entity = existingAvatars.get(avatarId);
                existingAvatars.remove(avatarId);
                if (entity == currentEntity) {
                    prevSelectedAvatarIndex = i;
                }
            } else {
                var player = this.getPlayer();
                entity =
                    EntityCreationEvent.call(
                        EntityAvatar.class,
                        new Class<?>[] {Scene.class, Avatar.class},
                        new Object[] {player.getScene(), getActivePartyAvatar(avatarId)});
            }

            // An id with no owned avatar behind it (a trial avatar's) gives no entity.
            if (entity != null) this.getActiveTeam().add(entity);
        }

        for (var entity : existingAvatars.values()) {
            if (entity == currentEntity) {
                entity.getAvatar().save();
                continue;
            }
            this.getPlayer().getScene().removeEntity(entity);
            entity.getAvatar().save();
        }

        if (prevSelectedAvatarIndex == -1) {

            prevSelectedAvatarIndex =
                Math.min(this.currentCharacterIndex, this.getActiveTeam().size() - 1);
        }
        this.currentCharacterIndex = prevSelectedAvatarIndex;

        this.updateTeamProperties();

        try {
            emu.grasscutter.game.avatar.SkirkTeamBonusHelper.onTeamChanged(this.getPlayer());
        } catch (Throwable ignored) {
        }
        try {
            emu.grasscutter.game.avatar.TartagliaTeamBonusHelper.onTeamChanged(this.getPlayer());
        } catch (Throwable ignored) {
        }

        // Re-attach dive abilities after new avatar entities exist (underwater team swap).
        try {
            emu.grasscutter.game.player.DiveAbilityHelper.onTeamOrSceneRebuild(this.getPlayer());
        } catch (Throwable ignored) {
        }

        if (responsePacket != null) {
            this.getPlayer().sendPacket(responsePacket);
        }

        checkCurrentAvatarIsAlive(currentEntity);
    }

    public void checkCurrentAvatarIsAlive(EntityAvatar currentEntity) {
        if (currentEntity == null) {
            currentEntity = this.getCurrentAvatarEntity();
        }

        if (!this.getActiveTeam().get(this.currentCharacterIndex).isAlive()) {

            int replaceIndex = getDeadAvatarReplacement();
            if (0 <= replaceIndex && replaceIndex < this.getActiveTeam().size()) {
                this.currentCharacterIndex = replaceIndex;
            } else {

                this.currentCharacterIndex = 0;
                this.reviveAvatar(this.getCurrentAvatarEntity().getAvatar());
            }
        }

        var newAvatarEntity = this.getCurrentAvatarEntity();
        if (currentEntity != null && newAvatarEntity != null && currentEntity != newAvatarEntity) {

            var event =
                new PlayerSwitchAvatarEvent(
                    this.getPlayer(), currentEntity.getAvatar(), newAvatarEntity.getAvatar());
            if (!event.call()) return;

            this.getPlayer().getScene().replaceEntity(currentEntity, newAvatarEntity);
        }
    }

    public synchronized void setupAvatarTeam(int teamId, List<Long> list) {
        if (isQuestPartyLocked()) {
            this.getPlayer().sendPacket(new PacketQuestPartyUpdateNotify(this.getPlayer()));
            return;
        }
        // Must look up by teamId. An old bug used get(list.size()) / get(team.size()) and always wrote team 1.
        TeamInfo teamInfo = this.getTeams().get(teamId);

        // Always unblock client UI with SetUpAvatarTeamRsp, even on failure / empty team.
        if (list == null
                || list.isEmpty()
                || list.size() > this.getMaxTeamSize()
                || this.getPlayer().isInMultiplayer()
                || teamInfo == null) {
            if (teamInfo != null) {
                this.getPlayer()
                        .sendPacket(new PacketSetUpAvatarTeamRsp(this.getPlayer(), teamId, teamInfo));
            } else {
                this.getPlayer().sendPacket(new PacketAvatarTeamUpdateNotify(this.getPlayer()));
            }
            return;
        }

        LinkedHashSet<Avatar> newTeam = new LinkedHashSet<>();
        for (Long aLong : list) {
            Avatar avatar = this.getPlayer().getAvatars().getAvatarByGuid(aLong);
            if (avatar == null || TpsAvatarSystem.isTpsAvatar(avatar) || newTeam.contains(avatar)) {
                this.getPlayer()
                        .sendPacket(new PacketSetUpAvatarTeamRsp(this.getPlayer(), teamId, teamInfo));
                return;
            }
            newTeam.add(avatar);
        }

        teamInfo.getAvatars().clear();
        this.addAvatarsToTeam(teamInfo, newTeam);

        try {
            this.getPlayer().save();
        } catch (Throwable ignored) {
        }
    }

    public void setupMpTeam(List<Long> list) {
        if (isQuestPartyLocked()) return;

        if (list.size() == 0
            || list.size() > this.getMaxTeamSize()
            || !this.getPlayer().isInMultiplayer()) {
            return;
        }

        TeamInfo teamInfo = this.getMpTeam();

        LinkedHashSet<Avatar> newTeam = new LinkedHashSet<>();
        for (Long aLong : list) {
            Avatar avatar = this.getPlayer().getAvatars().getAvatarByGuid(aLong);
            if (avatar == null || TpsAvatarSystem.isTpsAvatar(avatar) || newTeam.contains(avatar)) {

                return;
            }
            newTeam.add(avatar);
        }

        teamInfo.getAvatars().clear();
        this.addAvatarsToTeam(teamInfo, newTeam);
    }

    public void setupTrialAvatars(boolean save) {
        // Repeated quest grants must not replace the original roster with one containing trials.
        if (!this.isUsingTrialTeam()) {
            this.setPreviousIndex(this.getCurrentCharacterIndex());
            this.getTrialAvatarTeam().copyFrom(this.getCurrentTeamInfo());
            // Old trial cleanup could persist an owned fifth avatar in a normal four-slot team.
            this.getCurrentTeamInfo().copyFrom(this.getTrialAvatarTeam());
            var originalIds = new HashSet<>(this.getTrialAvatarTeam().getAvatars());
            this.getActiveTeam().removeIf(entity -> {
                if (originalIds.contains(entity.getAvatar().getAvatarId())) return false;
                if (this.getPlayer().getScene() != null)
                    this.getPlayer().getScene().removeEntity(
                        entity, VisionTypeOuterClass.VisionType.VisionType_VISION_REMOVE);
                return true;
            });
        }
        if (!save) this.getActiveTeam().clear();
        this.usingTrialTeam = true;
    }

    public void trialAvatarTeamPostUpdate(int newCharacterIndex) {
        this.setCurrentCharacterIndex(Math.min(newCharacterIndex, this.getActiveTeam().size() - 1));

        this.updateTeamProperties();
        if (this.getPlayer().getScene() != null)
            this.getPlayer().getScene().addEntity(this.getCurrentAvatarEntity());
    }

    public void addAvatarToTrialTeam(Avatar trialAvatar) {

        this.getActiveTeam()
            .forEach(
                x ->
                    this.getPlayer()
                        .getScene()
                        .removeEntity(x, VisionTypeOuterClass.VisionType.VisionType_VISION_REMOVE));

        this.getActiveTeam().removeIf(x -> x.getAvatar().getAvatarId() == trialAvatar.getAvatarId());
        this.getCurrentTeamInfo().getAvatars().removeIf(x -> x == trialAvatar.getAvatarId());

        var trialEntity =
                EntityCreationEvent.call(
                    EntityAvatar.class,
                    new Class<?>[] {Scene.class, Avatar.class},
                    new Object[] {player.getScene(), trialAvatar});
        if (trialEntity != null) this.getActiveTeam().add(trialEntity);
        this.getCurrentTeamInfo().addAvatar(trialAvatar);
        this.getTrialAvatars().put(trialAvatar.getAvatarId(), trialAvatar);
    }

    public long getTrialAvatarGuid(int trialAvatarId) {
        return this.getTrialAvatars().values().stream()
            .filter(avatar -> avatar.getTrialAvatarId() == trialAvatarId)
            .map(Avatar::getGuid)
            .findFirst()
            .orElse(0L);
    }

    public void unsetTrialAvatarTeam() {

        var index = this.getPreviousIndex();
        if (index < 0) index = 0;

        this.trialAvatarTeamPostUpdate(index);

        this.setPreviousIndex(-1);
    }

    public void removeTrialAvatarTeam() {
        this.removeTrialAvatarTeam(
            this.getTrialAvatars().values().stream().map(Avatar::getTrialAvatarId).toList());
    }

    public void removeTrialAvatarTeam(int avatarId) {
        this.removeTrialAvatarTeam(List.of(avatarId));
    }

    /** Retire trial identities and restore the saved roster only after the last trial leaves. */
    boolean retireTrialAvatarRoster(List<Integer> trialAvatarIds) {
        var ids = new HashSet<>(trialAvatarIds);
        var removedAvatarIds = this.getTrialAvatars().values().stream()
            .filter(avatar -> ids.contains(avatar.getTrialAvatarId()))
            .map(Avatar::getAvatarId).collect(java.util.stream.Collectors.toSet());
        if (removedAvatarIds.isEmpty()) return false;
        this.getTrialAvatars().values().removeIf(avatar -> ids.contains(avatar.getTrialAvatarId()));
        this.getActiveTeam().removeIf(entity -> ids.contains(entity.getAvatar().getTrialAvatarId()));
        if (!this.getTrialAvatars().isEmpty()) {
            this.getCurrentTeamInfo().getAvatars().removeIf(
                id -> removedAvatarIds.contains(id) && !this.getTrialAvatarTeam().getAvatars().contains(id));
            return false;
        }
        // Ownership cannot distinguish the trial Amber from an owned Amber added as the fifth slot.
        if (this.getTrialAvatarTeam().size() > 0)
            this.getCurrentTeamInfo().copyFrom(this.getTrialAvatarTeam());
        this.usingTrialTeam = false;
        this.trialAvatarTeam = new TeamInfo();
        this.removeUnownedAvatarsFromTeams();
        return true;
    }

    public void removeTrialAvatarTeam(List<Integer> trialAvatarIds) {
        if (this.getTrialAvatars().values().stream()
                .noneMatch(avatar -> trialAvatarIds.contains(avatar.getTrialAvatarId()))) return;
        var player = this.getPlayer();
        var scene = player.getScene();
        if (scene != null) this.getActiveTeam().forEach(entity -> scene.removeEntity(
            entity, VisionTypeOuterClass.VisionType.VisionType_VISION_REMOVE));
        boolean finished = this.retireTrialAvatarRoster(trialAvatarIds);
        this.getActiveTeam().clear();
        if (scene != null) {
            for (int avatarId : this.getCurrentTeamInfo().getAvatars()) {
                var avatar = this.getTrialAvatars().get(avatarId);
                if (avatar == null) avatar = player.getAvatars().getAvatarById(avatarId);
                if (avatar == null) continue;
                var entity = EntityCreationEvent.call(EntityAvatar.class,
                    new Class<?>[] {Scene.class, Avatar.class}, new Object[] {scene, avatar});
                if (entity != null) this.getActiveTeam().add(entity);
            }
            if (finished) this.unsetTrialAvatarTeam();
            else this.trialAvatarTeamPostUpdate(Math.max(0, this.getCurrentCharacterIndex()));
        } else {
            // Logout has already detached the scene; rebuilding entities here cannot succeed.
            this.setCurrentCharacterIndex(Math.max(0, finished ? this.getPreviousIndex() : this.getCurrentCharacterIndex()));
            if (finished) this.setPreviousIndex(-1);
        }
    }

    public void setupTemporaryTeam(List<List<Long>> guidList) {
        this.temporaryTeam =
            guidList.stream()
                .map(
                    list -> {

                        if (list.size() == 0 || list.size() > this.getMaxTeamSize()) {
                            return null;
                        }

                        LinkedHashSet<Avatar> newTeam = new LinkedHashSet<>();
                        for (Long aLong : list) {
                            Avatar avatar = this.getPlayer().getAvatars().getAvatarByGuid(aLong);
                            if (avatar == null || TpsAvatarSystem.isTpsAvatar(avatar) || newTeam.contains(avatar)) {

                                return null;
                            }
                            newTeam.add(avatar);
                        }

                        return newTeam.stream().map(Avatar::getAvatarId).toList();
                    })
                .filter(Objects::nonNull)
                .map(TeamInfo::new)
                .toList();
    }

    /**
     * Whether a temporary party list is currently set up.
     *
     * <p>{@code temporaryTeam} is {@code @Transient}, so it is gone after a relog even though the
     * abyss floor it belonged to is still in progress - callers use this to decide whether to
     * rebuild it before switching halves.
     */
    public boolean hasTemporaryTeam() {
        return this.temporaryTeam != null && !this.temporaryTeam.isEmpty();
    }

    /** How many temporary parties are set up; {@code 0} when there are none. */
    public int getTemporaryTeamCount() {
        return this.temporaryTeam == null ? 0 : this.temporaryTeam.size();
    }

    public void useTemporaryTeam(int index) {
        this.useTemporarilyTeamIndex = index;
        this.updateTeamEntities(null);
    }

    public boolean cleanTemporaryTeam() {

        if (useTemporarilyTeamIndex < 0) {
            return false;
        }

        this.useTemporarilyTeamIndex = -1;
        this.temporaryTeam = null;
        this.updateTeamEntities(null);
        return true;
    }

    public synchronized void setCurrentTeam(int teamId) {
        if (isQuestPartyLocked()) {
            this.getPlayer().sendPacket(new PacketQuestPartyUpdateNotify(this.getPlayer()));
            return;
        }

        if (this.getPlayer().isInMultiplayer()) {
            return;
        }

        TeamInfo teamInfo = this.getTeams().get(teamId);
        if (teamInfo == null || teamInfo.getAvatars().size() == 0) {
            return;
        }

        this.setCurrentTeamId(teamId);
        this.updateTeamEntities(new PacketChooseCurAvatarTeamRsp(teamId));

        // ChooseCurAvatarTeamRsp has no 7.1 CmdId (PacketOpcodes gives it 0), and GameSession.send()
        // drops any packet whose opcode is <= 0 - so the client never learns the active team changed.
        // It keeps showing the previous team as 出战, refuses to switch back to it, and shows no
        // confirmation; only a relogin repairs the view, because the full team data goes out on
        // login. Re-send the same notify that login and add/removeCustomTeam already use, which
        // does have a 7.1 CmdId and carries cur_avatar_team_id.
        this.getPlayer().sendPacket(new PacketAvatarTeamAllDataNotify(this.getPlayer()));
        // Also nudge the team-config screen: AvatarTeamUpdateNotify is what the other team
        // operations (reorder, trial avatar) use to get that screen to redraw.
        this.getPlayer().sendPacket(new PacketAvatarTeamUpdateNotify(this.getPlayer()));
    }

    public synchronized void setTeamName(int teamId, String teamName) {

        TeamInfo teamInfo = this.getTeams().get(teamId);
        if (teamInfo == null) {
            return;
        }

        teamInfo.setName(teamName);

        this.getPlayer().sendPacket(new PacketChangeTeamNameRsp(teamId, teamName));
    }

    public synchronized void changeAvatar(long guid) {
        EntityAvatar oldEntity = this.getCurrentAvatarEntity();
        if (oldEntity == null) {
            return;
        }
        if (guid == oldEntity.getAvatar().getGuid()) {
            // Already on the field: still answer, or the client waits on a switch it never gets.
            this.getPlayer().sendPacket(new PacketChangeAvatarRsp(guid));
            return;
        }

        EntityAvatar newEntity = null;
        int index = -1;
        for (int i = 0; i < this.getActiveTeam().size(); i++) {
            if (guid == this.getActiveTeam().get(i).getAvatar().getGuid()) {
                index = i;
                newEntity = this.getActiveTeam().get(i);
            }
        }

        if (index < 0 || newEntity == oldEntity) {
            // Requested avatar is not on the active team (stale client state after a team edit or a
            // reconnect). Answer with the avatar still on the field so the client stops retrying -
            // leaving it unanswered made the client resend ChangeAvatarReq several times a second.
            this.getPlayer().sendPacket(new PacketChangeAvatarRsp(oldEntity.getAvatar().getGuid()));
            return;
        }
        if (newEntity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP) <= 0) {
            // Server and client HP can disagree for a moment; answer with the avatar still on the
            // field so the client is not left waiting on the switch.
            this.getPlayer().sendPacket(new PacketChangeAvatarRsp(oldEntity.getAvatar().getGuid()));
            return;
        }

        var event =
            new PlayerSwitchAvatarEvent(this.getPlayer(), oldEntity.getAvatar(), newEntity.getAvatar());
        if (!event.call()) return;

        newEntity = event.getNewAvatarEntity();

        this.setCurrentCharacterIndex(index);

        oldEntity.setMotionState(MotionState.MotionState_MOTION_STANDBY);

        this.getPlayer().getScene().replaceEntity(oldEntity, newEntity);
        // Re-sync elemental energy after VISION_REPLACE so client does not keep a
        // stale/predicted burst-ready state that disagrees with server props.
        syncAvatarEnergyToClient(newEntity);
        this.getPlayer().sendPacket(new PacketChangeAvatarRsp(guid));
    }

    private void syncAvatarEnergyToClient(EntityAvatar entity) {
        if (entity == null || entity.getAvatar() == null || entity.getAvatar().getSkillDepot() == null) {
            return;
        }
        var depot = entity.getAvatar().getSkillDepot();
        if (depot.getElementType() == null) {
            return;
        }
        var curEnergyProp = depot.getElementType().getCurEnergyProp();
        this.getPlayer().sendPacket(new PacketAvatarFightPropUpdateNotify(entity.getAvatar(), curEnergyProp));
        this.getPlayer().sendPacket(new PacketEntityFightPropUpdateNotify(entity, curEnergyProp));
    }

    public void applyVoidDamage() {
        this.getActiveTeam()
            .forEach(
                entity -> {
                    entity.damage(entity.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP) * .1f);
                    player.sendPacket(new PacketAvatarLifeStateChangeNotify(entity.getAvatar()));
                });
    }

    public void onAvatarDie(long dieGuid) {
        EntityAvatar deadAvatar = this.getCurrentAvatarEntity();
        if (deadAvatar == null || deadAvatar.getId() != dieGuid) return;

        // Already rescued by Barbara C6 in killEntity - do not switch characters.
        float curHp = deadAvatar.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP);
        if (deadAvatar.isAlive() && curHp > 0f) {
            this.getPlayer().sendPacket(new PacketAvatarDieAnimationEndRsp(deadAvatar.getId(), 0));
            return;
        }

        // Fallback for deaths that did not go through the revive logic in Scene.killEntity.
        try {
            if (PartyReviveHelper.tryBarbaraC6AfterDeath(deadAvatar)) {
                Scene scene = this.getPlayer().getScene();
                if (scene != null && scene.getEntityById(deadAvatar.getId()) == null) {
                    scene.addEntity(deadAvatar);
                }
                this.getPlayer().sendPacket(new PacketAvatarDieAnimationEndRsp(deadAvatar.getId(), 0));
                return;
            }
        } catch (Throwable t) {
            Grasscutter.getLogger().warn("[BarbaraC6] onAvatarDie revive failed", t);
        }

        PlayerDieType dieType = deadAvatar.getKilledType();
        int killedBy = deadAvatar.getKilledBy();

        if (dieType == PlayerDieType.PlayerDieType_PLAYER_DIE_DRAWN) {

            this.getPlayer().sendPacket(new PacketWorldPlayerDieNotify(dieType, killedBy));
        } else {

            int replaceIndex = getDeadAvatarReplacement();
            if (0 <= replaceIndex && replaceIndex < this.getActiveTeam().size()) {
                EntityAvatar next = this.getActiveTeam().get(replaceIndex);
                // Keep combat continuous: manual changeAvatar uses VISION_REPLACE.
                // Death used to only addEntity (VISION_MEET) after the corpse was removed,
                // so monsters briefly lost their target and dropped out of battle.
                try {
                    if (next.getPosition() != null && deadAvatar.getPosition() != null) {
                        next.getPosition().set(deadAvatar.getPosition());
                    }
                    if (next.getRotation() != null && deadAvatar.getRotation() != null) {
                        next.getRotation().set(deadAvatar.getRotation());
                    }
                } catch (Throwable ignored) {
                }
                this.setCurrentCharacterIndex(replaceIndex);
                Scene scene = this.getPlayer().getScene();
                if (scene != null && scene.getEntityById(deadAvatar.getId()) != null) {
                    scene.replaceEntity(deadAvatar, next);
                } else if (scene != null) {
                    scene.addEntity(next);
                }
                reAlertMonstersAfterDeathSwap(next);
            } else {

                this.getPlayer().sendPacket(new PacketWorldPlayerDieNotify(dieType, killedBy));

                PlayerTeamDeathEvent event =
                    new PlayerTeamDeathEvent(
                        this.getPlayer(), this.getActiveTeam().get(this.getCurrentCharacterIndex()));
                event.call();
            }
        }

        this.getPlayer().sendPacket(new PacketAvatarDieAnimationEndRsp(deadAvatar.getId(), 0));
    }

    /**
     * After a death-swap, force nearby / already-engaged monsters back into battle so the
     * client does not flash an out-of-combat idle when the avatar entity id changes.
     */
    private void reAlertMonstersAfterDeathSwap(EntityAvatar next) {
        try {
            Scene scene = this.getPlayer().getScene();
            if (scene == null || next == null || next.getPosition() == null) {
                return;
            }
            Player player = this.getPlayer();
            for (GameEntity ge : new ArrayList<>(scene.getEntities().values())) {
                if (!(ge instanceof EntityMonster em) || !em.isAlive() || em.getPosition() == null) {
                    continue;
                }
                boolean engaged =
                        em.getPlayerOnBattle() != null && em.getPlayerOnBattle().contains(player);
                boolean near = em.getPosition().computeDistance(next.getPosition()) <= 45.0;
                if (!engaged && !near) {
                    continue;
                }
                if (em.getPlayerOnBattle() != null && !em.getPlayerOnBattle().contains(player)) {
                    em.getPlayerOnBattle().add(player);
                }
                scene.broadcastPacket(new PacketMonsterForceAlertNotify(em.getId()));
            }
        } catch (Throwable ignored) {
        }
    }

    public int getDeadAvatarReplacement() {
        int replaceIndex = -1;

        for (int i = 0; i < this.getActiveTeam().size(); i++) {
            EntityAvatar entity = this.getActiveTeam().get(i);
            if (entity.isAlive()) {
                replaceIndex = i;
                break;
            }
        }

        return replaceIndex;
    }

    public boolean reviveAvatar(Avatar avatar) {
        for (EntityAvatar entity : this.getActiveTeam()) {
            if (entity.getAvatar() == avatar) {
                if (entity.isAlive()) {
                    return false;
                }

                // Uses EntityAvatar.reviveToRatio() rather than setting CUR_HP here: that method
                // clears the dead flag as well, which this one used to skip - leaving isAlive()
                // false, so every later heal (the statue's included) refused the character and it
                // sat at 1 HP until a teleport rebuilt the entity.
                if (entity.reviveToRatio(0f) <= 0f) {
                    return false;
                }

                player.getSatiationManager().removeSatiationDirectly(entity.getAvatar(), 15000);
                return true;
            }
        }

        return false;
    }

    /** Native OptionalRevivalTeamType: none, full HP, full energy, full HP and energy. */
    public boolean optionalRevivalTeam(int mode) {
        if (mode < 0 || mode > 3) return false;
        if (mode == 0) return true;
        boolean fullHp = (mode & 1) != 0;
        boolean fullEnergy = (mode & 2) != 0;
        Map<Avatar, EntityAvatar> members = new LinkedHashMap<>();
        for (int id : this.getCurrentTeamInfo().getAvatars()) {
            var avatar = this.getActivePartyAvatar(id);
            if (avatar == null || fullHp && avatar.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP) <= 0f)
                return false;
            var entity = this.getActiveTeam().stream().filter(member -> member.getAvatar() == avatar)
                    .findFirst().orElse(null);
            members.put(avatar, entity);
        }
        if (members.isEmpty()) return false;
        for (var member : members.entrySet()) {
            var avatar = member.getKey();
            var entity = member.getValue();
            var updates = new HashMap<Integer, Float>();
            if (fullHp) {
                float maxHp = avatar.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
                if (entity != null && (!entity.isAlive()
                        || entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP) <= 0f)) entity.reviveToRatio(1f);
                avatar.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, maxHp);
                avatar.setCurrentHp(maxHp);
                updates.put(FightProperty.FIGHT_PROP_CUR_HP.getId(), maxHp);
            }
            if (fullEnergy && avatar.getSkillDepot() != null
                    && avatar.getSkillDepot().getEnergySkillData() != null) {
                var depot = avatar.getSkillDepot();
                boolean special = depot.getEnergySkillData().getSpecialEnergyMin() > 0;
                var current = special ? FightProperty.FIGHT_PROP_CUR_SPECIAL_ENERGY
                        : depot.getElementType().getCurEnergyProp();
                var maximum = special ? FightProperty.FIGHT_PROP_MAX_SPECIAL_ENERGY
                        : depot.getElementType().getMaxEnergyProp();
                float maxEnergy = avatar.getFightProperty(maximum);
                avatar.setCurrentEnergy(current, maxEnergy);
                // Energy-usage configuration may suppress the persisted helper, but quest refill is explicit.
                avatar.setFightProperty(current, maxEnergy);
                updates.put(current.getId(), maxEnergy);
            }
            avatar.save();
            if (this.getPlayer().hasSentLoginPackets()) {
                this.getPlayer().sendPacket(new PacketAvatarFightPropUpdateNotify(avatar, updates));
                if (fullHp) this.getPlayer().sendPacket(new PacketAvatarLifeStateChangeNotify(avatar));
                if (entity != null && entity.getScene() != null)
                    entity.getScene().broadcastPacket(new PacketEntityFightPropUpdateNotify(entity,
                            updates.keySet().stream().map(FightProperty::getPropById).toList()));
            }
        }
        var current = this.getCurrentAvatarEntity();
        var scene = this.getPlayer().getScene();
        if (scene != null && current != null && current.isAlive()
                && this.getPlayer().getSceneLoadState() == Player.SceneLoadState.LOADED
                && !scene.isInScene(current)) scene.addEntity(current);
        return true;
    }

    /**
     * Tops up every avatar the player owns, not just the active team.
     *
     * <p>The active team is healed through their live entities so the client sees the change
     * immediately; everyone else is healed on the persisted {@code currentHp}, because an avatar
     * that is not in the team has no entity in the scene. Without this, avatars that were granted
     * and levelled through GM commands keep the low HP they were created with forever — no command
     * and no statue would ever touch them, since both only walk the active team.
     */
    public int healAllAvatars() {
        var team = this.getActiveTeam();
        var inTeam = new java.util.HashSet<emu.grasscutter.game.avatar.Avatar>();
        for (EntityAvatar entity : team) {
            inTeam.add(entity.getAvatar());
            var maxHp = entity.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
            var curHp = entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP);
            if (curHp >= maxHp) continue;
            entity.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, maxHp);
            if (entity.getWorld() != null) {
                entity.getWorld()
                        .broadcastPacket(
                                new PacketAvatarFightPropUpdateNotify(
                                        entity.getAvatar(), FightProperty.FIGHT_PROP_CUR_HP));
                entity.getWorld()
                        .broadcastPacket(new PacketAvatarLifeStateChangeNotify(entity.getAvatar()));
            }
        }

        int healedOffTeam = 0;
        for (var avatar : this.getPlayer().getAvatars().getAvatars().values()) {
            if (inTeam.contains(avatar)) continue;
            var maxHp = avatar.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
            if (maxHp <= 0f || avatar.getCurrentHp() >= maxHp) continue;
            avatar.setCurrentHp(maxHp);
            avatar.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, maxHp);
            healedOffTeam++;
        }
        if (healedOffTeam > 0) this.getPlayer().save();
        return healedOffTeam;
    }

    public boolean healAvatar(Avatar avatar, int healRate, int healAmount) {        for (EntityAvatar entity : this.getActiveTeam()) {
            if (entity.getAvatar() == avatar) {
                if (!entity.isAlive()) {
                    return false;
                }

                entity.setFightProperty(
                    FightProperty.FIGHT_PROP_CUR_HP,
                    (float)
                        Math.min(
                            (entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP)
                                + entity.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP)
                                * (float) healRate
                                / 100.0
                                + (float) healAmount / 100.0),
                            entity.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP)));
                this.getPlayer()
                    .sendPacket(
                        new PacketAvatarFightPropUpdateNotify(
                            entity.getAvatar(), FightProperty.FIGHT_PROP_CUR_HP));
                this.getPlayer().sendPacket(new PacketAvatarLifeStateChangeNotify(entity.getAvatar()));
                return true;
            }
        }
        return false;
    }

    public void respawnTeam() {

        this.getPlayer()
            .getStaminaManager()
            .stopSustainedStaminaHandler();

        for (EntityAvatar entity : this.getActiveTeam()) {
            if (entity.isAlive()) {
                entity.setFightProperty(
                    FightProperty.FIGHT_PROP_CUR_HP,
                    entity.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP) * .4f);
            } else {
                entity.reviveToRatio(.4f);
            }
            this.getPlayer().getSatiationManager().removeSatiationDirectly(entity.getAvatar(), 15000);
            this.getPlayer()
                .sendPacket(
                    new PacketAvatarFightPropUpdateNotify(
                        entity.getAvatar(), FightProperty.FIGHT_PROP_CUR_HP));
            this.getPlayer().sendPacket(new PacketAvatarLifeStateChangeNotify(entity.getAvatar()));
        }

        try {
            Position reviveAt = this.getRespawnPosition();
            this.getPlayer()
                .sendPacket(
                    new PacketPlayerEnterSceneNotify(
                        this.getPlayer(),
                        EnterType.EnterType_ENTER_SELF,
                        EnterReason.Revival,
                        this.getPlayer().getSceneId(),
                        reviveAt));
            this.getPlayer().sendPacket(new PacketEnterScenePeerNotify(this.getPlayer()));
            this.getPlayer().getPosition().set(reviveAt);
        } catch (Exception e) {
            // Stay near the death position - never dump the player on the world-start beach.
            Grasscutter.getLogger()
                    .warn("respawnTeam notify failed uid={}: {}", this.getPlayer().getUid(), e.toString());
            Position fallback = this.getPlayer().getPosition().clone();
            this.getPlayer()
                .sendPacket(
                    new PacketPlayerEnterSceneNotify(
                        this.getPlayer(),
                        EnterType.EnterType_ENTER_SELF,
                        EnterReason.Revival,
                        this.getPlayer().getSceneId(),
                        fallback));
            this.getPlayer().sendPacket(new PacketEnterScenePeerNotify(this.getPlayer()));
            this.getPlayer().getPosition().set(fallback);
        }
        // HandlerWorldPlayerReviveReq sends the one WorldPlayerReviveRsp after this returns.
    }

    public Position getRespawnPosition() {
        // Prefer nearest unlocked teleport waypoint (TransPointNormal / SceneTransPoint).
        // Old filter only accepted exact "SceneTransPoint", which matches nothing in 7.0
        // scene3 data ($type=TransPointNormal) made Optional.get() throw, landing on the START_POSITION beach.
        try {
            return RespawnPositionHelper.find(this.getPlayer());
        } catch (Throwable t) {
            Grasscutter.getLogger()
                    .warn("getRespawnPosition helper failed uid={}: {}", this.getPlayer().getUid(), t.toString());
            return this.getPlayer().getPosition().clone();
        }
    }

    public void saveAvatars() {

        for (EntityAvatar entity : this.getActiveTeam()) {
            entity.getAvatar().save();
        }
    }

    public void onPlayerLogin() {
        this.updateTeamResonances();
        try {
            emu.grasscutter.game.avatar.SkirkTeamBonusHelper.onPlayerLogin(this.getPlayer());
        } catch (Throwable ignored) {
        }
        try {
            emu.grasscutter.game.avatar.TartagliaTeamBonusHelper.onPlayerLogin(this.getPlayer());
        } catch (Throwable ignored) {
        }
    }

    public synchronized void addNewCustomTeam() {

        if (this.teams.size() == GameConstants.MAX_TEAMS) {
            player.sendPacket(new PacketAddBackupAvatarTeamRsp(Retcode.RET_FAIL));
            return;
        }

        int id = -1;
        for (int i = 5; i <= GameConstants.MAX_TEAMS; i++) {
            if (!this.teams.containsKey(i)) {
                id = i;
                break;
            }
        }

        this.teams.put(id, new TeamInfo());

        player.sendPacket(new PacketAvatarTeamAllDataNotify(player));
        player.sendPacket(new PacketAddBackupAvatarTeamRsp());
    }

    public synchronized void removeCustomTeam(int id) {

        if (!this.teams.containsKey(id)) {
            player.sendPacket(new PacketDelBackupAvatarTeamRsp(Retcode.RET_FAIL, id));
            // Without this the failure reply is followed by the success path below, so a team that
            // does not exist is reported as dissolved anyway.
            return;
        }

        this.teams.remove(id);

        player.sendPacket(new PacketAvatarTeamAllDataNotify(player));
        player.sendPacket(new PacketDelBackupAvatarTeamRsp(id));
    }

    public void applyAbilities(Scene scene) {
        try {
            var levelEntityConfig = scene.getSceneData().getLevelEntityConfig();
            var config = GameData.getConfigLevelEntityDataMap().get(levelEntityConfig);
            if (config == null) return;

            var avatars = this.getPlayer().getAvatars();
            var avatarIds = scene.getSceneData().getSpecifiedAvatarList();
            var specifiedAvatarList = this.getActiveTeam();

            if (avatarIds != null && avatarIds.size() > 0) {

                specifiedAvatarList.clear();
                for (int id : avatarIds) {
                    var avatar = avatars.getAvatarById(id);
                    if (avatar == null) continue;

                    var entity =
                        EntityCreationEvent.call(
                            EntityAvatar.class,
                            new Class<?>[] {Scene.class, Avatar.class},
                            new Object[] {scene, avatar});
                    if (entity != null) specifiedAvatarList.add(entity);
                }
            }

            for (var entityAvatar : specifiedAvatarList) {
                var avatarData = entityAvatar.getAvatar().getAvatarData();
                if (avatarData == null) {
                    continue;
                }

                avatarData.buildEmbryo();
                if (config.getAvatarAbilities() == null) {
                    continue;

                }

                for (var abilities : config.getAvatarAbilities()) {
                    avatarData.getAbilities().add(Utils.abilityHash(abilities.getAbilityName()));
                }
            }
        } catch (Exception e) {
            Grasscutter.getLogger()
                .error(
                    "Error applying level entity config for scene {}", scene.getSceneData().getId(), e);
        }
    }

    public List<Integer> getTrialAvatarParam(int trialAvatarId) {
        var custom = GameData.getTrialAvatarCustomData().get(trialAvatarId);
        var base = GameData.getTrialAvatarDataMap().get(trialAvatarId);
        return selectTrialAvatarParams(
                custom != null ? custom.getTrialAvatarParamList() : null,
                base != null ? base.getTrialAvatarParamList() : null);
    }

    /**
     * Activity trial-avatar configuration is sparse. A custom entry for a
     * different event must not suppress the quest's original trial avatar.
     */
    static List<Integer> selectTrialAvatarParams(
            List<String> customParams, List<Integer> nativeParams) {
        if (customParams != null && !customParams.isEmpty()) {
            var first = customParams.get(0);
            if (first != null && !first.isBlank()) {
                return Stream.of(first.split(";")).map(Integer::parseInt).toList();
            }
        }
        return nativeParams != null ? nativeParams : List.of();
    }

    public boolean addTrialAvatar(int avatarId, int questMainId, GrantReason reason) {
        List<Integer> trialAvatarBasicParam = getTrialAvatarParam(avatarId);
        if (trialAvatarBasicParam.isEmpty()) return false;

        var avatar = new Avatar(trialAvatarBasicParam.get(0));
        if (avatar.getAvatarData() == null || !this.getPlayer().hasSentLoginPackets()) return false;

        avatar.setOwner(this.getPlayer());

        avatar.setTrialAvatarInfo(trialAvatarBasicParam.get(1), avatarId, reason, questMainId);
        avatar.equipTrialItems();
        TpsAvatarSystem.onTrialAvatarCreated(avatar);
        if (TpsAvatarSystem.isTpsAvatar(avatar) && avatar.getTpsWeaponIds().isEmpty()) return false;

        avatar.recalcStats();

        this.getPlayer().sendPacket(new PacketAvatarAddNotify(avatar, false));

        this.addAvatarToTrialTeam(avatar);
        return true;
    }

    public void addTrialAvatar(int avatarId, int questMainId) {
        this.addTrialAvatars(List.of(avatarId), questMainId, true);

        this.getPlayer().sendPacket(new PacketAvatarTeamUpdateNotify(this.getPlayer()));
    }

    public void addTrialAvatars(List<Integer> avatarIds) {
        this.addTrialAvatars(avatarIds, 0, false);
    }

    public void addTrialAvatars(List<Integer> avatarIds, boolean save) {
        this.addTrialAvatars(avatarIds, 0, save);
    }

    public void addTrialAvatars(List<Integer> trialAvatarIds, int questId, boolean save) {
        this.setupTrialAvatars(save);

        trialAvatarIds.forEach(
            trialAvatarId -> {
                var result =
                    this.addTrialAvatar(
                        trialAvatarId,
                        questId,
                        questId != 0
                            ? GrantReason.GRANT_REASON_BY_QUEST
                            : GrantReason.GRANT_REASON_BY_TRIAL_AVATAR_ACTIVITY);

                if (!result) throw new RuntimeException("Unable to add trial avatar to team.");
            });

        this.trialAvatarTeamPostUpdate(questId != 0 ? this.getActiveTeam().size() - 1 : 0);
    }

    /** Dungeon/activity cleanup must preserve trial avatars granted by active quests. */
    public void removeTrialAvatar() {
        this.removeTrialAvatar(
            this.getTrialAvatars().values().stream()
                .filter(avatar -> avatar.getGrantReason()
                    == GrantReason.GRANT_REASON_BY_TRIAL_AVATAR_ACTIVITY.getNumber())
                .map(Avatar::getTrialAvatarId).toList());
    }

    public void removeTrialAvatar(int trialAvatarId) {
        this.removeTrialAvatar(List.of(trialAvatarId));
    }

    public void removeTrialAvatar(List<Integer> trialAvatarIds) {
        if (isQuestPartyLocked() && this.questPartyLock.isTrial()) {
            var remaining = this.questPartyLock.getAvatarIds().stream()
                    .filter(id -> !trialAvatarIds.contains(id)).toList();
            if (remaining.size() != this.questPartyLock.getAvatarIds().size()) {
                var lock = this.questPartyLock;
                if (remaining.isEmpty()) {
                    this.unlockQuestParty(lock.getQuestMainId(), 0);
                } else {
                    var removed = getQuestPartyAvatars().stream()
                            .filter(avatar -> trialAvatarIds.contains(avatar.getTrialAvatarId())).toList();
                    if (this.getPlayer().getScene() != null) {
                        this.getActiveTeam().stream()
                                .filter(entity -> removed.contains(entity.getAvatar()))
                                .forEach(entity -> this.getPlayer().getScene().removeEntity(
                                        entity, VisionTypeOuterClass.VisionType.VisionType_VISION_REMOVE));
                    }
                    this.getActiveTeam().removeIf(entity -> removed.contains(entity.getAvatar()));
                    this.questPartyAvatars.values().removeAll(removed);
                    this.questParty = new TeamInfo(new ArrayList<>(this.questPartyAvatars.keySet()));
                    this.questPartyLock = new QuestPartyLock(lock.getQuestMainId(), lock.getKind(), remaining,
                            lock.getPreviousCharacterIndex(), lock.getDungeonId());
                    if (this.getPlayer().hasSentLoginPackets()) this.getPlayer().sendPacket(new PacketAvatarDelNotify(
                            removed.stream().map(Avatar::getGuid).toList()));
                    if (questPartySceneReady()) {
                        this.currentCharacterIndex = Math.max(0, Math.min(this.currentCharacterIndex, this.questParty.size() - 1));
                        this.updateTeamProperties();
                        this.getPlayer().getScene().addEntity(this.getCurrentAvatarEntity());
                        this.getPlayer().sendPacket(new PacketQuestPartyUpdateNotify(this.getPlayer()));
                    }
                    this.getPlayer().save();
                }
            }
        }
        if (!this.isUsingTrialTeam()) return;
        var equippedIds = this.getTrialAvatars().values().stream()
            .map(Avatar::getTrialAvatarId).filter(trialAvatarIds::contains).distinct().toList();
        if (equippedIds.isEmpty()) return;
        this.getPlayer().sendPacket(new PacketAvatarDelNotify(
            equippedIds.stream().map(this::getTrialAvatarGuid).toList()));
        this.removeTrialAvatarTeam(equippedIds);
        this.getPlayer().sendPacket(new PacketAvatarTeamUpdateNotify(this.getPlayer()));
        this.getPlayer().save();
    }
}
