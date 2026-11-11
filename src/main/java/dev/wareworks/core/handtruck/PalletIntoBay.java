package dev.wareworks.core.handtruck;

/**
 * Whether a pallet carried on a hand truck may go into a rack bay in one go (ADR-084): the whole load or nothing, and
 * only into an <b>empty</b> bay. Pure rules, so the order of the refusals is pinned by a unit test; the world facts
 * are read by the caller ({@code content.handtruck.HandTruckPalletUnload}).
 */
public final class PalletIntoBay {
    /** The answer, the first refusal in declaration order winning. */
    public enum Verdict {
        /** The whole load goes in. */
        FITS,
        /** The bay already holds something: a pallet only goes into an empty bay. */
        NOT_EMPTY,
        /** A crane is on its way to this bay with goods; filling it now would leave the crane nowhere to put them. */
        CRANE_BRINGING,
        /** The bay's store filter does not take the carried item. */
        FILTERED,
        /** The bay holds fewer of the carried item than the pallet carries. */
        TOO_SMALL
    }

    private PalletIntoBay() {
    }

    /**
     * @param empty         the bay holds nothing
     * @param craneBringing a crane job is bringing goods to the bay
     * @param filterAllows  the bay's store filter lets the carried item in (true without a filter)
     * @param capacity      how many of the carried item the bay holds when empty
     * @param count         how many the pallet carries
     */
    public static Verdict verdict(boolean empty, boolean craneBringing, boolean filterAllows, long capacity,
            int count) {
        if (!empty)
            return Verdict.NOT_EMPTY;
        if (craneBringing)
            return Verdict.CRANE_BRINGING;
        if (!filterAllows)
            return Verdict.FILTERED;
        if (capacity < count)
            return Verdict.TOO_SMALL;
        return Verdict.FITS;
    }
}
