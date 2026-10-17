package dev.wareworks.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.simibubi.create.CreateClient;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsClient;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsInputHandler;

import dev.wareworks.content.storage.StorageFilterClicks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * One press sets one filter, and only a press does (issue #30, ADR-028, the client half of
 * {@link StorageFilterClicks}).
 * <p>
 * <b>Only the first use of a press.</b> Vanilla repeats a held use click every four ticks
 * ({@code Minecraft#handleKeybinds}: the key is down and {@code rightClickDelay} has run out), and NeoForge's
 * {@link InputEvent.InteractionKeyMappingTriggered} cannot tell such a repeat from the click a press queued. A hold
 * that starts somewhere else and slides onto a filter slot would then set the filter on one of its repeats: a player
 * pouring cobblestone into a rack bay one at a time with the button held, sweeping along the wall, would dedicate the
 * bay without pressing anything, and a bucket held on a fluid bay could leave an empty bucket as the filter. So the
 * guard remembers whether the current press has already been used once ({@code pressUsed}): set by every main-hand
 * use while the button is still down, wherever it went, and cleared by the use key's release. A use of a press that
 * was already used, which a storage filter slot would take, is swallowed. Before issue #30 such a repeat only started
 * Create's warmup, which then opened the board or was cancelled by the drift; leaving it to that warmup is not
 * possible any more, because the slot takes a click with an item before the warmup is asked, so it does nothing at
 * all now, and the board is still a deliberate hold with an empty hand away.
 * <p>
 * <b>Nothing after the filter was set.</b> While the use button that set a store filter on the press stays down, the
 * repeats do nothing on any storage filter slot. A repeat with the same item would only find its own sample in the
 * slot and change nothing, but a Create filter item has left a survival player's hand once the server applied it, and
 * the now empty hand is exactly the input Create's {@code ValueSettingsInputHandler} answers with its warmup: a
 * release a moment later would clear the filter again and give the filter item back, a longer hold would open the
 * priority board. Create guards its own warmup the same way ({@code ValueSettingsClient#cancelIfWarmupAlreadyStarted}),
 * and nothing on the server can tell a repeat from a new click, so the guard is here.
 * <p>
 * It works on {@link InputEvent.InteractionKeyMappingTriggered}, which {@code Minecraft#startUseItem} posts before it
 * asks the hit block anything: a swallowed use is never sent to the server, so client and server agree that nothing
 * happened, and it does not swing. A click elsewhere — a block placed past the slot, a rack bay's insert one pixel
 * beside it — is not touched. The press ends with the use key's own release ({@link InputEvent.MouseButton},
 * {@link InputEvent.Key}), so a release and a new press that both come before the next tick still make a new press
 * (a once-a-tick look at the key would find it down again), and once a tick by the key's state, for a release that a
 * screen or a lost window focus swallowed. Two whole clicks before one tick count as one press:
 * {@code Minecraft#handleKeybinds} drains both queued clicks in one {@code consumeClick} loop after both releases
 * were handled, so the second is swallowed as a repeat. That is harmless, because it holds the same item and would set
 * the same filter, and for a Create filter item that has left the hand, swallowing it is the point of the guard. A
 * click that queues no press of the button — a script that only calls {@code KeyMapping#click} — finds the key up and
 * is a press of its own.
 * <p>
 * The other way round, too: while Create's own warmup runs on a block — an empty hand or a wrench held on its slot — a
 * use on that slot is swallowed as well, also when the player has scrolled to an item meanwhile. Create cancels such a
 * repeat itself, but only on the client ({@code ValueSettingsClient#cancelIfWarmupAlreadyStarted}); the use packet is
 * sent all the same, and the server, which knows nothing of the warmup, would set the item {@link StorageFilterClicks}
 * marks there, while the warmup goes on to its own release or board.
 * <p>
 * Client only, registered by {@code WareworksClient}; the state is two flags of the one local player.
 */
public final class StorageFilterPressGuard {
    /** A use press set a store filter, and the use key has not been released since. */
    private static boolean armed;
    /**
     * The current press of the use key has been used once already, so the next use while it stays down is vanilla's
     * repeat, not a press.
     */
    private static boolean pressUsed;

    private StorageFilterPressGuard() {
    }

    /** Registers the guard on the NeoForge game event bus. Called once from {@code WareworksClient}. */
    public static void register(IEventBus gameBus) {
        // Also when another mod cancelled the use: it was a use of this press all the same.
        gameBus.addListener(EventPriority.NORMAL, true, StorageFilterPressGuard::onUseInput);
        gameBus.addListener(StorageFilterPressGuard::onMouseButton);
        gameBus.addListener(StorageFilterPressGuard::onKey);
        gameBus.addListener(StorageFilterPressGuard::onClientTick);
    }

    private static void onUseInput(InputEvent.InteractionKeyMappingTriggered event) {
        // The main hand is asked first, once per use, and a cancelled event ends startUseItem for both hands.
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND)
            return;
        boolean repeat = pressUsed;
        // A click whose button is already up again was a whole press of its own; the next one is a new press.
        pressUsed = useKey().isDown();
        if (event.isCanceled())
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK)
            return;
        if (armed || createWarmupRunsAt(hit)) {
            if (ValueSettingsInputHandler.canInteract(player)
                    && StorageFilterClicks.slotUnder(player, level, hit.getBlockPos(), hit) != null)
                swallow(event);
            return;
        }
        if (StorageFilterClicks.takenBy(player, level, hit.getBlockPos(), InteractionHand.MAIN_HAND, hit) != null) {
            // A held button that slid onto the slot from elsewhere did not press it.
            if (repeat)
                swallow(event);
            // Either way, the rest of this hold does nothing on any slot.
            armed = true;
        }
    }

    /** The use does nothing: not sent, not swung, not given to the off hand. */
    private static void swallow(InputEvent.InteractionKeyMappingTriggered event) {
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    /** Whether Create's own hold-to-edit warmup was started on the block under the crosshair and still runs. */
    private static boolean createWarmupRunsAt(BlockHitResult hit) {
        ValueSettingsClient warmup = CreateClient.VALUE_SETTINGS_HANDLER;
        return warmup.interactHeldTicks != -1 && hit.getBlockPos().equals(warmup.interactHeldPos);
    }

    private static void onMouseButton(InputEvent.MouseButton.Post event) {
        if (event.getAction() == InputConstants.RELEASE && useKey().matchesMouse(event.getButton()))
            released();
    }

    private static void onKey(InputEvent.Key event) {
        if (event.getAction() == InputConstants.RELEASE && useKey().matches(event.getKey(), event.getScanCode()))
            released();
    }

    private static void onClientTick(ClientTickEvent.Pre event) {
        if ((armed || pressUsed) && !useKey().isDown())
            released();
    }

    private static void released() {
        armed = false;
        pressUsed = false;
    }

    private static KeyMapping useKey() {
        return Minecraft.getInstance().options.keyUse;
    }
}
