package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The one scheduling rule of a Recipe Selector station (ADR-085, design §5.2): which of the station's open production
 * orders may fetch ingredients now, why the others wait, whose deadline is paused, and when the station's line may
 * switch to another result.
 * <p>
 * <b>One result in the line.</b> A selector station's buffer, Packagers, boxes on the way and machine never hold
 * ingredients of two different results (design I2). The gate keeps that where items enter the line, in the crane's
 * supply needs: only orders of the current {@linkplain StationFacts#selection() selection} are admitted, and the line
 * switches to another result only when no order of any other result has handed something over
 * ({@link ProductionOrder#engaged()}), the station's buffer is empty, no crane trip is inbound and, in slip mode, the
 * Packagers are idle (design I3). Same-result orders next to each other in creation order run together; a result
 * further back waits its turn (FIFO), and an engaged order of the selection keeps its lane even when an older order
 * becomes the head.
 * <p>
 * <b>Deadlines.</b> A queued order ({@link SelectorWait#queues()}) never times out; the head order that is not engaged
 * keeps a running deadline while it waits for something physical, so a broken line still ends visibly; nothing behind
 * the head and no engaged order stopped by a fault runs one (design §9). Under a hold nothing is admitted and nothing
 * runs a deadline.
 * <p>
 * Pure and stateless: the caller evaluates it whenever it needs an answer and performs the switch itself.
 */
public final class SelectorGate {
    private SelectorGate() {}

    /**
     * What the caller knows about one selector station, read without a world scan (design §5.3).
     *
     * @param mode          how the station selects; {@link SelectorMode#NONE} admits every candidate
     * @param selection     the result the station's line is set to, empty before its first switch
     * @param armed         local mode: the selector has confirmed the machine's filter
     * @param fault         a station fault that stops fetching (no selector, no Packager, a filter item, ...); empty for
     *                      none. {@link SelectorWait#MACHINE_BUSY} is special: it stops a switch, not the current lane
     * @param hold          the station is stopped until the player confirms (design §8)
     * @param probeOrder    slip mode: the order that may fetch one run only, until it has brought a result back
     * @param bufferEmpty   the station's buffer (and any tank) is empty
     * @param inboundZero   no crane trip is on its way to the station
     * @param packagersIdle slip mode: no Packager of the station holds or queues a box
     * @param <K>           item key type
     */
    public record StationFacts<K>(SelectorMode mode, Optional<K> selection, boolean armed,
                                  Optional<SelectorWait> fault, boolean hold, Optional<UUID> probeOrder,
                                  boolean bufferEmpty, boolean inboundZero, boolean packagersIdle) {
        public StationFacts {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(fault, "fault");
            Objects.requireNonNull(probeOrder, "probeOrder");
            fault = fault.filter(wait -> wait != SelectorWait.NONE);
        }
    }

    /**
     * The gate's answer for one station.
     *
     * @param admitted    the orders whose supply lines may fetch now
     * @param waits       why each other candidate waits ({@link SelectorWait#PROBE_ONE_RUN} also names an admitted
     *                    order that is capped)
     * @param runCaps     admitted orders that may fetch only this many runs' worth of each line (the probe: 1)
     * @param paused      the orders whose deadline must not run ({@code ProductionOrders#timeOut} also-blocked set)
     * @param switchTo    the result the caller should switch the station's line to now, if any
     * @param firstResult the result the station is working on or about to: the selection while it runs, else the
     *                    head's result (for the wait text)
     * @param <K>         item key type
     */
    public record Decision<K>(Set<UUID> admitted, Map<UUID, SelectorWait> waits, Map<UUID, Integer> runCaps,
                              Set<UUID> paused, Optional<K> switchTo, Optional<K> firstResult) {
        public Decision {
            admitted = Collections.unmodifiableSet(new LinkedHashSet<>(admitted));
            waits = Collections.unmodifiableMap(new LinkedHashMap<>(waits));
            runCaps = Collections.unmodifiableMap(new LinkedHashMap<>(runCaps));
            paused = Collections.unmodifiableSet(new LinkedHashSet<>(paused));
            Objects.requireNonNull(switchTo, "switchTo");
            Objects.requireNonNull(firstResult, "firstResult");
        }

        /** No candidate: nothing admitted, nothing waits, nothing switches. */
        public static <K> Decision<K> empty() {
            return new Decision<>(Set.of(), Map.of(), Map.of(), Set.of(), Optional.empty(), Optional.empty());
        }

        /** Why {@code orderId} waits, {@link SelectorWait#NONE} for an admitted order or one the gate did not see. */
        public SelectorWait waitOf(UUID orderId) {
            return waits.getOrDefault(orderId, SelectorWait.NONE);
        }
    }

    /**
     * Decides for one station.
     *
     * @param openAtStation the station's open orders in creation order (finished ones are ignored)
     * @param chainBlocked  whether an order waits for a step of its own plan; such an order fetches nothing anyway
     *                      (ADR-032) and is no candidate, but still counts when it is engaged
     * @param facts         what the caller knows about the station
     */
    public static <K, L> Decision<K> decide(List<ProductionOrder<K, L>> openAtStation, Predicate<UUID> chainBlocked,
            StationFacts<K> facts) {
        Objects.requireNonNull(openAtStation, "openAtStation");
        Objects.requireNonNull(chainBlocked, "chainBlocked");
        Objects.requireNonNull(facts, "facts");
        List<ProductionOrder<K, L>> open = new ArrayList<>(openAtStation.size());
        for (ProductionOrder<K, L> order : openAtStation) {
            if (order != null && order.isOpen())
                open.add(order);
        }
        List<ProductionOrder<K, L>> candidates = new ArrayList<>(open.size());
        for (ProductionOrder<K, L> order : open) {
            if (!chainBlocked.test(order.id()))
                candidates.add(order);
        }
        if (candidates.isEmpty())
            return Decision.empty();
        ProductionOrder<K, L> head = candidates.getFirst();
        Optional<K> selection = facts.selection();
        Set<UUID> admitted = new LinkedHashSet<>();
        Map<UUID, SelectorWait> waits = new LinkedHashMap<>();
        Map<UUID, Integer> runCaps = new LinkedHashMap<>();
        Set<UUID> paused = new LinkedHashSet<>();

        if (!facts.mode().selects()) {
            for (ProductionOrder<K, L> order : candidates)
                admitted.add(order.id());
            return new Decision<>(admitted, waits, runCaps, paused, Optional.empty(), Optional.of(head.result()));
        }

        // A hold stops the whole station (design I5, §8): nothing fetches, nothing switches, no deadline runs.
        if (facts.hold()) {
            for (ProductionOrder<K, L> order : candidates) {
                waits.put(order.id(), SelectorWait.HOLD);
                paused.add(order.id());
            }
            return new Decision<>(admitted, waits, runCaps, paused, Optional.empty(),
                    Optional.of(selection.orElse(head.result())));
        }

        Optional<SelectorWait> fault = facts.fault();
        if (facts.mode() == SelectorMode.LOCAL && !facts.armed() && fault.isEmpty())
            fault = Optional.of(SelectorWait.ARMING);

        // 0. Defensive (review fix): no selection although an order has handed something over. The line already holds
        //    that order's ingredients, so the selection goes back to its result at once, without the drain checks a
        //    real switch needs. FIFO alone would leave the engaged order BEHIND an older head of another result, which
        //    waits LINE_BUSY for it, and nothing would ever move again or time out.
        if (selection.isEmpty()) {
            Optional<K> inLine = oldestEngagedResult(open);
            if (inLine.isPresent()) {
                Optional<SelectorWait> otherFault = fault
                        .filter(wait -> wait != SelectorWait.MACHINE_BUSY && wait != SelectorWait.ARMING);
                SelectorWait restoring = otherFault.orElse(fault.filter(SelectorWait.MACHINE_BUSY::equals).isPresent()
                        ? SelectorWait.MACHINE_BUSY : SelectorWait.SWITCHING);
                for (ProductionOrder<K, L> order : candidates) {
                    waits.put(order.id(), order.result().equals(inLine.get()) ? restoring : SelectorWait.BEHIND);
                    paused.add(order.id());
                }
                return new Decision<>(admitted, waits, runCaps, paused, inLine, inLine);
            }
        }

        // 1. The orders that may run on the current selection.
        List<ProductionOrder<K, L>> runSet = new ArrayList<>();
        if (selection.isPresent()) {
            K selected = selection.get();
            boolean prefix = true;
            for (ProductionOrder<K, L> order : candidates) {
                boolean same = order.result().equals(selected);
                prefix &= same;
                // Sticky: an engaged order of the selection finishes its lane; FIFO: same-result neighbours of the head
                // run together.
                if (same && (order.engaged() || prefix))
                    runSet.add(order);
            }
            // Never start a newcomer while another result is in the line.
            if (engagedOther(open, selected))
                runSet.removeIf(order -> !order.engaged());
        }

        // 2. A switch to the head's result.
        boolean switchDue = !selection.equals(Optional.of(head.result())) && !runSet.contains(head);
        Optional<K> switchTo = Optional.empty();
        SelectorWait headWait = SelectorWait.BEHIND;
        if (switchDue) {
            Optional<SelectorWait> otherFault = fault
                    .filter(wait -> wait != SelectorWait.MACHINE_BUSY && wait != SelectorWait.ARMING);
            if (engagedOther(open, head.result())) {
                headWait = SelectorWait.LINE_BUSY;
            } else if (!facts.bufferEmpty()) {
                headWait = SelectorWait.BUFFER_NOT_EMPTY;
            } else if (!facts.inboundZero()) {
                headWait = SelectorWait.CRANE_INBOUND;
            } else if (facts.mode() == SelectorMode.SLIPS && !facts.packagersIdle()) {
                headWait = SelectorWait.PACKAGER_BUSY;
            } else if (otherFault.isPresent()) {
                headWait = otherFault.get();
            } else {
                // The caller performs the switch (design §6.1, §7.4); a busy machine keeps it pending and retried.
                switchTo = Optional.of(head.result());
                headWait = fault.filter(SelectorWait.MACHINE_BUSY::equals).isPresent() ? SelectorWait.MACHINE_BUSY
                        : SelectorWait.SWITCHING;
            }
        }

        // 3. Admission of the run set.
        Optional<UUID> probe = facts.probeOrder().filter(id -> probeOpen(open, id));
        Optional<SelectorWait> stopping = fault.filter(wait -> wait != SelectorWait.MACHINE_BUSY);
        for (ProductionOrder<K, L> order : runSet) {
            if (stopping.isPresent()) {
                waits.put(order.id(), stopping.get());
            } else if (probe.isPresent() && !probe.get().equals(order.id()) && !order.engaged()) {
                waits.put(order.id(), SelectorWait.PROBE_BEHIND);
            } else {
                admitted.add(order.id());
                if (probe.isPresent() && probe.get().equals(order.id())) {
                    runCaps.put(order.id(), 1);
                    waits.put(order.id(), SelectorWait.PROBE_ONE_RUN);
                }
            }
        }

        // 4. Everybody else.
        for (ProductionOrder<K, L> order : candidates) {
            if (runSet.contains(order))
                continue;
            waits.put(order.id(), order == head && switchDue ? headWait : SelectorWait.BEHIND);
        }

        // 5. Deadlines: only the head may run its clock while it waits for something physical; queues never time out;
        //    an engaged order stopped by a fault waits for the player.
        for (ProductionOrder<K, L> order : candidates) {
            SelectorWait wait = waits.get(order.id());
            if (wait == null || admitted.contains(order.id()))
                continue;
            if (wait.queues() || order != head || order.engaged())
                paused.add(order.id());
        }
        Optional<K> firstResult = runSet.isEmpty() ? Optional.of(head.result()) : selection;
        return new Decision<>(admitted, waits, runCaps, paused, switchTo, firstResult);
    }

    /**
     * Whether any open order of the station, chain-blocked and trimmed ones included, has handed something over for a
     * result other than {@code result}.
     */
    private static <K, L> boolean engagedOther(List<ProductionOrder<K, L>> open, K result) {
        for (ProductionOrder<K, L> order : open) {
            if (order.engaged() && !order.result().equals(result))
                return true;
        }
        return false;
    }

    /** The result of the oldest open order of the station that has handed something over, if any. */
    private static <K, L> Optional<K> oldestEngagedResult(List<ProductionOrder<K, L>> open) {
        for (ProductionOrder<K, L> order : open) {
            if (order.engaged())
                return Optional.of(order.result());
        }
        return Optional.empty();
    }

    /** Whether the probe order {@code id} is open at the station and has not brought a result back yet. */
    private static <K, L> boolean probeOpen(List<ProductionOrder<K, L>> open, UUID id) {
        for (ProductionOrder<K, L> order : open) {
            if (order.id().equals(id))
                return order.produced() == 0L;
        }
        return false;
    }
}
