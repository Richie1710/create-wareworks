package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.core.warehouse.AisleName;
import dev.wareworks.core.warehouse.AisleNames;
import dev.wareworks.core.warehouse.NetworkStop;
import dev.wareworks.core.warehouse.RailNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * What the goggle tooltip of a warehouse controller says about the <b>rail network</b> its warehouse is made of: how
 * many rails and aisles it has, each aisle's letter and length, and where and why the discovery stopped
 * ({@code docs/warehouse-system.md} §1, §4, ADR-033).
 * <p>
 * <b>Absent for every warehouse that does not bend and ends cleanly</b> ({@link #of}). A straight aisle is a network of
 * one branch whose rails simply end, and the lines it has always shown say everything there is to say about it — so
 * this adds nothing at all to the chunk packet of such a warehouse, which is every warehouse built before M21.
 * <p>
 * Bounded like every other goggle record: at most {@value StorageAddress#AISLE_COUNT} letters and the same number of
 * lengths (a warehouse can never have more aisles than the address format has letters), at most {@value #NAMES_LISTED}
 * names of at most 16 characters each, one enum name and two offsets. Reading never throws; a value that cannot be
 * read comes back as the harmless one.
 *
 * @param rails        aisle blocks beyond the dock that the warehouse really contains
 * @param aisleLetters one character per aisle in aisle order, {@value CraneGoggleInfo#NO_LETTER} for an aisle without
 *                     a letter — the same encoding {@link CraneGoggleInfo#aisleLetters()} uses, so the two surfaces
 *                     cannot disagree about which aisle is which
 * @param aisleLengths the rails of each aisle beyond its own position 0, in the same order
 * @param aisleNames   the names a player gave those aisles (M25, issue #15, ADR-038), in the same order, separated by
 *                     a newline ({@link #NAME_SEPARATOR}) and empty for an aisle without one — <b>one</b> string and
 *                     not a list of them, because NBT accounting charges 36 bytes for a {@code StringTag} and 64 more
 *                     for the compound entry that holds it, so 26 of them would have cost about 1.8 kB of a tag that
 *                     travels with every chunk packet, against 312 for the worst case of one string. At most
 *                     {@value #NAMES_LISTED} fields: that is as many as the controller's aisle line and the display
 *                     board's names line ever draw, so nothing is synced that no surface could show. Empty for every
 *                     warehouse nobody named, which keeps this record byte-identical to 0.7.0's
 * @param stop         why the discovery ended where it did; {@link NetworkStop#END} means "the rails simply end"
 * @param stopDx       X offset of the block it stopped at, relative to the <b>dock</b> (never the controller)
 * @param stopDz       Z offset of that block
 */
public record NetworkGoggleInfo(int rails, String aisleLetters, List<Integer> aisleLengths, String aisleNames,
                                NetworkStop stop, int stopDx, int stopDz) {
    /**
     * How many aisles of a warehouse can carry a synced name. The same six the controller's aisle line names before it
     * falls back to "and N more" ({@code WarehouseControllerBlockEntity#GOGGLE_AISLES_LISTED}) and the same six the
     * display board's names line lists: a seventh would travel in every chunk packet and be drawn nowhere.
     */
    public static final int NAMES_LISTED = 6;
    /**
     * What separates two names inside {@link #aisleNames}: a newline, which {@code AisleName#sanitize} strips from
     * every name there is, so no name can ever contain one and the encoding needs no escaping at all.
     */
    public static final char NAME_SEPARATOR = '\n';

    private static final String RAILS = "Rails";
    private static final String LETTERS = "Letters";
    private static final String LENGTHS = "Lengths";
    private static final String NAMES = "Names";
    private static final String STOP = "Stop";
    private static final String STOP_DX = "StopDx";
    private static final String STOP_DZ = "StopDz";

    public NetworkGoggleInfo {
        rails = Math.max(0, rails);
        aisleLengths = sanitizeLengths(aisleLengths);
        aisleLetters = sanitizeLetters(aisleLetters, aisleLengths.size());
        aisleNames = sanitizeNames(aisleNames, aisleLengths.size());
        if (stop == null)
            stop = NetworkStop.END;
    }

    /**
     * The line a controller should show for {@code network} under the letters {@code aisleLetters}, or empty when
     * there is nothing a straight warehouse did not already say: one aisle, and rails that simply end.
     * <p>
     * A one-aisle warehouse whose discovery hit a <b>fault</b> does get one, because that is precisely the case this
     * record exists for — a player who has just laid a T on a straight aisle, or run into a cap, has to be told where
     * the warehouse stops and why.
     */
    public static Optional<NetworkGoggleInfo> of(RailNetwork network, String aisleLetters, AisleNames names) {
        if (network == null)
            return Optional.empty();
        if (network.branchCount() < 2 && !network.stop().isFault())
            return Optional.empty();
        List<Integer> lengths = new ArrayList<>(network.branchCount());
        network.geometry().branches().forEach(branch -> lengths.add(branch.length()));
        return Optional.of(new NetworkGoggleInfo(network.rails(), aisleLetters, lengths, names(aisleLetters, names),
                network.stop(), network.stopDx(), network.stopDz()));
    }

    /**
     * The synced form of a warehouse's aisle names (M25, issue #15): the name of the aisle whose letter stands at each
     * place of {@code aisleLetters}, in that order.
     * <p>
     * Keyed by <b>letter</b> like the table itself, and turned into aisle order here and only here, so the two
     * surfaces that read it — the controller's aisle line and a member's address — can never disagree about which
     * name belongs to which aisle.
     */
    public static String names(String aisleLetters, @Nullable AisleNames names) {
        if (aisleLetters == null || aisleLetters.isEmpty() || names == null || names.isEmpty())
            return "";
        StringBuilder joined = new StringBuilder();
        int aisles = Math.min(aisleLetters.length(), NAMES_LISTED);
        for (int aisle = 0; aisle < aisles; aisle++) {
            if (aisle > 0)
                joined.append(NAME_SEPARATOR);
            joined.append(names.nameOf(aisleLetters.charAt(aisle)).orElse(AisleName.NONE));
        }
        return trimTrailingSeparators(joined.toString());
    }

    /** Number of straight aisles the warehouse is made of. */
    public int aisleCount() {
        return aisleLengths.size();
    }

    /** The letter of one aisle, or empty when it has none. */
    public Optional<Character> letterOf(int aisle) {
        if (aisle < 0 || aisle >= aisleLetters.length())
            return Optional.empty();
        char letter = aisleLetters.charAt(aisle);
        return StorageAddress.isValidAisle(letter) ? Optional.of(letter) : Optional.empty();
    }

    /**
     * The name a player gave one aisle, or empty when it has none — which is every aisle past
     * {@value #NAMES_LISTED} and every aisle of a warehouse nobody named (M25, issue #15).
     */
    public Optional<String> nameOf(int aisle) {
        if (aisle < 0 || aisleNames.isEmpty())
            return Optional.empty();
        int from = 0;
        for (int skipped = 0; skipped < aisle; skipped++) {
            int next = aisleNames.indexOf(NAME_SEPARATOR, from);
            if (next < 0)
                return Optional.empty();
            from = next + 1;
        }
        int end = aisleNames.indexOf(NAME_SEPARATOR, from);
        String name = aisleNames.substring(from, end < 0 ? aisleNames.length() : end);
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /** Whether any aisle of this warehouse carries a name at all — the gate of the controller's teaching hint. */
    public boolean hasNames() {
        return !aisleNames.isEmpty();
    }

    /** Whether the warehouse stops short of something a player would want in it. */
    public boolean stopsShort() {
        return stop.isFault();
    }

    /** Writes this record into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        tag.putInt(RAILS, rails);
        tag.putString(LETTERS, aisleLetters);
        int[] lengths = new int[aisleLengths.size()];
        for (int i = 0; i < lengths.length; i++)
            lengths[i] = aisleLengths.get(i);
        tag.putIntArray(LENGTHS, lengths);
        // Left out while nobody has named an aisle of this warehouse, which is every warehouse built before M25: this
        // tag rides every chunk packet, and a missing key reads back as "no names at all".
        if (!aisleNames.isEmpty())
            tag.putString(NAMES, aisleNames);
        // Left out while the rails simply end, which a missing key reads back as.
        if (stop != NetworkStop.END) {
            tag.putString(STOP, stop.name());
            tag.putInt(STOP_DX, stopDx);
            tag.putInt(STOP_DZ, stopDz);
        }
    }

    /** Reads a record written by {@link #write}. Never throws; anything unreadable reads as 0 / no letter / END. */
    public static NetworkGoggleInfo read(CompoundTag tag) {
        int[] lengths = tag.getIntArray(LENGTHS);
        List<Integer> read = new ArrayList<>(lengths.length);
        for (int length : lengths)
            read.add(length);
        return new NetworkGoggleInfo(tag.getInt(RAILS), tag.getString(LETTERS), read, tag.getString(NAMES),
                stopByName(tag.getString(STOP)), tag.getInt(STOP_DX), tag.getInt(STOP_DZ));
    }

    private static List<Integer> sanitizeLengths(List<Integer> lengths) {
        if (lengths == null || lengths.isEmpty())
            return List.of();
        int count = Math.min(lengths.size(), StorageAddress.AISLE_COUNT);
        List<Integer> sanitized = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Integer length = lengths.get(i);
            sanitized.add(length == null ? 0 : Math.clamp(length.intValue(), 0, AisleGeometry.MAX_LENGTH));
        }
        return List.copyOf(sanitized);
    }

    /**
     * At most one name per aisle and never more than {@value #NAMES_LISTED} of them, each through
     * {@code AisleName#sanitize} — so a hand-edited save, a malformed packet or an old client's tag can never put a
     * name on a surface that the spelling rule would not allow (M25, issue #15).
     * <p>
     * Trailing empty fields are dropped, which makes the encoding canonical: a warehouse whose last three aisles are
     * unnamed produces exactly the string a warehouse of its first named aisles produces, so two equal warehouses can
     * never compare unequal and sync a packet that changes nothing.
     */
    private static String sanitizeNames(@Nullable String names, int aisles) {
        if (names == null || names.isEmpty() || aisles <= 0)
            return "";
        StringBuilder sanitized = new StringBuilder(names.length());
        int fields = Math.min(aisles, NAMES_LISTED);
        int from = 0;
        for (int field = 0; field < fields && from <= names.length(); field++) {
            int next = names.indexOf(NAME_SEPARATOR, from);
            int end = next < 0 ? names.length() : next;
            if (field > 0)
                sanitized.append(NAME_SEPARATOR);
            sanitized.append(AisleName.sanitize(names.substring(from, end)));
            if (next < 0)
                break;
            from = next + 1;
        }
        return trimTrailingSeparators(sanitized.toString());
    }

    /** Drops the empty fields at the end, so "Ores\n\n" and "Ores" are the same table. */
    private static String trimTrailingSeparators(String names) {
        int end = names.length();
        while (end > 0 && names.charAt(end - 1) == NAME_SEPARATOR)
            end--;
        return end == names.length() ? names : names.substring(0, end);
    }

    /** Exactly one character per aisle, so a letter can never be read against the wrong aisle. */
    private static String sanitizeLetters(String letters, int aisles) {
        StringBuilder sanitized = new StringBuilder(aisles);
        for (int aisle = 0; aisle < aisles; aisle++) {
            char letter = letters != null && aisle < letters.length() ? letters.charAt(aisle)
                    : CraneGoggleInfo.NO_LETTER;
            sanitized.append(StorageAddress.isValidAisle(letter) ? letter : CraneGoggleInfo.NO_LETTER);
        }
        return sanitized.toString();
    }

    private static NetworkStop stopByName(String name) {
        for (NetworkStop stop : NetworkStop.values()) {
            if (stop.name().equals(name))
                return stop;
        }
        return NetworkStop.END;
    }

    /** Whether a tag holds one, i.e. whether the warehouse has anything beyond a clean straight aisle to report. */
    public static boolean isPresent(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_COMPOUND);
    }
}
