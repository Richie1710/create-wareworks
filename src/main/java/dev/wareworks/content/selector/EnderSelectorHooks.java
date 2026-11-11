package dev.wareworks.content.selector;

import java.util.Optional;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The two ways a far factory's Ender Recipe Selector gets to judge a package before Create unpacks it (ADR-085, design
 * §2, §7.6).
 * <ul>
 * <li><b>The item capability</b> of Create's Packager: a second provider for {@code AllBlockEntityTypes.PACKAGER},
 * registered at {@link EventPriority#HIGH}, so NeoForge asks it before Create's own (the first non-null answer wins).
 * It answers a {@link SelectorPackagerItemHandler} only while a loaded Ender selector sits on that Packager, else
 * {@code null}, and Create's provider answers as always. The selector asks NeoForge to look again whenever it is placed,
 * removed or loaded ({@code Level#invalidateCapabilities}). Ports, funnels, frogports, hoppers and bees all hand a box
 * over through this capability.</li>
 * <li><b>The hand</b>: Create's {@code PackagerBlock#useItemOn} unpacks a package from a player's hand directly, past the
 * capability. A {@code RightClickBlock} listener (server side, before the block is used) judges such a box the same
 * way; a refusal cancels the click and says why in the action bar, the box stays in the hand; an accepted switch sets
 * the filter first and lets Create unpack.</li>
 * </ul>
 * Registered once from {@code Wareworks}: the provider on the mod bus, the listener on the game bus. Safe on both dists.
 */
public final class EnderSelectorHooks {
    private EnderSelectorHooks() {
    }

    /** Mod bus: the capability provider, ahead of Create's. */
    public static void registerCapabilities(IEventBus modBus) {
        modBus.addListener(EventPriority.HIGH, EnderSelectorHooks::onRegisterCapabilities);
    }

    /** Game bus: the hand path. */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(EnderSelectorHooks::onRightClickBlock);
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, AllBlockEntityTypes.PACKAGER.get(),
                (packager, side) -> handlerFor(packager));
    }

    /** The veto handler while an Ender selector sits on {@code packager}, else {@code null} (Create answers). */
    static SelectorPackagerItemHandler handlerFor(PackagerBlockEntity packager) {
        if (!SlipVeto.isUnpackingPackager(packager) || packager.isRemoved())
            return null;
        Level level = packager.getLevel();
        if (level == null)
            return null;
        Optional<RecipeSelectorBlockEntity> selector = SlipVeto.selectorOn(level, packager.getBlockPos());
        return selector.map(found -> new SelectorPackagerItemHandler(packager, found)).orElse(null);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide || !(event.getEntity() instanceof ServerPlayer player))
            return;
        ItemStack box = event.getItemStack();
        if (box.isEmpty() || !PackageItem.isPackage(box))
            return;
        // Create's own guard: a sneaking player with an item never reaches the block's use.
        if (player.isSecondaryUseActive())
            return;
        if (!level.isLoaded(event.getPos())
                || !(level.getBlockEntity(event.getPos()) instanceof PackagerBlockEntity packager)
                || !SlipVeto.isUnpackingPackager(packager))
            return;
        Optional<RecipeSelectorBlockEntity> selector = SlipVeto.selectorOn(level, event.getPos());
        if (selector.isEmpty() || !SlipVeto.handPathWouldUnpack(packager, box))
            return;
        RecipeSelectorBlockEntity found = selector.get();
        SlipVeto.Verdict verdict;
        try {
            verdict = SlipVeto.judge(level, packager, found, box);
        } catch (RuntimeException error) {
            found.fail(error, box);
            refuse(event, player, SlipVeto.Verdict.refuse(RecipeSelectorStatus.FAILED, Optional.empty()));
            return;
        }
        if (!verdict.accept()) {
            found.refused(verdict.reason(), box, true, verdict.detail());
            refuse(event, player, verdict);
            return;
        }
        if (verdict.mustApply()) {
            SelectorMachine.ApplyResult applied = verdict.machine().apply(verdict.result(), true, () -> {
            });
            if (!applied.reads()) {
                RecipeSelectorStatus reason = applied == SelectorMachine.ApplyResult.BUSY
                        ? RecipeSelectorStatus.WAITS_FOR_EMPTY_LINE
                        : applied == SelectorMachine.ApplyResult.FILTER_ITEM ? RecipeSelectorStatus.FILTER_ITEM
                                : RecipeSelectorStatus.FILTER_REFUSED;
                found.refused(reason, box, true);
                refuse(event, player, SlipVeto.Verdict.refuse(reason, verdict.slip(),
                        level.getBlockState(SlipVeto.unpackTarget(packager)).getBlock().getName()));
                return;
            }
        }
        // Create unpacks it in its own useItemOn right after this listener.
        found.accepted(verdict.slip());
    }

    private static void refuse(PlayerInteractEvent.RightClickBlock event, ServerPlayer player,
            SlipVeto.Verdict verdict) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        player.displayClientMessage(verdict.message(), true);
    }
}
