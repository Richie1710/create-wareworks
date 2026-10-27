package dev.wareworks.core.computer;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * One page of a list a computer reads (issue #9, ADR-074, {@code docs/computercraft.md}): Lua's 1-based {@code start}
 * and a {@code limit} of at most {@value #MAX_LIMIT} rows, so no single call hands a computer an unbounded table.
 * <p>
 * Built with {@link #of}, which refuses a {@code start} below 1 and a {@code limit} outside 1 to {@value #MAX_LIMIT}
 * with an {@link IllegalArgumentException} whose message is the one the computer is shown. A {@code start} past the end
 * is no error: the page is empty and there is no next one.
 *
 * @param from      first index of the page, 0-based and inclusive
 * @param to        end of the page, 0-based and exclusive
 * @param nextStart the 1-based {@code start} of the next page; empty when this page reaches the end
 */
public record ResultPage(int from, int to, OptionalInt nextStart) {
    /** Largest page a computer can ask for, and the page size when it names none. */
    public static final int MAX_LIMIT = 256;

    public ResultPage {
        Objects.requireNonNull(nextStart, "nextStart");
        if (from < 0 || to < from)
            throw new IllegalArgumentException("bad page bounds: " + from + ".." + to);
    }

    /**
     * The page of a list of {@code size} rows that starts at the 1-based {@code start} and holds at most {@code limit}.
     *
     * @throws IllegalArgumentException if {@code start < 1} or {@code limit} is not in 1..{@value #MAX_LIMIT}
     */
    public static ResultPage of(int start, int limit, int size) {
        if (start < 1)
            throw new IllegalArgumentException("start must be at least 1, got " + start);
        if (limit < 1 || limit > MAX_LIMIT)
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ", got " + limit);
        int total = Math.max(0, size);
        int from = (int) Math.min(total, start - 1L);
        int to = (int) Math.min(total, (long) from + limit);
        return new ResultPage(from, to, to < total ? OptionalInt.of(to + 1) : OptionalInt.empty());
    }

    /** The rows of this page out of {@code rows}, which must be the list the page was built for. */
    public <T> List<T> slice(List<T> rows) {
        Objects.requireNonNull(rows, "rows");
        return List.copyOf(rows.subList(Math.min(from, rows.size()), Math.min(to, rows.size())));
    }
}
