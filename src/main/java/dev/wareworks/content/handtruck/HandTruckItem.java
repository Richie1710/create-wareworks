package dev.wareworks.content.handtruck;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The hand truck (issue #37, ADR-070, {@code docs/hand-truck.md}): a held item that carries one chest or barrel with
 * everything in it.
 * <ul>
 * <li><b>Empty</b>, right-clicked on a carriable container: the container goes onto the truck
 * ({@link HandTruckPickup}). It costs nothing to have an empty truck in the inventory.</li>
 * <li><b>Loaded</b>, right-clicked on a surface: the container is set down again, facing the player, with exactly what
 * it held ({@link HandTruckPlacement}). While it is anywhere in the inventory, the player walks slowly, cannot sprint
 * and cannot glide ({@link HandTruckCarry}).</li>
 * </ul>
 * Both clicks are answered in {@link #onItemUseFirst}, which NeoForge runs <b>before</b> the clicked block's own use:
 * a carriable chest never opens under an empty truck, and no block's own click — a depot, a deployer, a station — ever
 * receives a loaded one.
 * <p>
 * <b>A loaded truck never leaves the player's inventory</b>, which is what keeps the item conservation invariant and
 * keeps a full chest out of every other container: it fits into no container item ({@link #canFitInsideContainerItems}),
 * the drop key is refused ({@link #onDroppedByPlayer}), menus hand it straight back ({@link HandTruckSlotGuard}),
 * entities cannot take it and a thrown one is returned ({@link HandTruckHooks}), and one that becomes an item entity
 * anyway tips over into plain stacks on its first tick ({@link #onEntityItemUpdate}, {@link HandTruckSpill}).
 */
public class HandTruckItem extends Item {
    public HandTruckItem(Properties properties) {
        super(properties);
    }

    /** Whether {@code stack} is a hand truck with a container on it. */
    public static boolean isLoaded(ItemStack stack) {
        return stack.getItem() instanceof HandTruckItem && stack.has(WareworksDataComponents.HAND_TRUCK_LOAD.get());
    }

    /** What a loaded truck carries, or {@code null} for anything else. */
    @Nullable
    public static HandTruckLoad load(ItemStack stack) {
        return stack.getItem() instanceof HandTruckItem ? stack.get(WareworksDataComponents.HAND_TRUCK_LOAD.get())
                : null;
    }

    /** Whether {@code player} holds a loaded truck anywhere: inventory, armour, offhand or on the cursor. */
    public static boolean holdsLoadedTruck(Player player) {
        for (List<ItemStack> compartment : List.of(player.getInventory().items, player.getInventory().armor,
                player.getInventory().offhand))
            for (ItemStack stack : compartment)
                if (isLoaded(stack))
                    return true;
        AbstractContainerMenu menu = player.containerMenu;
        return isLoaded(menu.getCarried()) || isLoaded(player.inventoryMenu.getCarried());
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (isLoaded(stack))
            return HandTruckPlacement.unload(stack, context);
        return HandTruckPickup.load(stack, context);
    }

    /** A loaded truck goes into no shulker box, bundle or backpack that follows the loader's convention. */
    @Override
    public boolean canFitInsideContainerItems(ItemStack stack) {
        return !isLoaded(stack);
    }

    /**
     * The drop key is refused for a loaded truck. The client has already taken it out of the slot by the time the server
     * answers, so the slot is marked as unknown to the client and the next broadcast puts the truck back.
     */
    @Override
    public boolean onDroppedByPlayer(ItemStack stack, Player player) {
        if (!isLoaded(stack))
            return true;
        if (player instanceof ServerPlayer serverPlayer) {
            HandTruckRules.tell(serverPlayer, WareworksLang.HAND_TRUCK_DROP_REFUSED);
            int selected = serverPlayer.getInventory().selected;
            for (AbstractContainerMenu menu : List.of(serverPlayer.containerMenu, serverPlayer.inventoryMenu))
                menu.findSlot(serverPlayer.getInventory(), selected)
                        .ifPresent(slot -> menu.setRemoteSlot(slot, ItemStack.EMPTY));
        }
        return false;
    }

    /**
     * A loaded truck that became an item entity tips over on its first tick: its contents, the container's block item
     * and the empty truck lie there instead. Entities tick before block entities, so no hopper, funnel or chute ever
     * sees the loaded truck.
     */
    @Override
    public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entity) {
        if (entity.level().isClientSide || !isLoaded(stack))
            return false;
        HandTruckSpill.spillInto(entity.level(), entity.position(), stack);
        entity.discard();
        return true;
    }

    /** Writes the carry stamp that the slow walk reads ({@link HandTruckCarry}); no scan of anything. */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (entity instanceof Player player && isLoaded(stack))
            HandTruckCarry.stamp(player);
    }

    @Override
    public Component getName(ItemStack stack) {
        HandTruckLoad load = load(stack);
        if (load == null)
            return super.getName(stack);
        return WareworksLang.translateDirect(WareworksLang.HAND_TRUCK_LOADED, load.displayName());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        HandTruckLoad load = load(stack);
        if (load == null)
            return;
        if (load.hasUnrolledLoot())
            tooltip.add(WareworksLang.translateDirect(WareworksLang.HAND_TRUCK_CARRYING_LOOT)
                    .withStyle(ChatFormatting.GRAY));
        else
            tooltip.add(WareworksLang.translateDirect(WareworksLang.HAND_TRUCK_CARRYING, load.stackCount())
                    .withStyle(ChatFormatting.GRAY));
        tooltip.add(WareworksLang.translateDirect(WareworksLang.HAND_TRUCK_SLOWED).withStyle(ChatFormatting.GOLD));
    }
}
