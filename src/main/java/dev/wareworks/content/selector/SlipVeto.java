package dev.wareworks.content.selector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.repackager.RepackagerBlockEntity;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The far factory's judge (ADR-085, design §7.6): may this package be unpacked by the Packager an Ender Recipe Selector
 * sits on, and must the machine's filter be set first?
 * <p>
 * Asked twice, with the same answer: by {@link SelectorPackagerItemHandler} for every box a port, a funnel, a frogport
 * or a bee hands the Packager through its item capability, and by {@link EnderSelectorHooks} for a box a player hands
 * over by hand. A refused box is never touched: it stays where it was (the port, the bee, the hand). The judge reads
 * only the Packager, the selector, at most one machine, that machine's six filter neighbours and the item handler of
 * the Packager's unpack target. Nothing else, and no chunk is ever loaded.
 * <p>
 * The steps, in order (the first that fails decides):
 * <ol>
 * <li>no slip: accept, filter untouched;</li>
 * <li>the selector serves another station ({@code FOREIGN_SLIP});</li>
 * <li>the slip is of an older switch of the station it serves ({@code STALE_SLIP});</li>
 * <li>the machine: the linked one (loaded, a saw or basin, within {@code selector.machineRange}), else the Packager's
 * unpack target, else the one machine touching the selector; never another one ({@code NO_MACHINE},
 * {@code MACHINE_NOT_LOADED});</li>
 * <li>a local Recipe Selector writes that machine's filter ({@code CONTESTED});</li>
 * <li>a filter item sits in the machine ({@code FILTER_ITEM});</li>
 * <li>the machine cannot make the slip's result from what is in the box ({@code CANNOT_MAKE});</li>
 * <li>the filter already names the result: accept;</li>
 * <li>else a switch: only with the machine idle and the unpack target empty, then accept with the filter set first;
 * otherwise wait ({@code WAITS_FOR_EMPTY_LINE}).</li>
 * </ol>
 * It never moves an item and never changes the box. The one Packager action it calls is Create's own read-only
 * simulation of an unpack on a copy, for the hand path ({@link #handPathWouldUnpack}).
 */
public final class SlipVeto {
    /**
     * The answer for one box.
     *
     * @param accept    whether the box may be unpacked
     * @param reason    why not ({@link RecipeSelectorStatus#SET} when accepted)
     * @param slip      the box's slip, if it has one
     * @param machine   the machine whose filter must be set first, if {@code mustApply}
     * @param result    the item to set it to
     * @param mustApply whether the filter must be written before the box is unpacked
     * @param detail    what a refusal names (the latched station, the box's station, the unpack target)
     */
    public record Verdict(boolean accept, RecipeSelectorStatus reason, Optional<RecipeSlip> slip,
                          @Nullable SelectorMachine machine, @Nullable Item result, boolean mustApply,
                          List<Component> detail) {
        static Verdict accept(Optional<RecipeSlip> slip) {
            return new Verdict(true, RecipeSelectorStatus.SET, slip, null, null, false, List.of());
        }

        static Verdict apply(RecipeSlip slip, SelectorMachine machine, Item result) {
            return new Verdict(true, RecipeSelectorStatus.SET, Optional.of(slip), machine, result, true, List.of());
        }

        static Verdict refuse(RecipeSelectorStatus reason, Optional<RecipeSlip> slip, Component... detail) {
            return new Verdict(false, reason, slip, null, null, false, List.of(detail));
        }

        /** The action-bar line a player handing the box over is told. */
        public Component message() {
            return WareworksLang.translateDirect(reason.langKey(), detail.toArray());
        }
    }

    private SlipVeto() {
    }

    /** The Ender Recipe Selector sitting on the Packager at {@code packagerPos}, if a loaded one does. */
    public static Optional<RecipeSelectorBlockEntity> selectorOn(Level level, BlockPos packagerPos) {
        if (level == null)
            return Optional.empty();
        for (Direction side : Direction.values()) {
            BlockPos pos = packagerPos.relative(side);
            if (!level.isLoaded(pos))
                continue;
            if (level.getBlockEntity(pos) instanceof RecipeSelectorBlockEntity selector && !selector.isRemoved()
                    && selector.isEnder() && selector.machinePos().equals(packagerPos))
                return Optional.of(selector);
        }
        return Optional.empty();
    }

    /** Whether {@code packager} is one an Ender selector may sit on: a Packager that is not a Repackager. */
    public static boolean isUnpackingPackager(@Nullable Object packager) {
        return packager instanceof PackagerBlockEntity && !(packager instanceof RepackagerBlockEntity);
    }

    /** Judges {@code box} for the Packager {@code packager} and its Ender selector {@code selector}. */
    public static Verdict judge(Level level, PackagerBlockEntity packager, RecipeSelectorBlockEntity selector,
            ItemStack box) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(packager, "packager");
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(box, "box");
        Optional<RecipeSlip> read = RecipeSlip.read(box);
        // 1. A box without a slip leaves the filter alone.
        if (read.isEmpty())
            return Verdict.accept(read);
        RecipeSlip slip = read.get();
        // 2. One station at a time.
        if (selector.latch().isPresent() && !selector.latch().get().equals(slip.origin()))
            return Verdict.refuse(RecipeSelectorStatus.FOREIGN_SLIP, read, Component.literal(slip.from()),
                    Component.literal(selector.lastSlip().map(RecipeSlip::from)
                            .orElseGet(() -> label(selector.latch().get().pos()))));
        // 3. Never an older switch of that station.
        if (selector.latch().isPresent() && slip.seq() < selector.lastSeq())
            return Verdict.refuse(RecipeSelectorStatus.STALE_SLIP, read, Component.literal(slip.from()));
        // 4. The machine: linked, else the unpack target, else the one touching the selector; never a fallback.
        BlockPos packagerPos = packager.getBlockPos();
        BlockPos target = unpackTarget(packager);
        Optional<SelectorMachine> machine;
        if (selector.machineLink().isPresent()) {
            BlockPos linked = selector.machineLink().get();
            if (!level.isLoaded(linked))
                return Verdict.refuse(RecipeSelectorStatus.MACHINE_NOT_LOADED, read);
            if (linked.getCenter().distanceTo(selector.getBlockPos().getCenter())
                    > WareworksConfig.selectorMachineRange())
                return Verdict.refuse(RecipeSelectorStatus.NO_MACHINE, read);
            machine = SelectorMachine.resolve(level, linked);
        } else {
            machine = level.isLoaded(target) ? SelectorMachine.resolve(level, target) : Optional.empty();
            if (machine.isEmpty())
                machine = touchingMachine(level, selector, packagerPos);
        }
        if (machine.isEmpty())
            return Verdict.refuse(RecipeSelectorStatus.NO_MACHINE, read);
        SelectorMachine found = machine.get();
        // 5. One writer per filter (I8).
        if (found.otherWriter(selector.getBlockPos()).isPresent())
            return Verdict.refuse(RecipeSelectorStatus.CONTESTED, read);
        // 6. Never over a filter item (I6).
        if (found.holdsFilterItem())
            return Verdict.refuse(RecipeSelectorStatus.FILTER_ITEM, read);
        // 7. The machine must be able to make the result from what is in the box.
        Optional<Item> result = slip.item();
        if (result.isEmpty() || !SelectorRecipeCheck.canMake(level, found.kind(), result.get(), contents(box)))
            return Verdict.refuse(RecipeSelectorStatus.CANNOT_MAKE, read,
                    result.map(item -> (Component) new ItemStack(item).getHoverName())
                            .orElseGet(() -> Component.literal(slip.result().toString())));
        // 8. Already set.
        if (found.filterReads(result.get()))
            return Verdict.accept(read);
        // 9. A switch: only on an empty line.
        if (!found.idle() || !targetEmpty(level, packager, target))
            return Verdict.refuse(RecipeSelectorStatus.WAITS_FOR_EMPTY_LINE, read,
                    level.isLoaded(target) ? level.getBlockState(target).getBlock().getName()
                            : Component.literal(label(target)));
        return Verdict.apply(slip, found, result.get());
    }

    /**
     * The hand path's pre-check (design §7.6): would Create's own Packager unpack {@code box} at all right now? The
     * same tests {@code PackagerBlock#useItemOn} makes before it unwraps, with Create's read-only simulation on a copy,
     * so the filter is never set for a box the player keeps.
     */
    public static boolean handPathWouldUnpack(PackagerBlockEntity packager, ItemStack box) {
        if (!packager.heldBox.isEmpty() || packager.animationTicks > 0 || !PackageItem.isPackage(box))
            return false;
        return packager.unwrapBox(box.copy(), true);
    }

    /** The block the Packager unpacks into: the one its back touches. */
    public static BlockPos unpackTarget(PackagerBlockEntity packager) {
        BlockState state = packager.getBlockState();
        Direction facing = state.getOptionalValue(PackagerBlock.FACING).orElse(Direction.UP);
        return packager.getBlockPos().relative(facing.getOpposite());
    }

    /** The items in {@code box}. */
    private static List<ItemStack> contents(ItemStack box) {
        ItemStackHandler handler = PackageItem.getContents(box);
        List<ItemStack> items = new ArrayList<>(handler.getSlots());
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty())
                items.add(stack);
        }
        return items;
    }

    /** The one saw or basin touching the selector on a face other than the Packager's, if exactly one does. */
    private static Optional<SelectorMachine> touchingMachine(Level level, RecipeSelectorBlockEntity selector,
            BlockPos packagerPos) {
        Optional<SelectorMachine> found = Optional.empty();
        for (Direction side : Direction.values()) {
            BlockPos pos = selector.getBlockPos().relative(side);
            if (pos.equals(packagerPos) || !level.isLoaded(pos))
                continue;
            Optional<SelectorMachine> machine = SelectorMachine.resolve(level, pos);
            if (machine.isPresent()) {
                if (found.isPresent())
                    return Optional.empty();
                found = machine;
            }
        }
        return found;
    }

    /** Whether the Packager's unpack target holds nothing (all its slots, bounded); no inventory counts as empty. */
    private static boolean targetEmpty(Level level, PackagerBlockEntity packager, BlockPos target) {
        if (!level.isLoaded(target))
            return false;
        Direction facing = packager.getBlockState().getOptionalValue(PackagerBlock.FACING).orElse(Direction.UP);
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, target, facing);
        if (handler == null)
            return true;
        int slots = Math.min(handler.getSlots(), 256);
        for (int slot = 0; slot < slots; slot++) {
            if (!handler.getStackInSlot(slot).isEmpty())
                return false;
        }
        return true;
    }

    private static String label(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
