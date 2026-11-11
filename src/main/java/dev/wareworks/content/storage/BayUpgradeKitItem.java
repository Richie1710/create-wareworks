package dev.wareworks.content.storage;

import java.util.Objects;

import dev.wareworks.core.storage.BayUpgrade;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

/**
 * A rack or fluid bay upgrade kit (ADR-083): right-clicked on a bay of the tier it starts from, the bay becomes the
 * next tier where it stands, with its goods, filter, priority and address ({@link BayUpgrades}).
 * <p>
 * The click is answered in {@link #onItemUseFirst}, which NeoForge runs <b>before</b> the clicked block's own use, so a
 * bay's hand gesture can never store a kit, and no block snapshot capture of {@code Item#useOn} gets in the way of the
 * swap. Everywhere but on a bay the kit passes and does nothing.
 */
public class BayUpgradeKitItem extends Item {
    private final BayUpgrade step;

    public BayUpgradeKitItem(Properties properties, BayUpgrade step) {
        super(properties);
        this.step = Objects.requireNonNull(step, "step");
    }

    /** The one step up the ladder this kit makes. */
    public BayUpgrade step() {
        return step;
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return BayUpgrades.use(stack, context, step);
    }
}
