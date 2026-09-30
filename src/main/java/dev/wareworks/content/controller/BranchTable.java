package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.StorageAddress;

/**
 * The aisle letters and origin ends a warehouse has pinned to its rails ({@code docs/warehouse-system.md} §2,
 * ADR-033) — the reason ordinary building never reshuffles a player's addresses.
 * <p>
 * <b>What is pinned, and to what.</b> An entry is keyed by the branch's <b>line</b>: its axis plus the one coordinate
 * that is fixed along it, given relative to the dock (which is always the block in front of the controller, so this is
 * the controller-relative key of ADR-033 shifted by one block). A line key survives exactly the things a player does:
 * extending an aisle at either end, shortening it, and a junction appearing in its middle all leave the line where it
 * was. Only tearing an aisle down and building it somewhere else makes a new line, and that really is a new aisle.
 * <p>
 * <b>A line is infinite, so it is not by itself an aisle.</b> One chain can lay two disjoint aisles on the same line —
 * a serpentine that comes back to the same row with a gap in it is the shape a player builds for a comb — and while
 * each line held a single entry those two shared it: the nearer aisle claimed the letter the farther one had written
 * last, the farther one fell through to the lowest free letter, and the two <b>traded letters on every refresh</b>,
 * for ever (M21 review fix). A line therefore holds as many entries as there are aisles on it, and a branch is matched
 * to the one whose pinned origin still lies on that branch's own stretch of rails — preferring one of its two ends,
 * which is the case every ordinary build produces.
 * <p>
 * <b>Letters.</b> Branch {@value RackPosition#FIRST_BRANCH} always takes the controller's own "Aisle" value box, so a
 * straight warehouse built before M21 keeps reading exactly as it did. Every further branch takes its pinned letter if
 * it has one and nothing nearer has claimed it, otherwise the <b>lowest free</b> letter. Assignment is a function of
 * the branch order and this table alone, so it is reproducible across restarts.
 * <p>
 * <b>Origins.</b> A branch is numbered from its origin outwards, so moving the origin renumbers every position on it.
 * A saved origin is therefore kept whenever it is still one of the branch's two ends — if it became the far end, the
 * branch is flipped rather than renumbered. An origin that is no longer an end at all (the aisle grew past it) cannot
 * be kept, and the controller remaps the records through their world positions instead.
 * <p>
 * Bounded by {@value #MAX_ENTRIES} entries, one per possible letter; the least recently seen aisle is dropped first.
 * Not thread-safe (server thread only).
 */
final class BranchTable {
    /** One entry per aisle letter: a warehouse can never have more branches than the address format has letters. */
    static final int MAX_ENTRIES = StorageAddress.AISLE_COUNT;

    /** No pinned entry belongs to a branch. */
    private static final int NO_PIN = -1;

    /**
     * What one line of rails is remembered as.
     *
     * @param letter   the aisle letter that line had
     * @param originDx X offset of position 0 from the dock block
     * @param originDz Z offset of position 0 from the dock block
     */
    record Entry(char letter, int originDx, int originDz) {
        Entry {
            if (!StorageAddress.isValidAisle(letter))
                throw new IllegalArgumentException("aisle letter must be A-Z: " + letter);
        }
    }

    /** The letters and pinned geometry one discovery run resolved to. */
    record Assignment(NetworkGeometry network, List<Optional<Character>> letters) {
        Assignment {
            Objects.requireNonNull(network, "network");
            letters = List.copyOf(Objects.requireNonNull(letters, "letters"));
        }
    }

    /**
     * One pin per remembered aisle, in the order they were last seen (oldest first), so the oldest is the one dropped
     * at {@link #MAX_ENTRIES}. Several pins can share a line key — see the class comment.
     */
    private final List<Map.Entry<Long, Entry>> pins = new ArrayList<>();

    /** The line key of a branch: its axis and the one coordinate that does not change along it. */
    static long lineOf(BranchGeometry branch) {
        Objects.requireNonNull(branch, "branch");
        boolean alongX = branch.heading().axis() == Heading.Axis.X;
        int fixed = alongX ? branch.originDz() : branch.originDx();
        return ((long) (alongX ? 0 : 1) << 32) | (fixed & 0xFFFFFFFFL);
    }

    boolean isEmpty() {
        return pins.isEmpty();
    }

    int size() {
        return pins.size();
    }

    /** The pinned aisles as a list in insertion order, which is what persistence writes. */
    List<Map.Entry<Long, Entry>> entries() {
        return List.copyOf(pins);
    }

    void restore(List<Map.Entry<Long, Entry>> saved) {
        pins.clear();
        for (Map.Entry<Long, Entry> entry : Objects.requireNonNull(saved, "saved")) {
            if (pins.size() >= MAX_ENTRIES)
                break;
            pins.add(Map.entry(entry.getKey(), entry.getValue()));
        }
    }

    /** Forgets every aisle: the warehouse this table described is gone (no dock, or another dock or direction). */
    void clear() {
        pins.clear();
    }

    /**
     * Pins {@code network}: every branch beyond the first keeps its saved origin end when that is still one of its
     * ends, and keeps its saved letter when nothing nearer the dock has claimed it. The table is updated with what was
     * assigned, so the next run answers the same thing.
     *
     * @param network     the freshly discovered geometry
     * @param firstLetter the controller's own aisle letter, which branch {@value RackPosition#FIRST_BRANCH} always takes
     */
    Assignment assign(NetworkGeometry network, char firstLetter) {
        Objects.requireNonNull(network, "network");
        if (!StorageAddress.isValidAisle(firstLetter))
            throw new IllegalArgumentException("aisle letter must be A-Z: " + firstLetter);
        List<BranchGeometry> pinned = new ArrayList<>(network.branchCount());
        Map<Integer, Character> claimed = new HashMap<>();
        Set<Character> used = new HashSet<>();
        used.add(firstLetter);
        pinned.add(network.firstBranch());
        int[] matched = new int[network.branchCount()];
        Arrays.fill(matched, NO_PIN);
        Set<Integer> taken = new HashSet<>();
        Map<Long, Integer> branchesPerLine = new HashMap<>();
        for (BranchGeometry branch : network.branches())
            branchesPerLine.merge(lineOf(branch), 1, Integer::sum);
        for (int i = RackPosition.FIRST_BRANCH + 1; i < network.branchCount(); i++) {
            BranchGeometry branch = network.branch(i);
            matched[i] = pinFor(branch, taken, branchesPerLine.getOrDefault(lineOf(branch), 1));
            Entry saved = matched[i] == NO_PIN ? null : pins.get(matched[i]).getValue();
            if (saved != null)
                taken.add(matched[i]); // one pin belongs to one aisle, so two aisles can never read the same one
            pinned.add(saved == null ? branch : repin(branch, saved));
            if (saved != null && used.add(saved.letter()))
                claimed.put(i, saved.letter());
        }
        List<Optional<Character>> letters = new ArrayList<>(pinned.size());
        letters.add(Optional.of(firstLetter));
        for (int i = RackPosition.FIRST_BRANCH + 1; i < pinned.size(); i++) {
            Character letter = claimed.get(i);
            if (letter == null)
                letter = lowestFree(used);
            if (letter != null)
                used.add(letter);
            letters.add(Optional.ofNullable(letter));
        }
        NetworkGeometry result = new NetworkGeometry(pinned, network.height());
        remember(result, letters, matched);
        return new Assignment(result, letters);
    }

    /**
     * The pinned entry that belongs to {@code branch}: one on the same line whose origin still lies on that branch's
     * own stretch of rails, preferring one that is exactly one of its two ends. Entries already claimed by a nearer
     * branch of the same run are skipped, so two aisles on one line always read two different entries or none.
     * <p>
     * When nothing pinned lies on the branch any more — it was shortened at its origin end, or rebuilt further along
     * the same line — the line's single entry is still unambiguously its own, so it keeps its letter exactly as it did
     * before. That fallback is refused as soon as the line carries more than one aisle or more than one entry: there
     * is then no such thing as "the" entry of that line, and guessing one is what made two aisles trade letters.
     *
     * @param branchesOnLine how many branches of the network being assigned lie on this branch's line
     * @return an index into {@link #pins}, or {@link #NO_PIN}
     */
    private int pinFor(BranchGeometry branch, Set<Integer> taken, int branchesOnLine) {
        long line = lineOf(branch);
        int overlapping = NO_PIN;
        int onlyFree = NO_PIN;
        int onLine = 0;
        for (int i = 0; i < pins.size(); i++) {
            if (pins.get(i).getKey() != line)
                continue;
            onLine++;
            if (taken.contains(i))
                continue;
            if (onlyFree == NO_PIN)
                onlyFree = i;
            Entry entry = pins.get(i).getValue();
            if (!liesOn(branch, entry))
                continue;
            if (isEndOf(branch, entry))
                return i;
            if (overlapping == NO_PIN)
                overlapping = i; // the aisle grew past this origin: still the same aisle, so it keeps its letter
        }
        if (overlapping != NO_PIN)
            return overlapping;
        return branchesOnLine == 1 && onLine == 1 ? onlyFree : NO_PIN;
    }

    /** Whether a pinned origin is a block of {@code branch}, ends included. */
    private static boolean liesOn(BranchGeometry branch, Entry entry) {
        boolean alongX = branch.heading().axis() == Heading.Axis.X;
        if ((alongX ? branch.originDz() : branch.originDx()) != (alongX ? entry.originDz() : entry.originDx()))
            return false;
        int from = alongX ? branch.originDx() : branch.originDz();
        int to = alongX ? branch.cellDx(branch.length()) : branch.cellDz(branch.length());
        int at = alongX ? entry.originDx() : entry.originDz();
        return at >= Math.min(from, to) && at <= Math.max(from, to);
    }

    /** Whether a pinned origin is one of the two ends of {@code branch} — the case ordinary building produces. */
    private static boolean isEndOf(BranchGeometry branch, Entry entry) {
        return (entry.originDx() == branch.originDx() && entry.originDz() == branch.originDz())
                || (entry.originDx() == branch.cellDx(branch.length())
                        && entry.originDz() == branch.cellDz(branch.length()));
    }

    /**
     * The same branch numbered from its saved origin, if that is still one of its ends. A saved origin that became the
     * <b>far</b> end flips the branch rather than renumbering it; one that is no longer an end at all is dropped,
     * because a branch has no position before its origin.
     */
    private static BranchGeometry repin(BranchGeometry branch, Entry saved) {
        if (saved.originDx() == branch.originDx() && saved.originDz() == branch.originDz())
            return branch;
        int farDx = branch.cellDx(branch.length());
        int farDz = branch.cellDz(branch.length());
        if (saved.originDx() != farDx || saved.originDz() != farDz)
            return branch; // the aisle grew past its old origin: the controller remaps instead
        return new BranchGeometry(branch.index(), farDx, farDz, branch.heading().opposite(), branch.length());
    }

    @Nullable
    private static Character lowestFree(Set<Character> used) {
        for (int i = 0; i < StorageAddress.AISLE_COUNT; i++) {
            char letter = StorageAddress.aisleLetter(i);
            if (!used.contains(letter))
                return letter;
        }
        return null; // a network cannot have more branches than there are letters, so this is unreachable
    }

    /**
     * Refreshes every assigned aisle, so the oldest entry is the aisle that has not been seen for longest. An aisle
     * replaces <b>the entry it was matched to</b> and nothing else: overwriting whatever was written to its line last
     * is what let two aisles on one line take turns owning a single entry (M21 review fix).
     * <p>
     * Branch {@value RackPosition#FIRST_BRANCH} is deliberately <b>not</b> remembered: its letter is the controller's
     * own value box and its origin is the dock, so there is nothing about it a table could pin — and leaving it out is
     * what keeps the table, and therefore the whole {@code Network} tag, empty for a warehouse of one aisle.
     */
    private void remember(NetworkGeometry network, List<Optional<Character>> letters, int[] matched) {
        List<Map.Entry<Long, Entry>> refreshed = new ArrayList<>();
        Set<Integer> replaced = new HashSet<>();
        for (int i = RackPosition.FIRST_BRANCH + 1; i < network.branchCount(); i++) {
            Optional<Character> letter = i < letters.size() ? letters.get(i) : Optional.empty();
            if (letter.isEmpty())
                continue;
            BranchGeometry branch = network.branch(i);
            if (matched[i] != NO_PIN)
                replaced.add(matched[i]);
            refreshed.add(Map.entry(lineOf(branch), new Entry(letter.get(), branch.originDx(), branch.originDz())));
        }
        List<Map.Entry<Long, Entry>> kept = new ArrayList<>(pins.size() + refreshed.size());
        for (int i = 0; i < pins.size(); i++) {
            if (!replaced.contains(i))
                kept.add(pins.get(i));
        }
        kept.addAll(refreshed);
        pins.clear();
        pins.addAll(kept.size() > MAX_ENTRIES ? kept.subList(kept.size() - MAX_ENTRIES, kept.size()) : kept);
    }

    @Override
    public String toString() {
        return "BranchTable[" + pins.size() + " aisles]";
    }
}
