package dev.wareworks.core.terminal;

import java.util.Objects;
import java.util.Optional;

/**
 * One line of the clipboard a player put into a warehouse terminal, as the list order reads it
 * ({@code docs/warehouse-system.md} §3.4.4, M23, issue #19, ADR-036).
 * <p>
 * It is the whole of what Create's clipboard offers that this feature is allowed to believe: the item an entry shows,
 * how many of it the entry asks for, whether it is already ticked off, and where on the clipboard it sits. Everything
 * else an entry carries is deliberately left outside — above all its <b>text</b>, which is a foreign
 * {@code Component} that may bring hover and click events with it ({@code MaterialChecklist} attaches
 * {@code HoverEvent.SHOW_ITEM} to every line of a Schematicannon checklist). The authoritative amount is the entry's
 * own {@code itemAmount} and the authoritative item is its icon; the text is never read, never rendered by us and
 * never copied into one of our payloads.
 * <p>
 * <b>Which entries are an order.</b> {@link #orderable()} is the whole rule, and it is the same reading Create's own
 * stock keeper applies to a checklist clipboard ({@code StockKeeperRequestScreen#requestSchematicList}): an entry
 * without an icon is a page separator and not an item, an entry that is already ticked has been dealt with, and an
 * entry that asks for nothing asks for nothing. A clipboard printed by a Schematicannon contains all three.
 *
 * @param page    the clipboard page the entry sits on, counted from 0
 * @param index   the entry's position on that page, counted from 0. Together with {@link #page()} this is what the
 *                tick mark is written back to, and what is checked again before it is written ({@code §3.4.4})
 * @param key     the item the entry's icon shows, empty for an entry without one — a page separator ({@code ">>>"}).
 *                It is resolved from the <b>icon</b> and never from the text
 * @param amount  how many items the entry asks for ({@code ClipboardEntry#itemAmount}); 0 or less asks for nothing
 * @param checked whether the entry is already ticked off, either by a player, by the Schematicannon that printed it
 *                for material it had already gathered, or by an earlier list order
 * @param <K>     item key type
 */
public record ListEntry<K>(int page, int index, Optional<K> key, int amount, boolean checked) {
    public ListEntry {
        Objects.requireNonNull(key, "key");
        page = Math.max(0, page);
        index = Math.max(0, index);
        amount = Math.max(0, amount);
    }

    /** An entry asking for {@code amount} items of {@code key}. */
    public static <K> ListEntry<K> of(int page, int index, K key, int amount) {
        return new ListEntry<>(page, index, Optional.of(Objects.requireNonNull(key, "key")), amount, false);
    }

    /** An entry that is already ticked off, which a list order never orders again. */
    public static <K> ListEntry<K> checked(int page, int index, K key, int amount) {
        return new ListEntry<>(page, index, Optional.of(Objects.requireNonNull(key, "key")), amount, true);
    }

    /**
     * A page separator: the {@code ">>>"} entry a Schematicannon's checklist puts between its pages, which carries no
     * icon and is therefore not an item.
     */
    public static <K> ListEntry<K> separator(int page, int index) {
        return new ListEntry<>(page, index, Optional.empty(), 0, false);
    }

    /** Whether this entry is something to fetch at all, i.e. whether a list order takes a line from it. */
    public boolean orderable() {
        return key.isPresent() && !checked && amount >= 1;
    }
}
