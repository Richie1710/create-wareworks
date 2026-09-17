package dev.wareworks.content.storage;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The store filter of a warehouse interface ({@code docs/warehouse-system.md} §3.1.1, ADR-021): Create's
 * {@link FilteringBehaviour} with one change, so that a rack wall of interfaces stays within the update-tag budget.
 * <p>
 * <b>An empty filter writes nothing into client packets.</b> Create's {@code write} emits {@code Filter},
 * {@code FilterAmount} and {@code UpTo} unconditionally, which costs roughly 215 bytes of NBT size accounting in
 * <b>every</b> interface's update tag — and that tag is part of every chunk packet, read by clients with a 2 MB quota
 * (§3.1.1). Almost every interface of a warehouse carries no filter at all, so for those the whole block is skipped;
 * Create's {@code read} builds an empty {@code FilterItemStack} from a missing tag, so an absent entry reads back
 * exactly as "accepts everything". Saves are untouched ({@code clientPacket == false}), so the filter still persists and
 * still travels in schematics.
 * <p>
 * The amount is deliberately not carried either: the slot has no {@code showCount()}, so {@code isCountVisible()} is
 * false and the client never renders a number. Only interfaces a player actually filtered pay for their filter stack,
 * which is the same exposure every Create filter block has (GameTests {@code interfacesummarysyncisbounded} and
 * {@code filtergoggles} pin both directions).
 */
public class StorageFilterBehaviour extends FilteringBehaviour {
    public StorageFilterBehaviour(SmartBlockEntity be, ValueBoxTransform slot) {
        super(be, slot);
    }

    /**
     * Only a real player may change a storage location's partitioning (M8 review fix).
     * <p>
     * Create's {@code ValueSettingsInputHandler} skips the 4 px hit test of a value box entirely for a
     * {@code FakePlayer} and then interacts immediately, so <b>any</b> right-click a deployer (or another mod's
     * automation) aims at this block's aisle face would set the filter, wherever it hits — and
     * {@code onShortInteract} hands a previously set Create filter item to that fake player's inventory, where it is
     * discarded. The aisle face is the one face in-aisle automation can reach, and re-dedicating a chest by accident
     * is permanent (nothing is ever re-shuffled, ADR-021), so fake players are refused here. This is checked before
     * that shortcut, and it also covers the clipboard path, which asks {@code mayInteract} as well.
     */
    @Override
    public boolean mayInteract(Player player) {
        return !(player instanceof FakePlayer) && super.mayInteract(player);
    }

    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        // A missing tag reads back as an empty filter, so an unfiltered interface needs to sync nothing at all.
        if (clientPacket && getFilter().isEmpty())
            return;
        super.write(nbt, registries, clientPacket);
    }
}
