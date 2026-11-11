package dev.wareworks.core.storage;

/**
 * One step up a bay's tier ladder, as an upgrade kit makes it where the bay stands (ADR-083).
 * <p>
 * There is <b>one kit per step</b> and no other: a kit never skips a tier (wood to brass takes two kits) and never goes
 * down, so a kit fits exactly one tier of exactly one family — the bay it upgrades — and {@link #fits} is the whole of
 * that rule. Each step is built from the ladders' own constants rather than from numbers, and the JUnit test walks
 * both ladders, so a tier appended to either one without a kit of its own is a failing test and not a gap a player
 * finds.
 * <p>
 * <b>Why the column rule can only fail below.</b> An upgrade only raises a bay's strength, and "may carry" is monotone
 * in the carrier's strength: whatever the old tier carried above it, the new one carries too. So the bays above an
 * upgraded bay can never refuse it, and the only question left is whether every bay <i>below</i> still carries the
 * stronger one — which is why a column is upgraded from the bottom up, and why the source tier itself never carries the
 * target ({@code !from.mayCarry(to)}, asserted by the test).
 */
public enum BayUpgrade {
    /** Wood to andesite, on the item ladder. */
    ANDESITE_RACK(BayFamily.ITEM, BayTier.WOOD.strength(), BayTier.ANDESITE.strength()),
    /** Andesite to brass, on the item ladder. */
    BRASS_RACK(BayFamily.ITEM, BayTier.ANDESITE.strength(), BayTier.BRASS.strength()),
    /** Copper to brass, on the fluid ladder: breaking a fluid bay loses the fluid, so it needs the kit most. */
    BRASS_FLUID(BayFamily.FLUID, FluidBayTier.COPPER.strength(), FluidBayTier.BRASS.strength());

    private final BayFamily family;
    private final int fromStrength;
    private final int toStrength;

    BayUpgrade(BayFamily family, int fromStrength, int toStrength) {
        this.family = family;
        this.fromStrength = fromStrength;
        this.toStrength = toStrength;
    }

    /** The ladder this step belongs to; a kit does nothing to a bay of the other family. */
    public BayFamily family() {
        return family;
    }

    /** The strength of the tier this kit upgrades, on its family's ladder. */
    public int fromStrength() {
        return fromStrength;
    }

    /** The strength of the tier the bay becomes, one step above {@link #fromStrength()}. */
    public int toStrength() {
        return toStrength;
    }

    /**
     * Whether this kit upgrades a bay of {@code family} at {@code strength}: only its own family, and only at exactly
     * the tier it starts from — so never a downgrade, never a skipped tier and never the top tier.
     */
    public boolean fits(BayFamily family, int strength) {
        return this.family == family && fromStrength == strength;
    }
}
