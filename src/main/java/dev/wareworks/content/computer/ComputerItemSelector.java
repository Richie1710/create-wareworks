package dev.wareworks.content.computer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.item.ItemTypeSummaries;
import dev.wareworks.core.computer.ItemChoice;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;

/**
 * Turns a computer's item argument into one of the keys a warehouse terminal lists (issue #9, ADR-074,
 * {@code docs/computercraft.md}).
 * <p>
 * The argument is a string id ({@code "minecraft:oak_log"}, or {@code "oak_log"} for the {@code minecraft}
 * namespace) or a table {@code {name = "<id>", fingerprint = "<hex>"}}. An id that is malformed or names no registered
 * item is an error; which listed key it means is {@link ItemChoice}'s decision.
 * <p>
 * <b>The fingerprint</b> tells a computer the variants of one item apart (enchanted books, named or damaged tools). It
 * is the first eight hex digits of the SHA-1 of the item id and the key's saved form ({@link ItemKey#CODEC}), written
 * out with every compound's keys sorted, so it is the same on every start, server and JVM. {@code ComponentHash} cannot serve:
 * it mixes in identity hash codes, which change with every start (ADR-064).
 */
public final class ComputerItemSelector {
    /** Hex digits of a fingerprint. */
    public static final int FINGERPRINT_LENGTH = 8;

    private ComputerItemSelector() {
    }

    /** A parsed item argument: a registered item's normalised id and the fingerprint, if one was given. */
    public record ItemArgument(String id, Optional<String> fingerprint) {
    }

    /**
     * Reads a computer's item argument.
     *
     * @throws ComputerArgumentException if it is neither a string nor a table with a string {@code name}, or the id is
     *                                   malformed or unknown
     */
    public static ItemArgument parse(Object argument) {
        String name;
        Optional<String> fingerprint = Optional.empty();
        if (argument instanceof String text) {
            name = text;
        } else if (argument instanceof Map<?, ?> table) {
            if (!(table.get("name") instanceof String text))
                throw new ComputerArgumentException("item table needs a string name");
            name = text;
            Object print = table.get("fingerprint");
            if (print != null) {
                if (!(print instanceof String printText))
                    throw new ComputerArgumentException("item fingerprint must be a string");
                fingerprint = Optional.of(printText);
            }
        } else {
            throw new ComputerArgumentException("item must be an id string or a table {name=..., fingerprint=...}");
        }
        Optional<Item> item = ItemTypeSummaries.itemById(name);
        if (item.isEmpty())
            throw new ComputerArgumentException("unknown item: " + name);
        return new ItemArgument(ItemTypeSummaries.itemId(item.get()), fingerprint);
    }

    /**
     * The listed key {@code argument} means.
     *
     * @return the key, or empty when none of {@code listed} matches (a refusal, {@code not_in_stock})
     * @throws ComputerArgumentException if the id alone names several variants
     */
    public static Optional<ItemKey> select(ItemArgument argument, List<ItemKey> listed,
            HolderLookup.Provider registries) {
        List<ItemChoice.Candidate<ItemKey>> candidates = new ArrayList<>();
        for (ItemKey key : listed)
            candidates.add(new ItemChoice.Candidate<>(key, ItemTypeSummaries.itemId(key.getItem()),
                    !key.toStack(1).getComponentsPatch().isEmpty()));
        ItemChoice<ItemKey> choice = ItemChoice.choose(candidates, argument.id(), argument.fingerprint(),
                key -> fingerprint(key, registries));
        return switch (choice) {
            case ItemChoice.Found<ItemKey> found -> Optional.of(found.key());
            case ItemChoice.Absent<ItemKey> absent -> Optional.empty();
            case ItemChoice.Ambiguous<ItemKey> ambiguous -> throw new ComputerArgumentException("ambiguous item "
                    + argument.id() + ": " + ambiguous.variants() + " variants, pass {name=..., fingerprint=...}");
        };
    }

    /** The fingerprint of {@code key}: {@value #FINGERPRINT_LENGTH} lowercase hex digits, stable across restarts. */
    public static String fingerprint(ItemKey key, HolderLookup.Provider registries) {
        // The id leads, so a key whose saved form could not be written (an empty compound, logged by ItemKey#save)
        // still gets its item's own fingerprint.
        StringBuilder canonical = new StringBuilder(ItemTypeSummaries.itemId(key.getItem())).append('|');
        write(key.save(registries), canonical);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, FINGERPRINT_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime has SHA-1", e);
        }
    }

    /** {@code tag} as text with every compound's keys in sorted order; lists keep theirs. */
    private static void write(Tag tag, StringBuilder out) {
        if (tag instanceof CompoundTag compound) {
            out.append('{');
            boolean first = true;
            for (String name : new TreeSet<>(compound.getAllKeys())) {
                if (!first)
                    out.append(',');
                first = false;
                out.append(name.length()).append(':').append(name).append('=');
                write(compound.get(name), out);
            }
            out.append('}');
        } else if (tag instanceof CollectionTag<?> list) {
            out.append('[').append(tag.getId()).append(';');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0)
                    out.append(',');
                write(list.get(i), out);
            }
            out.append(']');
        } else if (tag != null) {
            out.append(tag.getId()).append(':').append(tag.getAsString());
        }
    }
}
