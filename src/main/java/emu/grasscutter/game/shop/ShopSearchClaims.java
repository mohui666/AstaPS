package emu.grasscutter.game.shop;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.player.Player;

/** Shares the existing personal browser session without changing the live ShopSearch class schema. */
public final class ShopSearchClaims {
    private ShopSearchClaims() {}

    /** Call on the game logic thread, immediately before the requested operation. */
    public static Player authorize(String token) {
        return ShopSearch.authorize(token, uid -> Grasscutter.getGameServer() == null
                ? null : Grasscutter.getGameServer().getPlayerByUid(uid));
    }
}
