package dev.wareworks.client.selector;

import java.util.Optional;

import dev.wareworks.content.selector.RecipeSlip;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * The recipe slip in a package's tooltip (ADR-085, design §7.1): one grey line "Recipe slip: <result>" under Create's
 * own address and contents lines, for any package that carries one, wherever it is shown (a chest, a port, a hand).
 * <p>
 * A package is Create's item, so this is a listener on {@link ItemTooltipEvent} rather than an override; it reads the
 * slip from the stack the client already has (the box's {@code custom_data} is synced with it) and adds nothing to any
 * other item. Client only, registered from {@code WareworksClient}.
 */
public final class RecipeSlipTooltip {
    private RecipeSlipTooltip() {
    }

    /** Registers the listener on the NeoForge game event bus. */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(RecipeSlipTooltip::onTooltip);
    }

    private static void onTooltip(ItemTooltipEvent event) {
        Optional<RecipeSlip> slip = RecipeSlip.read(event.getItemStack());
        if (slip.isEmpty())
            return;
        Component result = slip.get().item().map(item -> new ItemStack(item).getHoverName())
                .orElseGet(() -> Component.literal(slip.get().result().toString()));
        event.getToolTip().add(WareworksLang.translateDirect(WareworksLang.RECIPE_SLIP_TOOLTIP, result)
                .withStyle(ChatFormatting.GRAY));
    }
}
