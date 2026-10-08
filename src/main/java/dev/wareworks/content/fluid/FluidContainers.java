package dev.wareworks.content.fluid;

import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.wareworks.content.item.ItemKey;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

/**
 * Which items carry fluid, how much of it, and what one of them turns into when it is emptied or filled (M30, issue
 * #21). <b>The whole of that decision lives here</b>, so the bay, the store gate, the planner, the terminal and both
 * censuses are built on one answer rather than five.
 *
 * <h2>The capability is the only authority</h2>
 * An item carries fluid exactly when it answers {@link Capabilities.FluidHandler#ITEM}. There is no list of container
 * items anywhere in this mod — buckets, a modded canister, a modded tank item, all of them work the day they are
 * installed, and nothing has to be taught about them.
 * <p>
 * Two consequences a reader should know before they go looking for a bug:
 * <ul>
 *   <li><b>A glass bottle is not a container</b>, and neither is a water bottle. Nothing in NeoForge registers the item
 *       fluid capability for them: the only item registrations are two bucket lines guarded by
 *       {@code item.getClass() == BucketItem.class} — exact class equality, which already excludes fish buckets and
 *       powder snow — plus milk re-added by hand, and Create adds none. Create makes bottles work in a Spout and an
 *       Item Drain by special-casing {@code Items.GLASS_BOTTLE} <b>before</b> it asks the capability, i.e. by a list of
 *       its own. So a bottle is refused here, and the refusal is the rule working rather than failing.</li>
 *   <li><b>Create's own emptying and filling routines are deliberately not used.</b> They would bring bottles and
 *       Create's {@code EMPTYING}/{@code FILLING} recipes along, which is attractive — but
 *       {@code GenericItemEmptying.emptyItem} drains a hard-coded 1000 mB per call, so a 5 000 mB modded tank item
 *       would come back holding 4 000, and that is a different {@link ItemKey} per fill level (item key equality is
 *       {@code isSameItemSameComponents}). One stock row, one terminal row and one save entry per millibucket is the
 *       outcome the whole-container rule below exists to prevent. If that trade is ever reopened, this class is the
 *       only file that changes.</li>
 * </ul>
 *
 * <h2>Whole containers only</h2>
 * A container is drained <b>to empty</b> or refused, and filled <b>to full</b> or refused — never partially, for the
 * key-explosion reason above. A bay with 999 mB of room therefore gets nothing at all from a bucket, which is also the
 * vanilla bucket's own rule ({@code FluidBucketWrapper.fill} refuses a resource below a whole bucket). The arithmetic
 * of "how many whole containers fit" is {@code core.storage.FluidBayTier#wholeContainers}.
 * <p>
 * A container whose empty form is <b>nothing</b> — a consumable the fluid handler reports as
 * {@link ItemStack#EMPTY} — is refused outright: the crane would have nothing to carry back, an item census would
 * record a real loss the warehouse caused, and {@link ItemKey} cannot even name it.
 *
 * <h2>The four traps this class exists to absorb</h2>
 * <ol>
 *   <li><b>Every operation needs a stack of exactly one.</b> {@code FluidUtil}'s own javadoc: "the itemStack MUST have
 *       a stackSize of 1 ... if you do then liquid is multiplied or destroyed", and
 *       {@code FluidBucketWrapper} simply answers 0 and {@code EMPTY} for a stack of any other size. Every method here
 *       works on its own freshly built single-item probe, so no caller can get that wrong — and
 *       {@link #contentsOf(ItemStack)} is documented as the amount in <b>one</b> item of a stack, never in the
 *       stack.</li>
 *   <li><b>A {@link FluidStack} a handler hands out is mutable and may be the live instance.</b> Everything read here
 *       becomes a {@link FluidKey} or an {@code int} immediately, and nothing keeps a {@code FluidStack} past the call
 *       that read it.</li>
 *   <li><b>A simulated drain lies about the resulting container.</b> {@code FluidBucketWrapper.drain} only calls
 *       {@code setFluid} when the action executes, so after a simulation {@code getContainer()} is still the filled
 *       bucket. {@link #drained} and {@link #filled} therefore run the <b>real</b> operation — on a probe of their own,
 *       so nothing in the world moves. It is the same thing Create's own Item Drain recipe listing does.</li>
 *   <li><b>{@code getContainer()} may be a different item, or empty.</b> {@code FluidBucketWrapper.setFluid}
 *       reassigns the handler's own field and never touches the caller's stack, so the emptied bucket is only reachable
 *       through {@code getContainer()}; the interface's own javadoc adds "May be an empty item if the container was
 *       drained and is consumable".</li>
 * </ol>
 *
 * <h2>Never throws</h2>
 * A container whose capability throws is treated as not a container: every method answers "no fluid" or "refused" and
 * notes it at {@code debug}. This is a hot path with no level and no owner to rate-limit a warning, and a modded item
 * must not be able to break a dispatch pass or a conservation check; the places that perform a <b>real</b> transfer own
 * their own throttled diagnostics.
 */
public final class FluidContainers {
    private static final Logger LOGGER = LogUtils.getLogger();

    private FluidContainers() {
    }

    /**
     * What one container carries.
     *
     * @param fluid        the fluid in it, never empty
     * @param millibuckets how much of it, always above 0
     */
    public record Contents(FluidKey fluid, int millibuckets) {
        public Contents {
            Objects.requireNonNull(fluid, "fluid");
            if (millibuckets <= 0)
                throw new IllegalArgumentException("contents must be above 0 mB: " + millibuckets);
        }
    }

    /**
     * What emptying one whole container yields: the fluid that comes out and the item that is left. The exchange the
     * crane performs at a fluid bay is exactly this, one container at a time.
     *
     * @param fluid        the fluid that comes out, never empty
     * @param millibuckets how much comes out, always above 0 — the whole contents, because there is no partial drain
     * @param emptied      the item the container turns into, never empty and never the filled container itself
     */
    public record Drained(FluidKey fluid, int millibuckets, ItemKey emptied) {
        public Drained {
            Objects.requireNonNull(fluid, "fluid");
            Objects.requireNonNull(emptied, "emptied");
            if (millibuckets <= 0)
                throw new IllegalArgumentException("a drain must yield more than 0 mB: " + millibuckets);
        }
    }

    /**
     * What filling one whole empty container with a fluid yields: the filled item and how much fluid it took.
     *
     * @param filled       the item the container turns into, never empty
     * @param millibuckets how much fluid it took, always above 0 — its whole capacity for that fluid
     */
    public record Filled(ItemKey filled, int millibuckets) {
        public Filled {
            Objects.requireNonNull(filled, "filled");
            if (millibuckets <= 0)
                throw new IllegalArgumentException("a fill must take more than 0 mB: " + millibuckets);
        }
    }

    /**
     * What one container of {@code container} carries, or empty for an item that carries no fluid — which includes
     * every <b>empty</b> container, so this is also the question "is this a filled container".
     * <p>
     * Read-only: it simulates, it copies, and it never changes anything. This is the question the store gate asks of
     * an arriving item ("does this carry the fluid that bay is dedicated to") and the question a fluid census asks of
     * every item it walks past.
     */
    public static Optional<Contents> contents(ItemKey container) {
        Objects.requireNonNull(container, "container");
        return read(container.toStack(), container);
    }

    /**
     * What <b>one item</b> of {@code stack} carries, whatever the stack's size — never the whole stack's fluid. A
     * census multiplies by {@link ItemStack#getCount()} itself, which is the only correct way round: handing a stack of
     * 16 filled buckets to a fluid handler answers nothing at all, and a census that did would be blind to a chest of
     * them.
     */
    public static Optional<Contents> contentsOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return Optional.empty();
        return read(stack.copyWithCount(1), stack.getItem());
    }

    /** Whether {@code key} is an item that carries fluid right now, i.e. a filled container. */
    public static boolean carriesFluid(ItemKey key) {
        return contents(key).isPresent();
    }

    /**
     * Whether one item of {@code stack} is a fluid container <b>at all</b>, filled or empty: an item that answers
     * {@code Capabilities.FluidHandler.ITEM}.
     * <p>
     * This is the question a hand gesture asks, and the only one it can ask: a right-click at a fluid bay has to be
     * <b>consumed</b> for every container, including one the bay cannot serve, or the click falls through to the item's
     * own use and a bucket of lava is poured against the face of a wooden rack wall. Create's own Fluid Tank answers
     * exactly this way (its {@code useItemOn} returns {@code SUCCESS} for an item that can be emptied or filled even
     * when the transfer failed), and it is why the question is "is this a container" rather than "does this carry
     * fluid".
     * <p>
     * Asked of a <b>single</b> item, like every other reading in this class: a capability resolves for a stack of 16,
     * but every operation on one refuses, so the stack size is not part of the question.
     */
    public static boolean isContainer(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return false;
        ItemStack probe = stack.copyWithCount(1);
        return handlerOf(probe, stack.getItem()) != null;
    }

    /**
     * Whether {@code key} is a container that carries no fluid: an item with the fluid capability and nothing in it.
     * The warehouse's "empty container", the one kind of container that is ordinary item stock.
     * <p>
     * A container whose capability <b>throws</b> answers {@code false} here rather than "empty", deliberately: an
     * empty container is a thing the warehouse will later try to fill, and one it cannot even read is not that.
     */
    public static boolean isEmptyContainer(ItemKey key) {
        Objects.requireNonNull(key, "key");
        ItemStack probe = key.toStack();
        IFluidHandlerItem handler = handlerOf(probe, key);
        if (handler == null)
            return false;
        try {
            return toContents(handler.drain(Integer.MAX_VALUE, FluidAction.SIMULATE)).isEmpty();
        } catch (RuntimeException e) {
            debug("read", key, e);
            return false;
        }
    }

    /**
     * What emptying one container of {@code container} would really yield, or empty if this container may not be
     * emptied whole.
     * <p>
     * It is refused when the item carries no fluid, when the handler gives back less than the whole contents (a
     * per-call cap, a multi-tank item), when the container is not empty afterwards, when its empty form is nothing at
     * all (a consumable), or when one container in did not give one container out.
     * <p>
     * <b>This runs the real drain</b>, on a single-item probe of its own, because a simulated one leaves
     * {@code getContainer()} still holding the filled item. Nothing in the world is touched, and the answer is
     * therefore the truth rather than a prediction.
     */
    public static Optional<Drained> drained(ItemKey container) {
        Objects.requireNonNull(container, "container");
        ItemStack probe = container.toStack();
        IFluidHandlerItem handler = handlerOf(probe, container);
        if (handler == null)
            return Optional.empty();
        try {
            Optional<Contents> before = readFrom(handler, container);
            if (before.isEmpty())
                return Optional.empty();
            FluidKey fluid = before.get().fluid();
            int amount = before.get().millibuckets();

            FluidStack taken = handler.drain(fluid.toStack(amount), FluidAction.EXECUTE);
            ItemStack result = handler.getContainer();
            return judgeDrain(fluid, amount, taken, result);
        } catch (RuntimeException e) {
            debug("drain", container, e);
            return Optional.empty();
        }
    }

    /**
     * The refusal rule of a drain, with the handler's two answers already in hand: what came out, and what the
     * container turned into. It is a method of its own so the rule can be stated once and tested on its own — the
     * cases that matter most are the ones no vanilla container produces (a consumable that leaves nothing, a
     * per-call cap that gives back less than the whole contents, a handler that answers with a stack of two).
     *
     * @param fluid        the fluid the container was measured to hold
     * @param millibuckets how much it was measured to hold, which the drain must deliver in full
     * @param taken        what {@code drain} really answered
     * @param result       what {@code getContainer()} really answered after that drain
     */
    public static Optional<Drained> judgeDrain(FluidKey fluid, int millibuckets, @Nullable FluidStack taken,
                                              @Nullable ItemStack result) {
        Objects.requireNonNull(fluid, "fluid");
        if (millibuckets <= 0)
            return Optional.empty();
        // All or nothing: less than the whole contents, or a different fluid, is a refusal rather than a partial move.
        if (taken == null || taken.getAmount() != millibuckets || !fluid.matches(taken))
            return Optional.empty();
        // One container in, one container out. An empty result is a consumable the crane could not carry back, and an
        // item census would record the loss as the warehouse's. Both halves are needed, and neither implies the other:
        // getCount() != 1 catches a handler answering with two containers, isEmpty() an AIR stack whose count is 1.
        if (result == null || result.isEmpty() || result.getCount() != 1)
            return Optional.empty();
        // Drained to empty, or refused: a container still holding something is a new item key per fill level.
        if (contentsOf(result).isPresent())
            return Optional.empty();
        return Optional.of(new Drained(fluid, millibuckets, ItemKey.of(result)));
    }

    /**
     * What filling one empty container of {@code container} with {@code fluid} would really yield, or empty if this
     * container may not be filled whole with that fluid.
     * <p>
     * It is refused when the item has no fluid capability, when it already carries fluid (only empties are filled),
     * when it takes nothing of that fluid, when it does not take its whole measured capacity in one call, when it ends
     * up holding something other than exactly that fluid and amount, or when one container in did not give one
     * container out.
     * <p>
     * <b>This runs the real fill</b>, for {@link #drained}'s reason. The capacity is <b>measured</b> and never looked
     * up: a bucket answers 1 000 because it says so, and a modded container answers whatever it says.
     */
    public static Optional<Filled> filled(ItemKey container, FluidKey fluid) {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(fluid, "fluid");
        ItemStack probe = container.toStack();
        IFluidHandlerItem handler = handlerOf(probe, container);
        if (handler == null)
            return Optional.empty();
        try {
            if (readFrom(handler, container).isPresent())
                return Optional.empty();

            int capacity = handler.fill(fluid.toStack(Integer.MAX_VALUE), FluidAction.SIMULATE);
            if (capacity <= 0)
                return Optional.empty();
            int accepted = handler.fill(fluid.toStack(capacity), FluidAction.EXECUTE);
            ItemStack result = handler.getContainer();
            return judgeFill(fluid, capacity, accepted, result);
        } catch (RuntimeException e) {
            debug("fill", container, e);
            return Optional.empty();
        }
    }

    /**
     * The refusal rule of a fill, with the handler's answers already in hand. Its own method for {@link #judgeDrain}'s
     * reason, and it asks one thing that rule cannot: the filled container must give the fluid <b>back</b> as the same
     * key and the same amount, so a container that quietly converts what it carries — a bucket filled with flowing
     * lava hands back a lava bucket — is refused rather than becoming fluid the warehouse cannot account for.
     *
     * @param fluid    the fluid the container was asked to take
     * @param capacity how much the simulated fill said it would take
     * @param accepted how much the real fill really took, which must be all of it
     * @param result   what {@code getContainer()} really answered after that fill
     */
    public static Optional<Filled> judgeFill(FluidKey fluid, int capacity, int accepted, @Nullable ItemStack result) {
        Objects.requireNonNull(fluid, "fluid");
        if (capacity <= 0 || accepted != capacity)
            return Optional.empty();
        if (result == null || result.isEmpty() || result.getCount() != 1)
            return Optional.empty();
        Optional<Contents> after = contentsOf(result);
        if (after.isEmpty() || !after.get().fluid().equals(fluid) || after.get().millibuckets() != capacity)
            return Optional.empty();
        return Optional.of(new Filled(ItemKey.of(result), capacity));
    }

    /**
     * The fluid in a probe that is already a single item, through {@code FluidUtil.getFluidContained} — which copies to
     * one item itself and simulate-drains, so it never mutates and needs no level.
     *
     * @param probe   a stack of exactly one item; consumed by this call and not reused by the caller
     * @param subject what to name in a diagnostic
     */
    private static Optional<Contents> read(ItemStack probe, Object subject) {
        try {
            return FluidUtil.getFluidContained(probe).flatMap(FluidContainers::toContents);
        } catch (RuntimeException e) {
            debug("read", subject, e);
            return Optional.empty();
        }
    }

    /** The fluid in a handler's own tank, by the same simulated whole drain, for a handler already in hand. */
    private static Optional<Contents> readFrom(IFluidHandlerItem handler, Object subject) {
        try {
            return toContents(handler.drain(Integer.MAX_VALUE, FluidAction.SIMULATE));
        } catch (RuntimeException e) {
            debug("read", subject, e);
            return Optional.empty();
        }
    }

    /** A freshly read stack turned into a key and a count, which is the last moment a {@code FluidStack} is held. */
    private static Optional<Contents> toContents(@Nullable FluidStack stack) {
        if (stack == null || stack.isEmpty() || stack.getAmount() <= 0)
            return Optional.empty();
        return Optional.of(new Contents(FluidKey.of(stack), stack.getAmount()));
    }

    @Nullable
    private static IFluidHandlerItem handlerOf(ItemStack probe, Object subject) {
        try {
            return probe.getCapability(Capabilities.FluidHandler.ITEM);
        } catch (RuntimeException e) {
            debug("capability", subject, e);
            return null;
        }
    }

    private static void debug(String what, Object subject, RuntimeException e) {
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Fluid container {} failed for {}, treating it as no container", what, subject, e);
    }
}
