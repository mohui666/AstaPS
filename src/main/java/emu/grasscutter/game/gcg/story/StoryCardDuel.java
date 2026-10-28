package emu.grasscutter.game.gcg.story;

import java.util.*;

/** Small explicit replacement rules, not an emulation of the unavailable native GCG skill VM. */
final class StoryCardDuel {
    private StoryCardDuel() {}

    static StoryCardState.Duel create(StoryCardCatalog catalog, StoryCardCatalog.Stage stage,
            int questStart, int level, emu.grasscutter.game.world.Position anchor, Random random) {
        var resources = catalog.level(level);
        var game = new StoryCardState.Duel();
        game.setQuestId(stage.quest()); game.setQuestStart(questStart); game.setLevelId(level);
        game.setSceneId(stage.scene()); game.setAnchor(anchor.clone()); game.setTutorial(stage.tutorial());
        game.setPlayerMaxHp(resources.playerHp()); game.setPlayerHp(resources.playerHp());
        game.setOpponentMaxHp(resources.opponentHp()); game.setOpponentHp(resources.opponentHp());
        game.setRound(1); game.setActionPoints(catalog.actionPoints());
        for (var card : catalog.cards()) for (int i = 0; i < card.copies(); i++) game.getDeck().add(card.id());
        Collections.shuffle(game.getDeck(), random);
        draw(game, resources.firstDraw(), resources.handLimit());
        return game;
    }

    static boolean play(StoryCardCatalog catalog, StoryCardState.Duel game, int handIndex) {
        if (game.getOutcome() != 0 || handIndex < 0 || handIndex >= game.getHand().size()) return false;
        var card = catalog.card(game.getHand().get(handIndex));
        if (card == null || game.getActionPoints() < card.cost()
                || card.heal() > 0 && game.getPlayerHp() == game.getPlayerMaxHp()) return false;
        game.setActionPoints(game.getActionPoints() - card.cost());
        game.getDiscard().add(game.getHand().remove(handIndex));
        game.setOpponentHp(Math.max(0, game.getOpponentHp() - card.damage()));
        game.setPlayerHp(Math.min(game.getPlayerMaxHp(), game.getPlayerHp() + card.heal()));
        game.setShield(game.getShield() + card.shield());
        if (game.getOpponentHp() == 0) game.setOutcome(1);
        game.setPage(Math.min(game.getPage(), Math.max(0, (game.getHand().size() - 1) / 5)));
        return true;
    }

    static boolean endRound(StoryCardCatalog catalog, StoryCardState.Duel game) {
        if (game.getOutcome() != 0) return false;
        int damage = game.getRound() % 3 == 0 ? catalog.enemyHeavyDamage() : catalog.enemyDamage();
        game.setPlayerHp(Math.max(0, game.getPlayerHp() - Math.max(0, damage - game.getShield())));
        game.setShield(0);
        if (game.getPlayerHp() == 0 || game.getRound() >= catalog.roundLimit()) game.setOutcome(2);
        else {
            game.setRound(game.getRound() + 1); game.setActionPoints(catalog.actionPoints());
            var resources = catalog.level(game.getLevelId());
            draw(game, resources.drawPerRound(), resources.handLimit());
        }
        return true;
    }

    static void draw(StoryCardState.Duel game, int count, int limit) {
        for (int i = 0; i < count && game.getDrawIndex() < game.getDeck().size(); i++) {
            int card = game.getDeck().get(game.getDrawIndex());
            game.setDrawIndex(game.getDrawIndex() + 1);
            if (game.getHand().size() < limit) game.getHand().add(card);
            else game.getDiscard().add(card);
        }
    }
}
