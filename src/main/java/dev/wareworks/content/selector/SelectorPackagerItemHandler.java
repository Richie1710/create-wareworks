package dev.wareworks.content.selector;

import java.util.Objects;
import java.util.Optional;

import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerItemHandler;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The item handler of a far factory's Packager while an Ender Recipe Selector sits on it (ADR-085, design §7.6):
 * Create's own {@link PackagerItemHandler}, with the selector's veto in front of every insert.
 * <p>
 * A subclass, because Create asks {@code instanceof PackagerItemHandler} (a frogport never pulls from one, a Packager
 * never packs from one). Provided by {@link EnderSelectorHooks} above Create's own provider, and only while a loaded
 * Ender selector touches the Packager; otherwise Create's handler answers as always.
 * <p>
 * An insert Create would refuse anyway is refused unchanged. Otherwise {@link SlipVeto#judge} decides: a refused box is
 * returned whole, so it stays in the port or bee that offered it; an accepted switch sets the machine's filter
 * <b>before</b> Create unpacks the box (only on a real insert, never in a simulation), and only then is the box handed to
 * Create's own insert. Any error refuses the box (fail closed).
 */
public class SelectorPackagerItemHandler extends PackagerItemHandler {
    private final PackagerBlockEntity packager;
    private final RecipeSelectorBlockEntity selector;

    public SelectorPackagerItemHandler(PackagerBlockEntity packager, RecipeSelectorBlockEntity selector) {
        super(packager);
        this.packager = Objects.requireNonNull(packager, "packager");
        this.selector = Objects.requireNonNull(selector, "selector");
    }

    /** The Ender selector this handler asks. */
    public RecipeSelectorBlockEntity selector() {
        return selector;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty())
            return stack;
        Level level = packager.getLevel();
        if (level == null || level.isClientSide)
            return super.insertItem(slot, stack, simulate);
        RecipeSelectorBlockEntity selector = this.selector;
        if (selector.isRemoved()) {
            // Fail closed (I4, review fix): a handler somebody kept (a port's capability cache) outlives the selector
            // when only the selector's chunk unloads; the selector is still there, merely out of reach, so the box
            // waits. Loaded and removed means broken, or replaced by a new instance after a reload: whichever Ender
            // selector sits on the Packager now judges, and without one Create's own insert answers as always.
            // Not guarded: a lookup made while the selector's chunk is unloaded never sees it (SlipVeto#selectorOn
            // reads loaded neighbours only) and gets Create's handler; the return input's quarantine is the backstop.
            if (!level.isLoaded(selector.getBlockPos()))
                return stack;
            Optional<RecipeSelectorBlockEntity> current = SlipVeto.selectorOn(level, packager.getBlockPos());
            if (current.isEmpty())
                return super.insertItem(slot, stack, simulate);
            selector = current.get();
        }
        if (super.insertItem(slot, stack, true).getCount() == stack.getCount())
            return stack;
        SlipVeto.Verdict verdict;
        try {
            verdict = SlipVeto.judge(level, packager, selector, stack);
        } catch (RuntimeException error) {
            selector.fail(error, stack);
            return stack;
        }
        if (!verdict.accept()) {
            selector.refused(verdict.reason(), stack, !simulate, verdict.detail());
            return stack;
        }
        if (!simulate && verdict.mustApply()) {
            SelectorMachine.ApplyResult applied = verdict.machine().apply(verdict.result(), true, () -> {
            });
            if (!applied.reads()) {
                selector.refused(applied == SelectorMachine.ApplyResult.BUSY
                        ? RecipeSelectorStatus.WAITS_FOR_EMPTY_LINE
                        : applied == SelectorMachine.ApplyResult.FILTER_ITEM ? RecipeSelectorStatus.FILTER_ITEM
                                : RecipeSelectorStatus.FILTER_REFUSED, stack, true);
                return stack;
            }
        }
        ItemStack rest = super.insertItem(slot, stack, simulate);
        if (!simulate && rest.getCount() < stack.getCount())
            selector.accepted(verdict.slip());
        return rest;
    }
}
