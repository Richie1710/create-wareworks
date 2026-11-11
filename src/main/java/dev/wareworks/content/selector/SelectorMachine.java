package dev.wareworks.content.selector;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.logistics.filter.FilterItem;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.item.ItemHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The machine a Recipe Selector sets the recipe filter of (ADR-085, design §4): an up-facing Mechanical Saw, or a
 * Basin (under a Mechanical Mixer, under a Mechanical Press, or with no operator yet).
 * <p>
 * A resolved machine is a view of one block entity in one tick; it holds nothing over a tick. {@link #apply} is the
 * <b>only</b> place Wareworks writes a machine's filter: it never overwrites a filter item (List, Attribute or Package
 * Filter, I6, because {@link FilteringBehaviour#setFilter} would not give it back), switches only an idle machine when
 * asked to, and reads the filter back. The filter tests the result <b>item</b> only, so an {@link Item} is all a
 * selector ever writes.
 */
public final class SelectorMachine {
    /** What kind of machine a selector serves. Saved by {@link #name()}, so a constant is never renamed. */
    public enum Kind {
        /** An up-facing Mechanical Saw: cutting and, where the server allows it, stonecutting recipes. */
        SAW,
        /** A Basin under a Mechanical Mixer. */
        MIXER_BASIN,
        /** A Basin under a Mechanical Press. */
        PRESS_BASIN,
        /** A Basin with no operator above it (yet). */
        BASIN_ALONE;

        /** The kind saved as {@code name}, if it is one. */
        public static Optional<Kind> byName(String name) {
            if (name == null)
                return Optional.empty();
            String upper = name.toUpperCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.name().equals(upper))
                    return Optional.of(kind);
            }
            return Optional.empty();
        }

        /** Whether the machine is a Basin (with or without its operator). */
        public boolean isBasin() {
            return this != SAW;
        }
    }

    /** What {@link #apply} came to. */
    public enum ApplyResult {
        /** The filter already named the item; nothing was written. */
        ALREADY,
        /** The filter was written and reads the item back. */
        SET,
        /** The filter holds a filter item, which is never overwritten (I6). */
        FILTER_ITEM,
        /** The machine is not idle and the caller asked for an idle one. */
        BUSY,
        /** The machine refused the item, or did not read it back. */
        REFUSED;

        /** Whether the filter names the item now. */
        public boolean reads() {
            return this == ALREADY || this == SET;
        }
    }

    /** Why a block is no machine a selector could serve (the placement and link refusals, design §3.3, §4). */
    public enum Refusal {
        /** Nothing with a recipe filter a selector knows. */
        NO_MACHINE,
        /** A horizontal saw: it cuts trees and has no recipe filter. */
        HORIZONTAL_SAW,
        /** A Mechanical Mixer or Press: the selector goes on the Basin below it. */
        OPERATOR
    }

    private final Level level;
    private final BlockPos pos;
    private final Kind kind;
    private final BlockEntity machine;

    private SelectorMachine(Level level, BlockPos pos, Kind kind, BlockEntity machine) {
        this.level = level;
        this.pos = pos.immutable();
        this.kind = kind;
        this.machine = machine;
    }

    /**
     * The machine at {@code pos}, if a selector can serve it there: an up-facing saw or a basin. The caller has checked
     * {@code level.isLoaded(pos)}; a Mixer or Press resolves to nothing (see {@link #refusalAt}).
     */
    public static Optional<SelectorMachine> resolve(Level level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || be.isRemoved())
            return Optional.empty();
        if (be instanceof SawBlockEntity && be.getBlockState().hasProperty(DirectionalKineticBlock.FACING)
                && be.getBlockState().getValue(DirectionalKineticBlock.FACING) == Direction.UP)
            return Optional.of(new SelectorMachine(level, pos, Kind.SAW, be));
        if (be instanceof BasinBlockEntity)
            return Optional.of(new SelectorMachine(level, pos, basinKind(level, pos), be));
        return Optional.empty();
    }

    /**
     * Why the block at {@code pos} is no machine a selector serves; meaningful only where {@link #resolve} found none.
     */
    public static Refusal refusalAt(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SawBlockEntity)
            return Refusal.HORIZONTAL_SAW;
        if (be instanceof MechanicalMixerBlockEntity || be instanceof MechanicalPressBlockEntity)
            return Refusal.OPERATOR;
        return Refusal.NO_MACHINE;
    }

    /** The kind of the basin at {@code pos}: read from the operator two blocks above it, as the basin itself does. */
    private static Kind basinKind(Level level, BlockPos pos) {
        BlockPos operator = pos.above(2);
        if (!level.isLoaded(operator))
            return Kind.BASIN_ALONE;
        BlockEntity above = level.getBlockEntity(operator);
        if (above instanceof MechanicalMixerBlockEntity)
            return Kind.MIXER_BASIN;
        if (above instanceof MechanicalPressBlockEntity)
            return Kind.PRESS_BASIN;
        return Kind.BASIN_ALONE;
    }

    public Kind kind() {
        return kind;
    }

    /** Where the filter is: the saw or the basin itself. */
    public BlockPos filterPos() {
        return pos;
    }

    /** The machine's name, as the game shows it. */
    public Component name() {
        return machine.getBlockState().getBlock().getName();
    }

    /** The machine's recipe filter, or {@code null} if it has none (never for a resolved machine). */
    public FilteringBehaviour filter() {
        return BlockEntityBehaviour.get(machine, FilteringBehaviour.TYPE);
    }

    /** What the filter names now ({@link ItemStack#EMPTY} without a filter). */
    public ItemStack currentFilter() {
        FilteringBehaviour filter = filter();
        return filter == null ? ItemStack.EMPTY : filter.getFilter();
    }

    /** Whether the filter names exactly {@code item} (the item, never its components: the recipe filter ignores them). */
    public boolean filterReads(Item item) {
        return ItemHelper.sameItem(currentFilter(), new ItemStack(item));
    }

    /** Whether the filter holds a List, Attribute or Package Filter, which a selector never overwrites (I6). */
    public boolean holdsFilterItem() {
        return currentFilter().getItem() instanceof FilterItem;
    }

    /**
     * Whether the machine has nothing in it: the saw's inventory is empty; the basin holds nothing in its inventories,
     * tanks and spout buffers, and its operator is not in the middle of a run.
     */
    public boolean idle() {
        if (machine instanceof SawBlockEntity saw)
            return saw.inventory.isEmpty();
        if (machine instanceof BasinBlockEntity basin) {
            if (!basin.isEmpty() || !basin.canContinueProcessing())
                return false;
            BlockPos operator = pos.above(2);
            if (!level.isLoaded(operator))
                return true;
            BlockEntity above = level.getBlockEntity(operator);
            if (above instanceof MechanicalMixerBlockEntity mixer)
                return !mixer.running;
            if (above instanceof MechanicalPressBlockEntity press)
                return !press.getPressingBehaviour().running;
            return true;
        }
        return false;
    }

    /**
     * Another Recipe Selector that writes this machine's filter (I8): one of the six neighbours of {@link #filterPos()}
     * whose front faces it, other than {@code caller}. Six bounded lookups, never a chunk load.
     */
    public Optional<BlockPos> otherWriter(BlockPos caller) {
        for (Direction side : Direction.values()) {
            BlockPos neighbour = pos.relative(side);
            if (neighbour.equals(caller) || !level.isLoaded(neighbour))
                continue;
            if (level.getBlockEntity(neighbour) instanceof RecipeSelectorBlockEntity selector
                    && !selector.isRemoved() && selector.machinePos().equals(pos))
                return Optional.of(neighbour);
        }
        return Optional.empty();
    }

    /**
     * Sets the filter to {@code result} (design §4): already set, never over a filter item, only on an idle machine when
     * {@code requireIdle}, and read back. {@code beforeWrite} runs right before the write, so the caller can record its
     * pending selection before {@link FilteringBehaviour#setFilter} notifies the neighbours (design §6.1).
     */
    public ApplyResult apply(Item result, boolean requireIdle, Runnable beforeWrite) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(beforeWrite, "beforeWrite");
        FilteringBehaviour filter = filter();
        if (filter == null)
            return ApplyResult.REFUSED;
        if (filterReads(result))
            return ApplyResult.ALREADY;
        if (holdsFilterItem())
            return ApplyResult.FILTER_ITEM;
        if (requireIdle && !idle())
            return ApplyResult.BUSY;
        beforeWrite.run();
        if (!filter.setFilter(new ItemStack(result)))
            return ApplyResult.REFUSED;
        return filterReads(result) ? ApplyResult.SET : ApplyResult.REFUSED;
    }
}
