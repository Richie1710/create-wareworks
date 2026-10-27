package dev.wareworks.content.handtruck;

import java.util.ArrayList;
import java.util.List;

import dev.wareworks.util.WareworksLang;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * The hand truck's game-bus listeners (issue #37, ADR-070), registered once from the {@code Wareworks} constructor.
 * Together with the item's own methods they keep a loaded truck in its player's inventory:
 * <ul>
 * <li>every menu a server player opens, and the player's own inventory menu, gets a {@link HandTruckSlotGuard};</li>
 * <li>a right-click on an entity with a loaded truck is cancelled — item frames, armour stands, allays, a donkey's
 * chest, villagers never receive it;</li>
 * <li>a loaded truck thrown out of a screen is put back into the inventory; only with no room there does it tip over
 * ({@link HandTruckSpill});</li>
 * <li>a loaded truck that becomes an item entity anyway cannot be picked up, and tips over on its first tick
 * ({@link HandTruckItem#onEntityItemUpdate});</li>
 * <li>a player who dies drops the plain stacks instead of the loaded truck, before any gravestone mod sees the drops;
 * with {@code keepInventory} it simply stays loaded;</li>
 * <li>{@link HandTruckCarry#onPlayerTick} stops gliding and sprinting while carrying.</li>
 * </ul>
 */
public final class HandTruckHooks {
    private HandTruckHooks() {
    }

    public static void register(IEventBus gameBus) {
        gameBus.addListener(HandTruckHooks::onContainerOpen);
        gameBus.addListener(HandTruckHooks::onLoggedIn);
        gameBus.addListener(HandTruckHooks::onRespawn);
        gameBus.addListener(HandTruckHooks::onEntityInteract);
        gameBus.addListener(HandTruckHooks::onEntityInteractSpecific);
        gameBus.addListener(HandTruckHooks::onToss);
        gameBus.addListener(HandTruckHooks::onEntityJoin);
        gameBus.addListener(EventPriority.HIGHEST, HandTruckHooks::onDrops);
        gameBus.addListener(HandTruckCarry::onPlayerTick);
    }

    private static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player)
            HandTruckSlotGuard.attach(player, event.getContainer());
    }

    private static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            HandTruckSlotGuard.attach(player, player.inventoryMenu);
    }

    /** A respawn makes a new player object with a new inventory menu. */
    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            HandTruckSlotGuard.attach(player, player.inventoryMenu);
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (HandTruckItem.isLoaded(event.getItemStack())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    private static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (HandTruckItem.isLoaded(event.getItemStack())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    /**
     * A loaded truck thrown out of a screen (the drop key over a slot, or dropped off the cursor) goes back into the
     * inventory. The drop key on the held item never gets here: {@link HandTruckItem#onDroppedByPlayer} refuses it
     * first. A truck left on the cursor of a player who died or disconnected reaches this from
     * {@code AbstractContainerMenu#removed}, after the death drops and after the player was saved: it tips over where
     * the player stands instead, since that inventory is never used again.
     */
    private static void onToss(ItemTossEvent event) {
        Player player = event.getPlayer();
        ItemStack truck = event.getEntity().getItem();
        if (player.level().isClientSide || !HandTruckItem.isLoaded(truck))
            return;
        event.setCanceled(true);
        ItemStack back = truck.copy();
        event.getEntity().setItem(ItemStack.EMPTY);
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.hasDisconnected() || !player.isAlive()
                || player.isRemoved()) {
            // The cursor stack of a player who died or left: vanilla drops it from AbstractContainerMenu#removed after
            // the death drops ran or the player was saved, so an inventory would never be read again. It tips over.
            HandTruckSpill.spillInto(player.level(), player.position(), back);
            return;
        }
        if (!player.getInventory().add(back))
            HandTruckSpill.spillInto(player.level(), player.position(), back);
        HandTruckRules.tell(player, WareworksLang.HAND_TRUCK_STAYS_WITH_YOU);
    }

    /** No player or mob picks a loaded truck up in the moment before it tips over. */
    private static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof ItemEntity item
                && HandTruckItem.isLoaded(item.getItem()))
            item.setNeverPickUp();
    }

    /** A dead player's loaded trucks are dropped as the plain stacks they hold. */
    private static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Player player))
            return;
        Level level = player.level();
        if (level.isClientSide)
            return;
        List<ItemEntity> replaced = new ArrayList<>();
        List<ItemEntity> spilled = new ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            if (!HandTruckItem.isLoaded(drop.getItem()))
                continue;
            replaced.add(drop);
            for (ItemStack stack : HandTruckSpill.stacks(drop.getItem(), level.registryAccess())) {
                ItemEntity entity = new ItemEntity(level, drop.getX(), drop.getY(), drop.getZ(), stack);
                entity.setDefaultPickUpDelay();
                spilled.add(entity);
            }
        }
        if (replaced.isEmpty())
            return;
        event.getDrops().removeAll(replaced);
        event.getDrops().addAll(spilled);
    }
}
