package dev.wareworks.client.handtruck;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.handtruck.HandTruckCarry;
import dev.wareworks.core.handtruck.HandTruckPenalty;
import net.minecraft.client.player.Input;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

/**
 * The hand truck's slow walk on the client (issue #37, ADR-070).
 * <p>
 * Movement is computed by the client, so the penalty is applied where vanilla applies its own slow walks: to the input
 * vector, right after {@code LocalPlayer#aiStep} has read the keys and before it decides about sprinting. Both
 * impulses are scaled by {@code handTruck.speedMultiplier}, and with {@code handTruck.preventSprinting} the forward
 * impulse is capped just below the 0.8 a sprint needs ({@link HandTruckPenalty#scale}) and a running sprint is
 * stopped — the same reason using an item stops a sprint in vanilla. The server config values are synced to the
 * client on login.
 */
public final class HandTruckClientEvents {
    private HandTruckClientEvents() {
    }

    public static void register(IEventBus gameBus) {
        gameBus.addListener(HandTruckClientEvents::onMovementInput);
    }

    private static void onMovementInput(MovementInputUpdateEvent event) {
        Player player = event.getEntity();
        if (!HandTruckCarry.isPenalised(player))
            return;
        boolean preventSprint = WareworksConfig.handTruckPreventsSprinting();
        Input input = event.getInput();
        HandTruckPenalty.Impulse scaled = HandTruckPenalty.scale(input.forwardImpulse, input.leftImpulse,
                WareworksConfig.handTruckSpeedMultiplier(), preventSprint);
        input.forwardImpulse = scaled.forward();
        input.leftImpulse = scaled.left();
        if (preventSprint && player.isSprinting())
            player.setSprinting(false);
    }
}
