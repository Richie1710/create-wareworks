package dev.wareworks.content.requester;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.schematics.cannon.MaterialChecklist;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.requester.ChecklistNeed;
import dev.wareworks.core.requester.RequesterStatus;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * What a Schematicannon Requester sees of the cannon in front of it (issue #45, ADR-080 §6): the <b>only</b> class that
 * touches Create's {@link SchematicannonBlockEntity}. Everything it reads is a public field of Create 6.0.10 — no mixin,
 * no access transformer — and the cannon is found through {@link NoTicket}, so a requester polling once a second never
 * loads or holds the cannon's chunk.
 * <p>
 * <b>Missing</b> is a strict gate: the cannon is paused, its status is {@code "missingBlock"}, it names a non-empty
 * missing item, it neither skips missing blocks nor has a creative crate, and its printer is loaded and sound. The
 * field {@code missingItem} alone is not enough — it stays set while the cannon waits for gunpowder, for an unloaded
 * target and after a reload — so only the status string together with the field is trusted. The cannon reports the
 * first missing stack of the block it waits on, a new stack every tick: {@link #missing()} is always a copy.
 * <p>
 * Built fresh for every poll and never kept, so a cannon that was broken or replaced in between is simply not seen.
 */
public final class CannonView {
    /** What the cannon does, as far as the requester cares; the order is the goggles' precedence. */
    public enum Phase {
        /** Not loaded, or no cannon in front. */
        NO_CANNON(RequesterStatus.NO_CANNON),
        /**
         * The cannon skips missing blocks and so never asks for anything. A cannon placed from its item starts this way:
         * Create's {@code applyImplicitComponents} defaults to {@code SchematicannonOptions(2, true, false)}.
         */
        SKIP_MISSING(RequesterStatus.SKIP_MISSING),
        /** A creative crate feeds the cannon: nothing is ever missing. */
        CREATIVE(RequesterStatus.CREATIVE),
        /** The cannon misses an item but has no inventory attached to deliver into. */
        NO_INVENTORY(RequesterStatus.NO_INVENTORY),
        /**
         * Stopped, idle, finished, or without a sound schematic; also paused on {@code "ready"}, waiting for the play
         * button: a stopped cannon never stays stopped, because Create's {@code tickPaperPrinter} loads the schematic
         * again on the next tick and leaves the cannon there.
         */
        NOT_PRINTING(RequesterStatus.NOT_PRINTING),
        /** Paused for something else than a missing item: gunpowder, an unloaded target, ... */
        BLOCKED(RequesterStatus.BLOCKED),
        /** Running, and nothing is missing. */
        PRINTING(RequesterStatus.PRINTING),
        /** The gate of the class comment holds: the requester may order. */
        MISSING(null);

        @Nullable
        private final RequesterStatus status;

        Phase(@Nullable RequesterStatus status) {
            this.status = status;
        }

        /** The requester status this phase shows on its own, or empty for {@link #MISSING}, which the order rule decides. */
        public Optional<RequesterStatus> status() {
            return Optional.ofNullable(status);
        }

        /** Whether the cannon prints nothing at all, or is gone: two such polls in a row give the orders back. */
        public boolean isIdle() {
            return this == NO_CANNON || this == NOT_PRINTING;
        }

        /** The name this phase is synced by, lower case. */
        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The phase of a {@link #serializedName()}, or {@link #NO_CANNON} for anything else. Never throws. */
        public static Phase byName(String name) {
            if (name == null || name.isEmpty())
                return NO_CANNON;
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return NO_CANNON;
            }
        }
    }

    /**
     * What identifies one wait of the cannon: the block it waits on and the stack it reports. Compared by content, so
     * the strictness of the requirement and the variant to order are worked out once per wait, not once per poll.
     *
     * @param target the printer's current target, {@code null} when it has none
     * @param key    the missing stack's item and components
     * @param count  how many the cannon reports missing
     */
    public record Signature(@Nullable BlockPos target, ItemKey key, int count) {
        public Signature {
            Objects.requireNonNull(key, "key");
        }
    }

    /**
     * How the cannon's requirement takes the missing stack.
     *
     * @param strict whether it wants exactly these components (Create's {@code StrictNbtStackRequirement}); a requirement
     *               that names no matching entry counts as strict, so the reported stack is ordered exactly or not at all
     * @param damage whether it only damages the item (super glue): one is then enough
     */
    public record Requirement(boolean strict, boolean damage) {
        /** What a requirement nobody can read counts as. */
        public static final Requirement UNKNOWN = new Requirement(true, false);
    }

    @Nullable
    private final SchematicannonBlockEntity cannon;
    private final Phase phase;

    private CannonView(@Nullable SchematicannonBlockEntity cannon) {
        this.cannon = cannon;
        this.phase = phaseOf(cannon);
    }

    /** The cannon at {@code pos}, read without loading or ticketing its chunk (server thread). */
    public static CannonView read(ServerLevel level, BlockPos pos) {
        return new CannonView(NoTicket.blockEntity(level, pos, SchematicannonBlockEntity.class));
    }

    private static Phase phaseOf(@Nullable SchematicannonBlockEntity cannon) {
        if (cannon == null || cannon.isRemoved())
            return Phase.NO_CANNON;
        if (cannon.skipMissing)
            return Phase.SKIP_MISSING;
        if (cannon.hasCreativeCrate)
            return Phase.CREATIVE;
        String status = cannon.statusMsg;
        boolean sound = cannon.printer != null && cannon.printer.isLoaded() && !cannon.printer.isErrored();
        if (cannon.state == null || cannon.state == SchematicannonBlockEntity.State.STOPPED || !sound
                || "idle".equals(status) || "finished".equals(status)
                || (cannon.state == SchematicannonBlockEntity.State.PAUSED && "ready".equals(status)))
            return Phase.NOT_PRINTING;
        if (cannon.state == SchematicannonBlockEntity.State.PAUSED) {
            if ("missingBlock".equals(status) && cannon.missingItem != null && !cannon.missingItem.isEmpty())
                return cannon.attachedInventories == null || cannon.attachedInventories.isEmpty() ? Phase.NO_INVENTORY
                        : Phase.MISSING;
            return Phase.BLOCKED;
        }
        return Phase.PRINTING;
    }

    public Phase phase() {
        return phase;
    }

    /** A copy of the stack the cannon misses, or an empty stack unless the cannon waits for one. */
    public ItemStack missing() {
        if (cannon == null || (phase != Phase.MISSING && phase != Phase.NO_INVENTORY))
            return ItemStack.EMPTY;
        return cannon.missingItem.copy();
    }

    /** Create's status word ({@code create.schematicannon.status.<word>}), for a cannon that is {@link Phase#BLOCKED}. */
    public String statusWord() {
        return cannon == null || cannon.statusMsg == null ? "" : cannon.statusMsg;
    }

    /** The signature of the current wait; only while {@link Phase#MISSING}. */
    public Signature signature() {
        ItemStack missing = missing();
        if (missing.isEmpty())
            throw new IllegalStateException("the cannon misses nothing");
        return new Signature(cannon.printer.getCurrentTarget(), ItemKey.of(missing), missing.getCount());
    }

    /**
     * How the cannon's current requirement takes the missing stack. {@code SchematicPrinter#getCurrentRequirement}
     * builds a block entity for blocks that have one, so this is asked once per {@link Signature}, never per poll; an
     * add-on whose requirement throws counts as {@link Requirement#UNKNOWN}.
     */
    public Requirement requirement() {
        ItemStack missing = missing();
        if (missing.isEmpty())
            return Requirement.UNKNOWN;
        try {
            for (ItemRequirement.StackRequirement entry : cannon.printer.getCurrentRequirement().getRequiredItems()) {
                if (entry != null && entry.stack != null && ItemStack.isSameItemSameComponents(entry.stack, missing))
                    return new Requirement(entry instanceof ItemRequirement.StrictNbtStackRequirement,
                            entry.usage == ItemRequirement.ItemUseType.DAMAGE);
            }
        } catch (RuntimeException e) {
            Wareworks.LOGGER.debug("Could not read the requirement of the schematicannon at {}",
                    cannon.getBlockPos(), e);
        }
        return Requirement.UNKNOWN;
    }

    /**
     * How many of {@code key}, up to {@code max}, the cannon's attached inventories could take now: the sum over every
     * inventory of what a simulated insert accepts, capped at {@code max}. Simulated only — nothing moves.
     */
    public long roomFor(ItemKey key, int max) {
        Objects.requireNonNull(key, "key");
        if (cannon == null || max < 1 || cannon.attachedInventories == null)
            return 0;
        long room = 0;
        for (IItemHandler handler : cannon.attachedInventories) {
            if (handler == null)
                continue;
            int wanted = (int) (max - room);
            if (wanted <= 0)
                break;
            ItemStack rest = ItemHandlerHelper.insertItemStacked(handler, key.toStack(wanted), true);
            room += wanted - rest.getCount();
        }
        return Math.min(room, max);
    }

    /** The cannon's material list as it is now, for {@link ChecklistNeed}. */
    public ChecklistNeed.Checklist<Item> checklist() {
        return cannon == null ? EmptyChecklist.INSTANCE : new Checklist(cannon);
    }

    /**
     * Asks the cannon to remake its material list — the same call Create makes for a book print mid-run
     * ({@code SchematicannonBlockEntity#updateChecklist}) — and returns it. Only ever reached through
     * {@link ChecklistNeed}'s rate limit.
     */
    public ChecklistNeed.Checklist<Item> refreshedChecklist() {
        if (cannon == null)
            return EmptyChecklist.INSTANCE;
        cannon.updateChecklist();
        return new Checklist(cannon);
    }

    /** A read of {@link MaterialChecklist}'s public maps, which are keyed by item, server only. */
    private record Checklist(SchematicannonBlockEntity cannon) implements ChecklistNeed.Checklist<Item> {
        @Override
        public long required(Item item) {
            return cannon.checklist.required.getInt(item);
        }

        @Override
        public long gathered(Item item) {
            return cannon.checklist.gathered.getInt(item);
        }

        @Override
        public ChecklistNeed.Fingerprint fingerprint() {
            return new ChecklistNeed.Fingerprint(sum(cannon.checklist.required), sum(cannon.checklist.gathered),
                    cannon.blocksToPlace);
        }

        private static long sum(Object2IntMap<Item> counts) {
            long sum = 0;
            for (int count : counts.values())
                sum += count;
            return sum;
        }
    }

    /** No cannon: an empty list, which makes every item one stack. */
    private enum EmptyChecklist implements ChecklistNeed.Checklist<Item> {
        INSTANCE;

        @Override
        public long required(Item item) {
            return 0;
        }

        @Override
        public long gathered(Item item) {
            return 0;
        }

        @Override
        public ChecklistNeed.Fingerprint fingerprint() {
            return new ChecklistNeed.Fingerprint(0, 0, 0);
        }
    }
}
