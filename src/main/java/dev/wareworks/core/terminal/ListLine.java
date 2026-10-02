package dev.wareworks.core.terminal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One line of a {@link ListOrder}: the warehouse's own record of what a clipboard entry asked for and what it has
 * really been given ({@code docs/warehouse-system.md} §3.4.4, M23, issue #19, ADR-036).
 * <p>
 * <b>The line is the truth, the tick mark is the receipt.</b> A tick mark on a clipboard cannot say "1300 of 2000", so
 * partial progress lives here and the clipboard is only ever written when a line is done
 * ({@link #isComplete()}). A clipboard pulled out of the terminal halfway through therefore reads truthfully: a ticked
 * entry was delivered in full, an unticked one was not.
 * <p>
 * <b>{@link #inFlight()} is what the open request still owes this line</b>, not what it was once granted. That one
 * decision removes a whole class of double counting:
 * <ul>
 * <li>a line is topped up only while it owes nothing ({@link #isInFlight()}), so one line never has two requests;</li>
 * <li>a delivery can never credit more than the portion that was really made for this line
 * ({@link #withCredited}), which is what keeps a <b>merged</b> request honest — repeated requests for one key and
 * destination become one request that keeps its id (ADR-020), so a player's own click and a list order's portion can
 * share an id, and each side only ever takes its own share;</li>
 * <li>once nothing is owed the id is forgotten, so the next pass makes a fresh portion for whatever the line still
 * wants. Nothing has to be reconciled for that to be right.</li>
 * </ul>
 * Immutable: progress creates a new record, exactly as a {@code RetrievalRequest} does.
 *
 * @param page      the clipboard page this line came from
 * @param index     the entry's position on that page. Together with {@link #page()} this is where the tick mark goes,
 *                  and what is validated again before it is written
 * @param key       the item to fetch, resolved from the entry's icon
 * @param wanted    how many of it the entry asked for, at least 1
 * @param delivered how many of them the crane has really put into the terminal's buffer, {@code 0..wanted}
 * @param inFlight  how many of the outstanding items an open request still owes this line, {@code 0..outstanding()};
 *                  0 exactly when no request is open for it
 * @param request   the retrieval request that owes them, empty exactly when {@link #inFlight()} is 0
 * @param <K>       item key type
 */
public record ListLine<K>(int page, int index, K key, int wanted, int delivered, int inFlight,
                          Optional<UUID> request) {
    public ListLine {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(request, "request");
        page = Math.max(0, page);
        index = Math.max(0, index);
        if (wanted < 1)
            throw new IllegalArgumentException("wanted must be at least 1: " + wanted);
        delivered = Math.max(0, Math.min(delivered, wanted));
        // Never more owed than is still missing, and an owed amount without a request — or a request owing nothing —
        // cannot exist: a line that is owed nothing is a line the next pass may top up. Hand-edited save data that
        // says otherwise reads back as a line with no open request, which is the state that cannot lose items.
        inFlight = Math.max(0, Math.min(inFlight, wanted - delivered));
        if (request.isEmpty() || inFlight == 0) {
            inFlight = 0;
            request = Optional.empty();
        }
    }

    /** A fresh line: nothing delivered, no request open. */
    public static <K> ListLine<K> of(int page, int index, K key, int wanted) {
        return new ListLine<>(page, index, key, wanted, 0, 0, Optional.empty());
    }

    /** Items the line is still missing, i.e. {@code wanted - delivered}. */
    public int outstanding() {
        return wanted - delivered;
    }

    /** Items the line is missing that no open request owes it, i.e. what a portion would be made for. */
    public int pending() {
        return outstanding() - inFlight;
    }

    /** Whether everything this line asked for has arrived — the moment the clipboard entry is ticked off. */
    public boolean isComplete() {
        return delivered >= wanted;
    }

    /** Whether a request is open for this line, which is what keeps the next pass from making a second one. */
    public boolean isInFlight() {
        return request.isPresent();
    }

    /** Whether {@code id} is the request that owes this line something. */
    public boolean owedBy(UUID id) {
        return id != null && request.isPresent() && request.get().equals(id);
    }

    /**
     * This line with a portion of {@code amount} items made for it by request {@code id}. The amount is bounded by
     * what the line is still missing, so a queue that granted more than was asked for cannot inflate a line.
     *
     * @throws IllegalStateException if a request is already open for this line
     */
    public ListLine<K> withPortion(UUID id, int amount) {
        Objects.requireNonNull(id, "id");
        if (isInFlight())
            throw new IllegalStateException("a line can only have one open request: " + request.get());
        return new ListLine<>(page, index, key, wanted, delivered, Math.min(Math.max(0, amount), outstanding()),
                Optional.of(id));
    }

    /**
     * This line with {@code amount} items credited to it: {@link #delivered()} grows and {@link #inFlight()} shrinks
     * by the same number, bounded by what the open request still owes. Crediting the last owed item forgets the
     * request, whether or not the line is complete — a request that delivered less than the line wants has had its
     * say, and the rest is a new portion.
     */
    public ListLine<K> withCredited(int amount) {
        int take = Math.min(Math.max(0, amount), inFlight);
        if (take == 0)
            return this;
        return new ListLine<>(page, index, key, wanted, delivered + take, inFlight - take,
                inFlight - take == 0 ? Optional.empty() : request);
    }

    /**
     * This line with no open request: what a line is left as when the request it was waiting for is gone — cancelled,
     * pruned, or lost with the save it was written in. Nothing is given back, because nothing was taken: the items it
     * still wants are {@link #outstanding()} either way, and the next pass makes a new portion for them.
     */
    public ListLine<K> released() {
        return isInFlight() ? new ListLine<>(page, index, key, wanted, delivered, 0, Optional.empty()) : this;
    }
}
