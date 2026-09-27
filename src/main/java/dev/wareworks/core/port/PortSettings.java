package dev.wareworks.core.port;

import java.util.Objects;

/**
 * The policy of one warehouse port ({@code docs/warehouse-system.md} §3.2, M17, issue #12): a signed <b>rank</b> and a
 * <b>redstone behaviour</b>. The filter slot the block already has stays what it is and is not part of this record.
 * <p>
 * <b>The row carries the sign, the column carries the magnitude.</b> Create's {@code ValueSettingsScreen} scans board
 * columns from 0, so no board can ever produce a negative number (ADR-028). The rank is therefore composed from the
 * board's row and column — {@code Overflow} → {@code −(v+1)}, {@code Diversion} → {@code +(v+1)}, {@code Request} → 0 —
 * and nothing is offset: the saved number <b>is</b> the signed number the block shows and the planner receives.
 * <ul>
 * <li>{@code rank == 0} is the {@link PortDirection#REQUEST} direction: the port asks for what its filter names, as
 * every warehouse output did before M17.</li>
 * <li>{@code rank < 0} is an <b>overflow</b>: a storage location always wins, so the port only ever receives what the
 * warehouse could not keep.</li>
 * <li>{@code rank > 0} is a <b>diversion</b>: the port takes incoming items <i>before</i> they are stored.</li>
 * </ul>
 * {@code v + 1} rather than {@code v} makes <b>every</b> accepting port's rank non-zero, so "0 means storage / not a
 * port" needs no extra flag, ranking is a plain comparison on one int, and the default strength 0 is already a usable
 * overflow ({@code −1}) instead of a neutral that would tie with storage. The magnitude keeps the storage priority's
 * range {@code 0..}{@value #MAX_STRENGTH} and its two milestones, so "priority" means one thing in the mod and a single
 * digit stays one glyph wide for the block renderer.
 * <p>
 * <b>Collecting is a sentinel, not a band</b> (M18, issue #13): {@link #COLLECT_RANK} is one value just above the accept
 * band, and it is the whole of the third direction. A collecting port has <b>no rank magnitude</b> — ordering against
 * other work is answered by where the arrival stage sits in dispatch, and ordering among several collecting ports is a
 * round robin, because a priority there would starve the weaker ports. Its board row therefore shows the same
 * {@link #NO_VALUE} dash the request row shows, {@link #strengthOf} answers 0 for it, and {@link #rowOf} maps it onto
 * {@link #COLLECT_ROW}. Row indices 0..2 are untouched, so no save, clipboard or schematic meaning changed and the
 * sentinel cannot appear in an old save: the pre-M18 range clamped to ±{@value #MAX_RANK}.
 * <p>
 * Pure data: the record clamps in its constructor, so a value read from a save, a clipboard or a packet is always in
 * range and a later, wider range never rewrites a player's number. The sentinel survives clamping unchanged, which is
 * what makes it a value of the range rather than an escape from it.
 *
 * @param rank     signed rank, {@value #MIN_RANK}..{@value #MAX_RANK}, or {@link #COLLECT_RANK}
 * @param redstone when the port acts
 */
public record PortSettings(int rank, PortRedstone redstone) {
    /** Highest magnitude a board column can name: one digit, like the storage priority. */
    public static final int MAX_STRENGTH = 9;
    /** Highest rank, i.e. the strongest diversion. */
    public static final int MAX_RANK = MAX_STRENGTH + 1;
    /** Lowest rank, i.e. the strongest overflow. */
    public static final int MIN_RANK = -MAX_RANK;
    /** The rank of a requesting port, and the rank storage itself sits at. */
    public static final int REQUEST_RANK = 0;
    /**
     * The rank of a <b>collecting</b> port (M18, issue #13): one sentinel just above the accept band, because a
     * collecting port is only ever a source and a source needs no magnitude.
     * <p>
     * A value the accept band can never produce ({@link #rankOf} composes at most {@value #MAX_RANK}) and that no save
     * from before M18 can hold (the old constructor clamped to ±{@value #MAX_RANK}), so it needs no migration and no
     * extra flag: {@link #direction()} stays a function of the one number.
     */
    public static final int COLLECT_RANK = MAX_RANK + 1;
    /** Magnitudes between two milestones of the hold-to-edit board (0, 3, 6, 9), as the storage priority uses. */
    public static final int STRENGTH_MILESTONE_INTERVAL = 3;

    /** Board row of the {@link PortDirection#REQUEST} direction. */
    public static final int REQUEST_ROW = 0;
    /** Board row of an accepting port that ranks <b>after</b> storage. */
    public static final int OVERFLOW_ROW = 1;
    /** Board row of an accepting port that ranks <b>before</b> storage. */
    public static final int DIVERSION_ROW = 2;
    /** Board row of the {@link PortDirection#COLLECT} direction, whose column means nothing (M18, issue #13). */
    public static final int COLLECT_ROW = 3;
    /** Number of rows of the rank board. */
    public static final int ROWS = 4;

    /** A warehouse output as it behaved before M17: it requests, and it acts on a rising edge. */
    public static final PortSettings DEFAULT = new PortSettings(REQUEST_RANK, PortRedstone.DEFAULT);

    /**
     * What a value box shows where a number would mean nothing: the magnitude of a requesting port, and the requested
     * amount of an accepting one. The same glyph in both places, so "this number is not a setting here" reads the same
     * way on both of the port's boards.
     */
    public static final String NO_VALUE = "—";

    private static final String ROW_LANG_PREFIX = "output.port.";
    private static final String[] ROW_LANG_KEYS = {"request", "overflow", "diversion", "collect"};

    public PortSettings {
        rank = clampRank(rank);
        redstone = Objects.requireNonNullElse(redstone, PortRedstone.DEFAULT);
    }

    /**
     * What the port does: {@link PortDirection#REQUEST} at rank 0, {@link PortDirection#COLLECT} at
     * {@link #COLLECT_RANK} and {@link PortDirection#ACCEPT} otherwise.
     */
    public PortDirection direction() {
        return directionOf(rank);
    }

    /** Whether the port takes incoming items before they are stored (never a collecting port). */
    public boolean isDiversion() {
        return rank > REQUEST_RANK && rank != COLLECT_RANK;
    }

    /** Whether the port only receives what no storage location took. */
    public boolean isOverflow() {
        return rank < REQUEST_RANK;
    }

    /** Whether the port asks for items instead of accepting or collecting them. */
    public boolean isRequesting() {
        return rank == REQUEST_RANK;
    }

    /** Whether the crane fetches items out of the inventory behind this port (M18, issue #13). */
    public boolean isCollecting() {
        return rank == COLLECT_RANK;
    }

    /**
     * The magnitude the board shows, {@code 0..}{@value #MAX_STRENGTH}; 0 for a requesting and for a collecting port,
     * neither of which ranks by a magnitude at all.
     */
    public int strength() {
        return strengthOf(rank);
    }

    /** The board row this rank belongs to. */
    public int row() {
        return rowOf(rank);
    }

    /**
     * Whether the port may act right now.
     *
     * @param powered the port's stored redstone signal
     * @param armed   whether a rising edge was seen and not used up yet ({@link PortRedstone#PULSE} only)
     */
    public boolean gateOpen(boolean powered, boolean armed) {
        return switch (redstone) {
            case PULSE -> armed;
            case WHILE_POWERED -> powered;
            case UNLESS_POWERED -> !powered;
        };
    }

    /**
     * What the rank {@code rank} means, as a static so callers that hold only the number — a block renderer, a block
     * state, a planner input — need no record instance ({@link #direction()}).
     */
    public static PortDirection directionOf(int rank) {
        if (rank == COLLECT_RANK)
            return PortDirection.COLLECT;
        return rank == REQUEST_RANK ? PortDirection.REQUEST : PortDirection.ACCEPT;
    }

    /**
     * The rank into the valid range: the accept band {@value #MIN_RANK}..{@value #MAX_RANK}, with
     * {@link #COLLECT_RANK} kept as itself (M18). Out-of-range numbers clamp into the band, so a tampered packet can
     * never become a collecting port by accident — only the sentinel itself is one.
     */
    public static int clampRank(int rank) {
        return rank == COLLECT_RANK ? COLLECT_RANK : Math.max(MIN_RANK, Math.min(MAX_RANK, rank));
    }

    /**
     * The rank a board row and column compose: the row carries the sign, the column the magnitude — except in the two
     * rows where a magnitude means nothing, the request row and the collect row.
     */
    public static int rankOf(int row, int strength) {
        int magnitude = Math.max(0, Math.min(MAX_STRENGTH, strength)) + 1;
        return switch (row) {
            case OVERFLOW_ROW -> -magnitude;
            case DIVERSION_ROW -> magnitude;
            case COLLECT_ROW -> COLLECT_RANK;
            default -> REQUEST_RANK;
        };
    }

    /** The magnitude a rank shows on its board row, {@code 0..}{@value #MAX_STRENGTH}; 0 for the sentinel. */
    public static int strengthOf(int rank) {
        if (rank == COLLECT_RANK)
            return 0;
        return Math.max(0, Math.min(MAX_STRENGTH, Math.abs(rank) - 1));
    }

    /** The board row a rank belongs to. */
    public static int rowOf(int rank) {
        if (rank == COLLECT_RANK)
            return COLLECT_ROW;
        if (rank == REQUEST_RANK)
            return REQUEST_ROW;
        return rank < REQUEST_RANK ? OVERFLOW_ROW : DIVERSION_ROW;
    }

    /** Relative lang key of a board row's label, e.g. {@code output.port.overflow}. */
    public static String rowLangKey(int row) {
        return ROW_LANG_PREFIX + ROW_LANG_KEYS[Math.max(0, Math.min(ROWS - 1, row))];
    }

    /**
     * The signed rank as a player reads it: {@code "0"} for a requesting port, otherwise with an explicit sign, and
     * {@link #NO_VALUE} for a <b>collecting</b> one (M18) — the sentinel is not a number a player set, so printing it
     * would put a "+11" on a block whose direction has no magnitude at all.
     */
    public static String formatRank(int rank) {
        if (rank == COLLECT_RANK)
            return NO_VALUE;
        return rank > 0 ? "+" + rank : String.valueOf(rank);
    }
}
