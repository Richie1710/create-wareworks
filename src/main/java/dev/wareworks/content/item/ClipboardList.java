package dev.wareworks.content.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardBlockItem;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;

import dev.wareworks.core.terminal.ListEntry;
import dev.wareworks.core.terminal.ListLine;
import net.minecraft.world.item.ItemStack;

/**
 * The adapter between Create's clipboard item and a list order ({@code docs/warehouse-system.md} §3.4.4, M23,
 * issue #19): it reads the entries a clipboard carries and it writes the tick marks back.
 * <p>
 * <b>Everything here is verified against Create 6.0.10's own sources</b>, because the clipboard belongs to another mod:
 * <ul>
 * <li>a clipboard's whole list is one <b>data component</b> on the item stack
 * ({@code AllDataComponents.CLIPBOARD_CONTENT}, a {@code DataComponentType<ClipboardContent>}), so reading and writing
 * it is {@code stack.get}/{@code stack.set} and needs no packet, no mixin and no reflection;</li>
 * <li>{@link ClipboardEntry#readAll(ItemStack)} hands out <b>fresh, mutable page lists</b> — Create's own comment there
 * says so — which is the sanctioned round trip: read, put the changed entries in, {@link ClipboardContent#setPages} and
 * set the component again. Only the two <b>list</b> levels are re-created, though: the {@link ClipboardEntry} objects
 * inside them are the very instances the old {@code ClipboardContent} still holds, and a data component value is shared
 * by reference between every copy of a stack ({@code PatchedDataComponentMap#copy} marks the patch map copy-on-write and
 * never touches the values). Changing an entry <b>in place</b> would therefore write the mark onto every clipboard that
 * shares the component — a stack of clipboards copied by Create's own item-copying recipe, and the menu's remembered
 * slot contents, which would then compare equal and suppress the slot packet. {@link #tickOff} replaces the entry with a
 * ticked copy instead and mutates nothing (M23 review fix);</li>
 * <li>the entry's authoritative item is its {@code icon} and its authoritative amount its {@code itemAmount}. The
 * entry's <b>text</b> is never read ({@link ListEntry}): it is a foreign {@code Component} that a Schematicannon's
 * checklist hangs {@code HoverEvent.SHOW_ITEM} on, and nothing of ours renders it or copies it into a payload.</li>
 * </ul>
 * <b>A read-only clipboard is still ticked off.</b> The Schematicannon writes its checklist with {@code readOnly = true}
 * ({@code MaterialChecklist#createWrittenClipboard}), and Create's own {@code ClipboardScreen} reads that flag in
 * exactly three places — the text cursor, the "next page" button past the last page and entering text edit mode. The
 * <b>checkbox</b> path has no read-only guard at all, and neither has {@code ClipboardEditPacket}, so a player may tick
 * a checklist off by hand and Create persists it. Writing {@code checked} is therefore not a violation of the flag but
 * the very thing it leaves open, and {@link #tickOff} keeps the flag as it found it.
 * <p>
 * <b>Nothing here is trusted.</b> A clipboard is an item a player can edit, swap or craft, so the order keeps its own
 * lines ({@link ListLine}) and this class never derives state from the clipboard after the order has started. Both
 * write paths validate the entry at the line's page and index <b>again</b> and leave everything alone unless it still
 * shows that very item; a clipboard that cannot be ticked off costs a message and not the order.
 */
public final class ClipboardList {
    private ClipboardList() {
    }

    /**
     * Whether {@code stack} is a clipboard at all, i.e. whether it may go into a terminal's list slot. An <b>empty</b>
     * clipboard is one: it may be put in, and pressing Fetch then says that the list is empty rather than refusing the
     * item.
     */
    public static boolean isClipboard(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && (stack.getItem() instanceof ClipboardBlockItem || stack.has(AllDataComponents.CLIPBOARD_CONTENT));
    }

    /**
     * The entries {@code stack} carries, in page and index order, as a list order reads them. Empty for anything that
     * is not a written clipboard; never throws.
     * <p>
     * Every entry of every page is returned, including the ones a list order will skip
     * ({@link ListEntry#orderable()}): the page and index of a line have to be the clipboard's own, because they are
     * where the tick mark goes, so nothing may be filtered out on the way.
     */
    public static List<ListEntry<ItemKey>> read(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return List.of();
        List<List<ClipboardEntry>> pages = ClipboardEntry.readAll(stack);
        List<ListEntry<ItemKey>> entries = new ArrayList<>();
        for (int page = 0; page < pages.size(); page++) {
            List<ClipboardEntry> content = pages.get(page);
            for (int index = 0; index < content.size(); index++) {
                ClipboardEntry entry = content.get(index);
                if (entry == null)
                    continue;
                // An entry without an icon is a page separator (">>>"), not an item: it has no key and is therefore
                // never orderable. The amount is the entry's own number and is clamped by ListEntry.
                entries.add(new ListEntry<>(page, index, ItemKey.fromStack(entry.icon), entry.itemAmount,
                        entry.checked));
            }
        }
        return entries;
    }

    /**
     * Whether the entry {@code line} came from still shows {@code line}'s item, i.e. whether the clipboard in the slot
     * is still the list this order was started for.
     * <p>
     * This is the whole identity check of the feature, and it is deliberately about the <b>icons</b> and nothing else:
     * a clipboard that was swapped for another one, shortened, or had an entry rewritten answers {@code false} for at
     * least one line, while a clipboard whose tick marks changed — by this order, or by a player's own hand — answers
     * {@code true}, because the order is authoritative about what it fetches and a tick mark is only its receipt.
     */
    public static boolean stillShows(ItemStack stack, ListLine<ItemKey> line) {
        Objects.requireNonNull(line, "line");
        return entryAt(ClipboardEntry.readAll(stack), line.page(), line.index())
                .filter(entry -> line.key().matches(entry.icon)).isPresent();
    }

    /**
     * Whether every line of {@code lines} still finds its item on {@code stack} ({@link #stillShows}). An order whose
     * clipboard answers {@code false} has lost its list and is given up.
     */
    public static boolean stillShowsAll(ItemStack stack, List<ListLine<ItemKey>> lines) {
        Objects.requireNonNull(lines, "lines");
        if (!isClipboard(stack))
            return false;
        List<List<ClipboardEntry>> pages = ClipboardEntry.readAll(stack);
        for (ListLine<ItemKey> line : lines) {
            if (entryAt(pages, line.page(), line.index()).filter(entry -> line.key().matches(entry.icon)).isEmpty())
                return false;
        }
        return true;
    }

    /**
     * What writing a set of tick marks did ({@link #tickOff}).
     * <p>
     * {@link #written} and {@link #already} are deliberately apart, because the difference between them is the whole
     * difference between "nothing to do" and "something is wrong": a caller that re-asserts every completed line's mark
     * after a reload writes <b>nothing</b> and is none the worse for it, while a line whose entry no longer shows its
     * item ({@link #missed}) really has lost its receipt and is worth a message (M23 review fix).
     *
     * @param written entries that were newly ticked off, i.e. what the component was changed for
     * @param already entries that already carried their mark
     * @param missed  lines whose entry no longer shows that line's item, so no mark could be written
     */
    public record Ticks(int written, int already, int missed) {
        /** Nothing was asked for, so nothing happened. */
        public static final Ticks NONE = new Ticks(0, 0, 0);

        public Ticks {
            written = Math.max(0, written);
            already = Math.max(0, already);
            missed = Math.max(0, missed);
        }

        /** Entries that carry their mark now, whether this call wrote it or found it. */
        public int marked() {
            return written + already;
        }

        /** Whether every line asked for ended up marked. */
        public boolean complete() {
            return missed == 0;
        }
    }

    /**
     * Ticks {@code lines} off on {@code stack} and says what that did ({@link Ticks}).
     * <p>
     * Each line is validated again before it is written ({@link #stillShows}): the entry at that page and index must
     * still show that item. Everything else about the clipboard is left exactly as it was — the type, the read-only
     * flag, the page it was last opened on, the copied value settings and every entry's text, icon and amount — so a
     * Schematicannon's checklist comes out of the terminal as the same checklist with the delivered lines ticked.
     * <p>
     * <b>The entry is replaced, never changed in place.</b> {@link ClipboardEntry#readAll} re-creates only the two list
     * levels, so the entries it hands out are shared with the old {@code ClipboardContent} and with every stack that
     * shares the component (see the class comment); a ticked <b>copy</b> is built exactly the way
     * {@link ClipboardEntry#CODEC} builds one, so the round trip stays faithful and the new component really compares
     * unequal to the old one — which is what makes the menu send the ticked clipboard to an open screen.
     * <p>
     * Writing a mark twice is harmless, which is what lets a caller re-assert every completed line's mark after a
     * reload. The component is only written when something really changed, so an order that has nothing new to tick off
     * does not touch the stack at all.
     */
    public static Ticks tickOff(ItemStack stack, List<ListLine<ItemKey>> lines) {
        Objects.requireNonNull(lines, "lines");
        if (!isClipboard(stack) || lines.isEmpty())
            return Ticks.NONE;
        ClipboardContent content = stack.get(AllDataComponents.CLIPBOARD_CONTENT);
        if (content == null)
            return new Ticks(0, 0, lines.size());
        List<List<ClipboardEntry>> pages = ClipboardEntry.readAll(content);
        int written = 0;
        int already = 0;
        int missed = 0;
        for (ListLine<ItemKey> line : lines) {
            Optional<ClipboardEntry> found = entryAt(pages, line.page(), line.index())
                    .filter(entry -> line.key().matches(entry.icon));
            if (found.isEmpty()) {
                missed++;
                continue;
            }
            ClipboardEntry entry = found.get();
            if (entry.checked) {
                already++;
                continue;
            }
            pages.get(line.page()).set(line.index(), ticked(entry));
            written++;
        }
        if (written > 0)
            stack.set(AllDataComponents.CLIPBOARD_CONTENT, content.setPages(pages));
        return new Ticks(written, already, missed);
    }

    /**
     * {@code source} with its tick mark set, as a new entry: the text is copied and the icon only carried over when
     * there is one, which is precisely what {@link ClipboardEntry#CODEC} and {@link ClipboardEntry#STREAM_CODEC} do, so
     * a replaced entry saves and sends byte for byte like the one it replaced.
     */
    private static ClipboardEntry ticked(ClipboardEntry source) {
        ClipboardEntry copy = new ClipboardEntry(true, source.text.copy());
        if (!source.icon.isEmpty())
            copy.displayItem(source.icon.copy(), source.itemAmount);
        return copy;
    }

    /** The entry at {@code page}/{@code index} of already read pages, empty when there is none. */
    private static Optional<ClipboardEntry> entryAt(List<List<ClipboardEntry>> pages, int page, int index) {
        if (page < 0 || page >= pages.size())
            return Optional.empty();
        List<ClipboardEntry> content = pages.get(page);
        if (index < 0 || index >= content.size())
            return Optional.empty();
        return Optional.ofNullable(content.get(index));
    }
}
