package dev.wareworks.core.storage;

/**
 * What kind of goods a bay holds, and therefore <b>which ladder its tier belongs to</b> (M30, issue #21).
 * <p>
 * It exists because there are two tier ladders that are each ordered and are <b>not comparable with each other</b>:
 * {@link BayTier} for item bays and {@link FluidBayTier} for fluid bays. "Is a copper tank stronger than an andesite
 * rack" is a question the column rule would otherwise have to answer, and nobody can — so the family is compared
 * <b>before</b> any strength, and a bay of the other family ends a column exactly as air does.
 * <p>
 * That is the whole of the concept. It is deliberately not "which block" and not "which material": two bays of one
 * family are bays whose strengths may be compared, and two bays of different families are two racks that happen to
 * stand on top of each other.
 *
 * <h2>What it does not decide</h2>
 * <b>Joining is across families</b> and does not ask this question at all ({@code content.storage.TieredBay#LEFT}):
 * the visual rule asks only for the same facing and deliberately not for the same tier — a wall is a wall — so a fluid
 * bay shares an upright with a rack bay, and the seam post is half rack and half tank, which is what it actually is.
 * <b>Carrying is within a family, joining is across them</b>, and those two sentences are the reason this enum has
 * exactly one job.
 *
 * <h2>Where it is answered</h2>
 * Each tier ladder answers for itself ({@link BayTier#family()}, {@link FluidBayTier#family()}) rather than each block
 * answering for its own tier, so the fact "this ladder is the item ladder" is stated once, in the pure layer, beside
 * the ladder it is about, and a block can only get it wrong by naming the wrong tier — which it cannot, because its
 * tier is what decides its capacity too.
 */
public enum BayFamily {
    /** Item bays: a rack bay, holding one item key as a count of items ({@link BayTier}). */
    ITEM,
    /** Fluid bays: a tank that is a storage location, holding one fluid as a count of millibuckets ({@link FluidBayTier}). */
    FLUID
}
