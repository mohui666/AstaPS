package emu.grasscutter.game.world;

import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.entity.EntityClientGadget;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ElementType;
import emu.grasscutter.net.proto.AttackResultOuterClass.AttackResult;
import emu.grasscutter.server.game.GameSession;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Opt-in real hits for trusted friends or an owner's registered test bot. */
public final class PvpDuels {
    private record Duel(Player first, Player second, World world, Scene scene,
                        GameSession firstSession, GameSession secondSession) {}
    private record Source(GameEntity entity, EntityAvatar avatar, boolean ownerMatches) {}
    private record Hit(Player reporter, Duel duel, Source source, EntityAvatar defender, AttackResult result) {}

    private static volatile Duel active;
    private static final Map<AttackResult, Hit> reported = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ThreadLocal<Hit> currentHit = new ThreadLocal<>();
    private static final Set<Integer> TRUSTED_UIDS = Set.of(1, 2, 3, 4);

    private PvpDuels() {}

    public static synchronized String enable(Player first, Player second) {
        if (first == null || second == null || !first.isOnline() || !second.isOnline())
            throw new IllegalArgumentException("对战双方必须同时在线。");
        if (!isPair(first, second)) throw new IllegalArgumentException("当前仅授权 UID 1–4 中不同的两人，或主人与已登记的测试机器人。");
        var registration = PvpTestBotRoster.entry(first);
        if (registration != null && registration.owner() == second) {
            var bot = first;
            first = second;
            second = bot;
        }
        var duel = new Duel(first, second, first.getWorld(), first.getScene(), first.getSession(), second.getSession());
        if (!valid(duel)) throw new IllegalArgumentException("两人必须已进入同一个世界、同一个场景。");
        if (first.getTeamManager().getCurrentAvatarEntity() == null
                || second.getTeamManager().getCurrentAvatarEntity() == null)
            throw new IllegalArgumentException("双方场上角色尚未就绪。");
        var previous = active;
        if (previous != null && valid(previous) && PvpTestBot.isOwnedPair(previous.first(), previous.second()))
            PvpDuelRoster.register(previous.first(), previous.second(), previous);
        boolean continuing = PvpDuelRoster.register(first, second, duel);
        PvpClientTargeting.grant(first, second);
        if (!continuing) reported.clear();
        active = duel;
        return "UID " + first.getUid() + " ↔ UID " + second.getUid() + " 服务端对战已启用：仅结算真实命中，保留护盾、无敌帧与正常死亡；离开场景或重登后失效。客户端启用PVP补丁后可对战。";
    }

    public static synchronized String disable() {
        var duel = active;
        PvpClientTargeting.clear();
        PvpDuelRoster.clear();
        active = null;
        reported.clear();
        return duel == null ? "服务端对战未启用。" : "已关闭 UID " + duel.first().getUid() + " ↔ UID " + duel.second().getUid() + " 的服务端对战。";
    }

    public static String status() {
        var duel = activeDuel();
        return duel == null ? "服务端对战未启用。" : "服务端对战：UID " + duel.first().getUid() + " ↔ UID " + duel.second().getUid() + "；仅处理真实命中。GM无敌：UID "
                + duel.first().getUid() + "=" + duel.first().isInGodMode() + "，UID "
                + duel.second().getUid() + "=" + duel.second().isInGodMode() + "。";
    }

    /** Scene.removePlayer calls this before membership changes, including same-scene re-entry. */
    public static synchronized void onPlayerLeave(Player player) {
        var duel = active;
        if (PvpDuelRoster.training() && PvpDuelRoster.contains(player)) {
            if (PvpDuelRoster.owns(player)) disable();
            else {
                PvpDuelRoster.leave(player);
                active = (Duel) PvpDuelRoster.any();
                if (active == null) disable();
            }
        } else if (duel != null && (duel.first() == player || duel.second() == player)) disable();
        PvpClientTargeting.onPlayerLeave(player);
        PvpTestBot.onPlayerLeave(player);
    }

    /** -1 rejects an unauthorized player hit, 0 preserves ordinary combat, 1 accepts a duel hit. */
    public static int checkReportedHit(Player reporter, AttackResult result) {
        var scene = reporter.getScene();
        if (scene == null) return 0;
        var target = scene.getEntityById(result.getDefenseId());
        if (!(target instanceof EntityAvatar defender)) return 0;
        var attackingEntity = scene.getEntityById(result.getAttackerId());
        var source = source(scene, attackingEntity);
        if (source == null) {
            // A remote player's HP cannot be attributed to an absent, unowned attacking entity.
            return crossesPlayers(attackingEntity, null, defender.getPlayer())
                    || crossesPlayers(attackingEntity, null, reporter)
                    || (attackingEntity == null && reporter != defender.getPlayer()) ? -1 : 0;
        }
        if (!crossesPlayers(attackingEntity, source, defender.getPlayer()))
            return source.avatar().getPlayer() == reporter ? 0 : -1;
        if (!isPair(source.avatar().getPlayer(), defender.getPlayer())) return -1;
        var duel = activeDuel();
        if (PvpDuelRoster.training()) duel = (Duel) PvpDuelRoster.find(source.avatar().getPlayer(), defender.getPlayer());
        return authorized(duel, reporter, source, defender)
                && Float.isFinite(result.getDamage()) && result.getDamage() >= 0 ? 1 : -1;
    }

    /** Save ownership before the normal ability batch retires the projectile that actually hit. */
    public static boolean reportHit(Player reporter, AttackResult result) {
        int verdict = checkReportedHit(reporter, result);
        if (verdict < 0) return false;
        if (verdict > 0) {
            var scene = reporter.getScene();
            var duel = activeDuel();
            var source = source(scene, scene.getEntityById(result.getAttackerId()));
            if (!(scene.getEntityById(result.getDefenseId()) instanceof EntityAvatar defender)) return false;
            if (PvpDuelRoster.training() && source != null)
                duel = (Duel) PvpDuelRoster.find(source.avatar().getPlayer(), defender.getPlayer());
            if (!authorized(duel, reporter, source, defender)) return false;
            reported.put(result, new Hit(reporter, duel, source, defender, result));
        }
        return true;
    }

    /** HandlerCombatInvocationsNotify.applyQueuedAttacks supplies the authenticated reporter. */
    public static void setReporter(Player reporter, AttackResult result) {
        var hit = reported.remove(result);
        if (hit != null && hit.reporter() == reporter) currentHit.set(hit);
        else currentHit.remove();
    }

    public static void clearReporter() { currentHit.remove(); }

    /** Scene.handleAttack must never fall through to ordinary damage for a cross-player hit. */
    public static boolean handleAttack(Scene scene, AttackResult result) {
        var hit = currentHit.get();
        if (hit == null || hit.result() != result) {
            var target = scene.getEntityById(result.getDefenseId());
            var attackingEntity = scene.getEntityById(result.getAttackerId());
            var source = source(scene, attackingEntity);
            return target instanceof EntityAvatar defender
                    && crossesPlayers(attackingEntity, source, defender.getPlayer());
        }
        currentHit.remove();
        var duel = activeDuel();
        var defender = hit.defender();
        if (PvpDuelRoster.training()) duel = (Duel) PvpDuelRoster.find(hit.source().avatar().getPlayer(), defender.getPlayer());
        if (duel == null || duel != hit.duel() || scene != duel.scene()
                || !authorized(duel, hit.reporter(), hit.source(), defender)
                || scene.getEntityById(defender.getId()) != defender) return true;
        if (!defender.isAlive() || defender.getPlayer().isInGodMode()
                || defender.getPlayer().getAbilityManager().isAbilityInvulnerable()) return true;
        // Native damage owns shields, modifier invulnerability, HP changes and death. No gadget cap/BoL skip.
        defender.damage(result.getDamage(), result.getAttackerId(), ElementType.getTypeByValue(result.getElementType()));
        return true;
    }

    private static boolean authorized(Duel duel, Player reporter, Source source, EntityAvatar defender) {
        if (duel == null || !valid(duel) || source == null || !source.ownerMatches()
                || source.avatar().getPlayer() != reporter) return false;
        var targetPlayer = defender.getPlayer();
        if (!((reporter == duel.first() && targetPlayer == duel.second())
                || (reporter == duel.second() && targetPlayer == duel.first()))) return false;
        if (defender.getScene() != duel.scene() || source.entity().getScene() != duel.scene()
                || source.avatar().getScene() != duel.scene()
                || targetPlayer.getTeamManager().getCurrentAvatarEntity() != defender) return false;
        return !(source.entity() instanceof EntityAvatar)
                || reporter.getTeamManager().getCurrentAvatarEntity() == source.entity();
    }

    private static Source source(Scene scene, GameEntity entity) {
        var original = entity;
        var seen = new HashSet<Integer>();
        Player clientOwner = null;
        boolean ownerMatches = true;
        while (entity != null && seen.add(entity.getId())) {
            if (entity instanceof EntityAvatar avatar)
                return new Source(original, avatar, ownerMatches && (clientOwner == null || clientOwner == avatar.getPlayer()));
            if (entity instanceof EntityClientGadget client) {
                if (client.getOwner() == null) ownerMatches = false;
                if (clientOwner != null && clientOwner != client.getOwner()) ownerMatches = false;
                clientOwner = client.getOwner();
                entity = scene.getEntityById(client.getOriginalOwnerEntityId());
            } else if (entity instanceof EntityGadget gadget) entity = gadget.getOwner();
            else return null;
        }
        return null;
    }

    private static boolean isPair(Player first, Player second) {
        return first != null && second != null
                && ((first.getUid() != second.getUid() && TRUSTED_UIDS.contains(first.getUid()) && TRUSTED_UIDS.contains(second.getUid()))
                        || PvpTestBot.isOwnedPair(first, second) || PvpTestBotRoster.isOwnedPair(first, second));
    }

    /** A retired/missing avatar owner cannot turn a player's gadget into ordinary world damage. */
    private static boolean crossesPlayers(GameEntity entity, Source source, Player defender) {
        if (source != null) return source.avatar().getPlayer() != defender || !source.ownerMatches();
        var seen = new HashSet<Integer>();
        while (entity != null && seen.add(entity.getId())) {
            if (entity instanceof EntityClientGadget client) return client.getOwner() != defender;
            if (entity instanceof EntityGadget gadget) entity = gadget.getOwner();
            else return false;
        }
        return false;
    }

    private static boolean valid(Duel duel) {
        return duel.world() != null && duel.scene() != null && duel.first().isOnline() && duel.second().isOnline()
                && duel.first().getWorld() == duel.world() && duel.second().getWorld() == duel.world()
                && duel.scene().getWorld() == duel.world()
                && duel.first().getScene() == duel.scene() && duel.second().getScene() == duel.scene()
                && duel.first().getSession() == duel.firstSession() && duel.second().getSession() == duel.secondSession()
                && duel.world().getPlayers().contains(duel.first()) && duel.world().getPlayers().contains(duel.second())
                && duel.scene().getPlayers().contains(duel.first()) && duel.scene().getPlayers().contains(duel.second());
    }

    private static Duel activeDuel() {
        var duel = active;
        if (PvpDuelRoster.training()) {
            if (!PvpDuelRoster.validOwner()) { disable(); return null; }
            duel = (Duel) PvpDuelRoster.any();
            active = duel;
            if (duel == null) disable();
            return duel;
        }
        if (duel != null && !valid(duel)) {
            synchronized (PvpDuels.class) {
                if (active == duel) disable();
            }
            return null;
        }
        return duel;
    }
}
