package dev.wareworks.content.handtruck;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.core.handtruck.HandTruckPenalty;
import dev.wareworks.registry.WareworksAttachments;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Who is carrying a loaded hand truck, and the part of the penalty that is not the walk itself (issue #37, ADR-070).
 * <p>
 * Only a <b>loaded</b> truck anywhere in the inventory counts — main slots, armour, offhand; an empty one costs nothing.
 * Nothing is scanned for it: vanilla ticks every stack of a player's inventory each tick on both sides, and a loaded
 * truck writes the player's own tick counter ({@code Entity#tickCount}) into the player's transient attachment {@code wareworks:hand_truck_carry} when it is
 * ticked ({@link HandTruckItem#inventoryTick}); {@link #isCarrying} reads that stamp
 * ({@link HandTruckPenalty#withinWindow}). The attachment is never saved and never copied on death.
 * <p>
 * Not the game time: the client's clock is reset from the server every 20 ticks (it jumps on a lagging server) and
 * stands still under {@code /tick freeze}, while players keep ticking. {@code tickCount} is advanced by the level
 * right before each entity tick, is never set from a packet and starts at zero for every new player object.
 * <p>
 * Creative and spectator players are exempt: a building session should not be slowed, and flying makes a walk
 * penalty meaningless. The slow walk and the sprint cap are applied to the movement input on the client
 * ({@code client.handtruck.HandTruckClientEvents}); this class stops elytra gliding on both sides every tick and keeps
 * the server's sprint flag honest.
 */
public final class HandTruckCarry {
    private HandTruckCarry() {
    }

    /** Marks {@code player} as carrying a loaded truck in this tick. */
    static void stamp(Player player) {
        player.setData(WareworksAttachments.HAND_TRUCK_CARRY.get(), (long) player.tickCount);
    }

    /** Whether a loaded truck was in {@code player}'s inventory this tick or the one before. */
    public static boolean isCarrying(Player player) {
        if (!player.hasData(WareworksAttachments.HAND_TRUCK_CARRY.get()))
            return false;
        return HandTruckPenalty.withinWindow(player.tickCount,
                player.getData(WareworksAttachments.HAND_TRUCK_CARRY.get()));
    }

    /** Whether the penalty applies to {@code player} now: carrying, and neither creative nor spectator. */
    public static boolean isPenalised(Player player) {
        return !player.isCreative() && !player.isSpectator() && isCarrying(player);
    }

    /** Both sides, after each player tick: no gliding and, on the server, no sprint flag while carrying. */
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (!isPenalised(player))
            return;
        if (WareworksConfig.handTruckPreventsElytra() && player.isFallFlying())
            player.stopFallFlying();
        if (!player.level().isClientSide && WareworksConfig.handTruckPreventsSprinting() && player.isSprinting())
            player.setSprinting(false);
    }
}
