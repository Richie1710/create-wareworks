package dev.wareworks.content.storage;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsInputHandler;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * A click <b>with an item in hand</b> on the store filter slot of a storage location — a warehouse interface, a rack
 * bay or a fluid bay ({@link StorageFilterBehaviour}) — sets the filter <b>on the press</b> ({@code
 * docs/warehouse-system.md} §3.1.1, ADR-028, issue #30).
 * <p>
 * <b>Why the slot needs this at all.</b> Since M16 the slot also carries the storage priority as a hold-to-edit board,
 * so {@link StorageFilterBehaviour#acceptsValueSettings()} is {@code true}, and Create's
 * {@code ValueSettingsInputHandler} then never applies a click itself: it starts the client warmup of
 * {@code ValueSettingsClient}, which sets the filter only when the button is <b>released</b>. Held for more than five
 * ticks the board opens and no filter is set (likelier at a low frame rate); moved off the 4 px sphere before the
 * release — a small mouse movement, drifting while flying — the click is dropped without a word although the hand
 * already swung. On a rack bay a click that missed the sphere puts the item into the bay, which looked like a filter.
 * <p>
 * <b>The split.</b> An item that is a filter — anything but an empty hand, a wrench and the clipboard — has no use for
 * the board, so its click is applied at once; an <b>empty hand</b> and a <b>wrench</b> are left to the warmup exactly
 * as before (a short click with an empty hand clears the filter, holding either opens the board). Everything else
 * Create's handler would let through is let through as well, because {@link #slotUnder} makes the same checks in the
 * same order: the clipboard (Create's own copy and paste), the Mechanical Arm item (its target selection,
 * {@link StorageFilterBehaviour#bypassesInput}), a sneaking, spectating or adventure-mode player
 * ({@code ValueSettingsInputHandler#canInteract}), a fake player ({@link StorageFilterBehaviour#mayInteract}, the M8
 * protection) and a click outside the sphere, so a rack bay's own insert gesture still works one pixel away.
 * <p>
 * <b>Create's own handler applies it.</b> This class cancels nothing and applies nothing. Its listener at
 * {@link EventPriority#HIGH}, on both sides, only <b>marks</b> the dispatch of a click it takes ({@link #isPressOn});
 * for that one dispatch the slot's {@link StorageFilterBehaviour#acceptsValueSettings()} answers {@code false}, so
 * {@code ValueSettingsInputHandler#onBlockActivated} at {@code NORMAL} takes the branch it takes for every value box
 * without settings: it cancels the click with {@code SUCCESS} on both sides and calls {@link
 * FilteringBehaviour#onShortInteract} at once — the very method the release used to reach. That method changes
 * nothing on the client and applies on the server, so a Create filter item is still taken from a survival player's
 * hand, the one it replaces still goes back, and the slot still keeps a sample of one
 * ({@link StorageFilterBehaviour#setFilter}). The client swings once and still sends its use packet
 * ({@code MultiPlayerGameMode#useItemOn} sends it whatever the event answered). A held stack that already <b>is</b>
 * the filter changes nothing and is not applied again ({@link StorageFilterBehaviour#onShortInteract}), so a second
 * click with the same item costs no update packet, no sound and no controller re-read. A second listener at
 * {@link EventPriority#LOWEST}, which also receives cancelled events, ends the mark, so outside the dispatch — the
 * renderer, {@code ValueSettingsPacket#applySettings}, the clipboard — the slot accepts value settings as always.
 * <p>
 * <b>Claims.</b> Because nothing here cancels, a protection listener at {@code HIGHEST} or at {@code HIGH} refuses
 * the click before Create's handler sees it, whatever order the mods were constructed in, and nothing is applied. Not
 * every claim mod refuses at {@code HIGHEST} (a mod built on Architectury refuses through Architectury's own NeoForge
 * listener), and at one priority NeoForge calls listeners in registration order, which follows mod construction, and
 * that runs in parallel: a listener of ours that cancelled at {@code HIGH} would hide the click from a claim listener
 * at {@code HIGH} that happened to register after it. A protection listener at {@code NORMAL} meets Create's handler
 * in registration order, as on every Create value box. The empty hand's and the wrench's release
 * ({@code ValueSettingsPacket}) fires no interaction event at all, so it passes a claim as on every Create value box;
 * that path is Create's and unchanged.
 * <p>
 * <b>Holding the button.</b> Vanilla repeats a held use click every four ticks. A repeat with an item would only find
 * its own sample in the slot, but a Create filter item has left a survival player's hand by then, and the empty hand
 * would start Create's warmup — clearing the filter again on release, or opening the board. A held button that only
 * reaches a slot later, from the face beside it, would set a filter nobody pressed for. The client therefore lets only
 * the first use of a press set a filter and swallows every use click on a storage filter slot from a press it took
 * until the button is released ({@code client.input.StorageFilterPressGuard}): one press sets one filter.
 * <p>
 * No mixin, no packet, no save change.
 */
public final class StorageFilterClicks {
    /**
     * The slot the right-click being dispatched on this thread sets on the press, from the {@code HIGH} listener to the
     * {@code LOWEST} one; {@code null} outside that dispatch and for a click that is not taken. Per thread, because an
     * integrated server dispatches its clicks on its own thread while the client dispatches the same ones on the
     * render thread.
     */
    private static final ThreadLocal<StorageFilterBehaviour> PRESS = new ThreadLocal<>();

    private StorageFilterClicks() {
    }

    /** Registers the two listeners on the NeoForge game event bus. Called once from {@code Wareworks}. */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(EventPriority.HIGH, StorageFilterClicks::markPress);
        gameBus.addListener(EventPriority.LOWEST, true, StorageFilterClicks::endPress);
    }

    /** Marks this dispatch as a press on the slot it takes, or as none; always overwritten, so nothing stale lasts. */
    private static void markPress(PlayerInteractEvent.RightClickBlock event) {
        PRESS.set(takenBy(event.getEntity(), event.getLevel(), event.getPos(), event.getHand(), event.getHitVec()));
    }

    /** Ends the mark, also when a listener in between cancelled the click. */
    private static void endPress(PlayerInteractEvent.RightClickBlock event) {
        PRESS.remove();
    }

    /**
     * Whether the right-click being dispatched on this thread is a press with an item on {@code slot}, which Create's
     * handler is to apply at once ({@link StorageFilterBehaviour#acceptsValueSettings()}).
     */
    public static boolean isPressOn(StorageFilterBehaviour slot) {
        return PRESS.get() == slot;
    }

    /**
     * The store filter slot this right-click sets on the press, or {@code null} for a click that is passed on
     * unchanged (see the class comment). The same answer on both sides; free of the event and visible to tests, which
     * can then tell "set on the press" from "left to the warmup": Create's handler cancels both with the same
     * {@code SUCCESS}.
     */
    @Nullable
    public static StorageFilterBehaviour takenBy(@Nullable Player player, Level level, BlockPos pos,
                                                 InteractionHand hand, @Nullable BlockHitResult hit) {
        // Create's handler acts for the main hand; an off-hand click only gets here when the main hand's was not
        // consumed, so it was never one on this slot.
        if (player == null || hand != InteractionHand.MAIN_HAND || hit == null)
            return null;
        if (!ValueSettingsInputHandler.canInteract(player) || !setsOnPress(player.getMainHandItem()))
            return null;
        return slotUnder(player, level, pos, hit);
    }

    /**
     * Whether a click holding {@code held} is a filter click that has no use for the priority board: not an empty
     * hand, not a wrench (the {@code c:tools/wrench} tag, the one Create's handler tests for its wrench-only boxes) and
     * not Create's clipboard, which copies and pastes the slot through its own handler.
     */
    public static boolean setsOnPress(ItemStack held) {
        return !held.isEmpty() && !held.is(Tags.Items.TOOLS_WRENCH) && !AllBlocks.CLIPBOARD.isIn(held);
    }

    /**
     * The store filter slot whose 4 px sphere {@code hit} lies in, if {@code player} may use it, whatever is in the
     * hand; {@code null} otherwise. The checks are {@code ValueSettingsInputHandler#onBlockActivated}'s, in its order,
     * so a click this answers {@code null} for is exactly a click Create would have passed on to the block: the
     * Mechanical Arm item, a fake player, the wrong face, outside the sphere.
     */
    @Nullable
    public static StorageFilterBehaviour slotUnder(Player player, Level level, BlockPos pos, BlockHitResult hit) {
        if (!(BlockEntityBehaviour.get(level, pos, FilteringBehaviour.TYPE) instanceof StorageFilterBehaviour slot))
            return null;
        ItemStack held = player.getMainHandItem();
        if (slot.bypassesInput(held) || !slot.mayInteract(player) || !slot.isActive())
            return null;
        // Sided#testHit also asks isSideActive, so the aisle face is the only one that answers.
        if (slot.getSlotPositioning() instanceof ValueBoxTransform.Sided sided)
            sided.fromSide(hit.getDirection());
        return slot.testHit(hit.getLocation()) ? slot : null;
    }
}
