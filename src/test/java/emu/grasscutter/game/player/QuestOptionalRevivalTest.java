package emu.grasscutter.game.player;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.game.world.TestWorld.TestPlayer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ServerResourceFixture.class)
final class QuestOptionalRevivalTest {
    @Test
    void nativeFullHpRestoresOnlyTheCurrentPartyIncludingItsDownedAvatar() {
        var player = new RevivalPlayer();
        var wounded = avatar(1, 7f);
        var downed = avatar(2, 0f);
        var reserve = avatar(3, 13f);
        player.getAvatars().getAvatars().put(1, wounded);
        player.getAvatars().getAvatars().put(2, downed);
        player.getAvatars().getAvatars().put(3, reserve);
        player.getTeamManager().getCurrentTeamInfo().getAvatars().addAll(List.of(1, 2));
        assertTrue(player.getTeamManager().optionalRevivalTeam(1));
        assertEquals(100f, wounded.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        assertEquals(100f, downed.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        assertEquals(100f, downed.getCurrentHp());
        assertEquals(13f, reserve.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
    }

    @Test
    void invalidModeOrUnresolvableRosterDoesNotPartiallyHeal() {
        var player = new RevivalPlayer();
        var wounded = avatar(1, 7f);
        player.getAvatars().getAvatars().put(1, wounded);
        player.getTeamManager().getCurrentTeamInfo().getAvatars().addAll(List.of(1, 2));
        assertFalse(player.getTeamManager().optionalRevivalTeam(4));
        assertFalse(player.getTeamManager().optionalRevivalTeam(1));
        assertEquals(7f, wounded.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        assertTrue(player.getTeamManager().optionalRevivalTeam(0));
    }

    private static Avatar avatar(int id, float hp) {
        var avatar = new Avatar() {
            @Override public int getAvatarId() { return id; }
            @Override public void save() {}
        };
        avatar.setFightProperty(FightProperty.FIGHT_PROP_MAX_HP, 100f);
        avatar.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, hp);
        avatar.setCurrentHp(hp);
        return avatar;
    }

    private static final class RevivalPlayer extends TestPlayer {
        private final TeamManager team = new TeamManager(this);
        @Override public TeamManager getTeamManager() { return team; }
        @Override public void save() {}
    }
}
