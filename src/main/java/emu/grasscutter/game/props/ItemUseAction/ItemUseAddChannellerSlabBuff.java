package emu.grasscutter.game.props.ItemUseAction;

import emu.grasscutter.game.props.ItemUseOp;
import emu.grasscutter.game.quest.reconstruction.LegacyChannellerSlabCollection;

public final class ItemUseAddChannellerSlabBuff extends ItemUseInt {
    public ItemUseAddChannellerSlabBuff(String[] useParam) { super(useParam); }

    @Override public ItemUseOp getItemUseOp() { return ItemUseOp.ITEM_USE_ADD_CHANNELLER_SLAB_BUFF; }

    @Override public boolean useItem(UseItemParams params) {
        return LegacyChannellerSlabCollection.collect(params.player, params.usedItemId, this.i, params.count);
    }
}
