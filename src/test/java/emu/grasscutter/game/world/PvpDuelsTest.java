package emu.grasscutter.game.world;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.Command;
import emu.grasscutter.command.commands.PvpCommand;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.Account;
import emu.grasscutter.game.ability.AbilityManager;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.entity.EntityClientGadget;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.entity.EntityMonster;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.BasePlayerManager;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.player.InvokeHandler;
import emu.grasscutter.game.managers.energy.EnergyManager;
import emu.grasscutter.net.proto.CombatInvocationsNotifyOuterClass.CombatInvocationsNotify;
import emu.grasscutter.net.proto.CombatInvokeEntryOuterClass.CombatInvokeEntry;
import emu.grasscutter.net.proto.CombatTypeArgumentOuterClass.CombatTypeArgument;
import emu.grasscutter.net.proto.EvtBeingHitInfoOuterClass.EvtBeingHitInfo;
import emu.grasscutter.server.packet.send.PacketCombatInvocationsNotify;
import emu.grasscutter.game.props.ElementType;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.AttackResultOuterClass.AttackResult;
import emu.grasscutter.net.proto.AbilityInvokeEntryOuterClass.AbilityInvokeEntry;
import emu.grasscutter.net.proto.AbilityScalarValueEntryOuterClass.AbilityScalarValueEntry;
import emu.grasscutter.net.proto.AbilityStringOuterClass.AbilityString;
import emu.grasscutter.net.proto.EvtCreateGadgetNotifyOuterClass.EvtCreateGadgetNotify;
import emu.grasscutter.net.proto.SceneEntityAppearNotifyOuterClass.SceneEntityAppearNotify;
import emu.grasscutter.net.proto.SceneEntityInfoOuterClass.SceneEntityInfo;
import emu.grasscutter.net.proto.SceneTeamAvatarOuterClass.SceneTeamAvatar;
import emu.grasscutter.net.proto.SceneTeamUpdateNotifyOuterClass.SceneTeamUpdateNotify;
import emu.grasscutter.net.proto.ServerGlobalValueChangeNotifyOuterClass.ServerGlobalValueChangeNotify;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.game.GameSessionManager;
import emu.grasscutter.server.packet.recv.HandlerCombatInvocationsNotify;
import emu.grasscutter.scripts.ScriptVisionManager;
import emu.grasscutter.utils.Utils;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import sun.misc.Unsafe;

final class PvpDuelsTest {
    private RecordingWorld world;
    private RecordingScene scene;
    private RecordingPlayer first, second, outsider;
    private RecordingAvatar attacker, defender;
    private GameServer gameServer;
    private List<Player> trainingBots;

    @BeforeEach
    void playersInOneRealWorldAndScene() throws Exception {
        PvpDuels.disable();
        world = allocate(RecordingWorld.class);
        world.players = new ArrayList<>();
        scene = allocate(RecordingScene.class);
        scene.world = world;
        scene.players = new ArrayList<>();
        scene.entities = new HashMap<>();
        scene.packets = new ArrayList<>();
        set(scene, Scene.class, "scriptVision", new ScriptVisionManager(scene));
        gameServer = allocate(GameServer.class);
        set(gameServer, GameServer.class, "players", new HashMap<Integer, Player>());
        trainingBots = new ArrayList<>();
        first = player(1);
        second = player(2);
        outsider = player(5);
        attacker = avatar(first, 0x200001);
        defender = avatar(second, 0x200002);
        avatar(outsider, 0x200005);
    }

    @AfterEach
    void closeDuel() {
        PvpDuels.clearReporter();
        PvpDuels.disable();
        for (var bot : trainingBots) PvpTestBotRoster.remove(bot);
    }

    @Test
    void oneOwnerCanQueueIndependentRealHitsAgainstSeveralRegisteredBots() throws Exception {
        var firstBot = bot(98, 0x200062);
        var nextBot = bot(99, 0x200063);
        var thirdBot = bot(100, 0x200064);
        var firstTarget = (RecordingAvatar) firstBot.getTeamManager().getCurrentAvatarEntity();
        var nextTarget = (RecordingAvatar) nextBot.getTeamManager().getCurrentAvatarEntity();
        var thirdTarget = (RecordingAvatar) thirdBot.getTeamManager().getCurrentAvatarEntity();
        PvpDuels.enable(first, firstBot);
        var queuedBeforeAddingTarget = hit(attacker.getId(), firstTarget.getId(), 18);
        assertTrue(PvpDuels.reportHit(first, queuedBeforeAddingTarget));
        PvpDuels.enable(first, nextBot);
        PvpDuels.enable(first, thirdBot);
        var nextHit = hit(attacker.getId(), nextTarget.getId(), 29);
        var thirdHit = hit(attacker.getId(), thirdTarget.getId(), 41);
        assertTrue(PvpDuels.reportHit(first, nextHit));
        assertTrue(PvpDuels.reportHit(first, thirdHit));
        apply(first, thirdHit);
        apply(first, queuedBeforeAddingTarget);
        apply(first, nextHit);
        assertEquals(18, firstTarget.lastDamage);
        assertEquals(29, nextTarget.lastDamage);
        assertEquals(41, thirdTarget.lastDamage);
        assertEquals(1, firstTarget.damageCalls);
        assertEquals(1, nextTarget.damageCalls);
        assertEquals(1, thirdTarget.damageCalls);
        assertFalse(PvpDuels.reportHit(second, hit(attacker.getId(), nextTarget.getId(), 90)));
    }

    @Test
    void everyLivingTrainingTargetSharesTheOppositeOwnerTokenAndDeadTargetsDoNotBlockOthers() throws Exception {
        var firstBot = bot(98, 0x200062);
        var nextBot = bot(99, 0x200063);
        var firstTarget = (RecordingAvatar) firstBot.getTeamManager().getCurrentAvatarEntity();
        var nextTarget = (RecordingAvatar) nextBot.getTeamManager().getCurrentAvatarEntity();
        PvpDuels.enable(first, firstBot);
        PvpDuels.enable(first, nextBot);
        float ownerToken = attacker.getGlobalAbilityValues().get(PvpClientTargeting.KEY);
        assertEquals(-ownerToken, firstTarget.getGlobalAbilityValues().get(PvpClientTargeting.KEY));
        assertEquals(-ownerToken, nextTarget.getGlobalAbilityValues().get(PvpClientTargeting.KEY));
        firstTarget.dead = true;
        scene.entities.remove(firstTarget.getId());
        sent(first.session, appearance(nextTarget.getId()));
        assertFalse(firstTarget.getGlobalAbilityValues().containsKey(PvpClientTargeting.KEY));
        assertEquals(ownerToken, attacker.getGlobalAbilityValues().get(PvpClientTargeting.KEY));
        assertEquals(-ownerToken, nextTarget.getGlobalAbilityValues().get(PvpClientTargeting.KEY));
        var hit = hit(attacker.getId(), nextTarget.getId(), 31);
        assertTrue(PvpDuels.reportHit(first, hit));
        apply(first, hit);
        assertEquals(31, nextTarget.lastDamage);
    }

    @Test
    void removingOneTrainingBotPreservesOtherPairsAndOwnerRelogOrOffRevokesTheGroup() throws Exception {
        var firstBot = bot(98, 0x200062);
        var nextBot = bot(99, 0x200063);
        var firstTarget = firstBot.getTeamManager().getCurrentAvatarEntity();
        var nextTarget = nextBot.getTeamManager().getCurrentAvatarEntity();
        PvpDuels.enable(first, firstBot);
        PvpDuels.enable(first, nextBot);
        // Native removal detaches its registry entry before Scene.removePlayer calls this hook.
        PvpTestBotRoster.remove(nextBot);
        PvpDuels.onPlayerLeave(nextBot);
        assertFalse(nextTarget.getGlobalAbilityValues().containsKey(PvpClientTargeting.KEY));
        assertEquals(1, PvpDuels.checkReportedHit(first, hit(attacker.getId(), firstTarget.getId(), 11)));
        assertFalse(PvpDuels.status().contains("未启用"));
        var oldSession = first.session;
        first.session = session(first);
        assertEquals(-1, PvpDuels.checkReportedHit(first, hit(attacker.getId(), firstTarget.getId(), 11)));
        assertFalse(firstTarget.getGlobalAbilityValues().containsKey(PvpClientTargeting.KEY));
        first.session = oldSession;
        PvpDuels.enable(first, firstBot);
        PvpDuels.disable();
        assertFalse(attacker.getGlobalAbilityValues().containsKey(PvpClientTargeting.KEY));
        assertFalse(firstTarget.getGlobalAbilityValues().containsKey(PvpClientTargeting.KEY));
        assertEquals(-1, PvpDuels.checkReportedHit(first, hit(attacker.getId(), firstTarget.getId(), 11)));
    }

    @Test
    void aRealPlayerDuelEndsTrainingAndDoesNotAuthorizeOldQueuedBotHits() throws Exception {
        var bot = bot(98, 0x200062);
        var target = bot.getTeamManager().getCurrentAvatarEntity();
        PvpDuels.enable(first, bot);
        var botHit = hit(attacker.getId(), target.getId(), 11);
        assertTrue(PvpDuels.reportHit(first, botHit));
        PvpDuels.enable(first, second);
        apply(first, botHit);
        assertEquals(0, ((RecordingAvatar) target).damageCalls);
        assertFalse(target.getGlobalAbilityValues().containsKey(PvpClientTargeting.KEY));
        assertEquals(1, PvpDuels.checkReportedHit(first, hit(attacker.getId(), defender.getId(), 13)));
        assertEquals(-1, PvpDuels.checkReportedHit(first, hit(attacker.getId(), target.getId(), 13)));
    }

    @ParameterizedTest
    @CsvSource({"1,3", "1,4", "2,3", "2,4", "3,4"})
    void newTrustedFriendPairsSettleBothDirectionsWithoutGrantingOtherPlayers(int leftUid, int rightUid)
            throws Exception {
        var third = player(3);
        var fourth = player(4);
        avatar(third, 0x200003);
        avatar(fourth, 0x200004);
        var left = gameServer.getPlayers().get(leftUid);
        var right = gameServer.getPlayers().get(rightUid);
        var leftAvatar = (RecordingAvatar) left.getTeamManager().getCurrentAvatarEntity();
        var rightAvatar = (RecordingAvatar) right.getTeamManager().getCurrentAvatarEntity();
        PvpDuels.enable(left, right);
        assertEquals(-leftAvatar.globals.get(PvpClientTargeting.KEY),
                rightAvatar.globals.get(PvpClientTargeting.KEY));
        var forward = hit(leftAvatar.getId(), rightAvatar.getId(), 31);
        assertTrue(PvpDuels.reportHit(left, forward));
        apply(left, forward);
        var reverse = hit(rightAvatar.getId(), leftAvatar.getId(), 17);
        assertTrue(PvpDuels.reportHit(right, reverse));
        apply(right, reverse);
        assertEquals(31, rightAvatar.lastDamage);
        assertEquals(17, leftAvatar.lastDamage);
        assertEquals(1, leftAvatar.damageCalls);
        assertEquals(1, rightAvatar.damageCalls);
        assertThrows(IllegalArgumentException.class, () -> PvpDuels.enable(left, left));
        assertThrows(IllegalArgumentException.class, () -> PvpDuels.enable(left, outsider));
        assertEquals(-1, PvpDuels.checkReportedHit(outsider, forward));
    }

    @Test
    void switchingFriendsRevokesTheOldPairAndQueuedHitBeforeAcceptingTheNewOpponent() throws Exception {
        var third = player(3);
        var thirdAvatar = avatar(third, 0x200003);
        PvpDuels.enable(first, second);
        var previousHit = hit(attacker.getId(), defender.getId(), 90);
        assertTrue(PvpDuels.reportHit(first, previousHit));
        PvpDuels.enable(first, third);
        assertFalse(defender.globals.containsKey(PvpClientTargeting.KEY));
        assertEquals(-attacker.globals.get(PvpClientTargeting.KEY),
                thirdAvatar.globals.get(PvpClientTargeting.KEY));
        apply(first, previousHit);
        assertEquals(0, defender.damageCalls);
        assertFalse(PvpDuels.reportHit(first, hit(attacker.getId(), defender.getId(), 90)));
        var current = hit(attacker.getId(), thirdAvatar.getId(), 23);
        assertTrue(PvpDuels.reportHit(first, current));
        apply(first, current);
        assertEquals(23, thirdAvatar.lastDamage);
        PvpDuels.onPlayerLeave(third);
        assertFalse(thirdAvatar.globals.containsKey(PvpClientTargeting.KEY));
        assertFalse(PvpDuels.reportHit(first, hit(attacker.getId(), thirdAvatar.getId(), 23)));
    }

    @Test
    void enablingDoesNotCreateHitsChangeHpOrChangeCharacters() {
        PvpDuels.enable(first, second);
        assertEquals(0, defender.damageCalls);
        assertEquals(0, attacker.damageCalls);
        assertSame(attacker, first.getTeamManager().getCurrentAvatarEntity());
        assertSame(defender, second.getTeamManager().getCurrentAvatarEntity());
        assertTrue(PvpDuels.status().contains("UID 1 ↔ UID 2"));
        assertThrows(IllegalArgumentException.class, () -> PvpDuels.enable(first, outsider));
    }

    @Test
    void targetingMarkersAreAnOppositeExactFloatPairAndClosingRevokesBoth() throws Exception {
        assertEquals(PvpClientTargeting.KEY_HASH, Utils.abilityHash(PvpClientTargeting.KEY));
        PvpDuels.enable(first, second);
        float value = attacker.getGlobalAbilityValues().get(PvpClientTargeting.KEY);
        assertTrue(Float.isFinite(value) && value >= 1 && value < (1 << 24));
        assertEquals((float) (int) value, value);
        assertEquals(-value, defender.getGlobalAbilityValues().get(PvpClientTargeting.KEY));
        assertFalse(outsider.getTeamManager().getCurrentAvatarEntity().getGlobalAbilityValues()
                .containsKey(PvpClientTargeting.KEY));
        assertEquals(2, scene.packets.size());
        assertEquals(value, marker(scene.packets.get(0)).getValue());
        assertEquals(-value, marker(scene.packets.get(1)).getValue());

        PvpDuels.disable();
        assertFalse(attacker.getGlobalAbilityValues().containsKey(PvpClientTargeting.KEY));
        assertFalse(defender.getGlobalAbilityValues().containsKey(PvpClientTargeting.KEY));
        assertEquals(0, marker(scene.packets.get(2)).getValue());
        assertEquals(0, marker(scene.packets.get(3)).getValue());
        assertEquals(0, attacker.damageCalls + defender.damageCalls);
    }

    @Test
    void nativeReappearanceReannouncesThePairAndSwitchingRevokesThePreviousAvatar() throws Exception {
        PvpDuels.enable(first, second);
        float value = attacker.getGlobalAbilityValues().get(PvpClientTargeting.KEY);
        scene.packets.clear();
        sent(first.session, appearance(defender.getId()));
        assertEquals(2, scene.packets.size(), "A new native appearance needs targeting values again");

        scene.entities.remove(defender.getId());
        second.team.getActiveTeam().clear();
        var replacement = avatar(second, 0x200004);
        scene.packets.clear();
        sent(first.session, appearance(replacement.getId()));
        assertFalse(defender.getGlobalAbilityValues().containsKey(PvpClientTargeting.KEY));
        assertEquals(value, attacker.getGlobalAbilityValues().get(PvpClientTargeting.KEY));
        assertEquals(-value, replacement.getGlobalAbilityValues().get(PvpClientTargeting.KEY));
        assertEquals(3, scene.packets.size());
        assertEquals(defender.getId(), marker(scene.packets.get(0)).getEntityId());
        assertEquals(0, marker(scene.packets.get(0)).getValue());
    }

    @Test
    void nativeTeamUpdatesRefreshOnlyTheAuthenticatedPairAndGlobalValueNotifiesDoNotRecurse() throws Exception {
        PvpDuels.enable(first, second);
        scene.packets.clear();
        var packet = new BasePacket(PacketOpcodes.SceneTeamUpdateNotify);
        packet.setData(SceneTeamUpdateNotify.newBuilder().addSceneTeamAvatarList(
                SceneTeamAvatar.newBuilder().setEntityId(attacker.getId())));
        sent(outsider.session, packet);
        assertTrue(scene.packets.isEmpty());
        sent(second.session, packet);
        assertEquals(2, scene.packets.size());
        sent(first.session, scene.packets.getFirst());
        assertEquals(2, scene.packets.size());
        sent(first.session, appearance(0x400099));
        assertEquals(2, scene.packets.size());
    }

    @Test
    void replacingASessionOrLeavingRevokesNativeTargetingWithoutReactivation() throws Exception {
        PvpDuels.enable(first, second);
        var previousSession = first.session;
        first.session = session(first);
        sent(previousSession, appearance(attacker.getId()));
        assertFalse(attacker.getGlobalAbilityValues().containsKey(PvpClientTargeting.KEY));
        assertFalse(defender.getGlobalAbilityValues().containsKey(PvpClientTargeting.KEY));
        PvpDuels.disable();
        PvpDuels.enable(first, second);
        PvpDuels.onPlayerLeave(second);
        assertFalse(attacker.getGlobalAbilityValues().containsKey(PvpClientTargeting.KEY));
        assertFalse(defender.getGlobalAbilityValues().containsKey(PvpClientTargeting.KEY));
        scene.packets.clear();
        sent(first.session, appearance(attacker.getId()));
        assertTrue(scene.packets.isEmpty());
    }

    @Test
    void clientGlobalValueUploadsCannotForgeOrClearTheServerPairByStringOrHash() throws Exception {
        PvpDuels.enable(first, second);
        float original = attacker.getGlobalAbilityValues().get(PvpClientTargeting.KEY);
        set(first.abilities, BasePlayerManager.class, "player", first);
        var handler = AbilityManager.class.getDeclaredMethod("handleGlobalFloatValue", AbilityInvokeEntry.class);
        handler.setAccessible(true);
        var clear = AbilityManager.class.getDeclaredMethod("handleClearGlobalFloatValue", AbilityInvokeEntry.class);
        clear.setAccessible(true);
        var hashes = GameData.getAbilityHashes();
        String previous = hashes.put(PvpClientTargeting.KEY_HASH, PvpClientTargeting.KEY);
        try {
            for (var key : List.of(AbilityString.newBuilder().setStr(PvpClientTargeting.KEY).build(),
                    AbilityString.newBuilder().setHash(PvpClientTargeting.KEY_HASH).build())) {
                var entry = AbilityScalarValueEntry.newBuilder().setKey(key).setFloatValue(123456).build();
                var upload = AbilityInvokeEntry.newBuilder().setEntityId(attacker.getId())
                        .setAbilityData(entry.toByteString()).build();
                handler.invoke(first.abilities, upload);
                assertEquals(original, attacker.getGlobalAbilityValues().get(PvpClientTargeting.KEY));
                clear.invoke(first.abilities, upload);
                assertEquals(original, attacker.getGlobalAbilityValues().get(PvpClientTargeting.KEY));
            }
        } finally {
            if (previous == null) hashes.remove(PvpClientTargeting.KEY_HASH);
            else hashes.put(PvpClientTargeting.KEY_HASH, previous);
        }
        attacker.getGlobalAbilityValues().put("ClientOwnedFloat", 12f);
        clear.invoke(first.abilities, AbilityInvokeEntry.newBuilder().setEntityId(attacker.getId())
                .setAbilityData(AbilityScalarValueEntry.newBuilder().setKey(
                        AbilityString.newBuilder().setStr("ClientOwnedFloat")).build().toByteString()).build());
        assertFalse(attacker.getGlobalAbilityValues().containsKey("ClientOwnedFloat"));
    }

    private static ServerGlobalValueChangeNotify marker(BasePacket packet) throws Exception {
        assertEquals(PacketOpcodes.ServerGlobalValueChangeNotify, packet.getOpcode());
        var marker = ServerGlobalValueChangeNotify.parseFrom(packet.getData());
        assertEquals(PvpClientTargeting.KEY_HASH, marker.getKeyHash());
        return marker;
    }

    private static BasePacket appearance(int entityId) {
        var packet = new BasePacket(PacketOpcodes.SceneEntityAppearNotify);
        packet.setData(SceneEntityAppearNotify.newBuilder().addEntityList(
                SceneEntityInfo.newBuilder().setEntityId(entityId)));
        return packet;
    }

    private static void sent(GameSession session, BasePacket packet) throws Exception {
        GameSessionManager.getLogicThread().submit(() -> PvpClientTargeting.onPacketSent(session, packet))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void aReportedDirectHitUsesNativeDamageWithTheActualAttackerAndElement() {
        PvpDuels.enable(first, second);
        var hit = hit(attacker.getId(), defender.getId(), 120);
        assertEquals(1, PvpDuels.checkReportedHit(first, hit));
        assertTrue(PvpDuels.reportHit(first, hit));
        apply(first, hit);
        assertEquals(1, defender.damageCalls);
        assertEquals(120f, defender.lastDamage);
        assertEquals(attacker.getId(), defender.lastAttacker);
        assertEquals(ElementType.Fire, defender.lastElement);
        assertEquals(0, attacker.damageCalls);
    }

    @Test
    void arrowsStillSettleAfterTheirNormalBatchRetiresThemAndDoNotUseTheOldBolOrOneHpCap() {
        PvpDuels.enable(first, second);
        var arrow = gadget(first, attacker.getId(), 0x900001);
        var hit = hit(arrow.getId(), defender.getId(), 120);
        assertTrue(PvpDuels.reportHit(first, hit));
        scene.entities.remove(arrow.getId());
        defender.bondOfLife = 50;
        apply(first, hit);
        assertEquals(1, defender.damageCalls);
        assertEquals(120f, defender.lastDamage, "Native damage, not curHP - 1, owns the final HP/death calculation");
        assertEquals(0, defender.bondReads, "The old gadget-specific BoL skip must not run for this pair");
    }

    @Test
    void aRealHitCanOnlyBeSettledOnceAndNoManualSceneCallInventsOne() {
        PvpDuels.enable(first, second);
        var hit = hit(attacker.getId(), defender.getId(), 25);
        assertTrue(PvpDuels.handleAttack(scene, hit));
        assertEquals(0, defender.damageCalls, "No authenticated hit was queued");
        assertTrue(PvpDuels.reportHit(first, hit));
        PvpDuels.setReporter(first, hit);
        try {
            assertTrue(PvpDuels.handleAttack(scene, hit));
            assertTrue(PvpDuels.handleAttack(scene, hit));
        } finally { PvpDuels.clearReporter(); }
        apply(first, hit);
        assertEquals(1, defender.damageCalls);
    }

    @Test
    void forgedReporterAndForgedGadgetOwnershipAreRejectedBeforeQueueing() {
        PvpDuels.enable(first, second);
        var direct = hit(attacker.getId(), defender.getId(), 50);
        assertEquals(-1, PvpDuels.checkReportedHit(outsider, direct));
        assertFalse(PvpDuels.reportHit(outsider, direct));
        assertFalse(PvpDuels.reportHit(second, direct));
        var forged = gadget(outsider, attacker.getId(), 0x900001);
        assertEquals(-1, PvpDuels.checkReportedHit(first, hit(forged.getId(), defender.getId(), 50)));
        assertFalse(PvpDuels.reportHit(first, hit(0x900099, defender.getId(), 50)));
        assertEquals(0, defender.damageCalls);
    }

    @Test
    void theDefendersGodModeAndNativeInvulnerabilityStillBlockDuelDamage() throws Exception {
        PvpDuels.enable(first, second);
        second.godMode = true;
        var firstHit = hit(attacker.getId(), defender.getId(), 50);
        assertTrue(PvpDuels.reportHit(first, firstHit));
        apply(first, firstHit);
        assertEquals(0, defender.damageCalls);
        second.godMode = false;
        set(second.abilities, AbilityManager.class, "abilityInvulnerable", true);
        var secondHit = hit(attacker.getId(), defender.getId(), 50);
        assertTrue(PvpDuels.reportHit(first, secondHit));
        apply(first, secondHit);
        assertEquals(0, defender.damageCalls);
    }

    @Test
    void theCastersInvulnerabilityDoesNotProtectTheOtherPlayer() throws Exception {
        PvpDuels.enable(first, second);
        set(first.abilities, AbilityManager.class, "abilityInvulnerable", true);
        var hit = hit(attacker.getId(), defender.getId(), 50);
        assertEquals(1, PvpDuels.checkReportedHit(first, hit));
        assertTrue(PvpDuels.reportHit(first, hit));
        apply(first, hit);
        assertEquals(1, defender.damageCalls);
    }

    @Test
    void actualCombatPacketsKeepOutgoingSkillDamageDuringBurstInvulnerabilityAndBlockIncomingDamage() throws Exception {
        set(first.abilities, AbilityManager.class, "abilityInvulnerable", true);
        set(first, Player.class, "attackResults", new java.util.concurrent.LinkedBlockingQueue<AttackResult>());
        set(first, Player.class, "combatInvokeHandler", new InvokeHandler<CombatInvokeEntry>(PacketCombatInvocationsNotify.class));
        set(first, Player.class, "energyManager", new EnergyManager(first) {
            @Override public void handleAttackHit(EvtBeingHitInfo hit) {}
        });
        var monster = allocate(RecordingMonster.class);
        monster.scene = scene;
        monster.hp = 100;
        monster.setId(0x400001);
        scene.entities.put(monster.getId(), monster);
        var arrow = gadget(first, attacker.getId(), 0x900001);
        var handler = new HandlerCombatInvocationsNotify();
        for (var hit : List.of(hit(arrow.getId(), monster.getId(), 25),
                               hit(attacker.getId(), monster.getId(), 10),
                               hit(monster.getId(), attacker.getId(), 40))) {
            var entry = CombatInvokeEntry.newBuilder()
                    .setArgumentType(CombatTypeArgument.CombatTypeArgument_COMBAT_EVT_BEING_HIT)
                    .setCombatData(EvtBeingHitInfo.newBuilder().setAttackResult(hit).build().toByteString()).build();
            handler.handle(first.session, new byte[0], CombatInvocationsNotify.newBuilder().addInvokeList(entry).build().toByteArray());
        }
        assertEquals(65, monster.hp, "own skill gadget and normal attack must both reach monster HP");
        assertEquals(0, attacker.damageCalls, "incoming monster damage still respects burst invulnerability");
        assertTrue(first.getAttackResults().isEmpty());
    }

    @Test
    void leaveThenRejoinAndReplacingTheLoginSessionCannotReactivateTheDuel() throws Exception {
        PvpDuels.enable(first, second);
        PvpDuels.onPlayerLeave(first);
        assertEquals(-1, PvpDuels.checkReportedHit(first, hit(attacker.getId(), defender.getId(), 20)));
        PvpDuels.enable(first, second);
        first.session = allocate(GameSession.class);
        assertEquals(-1, PvpDuels.checkReportedHit(first, hit(attacker.getId(), defender.getId(), 20)));
        assertTrue(PvpDuels.status().contains("未启用"));
    }

    @Test
    void disableAndChangingSceneOrCurrentAvatarCancelQueuedDamage() throws Exception {
        PvpDuels.enable(first, second);
        var disabledHit = hit(attacker.getId(), defender.getId(), 50);
        assertTrue(PvpDuels.reportHit(first, disabledHit));
        PvpDuels.disable();
        apply(first, disabledHit);
        assertEquals(0, defender.damageCalls);

        PvpDuels.enable(first, second);
        var previous = defender;
        second.team.getActiveTeam().clear();
        avatar(second, 0x200004);
        assertFalse(PvpDuels.reportHit(first, hit(attacker.getId(), previous.getId(), 50)));
        second.scene = allocate(RecordingScene.class);
        assertThrows(IllegalArgumentException.class, () -> PvpDuels.enable(first, second));
    }

    @Test
    void unpairedPlayerAndPlayerGadgetHitsCannotFallThroughToOrdinaryAvatarDamage() throws Exception {
        var third = player(3);
        var thirdAvatar = avatar(third, 0x200003);
        PvpDuels.enable(first, second);
        var otherAvatar = outsider.getTeamManager().getCurrentAvatarEntity();
        var outsideArrow = gadget(outsider, otherAvatar.getId(), 0x900001);
        var thirdArrow = gadget(third, thirdAvatar.getId(), 0x900002);
        var missingOwnerArrow = gadget(third, 0x200099, 0x900003);
        var nested = allocate(EntityGadget.class);
        nested.setId(0x900004);
        nested.setOwner(missingOwnerArrow);
        scene.entities.put(nested.getId(), nested);
        for (var attack : List.of(hit(otherAvatar.getId(), defender.getId(), 10),
                hit(thirdAvatar.getId(), defender.getId(), 10),
                hit(outsideArrow.getId(), defender.getId(), 10),
                hit(thirdArrow.getId(), defender.getId(), 10),
                hit(missingOwnerArrow.getId(), defender.getId(), 10),
                hit(nested.getId(), defender.getId(), 10))) {
            assertEquals(-1, PvpDuels.checkReportedHit(third, attack));
            assertFalse(PvpDuels.reportHit(third, attack));
            assertTrue(PvpDuels.handleAttack(scene, attack));
        }
        // Use the real Scene hook too: an unpaired, present player cannot damage an Avatar.
        scene.handleAttack(hit(otherAvatar.getId(), defender.getId(), 50));
        scene.handleAttack(hit(outsideArrow.getId(), defender.getId(), 50));
        assertEquals(0, defender.damageCalls);
        assertEquals(-1, PvpDuels.checkReportedHit(outsider, hit(0x900099, defender.getId(), 10)));
        PvpDuels.disable();
        assertFalse(PvpDuels.reportHit(first, hit(attacker.getId(), defender.getId(), 10)));
        scene.handleAttack(hit(attacker.getId(), defender.getId(), 50));
        assertEquals(0, defender.damageCalls);
    }

    @Test
    void selfDamageMonstersEnvironmentAndOrdinaryPveKeepTheirExistingPipeline() throws Exception {
        PvpDuels.enable(first, second);
        var monster = allocate(EntityMonster.class);
        monster.setId(0x400001);
        scene.entities.put(monster.getId(), monster);
        var environment = allocate(EntityGadget.class);
        environment.setId(0x900001);
        scene.entities.put(environment.getId(), environment);
        var ownArrow = gadget(first, attacker.getId(), 0x900002);
        for (var hit : List.of(hit(attacker.getId(), attacker.getId(), 10),
                hit(ownArrow.getId(), attacker.getId(), 10),
                hit(monster.getId(), attacker.getId(), 10),
                hit(environment.getId(), attacker.getId(), 10),
                hit(0, attacker.getId(), 10), hit(attacker.getId(), 0x400099, 10))) {
            assertEquals(0, PvpDuels.checkReportedHit(first, hit));
            assertTrue(PvpDuels.reportHit(first, hit));
            PvpDuels.setReporter(first, hit);
            try { assertFalse(PvpDuels.handleAttack(scene, hit)); }
            finally { PvpDuels.clearReporter(); }
        }
    }

    @Test
    void theCommandIsAnExplicitAdministrativeSwitchForThisPairOnly() {
        var annotation = PvpCommand.class.getAnnotation(Command.class);
        assertEquals("server.pvp", annotation.permission());
        assertFalse(annotation.inlineTarget());
        var cli = new PvpCommand().createCommandLine(null, null);
        assertTrue(cli.getUsageMessage().contains("@UID1 @UID2"));
        assertDoesNotThrow(() -> cli.parseArgs("on", "@3"));
        assertDoesNotThrow(() -> cli.parseArgs("on", "@3", "@4"));
        assertThrows(picocli.CommandLine.ParameterException.class, () -> cli.parseArgs("on", "3"));
        assertThrows(picocli.CommandLine.ParameterException.class, () -> cli.parseArgs("on", "@0"));
        assertThrows(picocli.CommandLine.ParameterException.class, () -> cli.parseArgs("on", "@1", "@2", "@3"));
    }

    @Test
    void commandResolvesExplicitOpponentsAndPreservesTheOldDefaultWithoutAcceptingOfflinePlayers()
            throws Exception {
        var third = player(3);
        var fourth = player(4);
        avatar(third, 0x200003);
        avatar(fourth, 0x200004);
        var previousServer = Grasscutter.getGameServer();
        try {
            set(null, Grasscutter.class, "gameServer", gameServer);
            new PvpCommand().createCommandLine(null, null).execute("on", "@3");
            assertTrue(PvpDuels.status().contains("UID 1 ↔ UID 3"));
            new PvpCommand().createCommandLine(null, null).execute("on", "@3", "@4");
            assertTrue(PvpDuels.status().contains("UID 3 ↔ UID 4"));
            new PvpCommand().createCommandLine(null, null).execute("on");
            assertTrue(PvpDuels.status().contains("UID 1 ↔ UID 2"));
            var before = PvpDuels.status();
            new PvpCommand().createCommandLine(null, null).execute("on", "@5");
            assertEquals(before, PvpDuels.status());
            fourth.session = null;
            new PvpCommand().createCommandLine(null, null).execute("on", "@4");
            assertEquals(before, PvpDuels.status());
            new PvpCommand().createCommandLine(null, null).execute("off", "@3");
            assertEquals(before, PvpDuels.status());
        } finally {
            set(null, Grasscutter.class, "gameServer", previousServer);
        }
    }

    @Test
    void handlerDuelVerdictSkipsVentiDamageInferenceAndTargetRewriting() throws Exception {
        record Jump(int variable, int opcode, Label target, int position) {}
        var jumps = new ArrayList<Jump>();
        var labels = new java.util.IdentityHashMap<Label, Integer>();
        int[] duelVariable = {-1}, ventiLookup = {-1}, lastRewrite = {-1}, reportHit = {-1};
        try (var bytes = HandlerCombatInvocationsNotify.class.getResourceAsStream("HandlerCombatInvocationsNotify.class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("handle")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        int position, lastLoad = -1;
                        boolean storingVerdict;
                        @Override public void visitLabel(Label label) { labels.put(label, position); lastLoad = -1; }
                        @Override public void visitVarInsn(int opcode, int variable) {
                            if (storingVerdict && opcode == Opcodes.ISTORE) { duelVariable[0] = variable; storingVerdict = false; }
                            lastLoad = opcode == Opcodes.ILOAD ? variable : -1;
                            position++;
                        }
                        @Override public void visitInsn(int opcode) { lastLoad = -1; position++; }
                        @Override public void visitJumpInsn(int opcode, Label target) {
                            jumps.add(new Jump(lastLoad, opcode, target, position++));
                            lastLoad = -1;
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            if (owner.endsWith("/PvpDuels") && name.equals("checkReportedHit")) storingVerdict = true;
                            if (name.equals("findVentiHexenzirkelArrowGadget")) ventiLookup[0] = position;
                            if (owner.contains("AttackResultOuterClass$")
                                    && (name.equals("toBuilder") || name.equals("setDefenseId") || name.equals("setDamage")))
                                lastRewrite[0] = position;
                            if (owner.endsWith("/PvpDuels") && name.equals("reportHit")) reportHit[0] = position;
                            lastLoad = -1;
                            position++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertTrue(duelVariable[0] >= 0 && ventiLookup[0] >= 0 && lastRewrite[0] > ventiLookup[0]);
        assertTrue(jumps.stream().anyMatch(jump -> jump.variable() == duelVariable[0] && jump.opcode() == Opcodes.IFNE
                        && jump.position() < ventiLookup[0] && labels.get(jump.target()) > lastRewrite[0]
                        && labels.get(jump.target()) <= reportHit[0]),
                "A nonzero duel verdict must jump over Venti lookup and AttackResult rewriting; zero preserves PvE");
    }

    private void apply(Player reporter, AttackResult hit) {
        PvpDuels.setReporter(reporter, hit);
        try { assertTrue(PvpDuels.handleAttack(scene, hit)); }
        finally { PvpDuels.clearReporter(); }
    }

    private static AttackResult hit(int source, int target, float damage) {
        return AttackResult.newBuilder().setAttackerId(source).setDefenseId(target).setDamage(damage).setElementType(1).build();
    }

    private EntityClientGadget gadget(Player owner, int originalOwner, int id) {
        var gadget = new EntityClientGadget(scene, owner, EvtCreateGadgetNotify.newBuilder()
                .setEntityId(id).setConfigId(9876100).setOwnerEntityId(originalOwner).build());
        scene.entities.put(id, gadget);
        return gadget;
    }

    private RecordingPlayer player(int uid) throws Exception {
        var player = allocate(RecordingPlayer.class);
        player.setUid(uid);
        player.scene = scene;
        player.world = world;
        player.session = session(player);
        player.account = new Account();
        player.account.setUsername("player_" + uid);
        player.team = new TeamManager(player);
        player.abilities = allocate(AbilityManager.class);
        scene.players.add(player);
        world.players.add(player);
        gameServer.getPlayers().put(uid, player);
        return player;
    }

    private Player bot(int uid, int entityId) throws Exception {
        var bot = allocate(PvpTestBot.BotPlayer.class);
        bot.setUid(uid);
        bot.setSession(session(bot));
        set(bot, Player.class, "world", world);
        set(bot, Player.class, "scene", scene);
        set(bot, Player.class, "teamManager", new TeamManager(bot));
        set(bot, Player.class, "abilityManager", allocate(AbilityManager.class));
        world.players.add(bot);
        scene.players.add(bot);
        gameServer.getPlayers().put(uid, bot);
        avatar(bot, entityId);
        PvpTestBotRoster.register(first, bot);
        trainingBots.add(bot);
        return bot;
    }

    private RecordingAvatar avatar(Player player, int id) throws Exception {
        var avatar = allocate(RecordingAvatar.class);
        avatar.player = player;
        avatar.scene = scene;
        avatar.globals = new HashMap<>();
        avatar.setId(id);
        player.getTeamManager().getActiveTeam().add(avatar);
        scene.entities.put(id, avatar);
        return avatar;
    }

    private GameSession session(Player player) throws Exception {
        var session = allocate(RecordingSession.class);
        session.player = player;
        session.server = gameServer;
        return session;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void set(Object target, Class<?> declaring, String name, Object value) throws Exception {
        Field field = declaring.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class RecordingWorld extends World {
        private List<Player> players;
        private RecordingWorld() { super((GameServer) null, null); }
        @Override public List<Player> getPlayers() { return players; }
    }

    private static final class RecordingScene extends Scene {
        private World world;
        private List<Player> players;
        private Map<Integer, GameEntity> entities;
        private List<BasePacket> packets;
        private RecordingScene() { super(null, null); }
        @Override public World getWorld() { return world; }
        @Override public List<Player> getPlayers() { return players; }
        @Override public GameEntity getEntityById(int id) { return entities.get(id); }
        @Override public void broadcastPacket(BasePacket packet) { packets.add(packet); }
    }

    private static final class RecordingSession extends GameSession {
        private Player player;
        private GameServer server;
        private RecordingSession() { super(null); }
        @Override public boolean isActive() { return true; }
        @Override public Player getPlayer() { return player; }
        @Override public GameServer getServer() { return server; }
    }

    private static final class RecordingMonster extends EntityMonster {
        private Scene scene;
        private float hp;
        private RecordingMonster() { super(null, null, null, null, 1); }
        @Override public Scene getScene() { return scene; }
        @Override public float getFightProperty(FightProperty property) { return hp; }
        @Override public void damage(float amount, int killerId, ElementType element) { hp -= amount; }
    }

    private static final class RecordingPlayer extends Player {
        private World world;
        private RecordingScene scene;
        private TeamManager team;
        private AbilityManager abilities;
        private GameSession session;
        private Account account;
        private boolean godMode;
        private RecordingPlayer() { super((GameSession) null); }
        @Override public boolean isOnline() { return session != null && session.isActive(); }
        @Override public Account getAccount() { return account; }
        @Override public World getWorld() { return world; }
        @Override public Scene getScene() { return scene; }
        @Override public GameSession getSession() { return session; }
        @Override public TeamManager getTeamManager() { return team; }
        @Override public AbilityManager getAbilityManager() { return abilities; }
        @Override public boolean isInGodMode() { return godMode; }
    }

    private static final class RecordingAvatar extends EntityAvatar {
        private Player player;
        private Scene scene;
        private int damageCalls, lastAttacker, bondReads;
        private float lastDamage, bondOfLife;
        private ElementType lastElement;
        private Map<String, Float> globals;
        private boolean dead;
        private RecordingAvatar() { super((Avatar) null); }
        @Override public Player getPlayer() { return player; }
        @Override public Scene getScene() { return scene; }
        @Override public boolean isAlive() { return !dead; }
        @Override public Map<String, Float> getGlobalAbilityValues() { return globals; }
        @Override public float getFightProperty(FightProperty prop) {
            if (prop == FightProperty.FIGHT_PROP_CUR_HP_DEBTS) { bondReads++; return bondOfLife; }
            if (prop == FightProperty.FIGHT_PROP_CUR_HP) return 20;
            return 0;
        }
        @Override public void damage(float amount, int killerId, ElementType type) {
            damageCalls++;
            lastDamage = amount;
            lastAttacker = killerId;
            lastElement = type;
        }
    }
}
