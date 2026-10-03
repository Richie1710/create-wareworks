package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.TerminalSort;
import dev.wareworks.core.terminal.TerminalUsage;
import dev.wareworks.registry.WareworksAttachments;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;

/**
 * What one player has settled about warehouse terminals: the order their stock list is in, and how often they have
 * asked for each item type (M24, issue #17, ADR-037).
 * <p>
 * <b>It lives on the server, on the player.</b> One of these is a NeoForge data attachment of every player
 * ({@link WareworksAttachments#TERMINAL_PREFERENCES}), so it is saved with that player's {@code .dat} file, survives a
 * restart and a reconnect, and is the same at <b>every</b> terminal of the world. The previous build kept the chosen
 * order in a static field of the screen, which meant it was lost on restart and meant nothing at all on a server with
 * several players.
 * <p>
 * <b>It is not an inventory.</b> Nothing here moves an item, and nothing here decides whether a request is granted:
 * {@link WarehouseTerminalBlockEntity#requestFromTerminal} asks the controller exactly as before and only
 * <b>reports</b> an accepted request to {@link #countRequest}. A player with no preferences at all behaves like the
 * build before this one ({@link TerminalSort#DEFAULT}, every count 0).
 *
 * <h2>What is counted, and where</h2>
 * {@link #countRequest(Player, ItemKey)} is called from the two places a <b>player</b> asks a terminal for something:
 * an accepted click ({@code requestFromTerminal}) and a clipboard order the player started
 * ({@code fetchList} → {@link #countListOrder}). A <b>redstone</b> request at a warehouse port never reaches them — a
 * port is not a player, and its requests carry {@code StockAccess.AUTOMATION} and no {@link Player} at all — so
 * automation can never teach a player's terminal anything. A refusal and a question count nothing: a mis-click is not
 * a habit. The counting rules, the bounds, the eviction order and the fade are {@link TerminalUsage}'s, and documented
 * there.
 *
 * <h2>The saved shape</h2>
 * One compound, written by {@link #save} and read by {@link #load}, both of which never throw:
 *
 * <pre>
 * { "v": 1, "sort": "USED", "used": [ { "item": {…}, "n": 12, "t": 40 }, … ] }
 * </pre>
 * <ul>
 * <li><b>{@code v}</b> is {@link #VERSION}. A tag without it is read as version 1 (there has never been another
 * shape); a tag from a <b>newer</b> version is read as far as this build understands it rather than discarded, because
 * the fields are append-only and a player who tried a newer build must not lose their order by going back.</li>
 * <li><b>{@code sort}</b> is {@link TerminalSort#name()}, never an ordinal: the cycle order is a presentation decision
 * that may change again, and {@link TerminalSort#byName} reads an unknown name as the default.</li>
 * <li><b>{@code used}</b> holds at most {@link #capacity()} entries, each an {@link ItemKey} plus two small numbers —
 * how often ({@code n}) and how recently ({@code t}). The recency is saved as well as the count because without it
 * every entry would be equally old after a world load and eviction would lose its tie-break
 * ({@link TerminalUsage.Entry}). An entry whose item cannot be decoded any more — its mod was removed — is skipped,
 * and {@link TerminalUsage#replaceAll} re-applies every bound to whatever is left, so crafted or truncated data can
 * neither throw nor grow the store.</li>
 * </ul>
 * Nothing is written for a player who has not touched a terminal ({@link #isDefault()}), so this costs an untouched
 * world exactly nothing.
 *
 * <h2>How large it may become</h2>
 * {@link TerminalUsage}'s own bounds are all counts of <b>things</b>: how many item types, how large a count, how
 * large a stamp. An {@link ItemKey} is the item plus its whole data-component patch, though
 * ({@code ItemStack.SINGLE_ITEM_CODEC}), so one key can be a shulker box carrying a {@code container} component with
 * 27 stacks in it, or a written book with a hundred pages — kilobytes each, and the entry count alone would happily
 * bound 64 of them in every player's {@code .dat} file (M24 review fix). The bytes are therefore bounded here, where
 * an item is visible at all:
 * <ul>
 * <li>{@link #MAX_KEY_SIZE} — a key larger than this is <b>never counted</b> ({@link #countRequest}), so an oversized
 * key cannot enter the store, the save, or the payload the screen is sent. The request itself is unaffected: this
 * decides what is remembered, never what a player may ask for.</li>
 * <li>{@link #MAX_USED_SIZE} — the whole {@code used} list is cut to this when it is written. The entries are
 * strongest first, so the cut keeps what the player would recognise. It only ever bites on save data from a build
 * without the first bound, or on a store full of near-{@code MAX_KEY_SIZE} keys.</li>
 * </ul>
 * Both are measured with {@link net.minecraft.nbt.Tag#sizeInBytes()}, Minecraft's own estimate of what a tag occupies
 * (an empty compound already counts 48, and a plain item key about 190, while the real on-disk form is several times
 * smaller) — the same measure {@code NbtAccounter} bounds a read with.
 */
public final class TerminalPreferences {
    /** Version of the saved shape; see the class comment. */
    public static final int VERSION = 1;

    /**
     * The most one remembered item type may measure, as {@link net.minecraft.nbt.Tag#sizeInBytes()} measures it; a
     * larger key is not counted at all (see the class comment).
     * <p>
     * 4 KiB is deliberately far above anything a player carries and far below the keys this bound exists for: a plain
     * item key measures about 190, a named and heavily enchanted tool one or two thousand, while a shulker box with a
     * {@code container} component of 27 stacks is upwards of ten thousand and a written book has no useful limit at
     * all.
     */
    public static final int MAX_KEY_SIZE = 4096;

    /** The most the whole saved list of counts may measure; the strongest entries are kept (see the class comment). */
    public static final int MAX_USED_SIZE = 64 * 1024;

    /** Tag names of the saved shape; public because the GameTests of that shape name them. */
    public static final String VERSION_TAG = "v";
    public static final String SORT_TAG = "sort";
    public static final String USED_TAG = "used";
    public static final String ITEM_TAG = "item";
    public static final String COUNT_TAG = "n";
    public static final String STAMP_TAG = "t";

    private final TerminalUsage<ItemKey> usage;
    private TerminalSort sort = TerminalSort.DEFAULT;

    /** A player's preferences, remembering as many item types as the server config allows. */
    public TerminalPreferences() {
        this(WareworksConfig.maxTerminalUsageEntries());
    }

    /** Preferences remembering {@code capacity} item types, clamped by {@link TerminalUsage}. */
    public TerminalPreferences(int capacity) {
        this.usage = new TerminalUsage<>(capacity);
    }

    // --- reaching a player's preferences ---------------------------------------------------------------------------

    /**
     * Server: {@code player}'s preferences, created empty on first use.
     * <p>
     * Reading them <b>stores</b> them on the player ({@code IAttachmentHolder#getData}), which is what makes a change
     * made through the returned object part of the next save. Call it on the server only: a client player holds its
     * own, empty attachment, and nothing on the client may count anything.
     */
    public static TerminalPreferences of(Player player) {
        Objects.requireNonNull(player, "player");
        return player.getData(WareworksAttachments.TERMINAL_PREFERENCES);
    }

    /** {@code player}'s preferences only if they already have some; this never creates any. */
    public static Optional<TerminalPreferences> existing(@Nullable Player player) {
        return player == null ? Optional.empty()
                : player.getExistingData(WareworksAttachments.TERMINAL_PREFERENCES);
    }

    /**
     * Server: counts one accepted request of {@code player} for {@code key} — the one event that teaches a terminal
     * what a player uses (see the class comment).
     * <p>
     * Does nothing without a player, on a client, or for a {@code null} item, so a caller never has to guard the call.
     * An item whose key is too large to remember is not counted either ({@link #MAX_KEY_SIZE}); the request itself has
     * already happened and is not affected.
     *
     * @return whether anything was counted
     */
    public static boolean countRequest(@Nullable Player player, @Nullable ItemKey key) {
        if (player == null || key == null || player.level() == null || player.level().isClientSide)
            return false;
        if (!worthRemembering(key, player.registryAccess()))
            return false;
        of(player).usage().record(key);
        return true;
    }

    /**
     * Server: counts the items of a clipboard order the player has just started, <b>once per item type</b> however
     * many lines of the list name it (M23's list order, M24's counter).
     * <p>
     * A list is counted when it starts rather than line by line as it runs, because that is the moment the player
     * asked for it: the order then works on by itself for minutes, with nobody at the terminal, and a line the
     * warehouse cannot cover must count the same as one it can — the player wanted the item either way.
     * <p>
     * It is <b>one</b> action, counted as one ({@link TerminalUsage#recordAll}), and that is what makes it work at all
     * (M24 review fix). A clipboard may name more item types than the store holds — {@code maxTerminalListEntries} is
     * 128 against 64 entries by default, and 1024 against 256 at the extremes — and counting it key by key made the
     * later keys of the list evict its earlier ones: a repeated identical order could then never raise a single count,
     * so the one action a building player repeats most taught the terminal nothing. A list that does not fit now keeps
     * the item types it named first, and the return value says how many were counted.
     *
     * @return how many item types were counted, which is fewer than the list has when the store ran out of room or a
     *         key was too large to remember ({@link #MAX_KEY_SIZE})
     */
    public static int countListOrder(@Nullable Player player, @Nullable Collection<? extends ItemKey> keys) {
        if (player == null || keys == null || keys.isEmpty() || player.level() == null
                || player.level().isClientSide)
            return 0;
        // A clipboard entry's icon is an arbitrary stack a player wrote, so the size bound matters most here: a list
        // may name item types the warehouse has never held, and nothing else would stop them entering the save.
        HolderLookup.Provider registries = player.registryAccess();
        List<ItemKey> worth = new ArrayList<>(keys.size());
        for (ItemKey key : keys) {
            if (key != null && worthRemembering(key, registries))
                worth.add(key);
        }
        return worth.isEmpty() ? 0 : of(player).usage().recordAll(worth);
    }

    /**
     * Whether {@code key} is small enough to be remembered at all ({@link #MAX_KEY_SIZE}, and see the class comment).
     * <p>
     * A key that cannot be encoded is not worth remembering either: {@link #save} would leave it out, so the count
     * would survive exactly until the next world load.
     * <p>
     * Public because the GameTest of this bound asks it the same question the two counting calls do.
     */
    public static boolean worthRemembering(ItemKey key, HolderLookup.Provider registries) {
        return keySize(key, registries) <= MAX_KEY_SIZE;
    }

    /** What {@code key} measures in the saved shape, or {@link Integer#MAX_VALUE} when it cannot be encoded. */
    private static int keySize(ItemKey key, HolderLookup.Provider registries) {
        CompoundTag one = new CompoundTag();
        key.saveTo(one, ITEM_TAG, registries);
        Tag written = one.get(ITEM_TAG);
        return written == null ? Integer.MAX_VALUE : written.sizeInBytes();
    }

    // --- state -----------------------------------------------------------------------------------------------------

    /** How often this player has asked for each item type; the server's own store, bounded and mutable. */
    public TerminalUsage<ItemKey> usage() {
        return usage;
    }

    /** How many item types these preferences remember at most. */
    public int capacity() {
        return usage.capacity();
    }

    /** The order this player last chose, {@link TerminalSort#DEFAULT} until they choose one. */
    public TerminalSort sort() {
        return sort;
    }

    /**
     * Remembers {@code chosen} as this player's order. {@code null} is read as {@link TerminalSort#DEFAULT}, so a
     * payload that names an order this build does not have cannot leave the field unset.
     *
     * @return whether the order changed
     */
    public boolean setSort(@Nullable TerminalSort chosen) {
        TerminalSort next = chosen == null ? TerminalSort.DEFAULT : chosen;
        if (next == sort)
            return false;
        sort = next;
        return true;
    }

    /** What this player asks for most often, strongest first — the list that is saved and sent. */
    public List<TerminalUsage.Entry<ItemKey>> counts() {
        return usage.entries();
    }

    /** Whether nothing has been chosen and nothing counted, i.e. whether there is anything worth saving at all. */
    public boolean isDefault() {
        return sort == TerminalSort.DEFAULT && usage.isEmpty();
    }

    /** Forgets everything, as a fresh player would have it. */
    public void clear() {
        sort = TerminalSort.DEFAULT;
        usage.clear();
    }

    // --- persistence -----------------------------------------------------------------------------------------------

    /**
     * The saved form of these preferences (see the class comment), or {@code null} when there is nothing to save.
     * <p>
     * Never throws: an item key that cannot be encoded is left out ({@link ItemKey#saveTo}) rather than failing the
     * whole player's save.
     * <p>
     * The two size bounds of the class comment are applied here as well as when a request is counted, because save
     * data can come from a build that had neither: an oversized key is dropped, and the list stops at
     * {@link #MAX_USED_SIZE}. Both cuts keep the strongest entries, which is the order {@link TerminalUsage#entries()}
     * hands them over in.
     */
    @Nullable
    public CompoundTag save(HolderLookup.Provider registries) {
        if (isDefault())
            return null;
        CompoundTag tag = new CompoundTag();
        tag.putInt(VERSION_TAG, VERSION);
        tag.putString(SORT_TAG, sort.name());
        ListTag used = new ListTag();
        int size = 0;
        // Strongest first, so save data cut short by anything keeps what the player would recognise (TerminalUsage).
        for (TerminalUsage.Entry<ItemKey> entry : usage.entries()) {
            CompoundTag one = new CompoundTag();
            entry.key().saveTo(one, ITEM_TAG, registries);
            Tag written = one.get(ITEM_TAG);
            if (written == null)
                continue; // an item that cannot be encoded: leave it out rather than write a count without an item
            int keySize = written.sizeInBytes();
            if (keySize > MAX_KEY_SIZE)
                continue; // never counted since M24's review; this drops one an older build may have written
            if (size + keySize > MAX_USED_SIZE)
                break; // the rest is weaker than everything already written (see the method comment)
            size += keySize;
            one.putInt(COUNT_TAG, entry.count());
            one.putInt(STAMP_TAG, entry.stamp());
            used.add(one);
        }
        if (!used.isEmpty())
            tag.put(USED_TAG, used);
        return tag;
    }

    /**
     * Replaces these preferences with what {@code tag} holds. Never throws and never rejects: a missing field keeps
     * the default, an unreadable item is skipped, and every bound is re-applied by
     * {@link TerminalUsage#replaceAll(Collection)} — including a {@link #capacity()} that a config change has lowered
     * since the save, which then keeps the strongest entries.
     */
    public void load(HolderLookup.Provider registries, @Nullable CompoundTag tag) {
        clear();
        if (tag == null || tag.isEmpty())
            return;
        // A tag from a newer build is read as far as this one understands it: the fields are append-only, so the two
        // this build knows mean the same there, and a player who went back to an older build keeps their order.
        sort = TerminalSort.byName(tag.getString(SORT_TAG));
        ListTag used = tag.getList(USED_TAG, Tag.TAG_COMPOUND);
        List<TerminalUsage.Entry<ItemKey>> entries = new ArrayList<>(used.size());
        for (int i = 0; i < used.size(); i++) {
            CompoundTag one = used.getCompound(i);
            Optional<ItemKey> key = ItemKey.loadFrom(one, ITEM_TAG, registries);
            if (key.isEmpty())
                continue; // the item's mod was removed, or the entry is crafted: forget that one count
            entries.add(new TerminalUsage.Entry<>(key.get(), one.getInt(COUNT_TAG), one.getInt(STAMP_TAG)));
        }
        usage.replaceAll(entries);
    }

    /**
     * These preferences as a fresh object: the attachment type's own copy handler, which NeoForge runs when a player
     * dies and when they return from the end ({@link WareworksAttachments#TERMINAL_PREFERENCES}).
     * <p>
     * The type passes it explicitly, because the handler a type is given <b>none</b> for writes the attachment and
     * reads it straight back ({@code AttachmentType.defaultCopyHandler}) — which would run both halves of
     * {@link #save}/{@link #load} on every respawn and would drop an entry whose key a codec happens not to encode,
     * i.e. the one thing a copy must not do (M24 review fix). This copies the numbers.
     */
    public TerminalPreferences copy() {
        TerminalPreferences copy = new TerminalPreferences(usage.capacity());
        copy.sort = sort;
        copy.usage.replaceAll(usage.entries());
        return copy;
    }

    @Override
    public String toString() {
        return "TerminalPreferences[sort=" + sort + ", used=" + usage.size() + "/" + usage.capacity() + "]";
    }
}
