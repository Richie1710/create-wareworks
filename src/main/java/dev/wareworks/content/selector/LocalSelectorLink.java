package dev.wareworks.content.selector;

import java.util.Optional;

import net.minecraft.core.BlockPos;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.production.SelectorWait;

/**
 * What a production station in local selector mode asks of the Recipe Selector it is linked to (ADR-085, design §3.3,
 * §6.1): whether it still links back to the station, and to set its machine's filter.
 * <p>
 * The station never writes a filter itself; it only asks here, once per arm pass, and commits a switch only after the
 * selector has read the filter back. Implemented by the selector's block entity.
 */
public interface LocalSelectorLink {
    /** The production station this selector is linked to, if any; the mutual position check uses it. */
    Optional<BlockPos> linkedStation();

    /**
     * Sets the machine's filter to {@code target}.
     *
     * @param target    the result the line is to make
     * @param switching whether the station switches its line to {@code target} now (the machine must then be idle); a
     *                  filter that differs from {@code target} without a switch is only rewritten on an idle machine too
     * @return empty when the filter reads {@code target} back, else why it does not ({@link SelectorWait#MACHINE_BUSY},
     *         {@link SelectorWait#FILTER_ITEM}, {@link SelectorWait#FILTER_REFUSED}, {@link SelectorWait#NO_MACHINE},
     *         {@link SelectorWait#MACHINE_NOT_LOADED}, {@link SelectorWait#CONTESTED})
     */
    Optional<SelectorWait> armFor(ItemKey target, boolean switching);

    /** The station let go (switched off, removed, released a hold): forget the selection, never touch the filter. */
    void clearSelection();

    /**
     * The kind of the machine in front of the selector as of now, if there is one: the station keeps it for its pattern
     * check (design §10) and reads it again on every arm pass.
     */
    Optional<SelectorMachine.Kind> machineKind();
}
