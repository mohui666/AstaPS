package emu.grasscutter.game.world;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.game.entity.EntityClientGadget;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.entity.EntityMonster;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.props.CampTargetType;
import emu.grasscutter.net.proto.AbilityActionCreateGadgetOuterClass.AbilityActionCreateGadget;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import java.util.Map;

/** Monster mechanics keep their native spawn points instead of replacing every same-ID summon. */
public final class MonsterSkillGadgets {
    private static final float SAME_POSITION_SQUARED = 0.25f * 0.25f;
    private static final int DVALIN_MOVING_CENTER = 42901008;

    private MonsterSkillGadgets() {}

    /**
     * Honor the client's positioned request, or an explicit world position in native onAdded data.
     * Other born rules require client placement and must not spawn at the monster's origin.
     */
    public static boolean createFromClientRequest(
            GameEntity owner, AbilityModifierAction action, ByteString payload) {
        var boss = rootMonster(owner);
        if (boss == null) return false;

        final AbilityActionCreateGadget request;
        try {
            request = AbilityActionCreateGadget.parseFrom(payload);
        } catch (InvalidProtocolBufferException invalid) {
            return false;
        }
        var position = request.hasPos() ? new Position(request.getPos())
                : payload.isEmpty() ? fixedWorldBornPosition(action) : null;
        if (position == null) return true;
        if (!Float.isFinite(position.getX()) || !Float.isFinite(position.getY())
                || !Float.isFinite(position.getZ())) return false;
        var rotation = request.hasRot() ? new Position(request.getRot()) : new Position(owner.getRotation());
        var scene = boss.getScene();
        if (scene == null || scene.getEntityById(boss.getId()) != boss || !boss.isAlive()
                || scene.getEntityById(owner.getId()) != owner || !owner.isAlive()) return false;

        synchronized (scene) {
            int owned = 0;
            for (var existing : scene.getEntities().values()) {
                if (!ownedGadget(existing, owner, boss, action.gadgetID)) continue;
                owned++;
                if (samePosition(existing.getPosition(), position)) return true;
            }
            int limit = switch (action.gadgetID) {
                case 42004010, 42004016 -> 3;
                case 42004017 -> 2;
                case 42004018, DVALIN_MOVING_CENTER -> 1;
                default -> Integer.MAX_VALUE;
            };
            if (owned >= limit) return true;

            var gadget = new SkillGadget(scene, action.gadgetID, position, rotation, action.campID,
                    CampTargetType.getTypeByName(action.campTargetType).getValue(), boss,
                    action.lifeByOwnerIsAlive, action.lifeByOwnerV2);
            gadget.setOwner(owner);
            var blossom = scene.getBlossomManager();
            if (blossom != null) blossom.onSceneEntityCreated(gadget);
            gadget.initAbilities();
            if (owner instanceof EntityGadget parent) parent.getChildren().add(gadget);
            scene.addEntity(gadget);
            Grasscutter.getLogger().debug("Monster skill gadget={} entityId={} owner={} position={}",
                    action.gadgetID, gadget.getId(), owner.getId(), position);
        }
        return true;
    }

    /** Monster constructors load abilities before registration, so replay this fixed flight anchor after it. */
    public static void onMonsterCreated(EntityMonster monster) {
        var monsterData = monster.getMonsterData();
        if (monsterData == null || (monsterData.getId() != 29010103 && monsterData.getId() != 29010101)) return;
        for (var ability : monster.getInstancedAbilities()) {
            var data = ability.getData();
            if (data == null || !"Dvalin_S01_Enviroment".equals(data.abilityName)
                    || data.onAdded == null) continue;
            for (var action : data.onAdded) {
                if (action != null && action.type == AbilityModifierAction.Type.CreateGadget
                        && action.gadgetID == DVALIN_MOVING_CENTER) {
                    createFromClientRequest(monster, action, ByteString.EMPTY);
                }
            }
        }
    }

    private static Position fixedWorldBornPosition(AbilityModifierAction action) {
        // Native 7.1 ConfigBornByWorld: NONIAFMFFBP is the absolute world position.
        if (action.born == null || !"AMOMFFPOKPI".equals(action.born.get("$type"))
                || !(action.born.get("NONIAFMFFBP") instanceof Map<?, ?> position)) return null;
        return new Position(coordinate(position, "x"), coordinate(position, "y"), coordinate(position, "z"));
    }

    private static float coordinate(Map<?, ?> position, String axis) {
        var value = position.get(axis);
        return value == null ? 0f : value instanceof Number number ? number.floatValue() : Float.NaN;
    }

    /** Resolve enemy ownership locally; do not change the avatar ownership rules of client gadgets. */
    public static EntityMonster rootMonster(GameEntity entity) {
        var seen = new java.util.HashSet<Integer>();
        while (entity != null && seen.add(entity.getId())) {
            if (entity instanceof EntityMonster monster) return monster;
            if (entity instanceof EntityClientGadget client) {
                var parent = client.getScene().getEntityById(client.getOwnerEntityId());
                if (parent == null && client.getOriginalOwnerEntityId() != client.getOwnerEntityId()) {
                    parent = client.getScene().getEntityById(client.getOriginalOwnerEntityId());
                }
                entity = parent;
            } else if (entity instanceof EntityGadget gadget) entity = gadget.getOwner();
            else return null;
        }
        return null;
    }

    /** A modifier applied to the boss creates its mechanic on that boss, even if its ability came from a child. */
    public static GameEntity resolveOwner(GameEntity abilityOwner, GameEntity target, AbilityModifierAction action) {
        if (action.ownerIsTarget || (action.gadgetID == 42906123 && target instanceof EntityMonster)) return target;
        var root = rootMonster(abilityOwner);
        return target != null && root != null && rootMonster(target) == root ? target : abilityOwner;
    }

    private static boolean ownedGadget(GameEntity entity, GameEntity owner, EntityMonster boss, int gadgetId) {
        if (!entity.isAlive()) return false;
        if (entity instanceof EntityClientGadget client) {
            return client.getGadgetId() == gadgetId
                    && (client.getOwnerEntityId() == owner.getId()
                            || (rootMonster(client) == boss
                                    && (owner == boss || client.getOwnerEntityId() == boss.getId())));
        }
        return entity instanceof EntityGadget gadget && gadget.getGadgetId() == gadgetId
                && gadget.getOwner() == owner;
    }

    private static boolean samePosition(Position a, Position b) {
        float x = a.getX() - b.getX(), y = a.getY() - b.getY(), z = a.getZ() - b.getZ();
        return x * x + y * y + z * z <= SAME_POSITION_SQUARED;
    }

    /** A later local SkillObj takes over its matching mechanic; the unique flight center may have moved. */
    public static void onClientGadgetCreated(EntityClientGadget client) {
        var boss = rootMonster(client);
        if (boss == null) return;
        var scene = client.getScene();
        synchronized (scene) {
            var duplicates = scene.getEntities().values().stream()
                    .filter(entity -> entity instanceof SkillGadget)
                    .map(entity -> (SkillGadget) entity)
                    .filter(gadget -> gadget.getOwner() != null && gadget.root == boss
                            && ownedGadget(client, gadget.getOwner(), boss, gadget.getGadgetId())
                            && (gadget.getGadgetId() == DVALIN_MOVING_CENTER
                                    || samePosition(gadget.getPosition(), client.getPosition())))
                    .toList();
            for (var gadget : duplicates) scene.removeEntity(gadget, VisionType.VisionType_VISION_REMOVE);
        }
    }

    /** Honor the original action's owner lifetime; independent mechanics and drops stay independent. */
    private static final class SkillGadget extends EntityGadget {
        private final EntityMonster root;
        private final boolean retireOnDeath;
        private final boolean followOwner;

        private SkillGadget(Scene scene, int id, Position position, Position rotation, int camp, int campType,
                EntityMonster root, boolean retireOnDeath, boolean followOwner) {
            super(scene, id, position, rotation, null, camp, campType, false);
            this.root = root;
            this.retireOnDeath = retireOnDeath;
            this.followOwner = retireOnDeath || followOwner;
        }

        @Override
        public void onTick(int sceneTime) {
            var owner = getOwner();
            if (followOwner && (owner == null || getScene().getEntityById(owner.getId()) != owner
                    || getScene().getEntityById(root.getId()) != root
                    || (retireOnDeath && (!owner.isAlive() || !root.isAlive())))) {
                getScene().removeEntity(this, VisionType.VisionType_VISION_REMOVE);
                return;
            }
            super.onTick(sceneTime);
        }
    }
}
