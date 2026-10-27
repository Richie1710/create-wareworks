package dev.wareworks.content.handtruck;

import dev.wareworks.util.WareworksLang;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Hands a loaded hand truck straight back to its player when it lands in any slot of a menu that is not the player's
 * own inventory (issue #37, ADR-070): a chest, a crafter, an anvil, a furnace, a villager's trade, a Create toolbox,
 * the 2x2 crafting grid of the player's own inventory screen.
 * <p>
 * A menu tells its listeners about every slot that changed in {@code broadcastChanges}, and a click packet runs the
 * click and that broadcast in one task ({@code ServerGamePacketListenerImpl#handleContainerClick}) — before any hopper,
 * funnel or arm ticks. So this covers picking and placing, Shift-click, hotbar swaps, dragging and double-click
 * collecting, in every menu whose slots are real slots. The truck leaves the slot through the slot's own
 * {@code set}, so the container really loses it, and goes back into the player's inventory; with no room there, onto
 * the cursor; with neither, it tips over at the player's feet ({@link HandTruckSpill}).
 * <p>
 * One instance per menu, added to every menu a server player opens and to the player's own inventory menu
 * ({@link HandTruckHooks}).
 */
public final class HandTruckSlotGuard implements ContainerListener {
    private final ServerPlayer player;

    private HandTruckSlotGuard(ServerPlayer player) {
        this.player = player;
    }

    /** Adds a guard for {@code player} to {@code menu}. */
    public static void attach(ServerPlayer player, AbstractContainerMenu menu) {
        menu.addSlotListener(new HandTruckSlotGuard(player));
    }

    @Override
    public void slotChanged(AbstractContainerMenu menu, int slotIndex, ItemStack stack) {
        if (!HandTruckItem.isLoaded(stack) || slotIndex < 0 || slotIndex >= menu.slots.size())
            return;
        Slot slot = menu.getSlot(slotIndex);
        if (slot.container == player.getInventory())
            return;
        if (!HandTruckItem.isLoaded(slot.getItem()))
            return;
        // Cleared in place first: the broadcast that called this still holds that very stack and sends it to the
        // client right after this returns, so it must read as empty by then.
        ItemStack truck = slot.getItem().copyAndClear();
        slot.set(ItemStack.EMPTY);
        if (!player.getInventory().add(truck)) {
            if (menu.getCarried().isEmpty())
                menu.setCarried(truck);
            else
                HandTruckSpill.spillInto(player.level(), player.position(), truck);
        }
        HandTruckRules.tell(player, WareworksLang.HAND_TRUCK_STAYS_WITH_YOU);
        // The menu remembers the truck as this slot's last content, so without a second broadcast a truck put into the
        // same slot again before the next tick would not be reported. This one sees the empty slot and the truck back
        // in the inventory; it calls this guard again only for slots that hold no loaded truck any more.
        menu.broadcastChanges();
    }

    @Override
    public void dataChanged(AbstractContainerMenu menu, int dataSlotIndex, int value) {
    }
}
