package dev.wareworks.core.port;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The rule by which a warehouse's package doors are listed (M27, issue #18, ADR-065 D9): bounded, in the order the
 * candidates come in, and with a transient memory of the doors that were seen, so a door whose chunk is not loaded turns
 * into a grey row instead of vanishing.
 * <p>
 * One {@link #build} <b>looks</b> at most {@code maxCandidates} candidates. A candidate that is <b>not loaded</b> is
 * never looked at — looking would load it — so it costs only that question and none of the bound, and it is listed
 * again in its unloaded form only when an earlier build saw it as a door; a loaded candidate is looked at, and what the
 * look answers is the row and the new memory of it, or no row and no memory. Once the bound is spent the build stops and
 * says so ({@link Built#unchecked()}), so a door further down the candidates is never silently left out: the caller
 * can tell the player that more was not checked. A candidate the build did not reach, or that is no longer among the
 * candidates, loses its memory, so the memory is bounded by the candidates. At most {@code maxRows} rows are listed;
 * the doors beyond them are only counted.
 * <p>
 * Pure Java, no Minecraft: the candidates, whether one is loaded and what a look finds are the caller's
 * ({@code content.controller.DoorDirectory}), so the rule itself is JUnit-testable.
 *
 * @param <K> what identifies a candidate, a rack position in the mod
 * @param <R> the row a door is listed as
 */
public final class DoorRows<K, R> {
    /**
     * What one build lists.
     *
     * @param rows      at most the bound of rows, in candidate order
     * @param notListed doors found beyond those rows
     * @param unchecked whether the bound of looks was spent before every candidate was checked, so doors may stand
     *                  among the candidates that were neither listed nor counted
     */
    public record Built<R>(List<R> rows, int notListed, boolean unchecked) {
        public Built {
            rows = List.copyOf(rows);
            notListed = Math.max(0, notListed);
        }
    }

    private final int maxCandidates;
    private final int maxRows;
    /** The row of every door seen at its last look, by candidate. */
    private final Map<K, R> lastSeen = new HashMap<>();

    /**
     * @param maxCandidates candidates one build looks at, at least 1
     * @param maxRows       rows one build lists, at least 1
     */
    public DoorRows(int maxCandidates, int maxRows) {
        this.maxCandidates = Math.max(1, maxCandidates);
        this.maxRows = Math.max(1, maxRows);
    }

    /**
     * Lists the doors among {@code candidates}.
     *
     * @param candidates in the order the rows are listed; duplicates count once
     * @param loaded     whether a candidate may be looked at without loading anything
     * @param look       the row of a loaded candidate that is a door, or empty for one that is none; called at most
     *                   {@code maxCandidates} times per build
     * @param unloaded   the form a remembered row is listed in while its candidate is not loaded
     */
    public Built<R> build(List<? extends K> candidates, Predicate<? super K> loaded, Function<? super K, Optional<R>> look,
            UnaryOperator<R> unloaded) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(loaded, "loaded");
        Objects.requireNonNull(look, "look");
        Objects.requireNonNull(unloaded, "unloaded");
        // Every candidate this build reached, once: the duplicates to skip, and the memory to keep.
        Set<K> reached = new HashSet<>();
        List<R> doors = new ArrayList<>();
        int looks = 0;
        boolean unchecked = false;
        for (K candidate : candidates) {
            if (candidate == null || reached.contains(candidate))
                continue;
            boolean isLoaded = loaded.test(candidate);
            // Only a look spends the bound: an unloaded candidate is never looked at, so it costs nothing of it.
            if (isLoaded && looks >= maxCandidates) {
                unchecked = true;
                break;
            }
            reached.add(candidate);
            if (!isLoaded) {
                R remembered = lastSeen.get(candidate);
                if (remembered != null)
                    doors.add(unloaded.apply(remembered));
                continue;
            }
            looks++;
            Optional<R> row = look.apply(candidate);
            if (row == null || row.isEmpty()) {
                lastSeen.remove(candidate);
                continue;
            }
            lastSeen.put(candidate, row.get());
            doors.add(row.get());
        }
        lastSeen.keySet().retainAll(reached);
        int listed = Math.min(doors.size(), maxRows);
        return new Built<>(doors.subList(0, listed), doors.size() - listed, unchecked);
    }

    /** Forgets every door: the warehouse is gone, so is what was seen of it. */
    public void forget() {
        lastSeen.clear();
    }

    /** How many doors are remembered: never more than the candidates of the last build. */
    public int remembered() {
        return lastSeen.size();
    }
}
