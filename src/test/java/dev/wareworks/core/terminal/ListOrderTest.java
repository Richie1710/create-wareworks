package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.stock.StockRule;

/**
 * A clipboard in a warehouse terminal, worked off in portions (M23, issue #19, ADR-036): the decisions a list order
 * makes on its own, without a warehouse around it.
 * <p>
 * The warehouse in these tests is the {@link Warehouse} at the bottom of the file, and it does only what the real
 * request path does to a list order: it grants what it has, refuses what it has not, <b>merges</b> a second request for
 * the same item into the first one and keeps its id (ADR-020), caps one merged request at
 * {@code maxTerminalRequestAmount}, and hands deliveries back as they arrive. That is enough to pin down every branch
 * the issue argues about — a list that fits, one that does not, an item nothing can serve, two entries of the same
 * item, an entry of amount zero, a partial delivery, a list of hundreds of entries, and an order that is finished
 * exactly once.
 */
class ListOrderTest {
    private static final String COBBLE = "cobblestone";
    private static final String PLANK = "oak_plank";
    private static final String LOG = "oak_log";
    private static final String DIAMOND = "diamond";
    private static final int CAP = 128;
    private static final int OPEN_LIMIT = 2;
    private static final int MAX_PER_REQUEST = 1024;
    private static final long INTERVAL = 20L;
    private static final long STALL = 6000L;

    // --- what a clipboard becomes ------------------------------------------------------------------------------------

    @Test
    void onlyEntriesThatAskForSomethingBecomeLines() {
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.separator(0, 0),
                ListEntry.of(0, 1, COBBLE, 2000), ListEntry.checked(0, 2, PLANK, 64),
                ListEntry.of(0, 3, LOG, 0), ListEntry.of(1, 0, DIAMOND, 1)),
                CAP, RequestAcknowledgement.NONE, 0L);
        assertEquals(List.of(COBBLE, DIAMOND), keysOf(order), "separator, ticked entry and zero amount are skipped");
        assertEquals(2000, order.lines().get(0).wanted());
        assertEquals(1, order.lines().get(1).page(), "the clipboard coordinates are what the tick mark is written to");
        assertEquals(0, order.lines().get(1).index());
        assertFalse(order.truncated());
        assertEquals(2001L, order.wanted());
        assertEquals(2001L, order.outstanding());
        assertEquals(0L, order.delivered());
    }

    @Test
    void aClipboardWithNothingToFetchIsFinishedAtOnce() {
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.separator(0, 0), ListEntry.checked(0, 1, PLANK, 64)),
                CAP, RequestAcknowledgement.NONE, 0L);
        assertEquals(0, order.entries());
        assertTrue(order.isComplete());
        assertEquals(ListOrderState.DONE, order.state());
        assertFalse(order.due(0L), "an order with nothing to do never measures anything");
        assertTrue(order.current().isEmpty());
        assertTrue(order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).isEmpty());
    }

    @Test
    void theEntryCapLeavesTheTailOfTheClipboardAlone() {
        List<ListEntry<String>> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++)
            entries.add(ListEntry.of(0, i, COBBLE, 64));
        ListOrder<String> order = ListOrder.of(entries, 4, RequestAcknowledgement.NONE, 0L);
        assertEquals(4, order.entries());
        assertEquals(6, order.dropped(), "the untaken tail stays unticked, which reads correctly");
        assertTrue(order.truncated());
        assertEquals(ListOrder.MIN_ENTRIES, ListOrder.of(entries, 0, RequestAcknowledgement.NONE, 0L).entries(),
                "a cap below the minimum is the minimum");
    }

    // --- a list that fits --------------------------------------------------------------------------------------------

    @Test
    void aListTheWarehouseHoldsIsFetchedAndEveryEntryTickedOff() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 2000).stocked(PLANK, 64);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 512), ListEntry.of(0, 1, PLANK, 64)),
                CAP, RequestAcknowledgement.NONE, 0L);
        assertEquals(List.of(COBBLE, PLANK), keysOf(workOff(order, warehouse)));
        assertEquals(ListOrderState.DONE, order.state());
        assertEquals(576L, order.delivered());
        assertEquals(0L, order.outstanding());
        assertEquals(2, order.entriesComplete());
        assertEquals(0, order.openRequests());
        assertEquals(2, order.completedLines().size(), "every tick mark the clipboard should carry");
    }

    @Test
    void aPortionIsWhatTheLineStillNeedsBoundedByTheRequestMaximum() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 4000);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 2000)), CAP,
                RequestAcknowledgement.NONE, 0L);
        order.beginPass(warehouse::alive);
        ListPortion<String> first = order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).orElseThrow();
        assertEquals(MAX_PER_REQUEST, first.amount(), "a whole schematic is ordered a portion at a time");
        assertEquals(0, first.line());
        Warehouse.Grant granted = warehouse.request(first).orElseThrow();
        order.granted(first, granted.id(), granted.amount(), 0L);
        assertTrue(order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).isEmpty(), "one open request per line");
        assertEquals(ListPass.OFFERED, order.finishPass(0L, INTERVAL, STALL));

        warehouse.deliver(order, granted.id(), 5L);
        order.beginPass(warehouse::alive);
        assertEquals(2000 - MAX_PER_REQUEST, order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).orElseThrow().amount(),
                "the next portion is the rest, not another full one");
    }

    @Test
    void noMoreRequestsAreOpenedThanTheLimitAllows() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 64).stocked(PLANK, 64).stocked(LOG, 64)
                .stocked(DIAMOND, 64);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 64), ListEntry.of(0, 1, PLANK, 64),
                ListEntry.of(0, 2, LOG, 64), ListEntry.of(0, 3, DIAMOND, 64)), CAP, RequestAcknowledgement.NONE, 0L);
        assertEquals(ListPass.OFFERED, pass(order, 0L, warehouse));
        assertEquals(OPEN_LIMIT, order.openRequests());
        assertEquals(OPEN_LIMIT, warehouse.openRequests(), "and the warehouse really only sees that many");
        assertEquals(ListPass.WAITING, pass(order, INTERVAL, warehouse),
                "with the limit reached there is nothing to offer, and that is not a stall");
        assertEquals(ListOrderState.RUNNING, order.state());
        assertEquals(COBBLE, order.current().orElseThrow().key(), "the first line in flight is what the player sees");
        assertEquals(128L, order.inFlight());
    }

    // --- duplicates of one item, and a merged request ----------------------------------------------------------------

    @Test
    void twoEntriesOfOneItemShareOneRequestAndEachTakesOnlyItsOwnPortion() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 1000);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 100), ListEntry.of(0, 1, COBBLE, 50)),
                CAP, RequestAcknowledgement.NONE, 0L);
        assertEquals(ListPass.OFFERED, pass(order, 0L, warehouse));
        UUID merged = order.lines().get(0).request().orElseThrow();
        assertEquals(merged, order.lines().get(1).request().orElseThrow(), "a repeated request keeps its id (ADR-020)");
        assertEquals(1, order.openRequests(), "requests are counted, not lines");

        ListCredit<String> first = order.credit(merged, 120, 10L);
        assertEquals(120, first.absorbed());
        assertEquals(1, first.completed().size(), "the first line takes its 100 and the rest spills to the second");
        assertEquals(0, first.completed().get(0).index());
        assertEquals(100, order.lines().get(0).delivered());
        assertEquals(20, order.lines().get(1).delivered());
        assertFalse(first.finished());

        ListCredit<String> rest = order.credit(merged, 30, 20L);
        assertEquals(30, rest.absorbed());
        assertEquals(1, rest.completed().size());
        assertTrue(rest.finished());
        assertEquals(ListOrderState.DONE, order.state());
    }

    @Test
    void aMergedRequestNeverGivesTheListMoreThanItsOwnPortionsAskedFor() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 10);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 10)), CAP,
                RequestAcknowledgement.NONE, 0L);
        pass(order, 0L, warehouse);
        UUID id = order.lines().get(0).request().orElseThrow();
        // The same request also serves a player's own click for 54 more. Both arrive; the list claims only its share.
        ListCredit<String> credit = order.credit(id, 64, 5L);
        assertEquals(10, credit.absorbed(), "a shared request is never double counted");
        assertEquals(10, order.lines().get(0).delivered());
        assertTrue(credit.finished());
        assertTrue(order.credit(id, 54, 6L).isEmpty(), "what is left of it belongs to whoever else asked");
    }

    // --- what cannot be served ---------------------------------------------------------------------------------------

    @Test
    void aLineTheWarehouseCannotServeIsSteppedOverAndNeverBlocksALaterOne() {
        Warehouse warehouse = new Warehouse().stocked(PLANK, 64);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, DIAMOND, 1), ListEntry.of(0, 1, PLANK, 64)),
                CAP, RequestAcknowledgement.NONE, 0L);
        assertEquals(ListPass.OFFERED, pass(order, 0L, warehouse));
        assertEquals(1, warehouse.refusals(), "the unobtainable line costs exactly one call");
        assertTrue(order.lines().get(1).isInFlight(), "and the next line is served all the same");

        warehouse.deliverEverything(order, 10L);
        assertEquals(ListPass.STARVED, pass(order, 20L, warehouse),
                "with the rest unserveable and nothing in flight the order is starved, not finished");
        assertEquals(ListOrderState.RUNNING, order.state());
        assertFalse(order.isComplete());
        assertEquals(1, order.entriesComplete());
        assertEquals(DIAMOND, order.current().orElseThrow().key(), "and it says which item it is waiting for");
    }

    @Test
    void aRefusedLineIsAskedAboutAgainInTheNextPassButOnlyOncePerPass() {
        Warehouse warehouse = new Warehouse();
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, DIAMOND, 1)), CAP,
                RequestAcknowledgement.NONE, 0L);
        assertEquals(ListPass.STARVED, pass(order, 0L, warehouse));
        assertEquals(1, warehouse.refusals());
        assertEquals(ListPass.STARVED, pass(order, INTERVAL, warehouse));
        assertEquals(2, warehouse.refusals(), "once per pass, never a loop inside one");
    }

    @Test
    void aBoundedPassStillWorksThroughALongListOfUnobtainableItems() {
        Warehouse warehouse = new Warehouse();
        List<ListEntry<String>> clipboard = new ArrayList<>();
        for (int i = 0; i < 40; i++)
            clipboard.add(ListEntry.of(i / 7, i % 7, DIAMOND + i, 1));
        clipboard.add(ListEntry.of(9, 0, COBBLE, 64));
        ListOrder<String> order = ListOrder.of(clipboard, CAP, RequestAcknowledgement.NONE, 0L);
        int perPass = OPEN_LIMIT * ListOrder.ATTEMPTS_PER_OPEN_REQUEST;

        assertEquals(ListPass.STARVED, pass(order, 0L, warehouse));
        assertEquals(perPass, warehouse.refusals(), "a pass measures a handful of lines, not a whole clipboard");
        warehouse.stocked(COBBLE, 64);
        ListPass found = null;
        for (long now = INTERVAL; now <= INTERVAL * 10L && found != ListPass.OFFERED; now += INTERVAL)
            found = pass(order, now, warehouse);
        assertEquals(ListPass.OFFERED, found, "and the next passes carry on where it stopped, so the last line is "
                + "reached instead of being starved by the ones before it");
        assertTrue(order.lines().get(40).isInFlight());
    }

    @Test
    void anOrderThatCanBeServedNothingParksAndAPlayerStartsItAgain() {
        Warehouse warehouse = new Warehouse();
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, DIAMOND, 1)), CAP,
                RequestAcknowledgement.NONE, 0L);
        assertEquals(ListPass.STARVED, pass(order, 0L, warehouse));
        assertEquals(ListOrderState.RUNNING, order.state(), "one fruitless pass is not a stall");
        assertEquals(ListPass.STARVED, pass(order, STALL - 1L, warehouse));
        assertEquals(ListOrderState.RUNNING, order.state());
        assertEquals(ListPass.STARVED, pass(order, STALL, warehouse));
        assertEquals(ListOrderState.PARKED, order.state(), "nothing in flight and no progress for the whole timeout");
        assertFalse(order.due(STALL * 10L), "a parked order costs nothing per tick");
        assertTrue(order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).isEmpty());

        warehouse.stocked(DIAMOND, 1);
        assertFalse(order.due(STALL * 10L), "and it does not notice the stock on its own");
        order.resume(STALL * 10L);
        assertEquals(ListOrderState.RUNNING, order.state());
        assertTrue(order.due(STALL * 10L));
        assertEquals(ListPass.OFFERED, pass(order, STALL * 10L, warehouse));
    }

    @Test
    void aStallTimeoutOfZeroNeverParks() {
        Warehouse warehouse = new Warehouse();
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, DIAMOND, 1)), CAP,
                RequestAcknowledgement.NONE, 0L);
        order.beginPass(warehouse::alive);
        order.refused(order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).orElseThrow());
        assertEquals(ListPass.STARVED, order.finishPass(STALL * 100L, INTERVAL, 0L));
        assertEquals(ListOrderState.RUNNING, order.state());
    }

    @Test
    void itemsOnTheirWayAreNeverAStallHoweverLongTheyTake() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 64);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 64)), CAP,
                RequestAcknowledgement.NONE, 0L);
        assertEquals(ListPass.OFFERED, pass(order, 0L, warehouse));
        // This is what a full terminal buffer looks like from here: the request stays open, no job is planned, and the
        // first freed slot restarts the trip (NoJobReason.OUTPUT_FULL).
        for (long now = INTERVAL; now < STALL * 3L; now += INTERVAL)
            assertEquals(ListPass.WAITING, pass(order, now, warehouse));
        assertEquals(ListOrderState.RUNNING, order.state(), "a full destination must never park an order");
        warehouse.deliverEverything(order, STALL * 3L);
        assertEquals(ListOrderState.DONE, order.state());
    }

    @Test
    void tenFruitlessPassesStretchTheInterval() {
        Warehouse warehouse = new Warehouse();
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, DIAMOND, 1)), CAP,
                RequestAcknowledgement.NONE, 0L);
        for (int i = 1; i < ListOrder.FRUITLESS_PASSES_BEFORE_BACKOFF; i++) {
            pass(order, i, warehouse);
            assertEquals(i + INTERVAL, order.nextPassTick(), "pass " + i + " still runs on the plain interval");
        }
        long last = ListOrder.FRUITLESS_PASSES_BEFORE_BACKOFF;
        pass(order, last, warehouse);
        assertEquals(last + INTERVAL * ListOrder.BACKOFF_FACTOR, order.nextPassTick(),
                "a hopeless list stops costing measurements");
        warehouse.stocked(DIAMOND, 1);
        pass(order, last + 1L, warehouse);
        assertEquals(last + 1L + INTERVAL, order.nextPassTick(), "and progress puts it back");
    }

    // --- progress ----------------------------------------------------------------------------------------------------

    @Test
    void aPartialDeliveryIsKeptAndTheRestIsAskedForAgainOnceTheRequestIsGone() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 100);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 100)), CAP,
                RequestAcknowledgement.NONE, 0L);
        pass(order, 0L, warehouse);
        UUID id = order.lines().get(0).request().orElseThrow();
        ListCredit<String> credit = order.credit(id, 40, 5L);
        assertEquals(40, credit.absorbed());
        assertTrue(credit.completed().isEmpty(), "a tick mark cannot say 1300 of 2000, so nothing is ticked yet");
        assertEquals(40, order.lines().get(0).delivered());
        assertEquals(60, order.lines().get(0).inFlight());
        assertEquals(0, order.lines().get(0).pending(), "the open request still owes the rest");
        assertEquals(ListPass.WAITING, pass(order, 10L, warehouse));

        // The request is lost — cancelled, pruned, or gone with the save it was written in.
        warehouse.forget(id);
        order.beginPass(warehouse::alive);
        assertFalse(order.lines().get(0).isInFlight());
        assertEquals(60, order.lines().get(0).outstanding(), "nothing is given back, because nothing was taken");
        assertEquals(60, order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).orElseThrow().amount());
    }

    @Test
    void aDeliveryNobodyWaitedForIsClaimedAsNothing() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 10);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 10)), CAP,
                RequestAcknowledgement.NONE, 0L);
        pass(order, 0L, warehouse);
        UUID id = order.lines().get(0).request().orElseThrow();
        assertTrue(order.credit(UUID.randomUUID(), 10, 5L).isEmpty(), "another station's delivery");
        assertTrue(order.credit(null, 10, 5L).isEmpty());
        assertTrue(order.credit(id, 0, 5L).isEmpty());
        assertEquals(0L, order.delivered());
        assertEquals(10, order.credit(id, 10, 5L).absorbed());
        assertTrue(order.credit(id, 10, 6L).isEmpty(), "and the same delivery credited twice changes nothing");
        assertEquals(10L, order.delivered());
    }

    @Test
    void aDeliveryMakesTheNextPassDueAtOnce() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 1000).stocked(PLANK, 64);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 10), ListEntry.of(0, 1, PLANK, 64)),
                CAP, RequestAcknowledgement.NONE, 0L);
        pass(order, 0L, warehouse);
        assertEquals(INTERVAL, order.nextPassTick());
        order.credit(order.lines().get(0).request().orElseThrow(), 10, 3L);
        assertEquals(3L, order.nextPassTick(), "a delivery frees a slot: as soon as space frees, the list continues");
        assertTrue(order.due(3L));
    }

    @Test
    void theOrderIsFinishedExactlyOnce() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 10);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 10)), CAP,
                RequestAcknowledgement.NONE, 0L);
        pass(order, 0L, warehouse);
        UUID id = order.lines().get(0).request().orElseThrow();
        assertFalse(order.credit(id, 4, 5L).finished());
        ListCredit<String> last = order.credit(id, 6, 6L);
        assertTrue(last.finished(), "the one delivery that completes the list says so");
        assertEquals(1, last.completed().size());
        assertEquals(ListOrderState.DONE, order.state());
        assertFalse(order.credit(id, 6, 7L).finished(), "and never again");
        assertEquals(ListPass.COMPLETE, pass(order, 8L, warehouse));
        assertEquals(ListOrderState.DONE, order.state());
        assertFalse(order.due(100L));
    }

    @Test
    void hundredsOfEntriesAreWorkedOffInListOrderWithoutEverPassingTheRequestLimit() {
        int entries = 300;
        Warehouse warehouse = new Warehouse().stocked(COBBLE, entries * 64);
        List<ListEntry<String>> clipboard = new ArrayList<>();
        for (int i = 0; i < entries; i++)
            clipboard.add(ListEntry.of(i / 7, i % 7, COBBLE, 64));
        ListOrder<String> order = ListOrder.of(clipboard, 512, RequestAcknowledgement.NONE, 0L);
        assertEquals(entries, order.entries());

        List<ListLine<String>> ticked = new ArrayList<>();
        long now = 0L;
        int rounds = 0;
        while (order.state().isOpen() && rounds++ < entries * 4) {
            pass(order, now, warehouse);
            assertTrue(order.openRequests() <= OPEN_LIMIT, "never more open requests than the limit allows");
            ticked.addAll(warehouse.deliverEverything(order, now));
            now += INTERVAL;
        }
        assertEquals(ListOrderState.DONE, order.state());
        assertEquals(entries, ticked.size(), "every entry is ticked off exactly once");
        assertEquals(entries, order.entriesComplete());
        assertEquals((long) entries * 64, order.delivered());
        // Depth first in list order: with everything servable the clipboard is ticked off from the top down.
        for (int i = 1; i < ticked.size(); i++)
            assertTrue(positionOf(ticked.get(i - 1)) < positionOf(ticked.get(i)),
                    "entry " + i + " was ticked out of list order");
    }

    // --- consent -----------------------------------------------------------------------------------------------------

    @Test
    void aPortionTheBudgetDoesNotCoverStopsTheOrderUntilAPlayerAnswers() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 64);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 64)), CAP,
                RequestAcknowledgement.NONE, 0L);
        RequestConfirmation<String> cost = new RequestConfirmation<>(COBBLE, 64L, 10L, 64L, 0L, 0L, StockRule.UNSET,
                List.of());
        assertFalse(order.covers(cost), "a plain Fetch pays for no reserve");
        order.beginPass(warehouse::alive);
        assertEquals(0, order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).orElseThrow().line());
        order.ask(cost);
        assertEquals(ListOrderState.ASKING, order.state());
        assertSame(cost, order.question().orElseThrow());
        assertTrue(order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).isEmpty(), "nothing is promised in between");
        assertEquals(ListPass.STARVED, order.finishPass(0L, INTERVAL, STALL));
        assertEquals(ListOrderState.ASKING, order.state(), "a pass never answers a question for the player");
        assertFalse(order.due(STALL * 10L), "and an asking order never parks either");

        order.answer(cost.acknowledgement(RequestScope.LIST), 100L);
        assertEquals(ListOrderState.RUNNING, order.state());
        assertTrue(order.question().isEmpty());
        assertTrue(order.covers(cost));
        assertTrue(order.due(100L));
        assertEquals(ListPass.OFFERED, pass(order, 100L, warehouse));
    }

    @Test
    void theConsentBudgetIsSpentDownSoOneYesCannotPayForPortionAfterPortion() {
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, PLANK, 128)), CAP,
                new RequestAcknowledgement(false, 0L, 0L, 0L, 100L), 0L);
        RequestConfirmation<String> made = new RequestConfirmation<>(PLANK, 64L, 0L, 0L, 0L, 60L, StockRule.UNSET,
                List.of());
        assertTrue(order.covers(made), "producing is what the Yes at Fetch was about");
        order.spend(made);
        assertEquals(40L, order.budget().produced());
        assertFalse(order.covers(made), "the second portion of 60 is asked about again");
        order.answer(new RequestAcknowledgement(false, 0L, 0L, 0L, 60L), 10L);
        assertEquals(100L, order.budget().produced(), "an answer tops the budget up, it does not replace it");
        assertTrue(order.covers(made));
    }

    @Test
    void whateverItCostsSpendsNothing() {
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, PLANK, 128)), CAP,
                RequestAcknowledgement.ANY, 0L);
        order.spend(new RequestConfirmation<>(PLANK, 64L, 9L, 64L, 0L, 60L, StockRule.UNSET, List.of()));
        assertEquals(RequestAcknowledgement.ANY, order.budget());
    }

    @Test
    void aYesAboutOneItemNeverPaysForAnother() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 64).stocked(PLANK, 64);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 64), ListEntry.of(0, 1, PLANK, 64)),
                CAP, RequestAcknowledgement.NONE, 0L);
        RequestConfirmation<String> cobbleReserve = new RequestConfirmation<>(COBBLE, 64L, 10L, 64L, 0L, 0L,
                StockRule.UNSET, List.of());
        RequestConfirmation<String> plankReserve = new RequestConfirmation<>(PLANK, 64L, 10L, 64L, 0L, 0L,
                StockRule.UNSET, List.of());

        order.beginPass(warehouse::alive);
        assertEquals(0, order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).orElseThrow().line());
        order.ask(cobbleReserve);
        order.answer(cobbleReserve.acknowledgement(RequestScope.LIST), 10L);

        assertTrue(order.covers(cobbleReserve), "the Yes authorises the question it was given");
        assertFalse(order.covers(plankReserve), "and nothing else: another item's reserve is asked about on its own");
        assertEquals(10L, order.budgetFor(COBBLE).fromReserve());
        assertEquals(0L, order.budgetFor(PLANK).fromReserve());
        assertEquals(0L, order.budget().fromReserve(), "a reserve is never part of the list-wide budget");

        order.spend(cobbleReserve);
        assertFalse(order.covers(cobbleReserve), "and it is spent where it was given, so a second portion asks again");
    }

    @Test
    void theAnsweredPortionIsOfferedAgainBeforeTheRestOfTheList() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 64).stocked(PLANK, 64).stocked(LOG, 64);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 64), ListEntry.of(0, 1, PLANK, 64),
                ListEntry.of(0, 2, LOG, 64)), CAP, RequestAcknowledgement.NONE, 0L);
        RequestConfirmation<String> cost = new RequestConfirmation<>(COBBLE, 64L, 10L, 64L, 0L, 0L, StockRule.UNSET,
                List.of());

        order.beginPass(warehouse::alive);
        assertEquals(0, order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).orElseThrow().line());
        order.ask(cost);
        order.answer(cost.acknowledgement(RequestScope.LIST), 10L);

        order.beginPass(warehouse::alive);
        assertEquals(0, order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).orElseThrow().line(),
                "the consent was given for that portion, so that portion is offered first");
    }

    @Test
    void sayingNoToAPortionParksTheOrderInsteadOfLeavingItAsking() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 64).stocked(PLANK, 64);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 64), ListEntry.of(0, 1, PLANK, 64)),
                CAP, RequestAcknowledgement.NONE, 0L);
        RequestConfirmation<String> cost = new RequestConfirmation<>(COBBLE, 64L, 10L, 64L, 0L, 0L, StockRule.UNSET,
                List.of());

        order.beginPass(warehouse::alive);
        order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).orElseThrow();
        order.ask(cost);
        assertTrue(order.decline(50L));
        assertEquals(ListOrderState.PARKED, order.state(), "a no parks it, so the list button becomes Resume");
        assertTrue(order.question().isEmpty(), "and the question is gone");
        assertFalse(order.covers(cost), "a no accepts nothing at all");
        assertFalse(order.decline(60L), "there is nothing left to say no to");

        order.resume(70L);
        assertEquals(ListOrderState.RUNNING, order.state());
        order.beginPass(warehouse::alive);
        assertEquals(1, order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).orElseThrow().line(),
                "a resumed order offers the rest of the list before it comes back to the refused portion");
    }

    @Test
    void onlyARunningOrderCanBeMadeToAsk() {
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.checked(0, 0, PLANK, 1)), CAP,
                RequestAcknowledgement.NONE, 0L);
        order.ask(RequestConfirmation.none(PLANK, 1L));
        assertEquals(ListOrderState.DONE, order.state(), "a finished order has nothing to ask about");
        assertTrue(order.question().isEmpty());
    }

    // --- giving up, and coming back ----------------------------------------------------------------------------------

    @Test
    void releasingAnOrderNamesEveryRequestItHeldAndKeepsItsLines() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 64).stocked(PLANK, 64);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 64), ListEntry.of(0, 1, PLANK, 64)),
                CAP, RequestAcknowledgement.NONE, 0L);
        pass(order, 0L, warehouse);
        Set<UUID> released = order.release();
        assertEquals(2, released.size(), "the caller cancels exactly these");
        assertEquals(0, order.openRequests());
        assertEquals(128L, order.outstanding(), "and the lines keep what they asked for");
        assertTrue(order.release().isEmpty());
    }

    @Test
    void savingAndRestoringKeepsEveryLineAndForgetsOnlyTheQuestion() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 1000).stocked(PLANK, 64);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 100), ListEntry.of(1, 3, PLANK, 64)),
                17, new RequestAcknowledgement(false, 0L, 0L, 0L, 40L), 0L);
        pass(order, 0L, warehouse);
        order.credit(order.lines().get(0).request().orElseThrow(), 30, 5L);

        ListOrder<String> reloaded = ListOrder.restore(order.lines(), order.dropped(), order.state(), order.budget(),
                order.nextPassTick(), order.lastProgressTick());
        assertEquals(order.lines(), reloaded.lines());
        assertEquals(order.dropped(), reloaded.dropped());
        assertEquals(order.state(), reloaded.state());
        assertEquals(order.budget(), reloaded.budget());
        assertEquals(order.nextPassTick(), reloaded.nextPassTick());
        assertEquals(order.lastProgressTick(), reloaded.lastProgressTick());
        assertEquals(30L, reloaded.delivered());
        assertEquals(2, reloaded.openRequests(), "a request that still exists is still waited for");

        ListOrder<String> asking = ListOrder.restore(order.lines(), 0, ListOrderState.ASKING,
                RequestAcknowledgement.NONE, 0L, 0L);
        assertEquals(ListOrderState.RUNNING, asking.state(), "the question is measured fresh, so it is never saved");
        assertTrue(asking.question().isEmpty());
    }

    @Test
    void aRestoredOrderForgetsRequestsTheControllerNoLongerHas() {
        Warehouse warehouse = new Warehouse().stocked(COBBLE, 64);
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 64)), CAP,
                RequestAcknowledgement.NONE, 0L);
        pass(order, 0L, warehouse);
        ListOrder<String> reloaded = ListOrder.restore(order.lines(), 0, ListOrderState.RUNNING,
                RequestAcknowledgement.NONE, 0L, 0L);
        assertEquals(1, reloaded.beginPass(id -> false), "nothing has to be loaded in any particular order");
        assertEquals(0, reloaded.openRequests());
        assertEquals(64L, reloaded.outstanding());
    }

    @Test
    void noSaveCanMakeOneOrderWalkAnUnboundedList() {
        List<ListLine<String>> crafted = new ArrayList<>();
        for (int i = 0; i < ListOrder.MAX_ENTRIES + 7; i++)
            crafted.add(ListLine.of(i / 7, i % 7, COBBLE, 1));
        ListOrder<String> order = ListOrder.restore(crafted, 2, ListOrderState.RUNNING, RequestAcknowledgement.NONE,
                0L, 0L);
        assertEquals(ListOrder.MAX_ENTRIES, order.entries());
        assertEquals(9, order.dropped(), "the tail is counted like any other untaken entry");
    }

    @Test
    void aRestoredOrderWhoseEveryLineIsDeliveredIsFinished() {
        ListLine<String> done = new ListLine<>(0, 0, COBBLE, 64, 64, 0, Optional.empty());
        ListOrder<String> order = ListOrder.restore(List.of(done), 0, ListOrderState.RUNNING,
                RequestAcknowledgement.NONE, 0L, 0L);
        assertEquals(ListOrderState.DONE, order.state());
        assertEquals(List.of(done), order.completedLines());
    }

    @Test
    void aLineOfSaveDataThatOwesWithoutARequestOwesNothing() {
        ListLine<String> crafted = new ListLine<>(0, 0, COBBLE, 64, 0, 40, Optional.empty());
        assertEquals(0, crafted.inFlight(), "an owed amount without a request cannot lose items");
        assertEquals(64, crafted.pending());
        assertEquals(0, new ListLine<>(0, 0, COBBLE, 64, 64, 10, Optional.of(UUID.randomUUID())).inFlight(),
                "nor can a request owe a line that has everything");
    }

    @Test
    void aPortionForALineThatDoesNotExistIsRefusedOutright() {
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 64)), CAP,
                RequestAcknowledgement.NONE, 0L);
        ListPortion<String> ghost = new ListPortion<>(7, COBBLE, 1);
        assertThrows(IllegalArgumentException.class, () -> order.refused(ghost));
        assertThrows(IllegalArgumentException.class, () -> order.granted(ghost, UUID.randomUUID(), 1, 0L));
        assertThrows(IllegalArgumentException.class, () -> new ListPortion<>(0, COBBLE, 0));
    }

    @Test
    void aGrantOfNothingIsARefusal() {
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 64), ListEntry.of(0, 1, PLANK, 64)),
                CAP, RequestAcknowledgement.NONE, 0L);
        order.beginPass(id -> true);
        ListPortion<String> first = order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).orElseThrow();
        assertFalse(order.granted(first, UUID.randomUUID(), 0, 0L));
        assertEquals(0, order.openRequests());
        assertEquals(1, order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).orElseThrow().line(), "the pass moves on");
        assertEquals(ListPass.STARVED, order.finishPass(0L, INTERVAL, STALL));
    }

    @Test
    void oneLineNeverHoldsTwoRequests() {
        ListOrder<String> order = ListOrder.of(List.of(ListEntry.of(0, 0, COBBLE, 64)), CAP,
                RequestAcknowledgement.NONE, 0L);
        order.beginPass(id -> true);
        ListPortion<String> portion = order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST).orElseThrow();
        UUID id = UUID.randomUUID();
        assertTrue(order.granted(portion, id, 64, 0L));
        assertThrows(IllegalStateException.class, () -> order.granted(portion, id, 64, 0L));
    }

    // --- the driver, and a warehouse that behaves like the real one --------------------------------------------------

    private static long positionOf(ListLine<String> line) {
        return (long) line.page() * 1000L + line.index();
    }

    private static List<String> keysOf(ListOrder<String> order) {
        return keysOf(order.lines());
    }

    private static List<String> keysOf(List<ListLine<String>> lines) {
        List<String> keys = new ArrayList<>();
        for (ListLine<String> line : lines)
            keys.add(line.key());
        return keys;
    }

    /** One top-up pass, exactly as the controller runs it: begin, offer until empty, finish. */
    private static ListPass pass(ListOrder<String> order, long now, Warehouse warehouse) {
        order.beginPass(warehouse::alive);
        Optional<ListPortion<String>> next;
        while ((next = order.nextPortion(OPEN_LIMIT, MAX_PER_REQUEST)).isPresent()) {
            ListPortion<String> portion = next.get();
            Optional<Warehouse.Grant> granted = warehouse.request(portion);
            if (granted.isPresent())
                order.granted(portion, granted.get().id(), granted.get().amount(), now);
            else
                order.refused(portion);
        }
        return order.finishPass(now, INTERVAL, STALL);
    }

    /** Passes and deliveries until the order is finished; the lines it ticked off, in the order it ticked them. */
    private static List<ListLine<String>> workOff(ListOrder<String> order, Warehouse warehouse) {
        List<ListLine<String>> ticked = new ArrayList<>();
        long now = 0L;
        for (int round = 0; round < 100 && order.state().isOpen(); round++) {
            pass(order, now, warehouse);
            ticked.addAll(warehouse.deliverEverything(order, now));
            now += INTERVAL;
        }
        return ticked;
    }

    /**
     * What a list order sees of the warehouse: stock that is promised away when a request is accepted, one request per
     * item that later requests merge into and that is capped like a real one (ADR-020), and deliveries of what a
     * request owes.
     */
    private static final class Warehouse {
        record Grant(UUID id, int amount) {
        }

        private final Map<String, Integer> stock = new HashMap<>();
        private final Map<String, UUID> openByKey = new HashMap<>();
        private final Map<UUID, Integer> owed = new LinkedHashMap<>();
        private int refusals;

        Warehouse stocked(String key, int amount) {
            stock.merge(key, amount, Integer::sum);
            return this;
        }

        Optional<Grant> request(ListPortion<String> portion) {
            int available = stock.getOrDefault(portion.key(), 0);
            UUID merging = openByKey.get(portion.key());
            int room = MAX_PER_REQUEST - (merging == null ? 0 : owed.getOrDefault(merging, 0));
            int granted = Math.min(Math.min(available, portion.amount()), room);
            if (granted < 1) {
                refusals++;
                return Optional.empty();
            }
            stock.put(portion.key(), available - granted);
            UUID id = openByKey.computeIfAbsent(portion.key(), key -> UUID.randomUUID());
            owed.merge(id, granted, Integer::sum);
            return Optional.of(new Grant(id, granted));
        }

        boolean alive(UUID id) {
            return owed.containsKey(id);
        }

        int refusals() {
            return refusals;
        }

        int openRequests() {
            return owed.size();
        }

        void forget(UUID id) {
            owed.remove(id);
            openByKey.values().remove(id);
        }

        /** Delivers everything one request owes and credits it; the lines that are now ticked off. */
        List<ListLine<String>> deliver(ListOrder<String> order, UUID id, long now) {
            Integer amount = owed.remove(id);
            openByKey.values().remove(id);
            return amount == null ? List.of() : order.credit(id, amount, now).completed();
        }

        /** Delivers every open request, in the order they were made. */
        List<ListLine<String>> deliverEverything(ListOrder<String> order, long now) {
            List<ListLine<String>> ticked = new ArrayList<>();
            for (UUID id : List.copyOf(owed.keySet()))
                ticked.addAll(deliver(order, id, now));
            return ticked;
        }
    }
}
