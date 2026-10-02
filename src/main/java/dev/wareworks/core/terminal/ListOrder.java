package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

/**
 * One list order: a clipboard in a warehouse terminal's list slot, worked off in portions
 * ({@code docs/warehouse-system.md} §3.4.4, M23, issue #19, ADR-036).
 * <p>
 * <b>What a list order is.</b> A stream of <b>ordinary</b> requests with one owner. The order resolves the clipboard
 * into {@link ListLine}s once, then keeps a small number of them in flight at a time, each through precisely the call a
 * click at the terminal goes through. There is no second request system beside the queue: merging (ADR-020), reserves,
 * maxima, filters, priorities, production chains, the safety stop and the full-destination back-off all keep working
 * unchanged, and nothing downstream can tell a portion from a click except by its {@link RequestScope}.
 * <p>
 * <b>What it decides, and this class decides all of it:</b>
 * <ul>
 * <li><b>which entries are an order at all</b> — {@link ListEntry#orderable()}: no icon is a page separator, an
 * already ticked entry has been dealt with, an amount below 1 asks for nothing. A Schematicannon's checklist contains
 * all three;</li>
 * <li><b>which line goes next</b> — {@link #nextPortion}: list order, depth first, one open request per line, at most
 * {@code terminalListOpenRequests} of them at a time. A line the warehouse refuses costs one call and is stepped over
 * for the rest of the pass, so one unobtainable item never blocks the rest of the list — and because a refusal is not
 * free to measure, a pass offers a bounded number of portions and the next one carries on where it stopped
 * ({@link #ATTEMPTS_PER_OPEN_REQUEST});</li>
 * <li><b>how big a portion is</b> — what the line still needs, bounded by {@code maxTerminalRequestAmount}
 * ({@link ListPortion});</li>
 * <li><b>what "nothing happened" means</b> — {@link ListPass}: items on the way ({@link ListPass#WAITING}, which is
 * also what a full terminal buffer looks like) is a different thing from nothing servable at all
 * ({@link ListPass#STARVED}), and only the second one ever leads to {@link ListOrderState#PARKED};</li>
 * <li><b>how a delivery maps back onto the clipboard</b> — {@link #credit}: items are credited to the lines that
 * really asked for them, in list order, bounded by what each line was owed, and a line that reaches its amount is the
 * instruction to tick that entry off ({@link ListCredit});</li>
 * <li><b>what one Yes pays for</b> — {@link #budgetFor}: the production total the Fetch dialog asked about is the
 * list's own and is spent fungibly, while a reserve, a maximum and an ingredient's reserve are consent about the
 * <b>item</b> they were named for and can pay for nothing else. A Yes about Iron never pays for Copper's reserve two
 * portions later, and the portion a question was raised for is the first one offered again when the Yes arrives.</li>
 * </ul>
 * <b>One pass is four calls</b>, and a caller that makes them in this order needs no state of its own:
 * <ol>
 * <li>{@link #beginPass} — forget every request the controller no longer has, and clear the last pass's refusals;</li>
 * <li>{@link #nextPortion} until it is empty, each time followed by {@link #granted} or {@link #refused};</li>
 * <li>{@link #finishPass} — the answer, the next pass's tick, and the park decision.</li>
 * </ol>
 * <b>The crane can never spin without progress.</b> Five guards, and four of them are older than this class: a full
 * destination is a planner skip with a back-off rather than a retry; a line with an open request is never asked for
 * twice; the pass runs on an interval and backs off by {@value #BACKOFF_FACTOR} after
 * {@value #FRUITLESS_PASSES_BEFORE_BACKOFF} fruitless passes; and an order with nothing in flight that has made no
 * progress for {@code terminalListStallTicks} stops measuring altogether until a player resumes it.
 * <p>
 * Pure Java with no Minecraft types, not thread-safe (server thread only). The terminal persists
 * {@link #lines()}, {@link #dropped()}, {@link #state()}, {@link #budget()}, {@link #nextPassTick()} and
 * {@link #lastProgressTick()} and restores the order with {@link #restore}. The per-portion question and the per-item
 * consent it was answered with are deliberately <b>not</b> saved: both are measured against the warehouse as it is now,
 * so a restored order comes back {@link ListOrderState#RUNNING} and asks again.
 *
 * @param <K> item key type
 */
public final class ListOrder<K> {
    /** Smallest entry cap a list order can be resolved with ({@code maxTerminalListEntries}). */
    public static final int MIN_ENTRIES = 1;
    /** Largest entry cap, i.e. the hard bound on what one clipboard can order and on what the save carries. */
    public static final int MAX_ENTRIES = 1024;
    /** Smallest number of requests one list order may keep open ({@code terminalListOpenRequests}). */
    public static final int MIN_OPEN_REQUESTS = 1;
    /** Fruitless passes after which the interval is stretched, so a hopeless list stops costing measurements. */
    public static final int FRUITLESS_PASSES_BEFORE_BACKOFF = 10;
    /** By how much it is stretched then. */
    public static final int BACKOFF_FACTOR = 5;
    /**
     * How many portions one pass offers per request slot it may fill, i.e. how many lines it is allowed to be refused
     * for before it gives the rest of the list its turn in the next pass.
     * <p>
     * A bound is needed because a refusal is not free — the request path measures stock and walks the aisle's
     * production patterns for every portion it refuses — and a list of 128 entries the warehouse can serve none of
     * would otherwise measure all 128 of them every {@code terminalListIntervalTicks}. A <b>cursor</b> is what keeps
     * that bound fair: the next pass carries on where this one stopped and wraps around the list, so a line is never
     * starved by the lines before it ({@code §3.4.4}, "one unobtainable item never blocks the rest of the list") while
     * a pass that has nothing to do still costs a handful of lookups and not a whole clipboard.
     */
    public static final int ATTEMPTS_PER_OPEN_REQUEST = 8;

    private final List<ListLine<K>> lines;
    private final int dropped;
    /** The lines a portion was refused for in the current pass, so the pass steps over them exactly once. */
    private final BitSet refused = new BitSet();
    private ListOrderState state;
    private RequestAcknowledgement budget;
    /**
     * What the player has agreed to for <b>one item</b>, from the questions single portions raised (M23 review fix).
     * <p>
     * A reserve, a maximum and an ingredient's reserve are consent about the item they were named for, so they are kept
     * per key and can pay for nothing else: a Yes to "this takes 10 of the 64 reserved Iron Ingots" must not quietly
     * pay for a portion of Copper later in the same list ({@link RequestAcknowledgement}, "an answer only ever
     * authorises the question it was given"). The list-wide {@link #budget} carries the one number that is fungible on
     * purpose — the production total the Fetch dialog asked about.
     * <p>
     * Deliberately <b>never saved</b>, exactly like {@link #question}: it is consent measured against the warehouse as
     * it was, and a restored order comes back {@link ListOrderState#RUNNING} and asks again.
     */
    private final Map<K, RequestAcknowledgement> answered = new HashMap<>();
    /**
     * The question the last portion raised, kept only while the order is {@link ListOrderState#ASKING} and
     * deliberately never saved: a confirmation is measured against the warehouse as it is now, which is the whole
     * reason it carries numbers instead of a flag ({@link RequestAcknowledgement}).
     */
    private @Nullable RequestConfirmation<K> question;
    /** The item {@link #question} is about, so an answer credits the key it was given for. */
    private @Nullable K askedKey;
    private long nextPassTick;
    private long lastProgressTick;
    private int fruitlessPasses;
    private boolean grantedThisPass;
    /** The line the next pass starts looking at, so a bounded pass still gives every line its turn. */
    private int cursor;
    /**
     * The line {@link #nextPortion} last handed out and that was neither granted nor refused, i.e. the line a question
     * is about while one is up; {@code -1} otherwise.
     * <p>
     * It is what lets {@link #answer} offer <b>that</b> portion again rather than handing the fresh consent to whatever
     * line the wrapping cursor happens to point at next (M23 review fix).
     */
    private int offeredLine = -1;
    /** Portions offered in the current pass, against {@link #ATTEMPTS_PER_OPEN_REQUEST}. */
    private int attempts;

    private ListOrder(List<ListLine<K>> lines, int dropped, ListOrderState state, RequestAcknowledgement budget,
            long nextPassTick, long lastProgressTick) {
        this.lines = lines;
        this.dropped = Math.max(0, dropped);
        this.budget = budget == null ? RequestAcknowledgement.NONE : budget;
        this.nextPassTick = nextPassTick;
        this.lastProgressTick = lastProgressTick;
        // An order with nothing left to fetch is finished, whatever a save or a caller says: a clipboard whose every
        // entry is a separator, is already ticked or asks for nothing is a list that was worked off before it started.
        this.state = state == null || isComplete() ? ListOrderState.DONE : state;
    }

    /**
     * The order a clipboard describes: its entries resolved into lines, in clipboard order.
     * <p>
     * Entries that are not an order are skipped ({@link ListEntry#orderable()}) and everything past {@code maxEntries}
     * orderable ones is left alone and counted in {@link #dropped()} — the untaken tail of a very long clipboard stays
     * unticked, which reads correctly, and the screen says how many entries were taken.
     *
     * @param entries    the clipboard's entries, in page and index order
     * @param maxEntries {@code maxTerminalListEntries}, clamped to {@value #MIN_ENTRIES}..{@value #MAX_ENTRIES}
     * @param budget     what the player accepted when they pressed Fetch, spent down as portions are made
     *                   ({@link #budget()})
     * @param now        the game time the order starts at: both its first pass and its stall timer start here
     */
    public static <K> ListOrder<K> of(List<ListEntry<K>> entries, int maxEntries, RequestAcknowledgement budget,
            long now) {
        Objects.requireNonNull(entries, "entries");
        int cap = Math.max(MIN_ENTRIES, Math.min(maxEntries, MAX_ENTRIES));
        List<ListLine<K>> resolved = new ArrayList<>(Math.min(entries.size(), cap));
        int dropped = 0;
        for (ListEntry<K> entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (!entry.orderable())
                continue;
            if (resolved.size() >= cap) {
                dropped++;
                continue;
            }
            resolved.add(ListLine.of(entry.page(), entry.index(), entry.key().orElseThrow(), entry.amount()));
        }
        return new ListOrder<>(resolved, dropped, ListOrderState.RUNNING, budget, now, now);
    }

    /**
     * The order a terminal saved, read back as it was.
     * <p>
     * Nothing has to be loaded in any particular order for this to be right. A line's request id either still exists
     * in the controller's own saved queue or is forgotten by the next {@link #beginPass} — and a forgotten request
     * gives nothing back, because what a line is still missing is {@link ListLine#outstanding()} either way.
     * <p>
     * {@link ListOrderState#ASKING} comes back as {@link ListOrderState#RUNNING} on purpose: the question it was
     * waiting for is measured against the warehouse as it is <b>now</b> and was therefore never saved, so the order
     * offers that portion again and asks again if it still costs more than the budget covers.
     * <p>
     * More than {@value #MAX_ENTRIES} lines cannot come out of a clipboard and are not read back from a save either:
     * the tail is counted in {@link #dropped()} like any other untaken entry, so no save can make one order walk an
     * unbounded list.
     */
    public static <K> ListOrder<K> restore(List<ListLine<K>> lines, int dropped, ListOrderState state,
            RequestAcknowledgement budget, long nextPassTick, long lastProgressTick) {
        Objects.requireNonNull(lines, "lines");
        List<ListLine<K>> restored = new ArrayList<>(Math.min(lines.size(), MAX_ENTRIES));
        int beyond = 0;
        for (ListLine<K> line : lines) {
            Objects.requireNonNull(line, "line");
            if (restored.size() < MAX_ENTRIES)
                restored.add(line);
            else
                beyond++;
        }
        dropped = Math.max(0, dropped) + beyond;
        ListOrderState resolved = state == ListOrderState.ASKING ? ListOrderState.RUNNING : state;
        return new ListOrder<>(restored, dropped, resolved, budget, nextPassTick, lastProgressTick);
    }

    // --- reading -----------------------------------------------------------------------------------------------------

    public ListOrderState state() {
        return state;
    }

    /** The order's lines, in clipboard order. Never changes length: a delivered line stays, as the receipt. */
    public List<ListLine<K>> lines() {
        return Collections.unmodifiableList(lines);
    }

    /** Orderable entries the entry cap left on the clipboard. They are untouched and unticked. */
    public int dropped() {
        return dropped;
    }

    /** Whether the clipboard held more than this order took. */
    public boolean truncated() {
        return dropped > 0;
    }

    /** Lines this order works off, i.e. entries it took from the clipboard. */
    public int entries() {
        return lines.size();
    }

    /** Lines that are delivered in full, i.e. entries that are ticked off on the clipboard. */
    public int entriesComplete() {
        int complete = 0;
        for (ListLine<K> line : lines) {
            if (line.isComplete())
                complete++;
        }
        return complete;
    }

    /** The lines that are delivered in full, in list order: every tick mark the clipboard should carry. */
    public List<ListLine<K>> completedLines() {
        List<ListLine<K>> complete = new ArrayList<>();
        for (ListLine<K> line : lines) {
            if (line.isComplete())
                complete.add(line);
        }
        return complete;
    }

    /** Items the whole list asks for. */
    public long wanted() {
        long total = 0L;
        for (ListLine<K> line : lines)
            total += line.wanted();
        return total;
    }

    /** Items the crane has really delivered for this order. */
    public long delivered() {
        long total = 0L;
        for (ListLine<K> line : lines)
            total += line.delivered();
        return total;
    }

    /** Items the list is still missing — what the status line counts down. */
    public long outstanding() {
        long total = 0L;
        for (ListLine<K> line : lines)
            total += line.outstanding();
        return total;
    }

    /** Items open requests still owe this order. */
    public long inFlight() {
        long total = 0L;
        for (ListLine<K> line : lines)
            total += line.inFlight();
        return total;
    }

    /**
     * How many retrieval requests this order has open. Requests are counted, not lines: two lines asking for the same
     * item merge into one request that keeps its id (ADR-020), and it is the queue's requests that
     * {@code terminalListOpenRequests} is about.
     */
    public int openRequests() {
        Set<UUID> open = null;
        for (ListLine<K> line : lines) {
            if (!line.isInFlight())
                continue;
            if (open == null)
                open = new LinkedHashSet<>();
            open.add(line.request().orElseThrow());
        }
        return open == null ? 0 : open.size();
    }

    /**
     * The line the order is working on, for the terminal's status line: the first one with items on the way, and
     * otherwise the first one still waiting for a portion. Empty for a finished order.
     */
    public Optional<ListLine<K>> current() {
        ListLine<K> pending = null;
        for (ListLine<K> line : lines) {
            if (line.isInFlight())
                return Optional.of(line);
            if (pending == null && !line.isComplete())
                pending = line;
        }
        return Optional.ofNullable(pending);
    }

    /** Whether every line has been delivered in full. */
    public boolean isComplete() {
        for (ListLine<K> line : lines) {
            if (!line.isComplete())
                return false;
        }
        return true;
    }

    /**
     * The <b>list-wide</b> consent: what the player accepted when they pressed Fetch, minus what the portions made so
     * far have spent of it ({@link #spend}). It is the production total and nothing else
     * ({@link ListOrderConfirmation#budget()}), which is the one cost a list can be asked about as a whole, and it is
     * what a terminal saves.
     * <p>
     * A portion is measured against {@link #budgetFor} rather than against this, because the three costs a single
     * portion can raise are consent about one item and are kept per key.
     */
    public RequestAcknowledgement budget() {
        return budget;
    }

    /**
     * What a portion of {@code key} may spend: the list-wide production budget plus whatever the player has agreed to
     * for <b>this item</b> in answer to an earlier portion's question (M23 review fix).
     */
    public RequestAcknowledgement budgetFor(@Nullable K key) {
        RequestAcknowledgement forKey = key == null ? null : answered.get(key);
        if (forKey == null)
            return budget;
        if (forKey.any())
            return RequestAcknowledgement.ANY;
        return budget.plus(forKey);
    }

    /** The question a portion raised, while the order is {@link ListOrderState#ASKING}. */
    public Optional<RequestConfirmation<K>> question() {
        return Optional.ofNullable(question);
    }

    /** The game time the next top-up pass is due at. */
    public long nextPassTick() {
        return nextPassTick;
    }

    /** The game time of the last thing that counted as progress: a portion granted, or items delivered. */
    public long lastProgressTick() {
        return lastProgressTick;
    }

    /** Whether a top-up pass is due. Only a {@link ListOrderState#RUNNING} order ever is. */
    public boolean due(long now) {
        return state.isRunning() && now - nextPassTick >= 0L;
    }

    /**
     * Whether {@code question} is covered by what the player has already accepted for this order and for the item it
     * is about ({@link #budgetFor}).
     */
    public boolean covers(RequestConfirmation<K> question) {
        return budgetFor(question == null ? null : question.key()).covers(question, RequestScope.LIST);
    }

    // --- one pass ----------------------------------------------------------------------------------------------------

    /**
     * Starts a top-up pass: forgets every request the controller no longer has, and clears the refusals of the
     * previous pass.
     * <p>
     * A forgotten request gives nothing back and needs no arithmetic — what a line is still missing is
     * {@link ListLine#outstanding()}, and the next portion is made for it as if the request had never existed. That is
     * what makes a cancelled request, a pruned queue and a reload all the same, harmless event.
     *
     * @param alive whether the controller still has the request with that id; asked once per distinct id
     * @return how many lines lost their request
     */
    public int beginPass(Predicate<UUID> alive) {
        Objects.requireNonNull(alive, "alive");
        refused.clear();
        grantedThisPass = false;
        attempts = 0;
        offeredLine = -1;
        Map<UUID, Boolean> answers = null;
        int released = 0;
        for (int i = 0; i < lines.size(); i++) {
            ListLine<K> line = lines.get(i);
            if (!line.isInFlight())
                continue;
            if (answers == null)
                answers = new HashMap<>();
            if (answers.computeIfAbsent(line.request().orElseThrow(), alive::test))
                continue;
            lines.set(i, line.released());
            released++;
        }
        return released;
    }

    /**
     * The next portion to request, or empty when there is nothing to ask for right now.
     * <p>
     * Depth first in list order: the first line that is neither finished, nor already waiting for a request, nor
     * refused earlier in this pass. The search starts where the previous pass stopped and wraps around the list once,
     * so a pass that may only offer {@link #ATTEMPTS_PER_OPEN_REQUEST} portions per request slot still works through a
     * long clipboard instead of measuring its first entries over and over.
     * <p>
     * Empty means one of four things, and {@link #finishPass} is what tells them apart — the order is finished,
     * everything left is on its way, the open-request limit is reached, or nothing more could be served in this pass.
     *
     * @param openLimit     {@code terminalListOpenRequests}, at least {@value #MIN_OPEN_REQUESTS}
     * @param maxPerRequest {@code maxTerminalRequestAmount}, the largest amount one request may ask for
     */
    public Optional<ListPortion<K>> nextPortion(int openLimit, int maxPerRequest) {
        if (!state.isRunning() || lines.isEmpty())
            return Optional.empty();
        int limit = Math.max(MIN_OPEN_REQUESTS, openLimit);
        if (openRequests() >= limit || attempts >= attemptsPerPass(limit))
            return Optional.empty();
        int perRequest = Math.max(1, maxPerRequest);
        for (int step = 0; step < lines.size(); step++) {
            int at = (cursor + step) % lines.size();
            if (refused.get(at))
                continue;
            ListLine<K> line = lines.get(at);
            if (line.isComplete() || line.isInFlight())
                continue;
            cursor = (at + 1) % lines.size();
            offeredLine = at;
            attempts++;
            return Optional.of(new ListPortion<>(at, line.key(), Math.min(line.outstanding(), perRequest)));
        }
        return Optional.empty();
    }

    /** How many portions one pass may offer: a few per request slot, and never more than the list is long. */
    private int attemptsPerPass(int openLimit) {
        long budget = (long) openLimit * ATTEMPTS_PER_OPEN_REQUEST;
        return (int) Math.min(Math.max(openLimit, budget), lines.size());
    }

    /**
     * Records that {@code portion} became request {@code requestId}, which owes the line {@code amount} items. The
     * amount is what the queue really granted, never more than the line is missing.
     * <p>
     * A grant counts as progress: it is a crane trip that is going to happen, so the stall timer starts over even
     * though no item has arrived yet.
     *
     * @return whether a portion was recorded; false for a grant of nothing, which is treated as a refusal
     * @throws IllegalStateException if the line already has an open request
     */
    public boolean granted(ListPortion<K> portion, UUID requestId, int amount, long now) {
        ListLine<K> line = lineOf(portion);
        Objects.requireNonNull(requestId, "requestId");
        if (amount < 1 || line.isComplete()) {
            refused(portion);
            return false;
        }
        offeredLine = -1;
        lines.set(portion.line(), line.withPortion(requestId, amount));
        grantedThisPass = true;
        fruitlessPasses = 0;
        lastProgressTick = now;
        return true;
    }

    /**
     * Records that nothing could be requested for {@code portion}. The line keeps everything it wants and is stepped
     * over for the rest of this pass, so the pass moves on to the next line instead of asking the same hopeless
     * question again — one item the warehouse cannot serve never blocks the rest of the list.
     */
    public void refused(ListPortion<K> portion) {
        lineOf(portion);
        offeredLine = -1;
        refused.set(portion.line());
    }

    /**
     * Ends the pass: what it found, when the next one is due, and whether the order has to park.
     * <p>
     * The interval is stretched by {@value #BACKOFF_FACTOR} once {@value #FRUITLESS_PASSES_BEFORE_BACKOFF} passes in a
     * row have granted nothing, and an order that has nothing in flight and has made no progress for
     * {@code stallTicks} stops measuring until a player resumes it ({@link #resume}). Parking needs both: items that
     * are on their way are progress waiting to happen, and a full destination is exactly that.
     *
     * @param intervalTicks {@code terminalListIntervalTicks}, at least 1
     * @param stallTicks    {@code terminalListStallTicks}; 0 never parks
     */
    public ListPass finishPass(long now, long intervalTicks, long stallTicks) {
        refused.clear();
        attempts = 0;
        boolean granted = grantedThisPass;
        grantedThisPass = false;
        if (isComplete()) {
            finish();
            return ListPass.COMPLETE;
        }
        long interval = Math.max(1L, intervalTicks);
        if (granted) {
            fruitlessPasses = 0;
            lastProgressTick = now;
            nextPassTick = now + interval;
            return ListPass.OFFERED;
        }
        fruitlessPasses = Math.min(fruitlessPasses + 1, FRUITLESS_PASSES_BEFORE_BACKOFF);
        nextPassTick = now + (fruitlessPasses >= FRUITLESS_PASSES_BEFORE_BACKOFF ? interval * BACKOFF_FACTOR
                : interval);
        if (openRequests() > 0)
            return ListPass.WAITING;
        if (state.isRunning() && stallTicks > 0L && now - lastProgressTick >= stallTicks)
            state = ListOrderState.PARKED;
        return ListPass.STARVED;
    }

    // --- deliveries --------------------------------------------------------------------------------------------------

    /**
     * Credits a delivery of {@code amount} items of request {@code requestId} to this order.
     * <p>
     * The items go to the lines that really asked for them: in list order, each bounded by what that line was owed
     * ({@link ListLine#inFlight()}), spilling to the next line with the same request id when one line's share is
     * full — two entries wanting the same item share one merged request, and each takes exactly its own portion. A
     * delivery nothing was owed for is claimed as nothing at all ({@link ListCredit#none()}), which is also what makes
     * crediting the same delivery twice harmless.
     * <p>
     * A delivery makes the order due again: it is the event that frees a buffer slot and a request slot, so the next
     * pass measures at once rather than after the interval. That is the "as soon as space frees, it continues with the
     * next stack" of issue #19.
     *
     * @return what was claimed, which lines are now ready to be ticked off, and whether this delivery finished the
     *         list
     */
    public ListCredit<K> credit(UUID requestId, int amount, long now) {
        if (requestId == null || amount < 1 || lines.isEmpty())
            return ListCredit.none();
        int left = amount;
        int absorbed = 0;
        List<ListLine<K>> completed = null;
        for (int i = 0; i < lines.size() && left > 0; i++) {
            ListLine<K> line = lines.get(i);
            if (!line.owedBy(requestId))
                continue;
            int take = Math.min(left, line.inFlight());
            if (take < 1)
                continue;
            ListLine<K> credited = line.withCredited(take);
            lines.set(i, credited);
            left -= take;
            absorbed += take;
            if (credited.isComplete()) {
                if (completed == null)
                    completed = new ArrayList<>();
                completed.add(credited);
            }
        }
        if (absorbed == 0)
            return ListCredit.none();
        fruitlessPasses = 0;
        lastProgressTick = now;
        nextPassTick = now;
        return new ListCredit<>(absorbed, completed == null ? List.of() : completed, finish());
    }

    // --- consent -----------------------------------------------------------------------------------------------------

    /**
     * Stops the order to ask about {@code question}: the portion it was measured for was <b>not</b> made, and the next
     * player to open the screen sees the ordinary confirmation panel for that one item ({@code §3.6.6}). Nothing is
     * promised in between.
     * <p>
     * Only a running order can ask, because only a running order measures anything.
     */
    public void ask(RequestConfirmation<K> question) {
        Objects.requireNonNull(question, "question");
        if (!state.isRunning())
            return;
        this.question = question;
        this.askedKey = question.key();
        this.state = ListOrderState.ASKING;
    }

    /**
     * The player's answer to {@link #question()}: it tops the consent up — it never replaces it — and the order runs
     * again from this tick, starting with the very portion the question was about ({@link #offeredLine}).
     * <p>
     * <b>Where the numbers go is the point</b> (M23 review fix). <b>Every</b> number of a portion's answer is consent
     * about the item that portion's question named, so the whole answer is kept for <b>that item only</b>
     * ({@link #budgetFor}): a Yes about Iron can never pay for Copper's reserve — or for Copper's production — two
     * portions later. The only consent a list spends fungibly is the one the Fetch dialog really asked about as a
     * whole, the list's production total, and that one stays in {@link #budget}. An answer given while nothing is being
     * asked about has no item to credit and tops up that total alone.
     */
    public void answer(RequestAcknowledgement more, long now) {
        if (more != null && more.given()) {
            K key = askedKey;
            if (key == null)
                budget = budget.plus(produceOnly(more));
            else if (more.any())
                // "Whatever it costs" — for this item. One portion's answer may not sign the whole list away.
                answered.put(key, RequestAcknowledgement.ANY);
            else
                answered.merge(key, more, RequestAcknowledgement::plus);
        }
        resume(now);
    }

    /**
     * The player's <b>no</b> to {@link #question()}: nothing is accepted, the question is dropped and the order parks
     * ({@link ListOrderState#PARKED}) instead of standing in {@link ListOrderState#ASKING} with nobody to answer it
     * (M23 review fix).
     * <p>
     * Parking rather than cancelling is what keeps the no reversible: the list button becomes <b>Resume</b>, the lines
     * and every tick mark stay as they are, and a player who changes their mind gets the same question again. The
     * cursor is deliberately <b>not</b> rewound, so a resumed order offers the rest of the list before it comes back to
     * the portion that was refused.
     *
     * @return whether there was a question to say no to
     */
    public boolean decline(long now) {
        if (state != ListOrderState.ASKING)
            return false;
        question = null;
        askedKey = null;
        offeredLine = -1;
        state = ListOrderState.PARKED;
        fruitlessPasses = 0;
        lastProgressTick = now;
        nextPassTick = now;
        return true;
    }

    /**
     * Takes what {@code cost} really cost out of the consent, so one Yes cannot pay for portion after portion
     * ({@link RequestAcknowledgement#minus}). A budget of "whatever it costs" spends nothing, and a question that
     * crosses nothing costs nothing.
     * <p>
     * It is spent on <b>both</b> sides, because both really were spent: the production a portion starts draws the
     * list's own total down, and it draws down what the player accepted for that item. Nothing can be spent twice by
     * that — the three boundary numbers exist only in the per-item consent and the list-wide total holds only
     * production — and where the two overlap the order simply asks a little sooner, which is the safe direction.
     */
    public void spend(RequestConfirmation<K> cost) {
        if (cost == null)
            return;
        budget = budget.minus(cost);
        RequestAcknowledgement forKey = answered.get(cost.key());
        if (forKey == null)
            return;
        RequestAcknowledgement left = forKey.minus(cost);
        if (left.given())
            answered.put(cost.key(), left);
        else
            answered.remove(cost.key());
    }

    /** The production total of {@code answer} and nothing else: the one number a whole list spends fungibly. */
    private static RequestAcknowledgement produceOnly(RequestAcknowledgement answer) {
        return answer.any() ? RequestAcknowledgement.ANY
                : new RequestAcknowledgement(false, 0L, 0L, 0L, answer.produced());
    }

    // --- ending ------------------------------------------------------------------------------------------------------

    /**
     * Runs the order again from this tick: the answer to a question, and the "click to try again" of a parked order.
     * The stall timer and the interval back-off start over; the lines and the budget are untouched.
     * <p>
     * A portion that was offered and then asked about is offered <b>first</b>, because the consent that has just
     * arrived was given for that portion and for nothing else (M23 review fix). A parked order has no such portion and
     * simply carries on where it stopped.
     */
    public void resume(long now) {
        question = null;
        askedKey = null;
        if (offeredLine >= 0 && offeredLine < lines.size())
            cursor = offeredLine;
        offeredLine = -1;
        if (!state.isOpen())
            return;
        state = ListOrderState.RUNNING;
        fruitlessPasses = 0;
        lastProgressTick = now;
        nextPassTick = now;
    }

    /**
     * Forgets every open request and names them, so the caller can cancel them: how a list order is given up — the
     * Cancel button, the clipboard taken out of the slot, the terminal broken.
     * <p>
     * The lines keep what they were asked for, so an order that is released and then resumed starts the outstanding
     * portions again rather than losing them. A production order one of those requests started keeps running and its
     * result lands in stock, exactly as for any other cancelled request ({@code §3.5}).
     */
    public Set<UUID> release() {
        Set<UUID> open = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            ListLine<K> line = lines.get(i);
            if (!line.isInFlight())
                continue;
            open.add(line.request().orElseThrow());
            lines.set(i, line.released());
        }
        return open;
    }

    /** Moves a complete order to {@link ListOrderState#DONE}; true only for the one call that really does it. */
    private boolean finish() {
        if (state == ListOrderState.DONE || !isComplete())
            return false;
        question = null;
        askedKey = null;
        offeredLine = -1;
        state = ListOrderState.DONE;
        return true;
    }

    private ListLine<K> lineOf(ListPortion<K> portion) {
        Objects.requireNonNull(portion, "portion");
        if (portion.line() >= lines.size())
            throw new IllegalArgumentException("no such line: " + portion.line() + " of " + lines.size());
        return lines.get(portion.line());
    }
}
