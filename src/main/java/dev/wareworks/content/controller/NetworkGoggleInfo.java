package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.StorageAddress;
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
 * lengths (a warehouse can never have more aisles than the address format has letters), one enum name and two
 * offsets. Reading never throws; a value that cannot be read comes back as the harmless one.
 *
 * @param rails        aisle blocks beyond the dock that the warehouse really contains
 * @param aisleLetters one character per aisle in aisle order, {@value CraneGoggleInfo#NO_LETTER} for an aisle without
 *                     a letter — the same encoding {@link CraneGoggleInfo#aisleLetters()} uses, so the two surfaces
 *                     cannot disagree about which aisle is which
 * @param aisleLengths the rails of each aisle beyond its own position 0, in the same order
 * @param stop         why the discovery ended where it did; {@link NetworkStop#END} means "the rails simply end"
 * @param stopDx       X offset of the block it stopped at, relative to the <b>dock</b> (never the controller)
 * @param stopDz       Z offset of that block
 */
public record NetworkGoggleInfo(int rails, String aisleLetters, List<Integer> aisleLengths, NetworkStop stop,
                                int stopDx, int stopDz) {
    private static final String RAILS = "Rails";
    private static final String LETTERS = "Letters";
    private static final String LENGTHS = "Lengths";
    private static final String STOP = "Stop";
    private static final String STOP_DX = "StopDx";
    private static final String STOP_DZ = "StopDz";

    public NetworkGoggleInfo {
        rails = Math.max(0, rails);
        aisleLengths = sanitizeLengths(aisleLengths);
        aisleLetters = sanitizeLetters(aisleLetters, aisleLengths.size());
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
    public static Optional<NetworkGoggleInfo> of(RailNetwork network, String aisleLetters) {
        if (network == null)
            return Optional.empty();
        if (network.branchCount() < 2 && !network.stop().isFault())
            return Optional.empty();
        List<Integer> lengths = new ArrayList<>(network.branchCount());
        network.geometry().branches().forEach(branch -> lengths.add(branch.length()));
        return Optional.of(new NetworkGoggleInfo(network.rails(), aisleLetters, lengths, network.stop(),
                network.stopDx(), network.stopDz()));
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
        return new NetworkGoggleInfo(tag.getInt(RAILS), tag.getString(LETTERS), read,
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
