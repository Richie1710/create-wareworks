package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * What pressing Fetch on a clipboard would really mean, measured over the whole list before anything is requested
 * ({@code docs/warehouse-system.md} §3.4.4, M23, issue #19, ADR-036).
 * <p>
 * This is the question the project owner settled on: <b>partial stock asks first</b> ("the list wants 2000 cobblestone
 * and the warehouse holds 1300 — fetch what there is?") and <b>producible items ask too</b> ("4 entries are not in
 * stock but can be produced — start production?"). Both are cheap to measure over a whole list, which is what makes one
 * dialog possible instead of one per entry: what could be produced right now is one walk over the aisle's patterns,
 * and what is in stock is a lookup per line. No plan is walked per line and nothing is promised by measuring.
 * <p>
 * <b>Shared stock is spent, not counted twice.</b> The lines are classified in list order against a <i>running</i>
 * stock and production budget, so two entries asking for the same item are told what the warehouse can really do for
 * both of them together — 1300 cobblestone cannot serve two entries of 1300 each. That is also what keeps the one
 * invariant this record is worth having: {@code wanted == serveable + producing + missing}, so no total can ever name
 * an amount no order could reach.
 * <p>
 * The answer travels back as three numbers and is <b>measured again</b> before the order starts ({@link #covers}): a
 * warehouse that gained stock in the meantime passes, one that lost stock asks again. That is the M15/M20 round trip
 * applied to a list, not a second mechanism.
 *
 * @param wanted           items the whole list still asks for
 * @param serveable        of those, what the racks can give right now
 * @param producing        of those, what a production pattern would have to make — the number a Yes agrees to, and the
 *                         consent budget the order then spends down portion by portion ({@link #budget()})
 * @param missing          of those, what the warehouse can neither give nor make: left unticked and reported
 * @param entriesServed    entries the racks cover in full
 * @param entriesShort     entries that are partly covered, i.e. the ones "fetch what there is" is about
 * @param entriesProducing entries that are covered in full but only with production
 * @param entriesImpossible entries the warehouse can do nothing at all for
 * @param entriesDropped   orderable entries the entry cap left on the clipboard ({@link ListOrder#dropped()}): they are
 *                         <b>not</b> part of any other number here, because the order never took them. They are part of
 *                         the question all the same — a player presses Fetch on "this list" and has to be told in the
 *                         dialog they consent in that part of their list was left out (M23 review fix)
 * @param named            at most {@value #MAX_NAMED} of the entries that are not simply served, in list order, for
 *                         the panel's sentences
 * @param <K>              item key type
 */
public record ListOrderConfirmation<K>(long wanted, long serveable, long producing, long missing, int entriesServed,
                                      int entriesShort, int entriesProducing, int entriesImpossible,
                                      int entriesDropped, List<Line<K>> named) {
    /** How many entries the panel names before it falls back to counting them. */
    public static final int MAX_NAMED = 5;

    public ListOrderConfirmation {
        wanted = Math.max(0L, wanted);
        serveable = Math.max(0L, serveable);
        producing = Math.max(0L, producing);
        missing = Math.max(0L, missing);
        entriesServed = Math.max(0, entriesServed);
        entriesShort = Math.max(0, entriesShort);
        entriesProducing = Math.max(0, entriesProducing);
        entriesImpossible = Math.max(0, entriesImpossible);
        entriesDropped = Math.max(0, entriesDropped);
        named = named == null ? List.of()
                : List.copyOf(named.size() <= MAX_NAMED ? named : named.subList(0, MAX_NAMED));
    }

    /**
     * One entry the panel names.
     *
     * @param key       the item the entry asks for
     * @param wanted    how many of it
     * @param serveable what the racks can give of that
     * @param producing what would have to be made for it
     * @param missing   what cannot be had at all
     * @param <K>       item key type
     */
    public record Line<K>(K key, long wanted, long serveable, long producing, long missing) {
        public Line {
            Objects.requireNonNull(key, "key");
            wanted = Math.max(0L, wanted);
            serveable = Math.max(0L, serveable);
            producing = Math.max(0L, producing);
            missing = Math.max(0L, missing);
        }

        /** Whether the warehouse falls short of this entry, i.e. whether it is one "fetch what there is" is about. */
        public boolean isShort() {
            return missing > 0L;
        }

        /** Whether the warehouse can do nothing at all for this entry. */
        public boolean isImpossible() {
            return serveable == 0L && producing == 0L;
        }
    }

    /** The question for a list with nothing left to ask about. */
    public static <K> ListOrderConfirmation<K> none() {
        return new ListOrderConfirmation<>(0L, 0L, 0L, 0L, 0, 0, 0, 0, 0, List.of());
    }

    /**
     * The question {@code lines} raise against the warehouse as it is right now. Lines that are already delivered in
     * full are not part of it, so resuming a half-finished order asks only about what is left.
     *
     * @param dropped    orderable entries the entry cap left on the clipboard ({@link ListOrder#dropped()}); they are
     *                   not among {@code lines} and are reported rather than measured
     * @param available  what the racks can give of a key, as the request path would see it for a player
     * @param producible how many of a key the aisle's patterns could make right now — the server's own number
     *                   ({@code WarehouseControllerBlockEntity#producibleAmounts}), never one a screen derived
     */
    public static <K> ListOrderConfirmation<K> of(List<ListLine<K>> lines, int dropped,
            ToLongFunction<? super K> available, ToLongFunction<? super K> producible) {
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(producible, "producible");
        Map<K, Long> stock = new HashMap<>();
        Map<K, Long> makeable = new HashMap<>();
        long wanted = 0L;
        long serveable = 0L;
        long producing = 0L;
        long missing = 0L;
        int served = 0;
        int shortOf = 0;
        int produced = 0;
        int impossible = 0;
        List<Line<K>> named = new ArrayList<>(MAX_NAMED);
        for (ListLine<K> line : lines) {
            Objects.requireNonNull(line, "line");
            if (line.isComplete())
                continue;
            long want = line.outstanding();
            wanted += want;
            // Both budgets are running totals: what an earlier entry of the same item would take is gone for this one,
            // which is the whole reason two entries of 1300 cobblestone are not told they can both be served.
            long inStock = stock.computeIfAbsent(line.key(), key -> Math.max(0L, available.applyAsLong(key)));
            long fromStock = Math.min(want, inStock);
            stock.put(line.key(), inStock - fromStock);
            long rest = want - fromStock;
            long canMake = makeable.computeIfAbsent(line.key(), key -> Math.max(0L, producible.applyAsLong(key)));
            long made = Math.min(rest, canMake);
            makeable.put(line.key(), canMake - made);
            long gap = rest - made;
            serveable += fromStock;
            producing += made;
            missing += gap;
            // Four kinds, and every entry is exactly one of them, so the counts add up to the entries asked about.
            if (gap > 0L && fromStock + made > 0L)
                shortOf++;
            else if (gap > 0L)
                impossible++;
            else if (made > 0L)
                produced++;
            else
                served++;
            if ((gap > 0L || made > 0L) && named.size() < MAX_NAMED)
                named.add(new Line<>(line.key(), want, fromStock, made, gap));
        }
        return new ListOrderConfirmation<>(wanted, serveable, producing, missing, served, shortOf, produced,
                impossible, dropped, named);
    }

    /** Entries this question is about, i.e. the lines that still want something. */
    public int entries() {
        return entriesServed + entriesShort + entriesProducing + entriesImpossible;
    }

    /** Whether the clipboard held more orderable entries than the order took. */
    public boolean truncated() {
        return entriesDropped > 0;
    }

    /**
     * Whether the player has to be asked before the order starts: the warehouse falls short somewhere, something would
     * have to be produced, or the entry cap left part of their list behind. A list a stocked warehouse covers from the
     * racks starts without a dialog.
     */
    public boolean required() {
        return missing > 0L || producing > 0L || entriesDropped > 0;
    }

    /**
     * Whether an answer that accepted {@code acceptedMissing} items it will not get, {@code acceptedProducing} items
     * made for it and {@code acceptedDropped} entries left on the clipboard authorises this question. A question that
     * asks nothing is covered by every answer.
     * <p>
     * It is the {@link RequestAcknowledgement#covers} discipline for the list: the numbers the player saw are compared
     * with the numbers the server measures again when the Yes arrives, so a warehouse that lost stock in between is
     * asked a second time instead of quietly ordering more production than was agreed to.
     */
    public boolean covers(long acceptedMissing, long acceptedProducing, long acceptedDropped) {
        if (!required())
            return true;
        return acceptedMissing >= missing && acceptedProducing >= producing && acceptedDropped >= entriesDropped;
    }

    /**
     * The consent budget a Yes to this question creates ({@link ListOrder#budget()}): the list's production total, and
     * nothing else.
     * <p>
     * The other two costs — a reserve and a maximum — cannot be measured over a list without walking a plan per entry,
     * and they are exactly the ones a player must not be able to sign away blindly. They are asked about per portion
     * instead, against this budget, by the very panel a click raises ({@code §3.6.6}).
     */
    public RequestAcknowledgement budget() {
        return new RequestAcknowledgement(false, 0L, 0L, 0L, producing);
    }
}
