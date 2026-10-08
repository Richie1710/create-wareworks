package dev.wareworks.content.storage;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.fluid.FluidTypeSummaries;
import dev.wareworks.core.inventory.CapacityMath;
import dev.wareworks.core.storage.BayContents;
import dev.wareworks.core.storage.FluidBayTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/**
 * The fluid handler of a fluid bay ({@code docs/warehouse-system.md} §3.9, M30, issue #21): <b>one tank, one fluid and
 * a millibucket count</b>, over a {@link BayContents} — the exact twin of {@link RackBayHandler}, with fluid instead of
 * items.
 *
 * <h2>Why this is hand-written and not a {@link FluidTank}</h2>
 * It is forced twice over, and both reasons are about losing fluid rather than about taste.
 * <ul>
 * <li><b>{@code FluidTank.fill} destroys fluid when the capacity is below what the tank holds</b>, which a lowered
 * {@code storage.<tier>FluidBayBuckets} legitimately produces under a standing bay: it computes
 * {@code filled = capacity - fluid.getAmount()}, which is <i>negative</i>, and then assigns the amount anyway, so the
 * first fill after a config change silently drops the tank to the new capacity. {@link BayContents#insert} answers 0
 * for a bay with no room instead, which is the promise the config comment makes — "lowering this never destroys
 * anything" — and it is the same promise, in the same words, that M28's D11 made for items.</li>
 * <li><b>{@code FluidTank.getFluidInTank} hands out its own live {@link FluidStack}</b>, against its own interface's
 * "SERIOUSLY: DO NOT MODIFY THE RETURNED FLUIDSTACK". A caller that mutated it would change the tank's contents
 * behind its back. Here there is no {@code FluidStack} to hand out at all: the contents are a count-less
 * {@link FluidKey} and an {@code int}, and every answer is a fresh stack.</li>
 * </ul>
 * <b>No {@code FluidStack} field anywhere, and none kept across a call.</b> A {@code FluidStack} overrides neither
 * {@code equals} nor {@code hashCode}, is mutable, and {@code save} throws on an empty one — which is why
 * {@link FluidKey} exists and why {@link #writeTo} never calls it.
 *
 * <h2>What it tells the world, and why honestly</h2>
 * <ul>
 * <li>{@link #getFluidInTank} is the true contents, copied on every call. It is also what a fluid census reads
 * ({@code gametest.FluidCensus}), deliberately instead of a simulated drain, because a simulated drain is gated by
 * {@code storage.fluidBayPipeExtraction} and a bay that refuses extraction would otherwise read as empty.</li>
 * <li>{@link #getTankCapacity} is the <b>configured</b> capacity, even for a bay that holds more than that after a
 * lowered config. That is the number that decides what the bay accepts, and the alternative — reporting the contents
 * as the capacity — would make a bay look fillable that takes nothing.</li>
 * <li>{@link #isFluidValid} answers the fluid filter <b>and</b> the one stored fluid. The contract asks for a rule
 * that ignores the tank's state, and the stored fluid is state — but it is the one state that cannot change except by
 * emptying the bay, and answering it is what makes a pipe back up instead of hammering a bay that will never take its
 * fluid. This is {@link RackBayHandler#isItemValid}'s documented departure, verbatim. Fullness is deliberately
 * <i>not</i> considered, which is what the contract is really about.</li>
 * <li>{@link #drain(int, FluidAction)} has <b>no per-call cap</b>: an {@code IItemHandler} must never return more than
 * one stack, so {@link RackBayHandler} passes the stack size to {@link BayContents#extract}, but an
 * {@code IFluidHandler} has no such bound and a pump asking for a bay's whole load gets it in one call.</li>
 * </ul>
 * Server thread only. Every real change runs {@link Rules#onContentsChanged()}.
 *
 * <h2>Filling is always allowed, drawing off is config-gated</h2>
 * The gate is <b>not</b> in this class: this handler is the bay's own, ungated view, which the bay's own operations and
 * a player's hand use. What pipes reach is {@link PipeView}, which refuses {@code drain} unless
 * {@code storage.fluidBayPipeExtraction} is on — Create's own shape for the same question
 * ({@code SmartFluidTankBehaviour.InternalFluidHandler} against {@code forceFill}).
 */
public class FluidBayHandler implements IFluidHandler {
    /** The bay has exactly one tank; a count, not a tank list, is what makes it hold 256 buckets. */
    public static final int TANKS = 1;
    /** The only tank index. */
    public static final int TANK = 0;
    /** Save key of the stored fluid, a count-less {@link FluidKey}; absent while the bay is empty. */
    public static final String FLUID_TAG = "Fluid";
    /** Save key of the stored millibuckets; absent while the bay is empty. Also the client packet's amount. */
    public static final String AMOUNT_TAG = "Amount";
    /**
     * <b>Client packet</b> key of the stored fluid, as a registry id string — never a {@link FluidKey} and never a
     * {@code FluidStack}; absent while the bay is empty. See {@link #writeClientPacket}.
     */
    public static final String STORED_FLUID_TAG = "StoredFluid";

    /**
     * What one {@link #drain(int, FluidAction)} call may answer at most: everything. A fluid handler has no per-call
     * bound of any kind, unlike an item handler, whose contract caps an extracted stack at the item's own stack size.
     */
    private static final int NO_PER_CALL_CAP = Integer.MAX_VALUE;

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * What the handler has to ask its bay, because none of it is a property of the contents: the capacity depends on
     * the tier and on a server config that may change under a standing bay, and the filter is a Create behaviour whose
     * stack has to be read as a fluid.
     */
    public interface Rules {
        /** How many millibuckets this bay holds in total ({@code WareworksConfig.fluidBayCapacity}). */
        long capacity();

        /** Whether the fluid filter lets {@code fluid} in at all; true for a bay nobody filtered. */
        boolean passesFilter(FluidKey fluid);

        /** Called after every real change through this handler (never on a load). */
        void onContentsChanged();

        /** Where this bay stands, for the one warning a bounded load may log. */
        BlockPos position();
    }

    private final BayContents<FluidKey> contents = new BayContents<>();
    private final Rules rules;

    public FluidBayHandler(Rules rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    // --- contents ------------------------------------------------------------------------------------------------

    /** The stored fluid, empty while the bay is empty — which is also how it forgets a learned fluid. */
    public Optional<FluidKey> stored() {
        return Optional.ofNullable(contents.stored());
    }

    /** {@link #stored()} without the wrapper, for callers that run per bay per frame or per census sweep. */
    @Nullable
    public FluidKey storedOrNull() {
        return contents.stored();
    }

    /** How many millibuckets are stored; 0 exactly when {@link #stored()} is empty. */
    public int millibuckets() {
        return contents.count();
    }

    /** {@code Clearable}: empties the bay without moving anything ({@code /setblock}, structure placement). */
    public void clear() {
        contents.clear();
    }

    /**
     * Empties the bay and answers what was in it, for the one caller that must not go through the handler: the block
     * being destroyed, which loses its fluid (there is no drop form for a fluid) and has to be able to name it in the
     * log line before it is gone.
     *
     * @return the whole load as one stack, or {@link FluidStack#EMPTY}
     */
    public FluidStack takeAll() {
        FluidKey key = contents.stored();
        if (key == null)
            return FluidStack.EMPTY;
        int load = contents.count();
        contents.clear();
        return key.toStack(load);
    }

    // --- fluid handler -------------------------------------------------------------------------------------------

    @Override
    public int getTanks() {
        return TANKS;
    }

    /**
     * The whole contents as one stack, built fresh on every call: the contract forbids a caller to modify what it gets
     * and a bay has no stack of its own to hand out.
     */
    @Override
    public FluidStack getFluidInTank(int tank) {
        FluidKey key = tank == TANK ? contents.stored() : null;
        return key == null ? FluidStack.EMPTY : key.toStack(contents.count());
    }

    @Override
    public int getTankCapacity(int tank) {
        return tank == TANK ? CapacityMath.toIntClamped(rules.capacity()) : 0;
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        if (tank != TANK || stack == null || stack.isEmpty())
            return false;
        FluidKey key = FluidKey.of(stack); // an amount-less copy: the caller's instance is never stored
        if (!rules.passesFilter(key))
            return false;
        FluidKey stored = contents.stored();
        return stored == null || stored.equals(key);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty() || resource.getAmount() <= 0)
            return 0;
        FluidKey key = FluidKey.of(resource);
        if (!rules.passesFilter(key))
            return 0;
        int accepted = contents.insert(key, resource.getAmount(), rules.capacity(), action.simulate());
        if (accepted <= 0)
            return 0;
        if (action.execute())
            rules.onContentsChanged();
        return accepted;
    }

    /** Fluid-sensitive drain: nothing at all unless the bay holds exactly {@code resource}'s fluid. */
    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty())
            return FluidStack.EMPTY;
        FluidKey key = contents.stored();
        if (key == null || !key.matches(resource))
            return FluidStack.EMPTY;
        return drain(resource.getAmount(), action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        FluidKey key = contents.stored();
        if (key == null || maxDrain <= 0)
            return FluidStack.EMPTY;
        int taken = contents.extract(maxDrain, NO_PER_CALL_CAP, action.simulate());
        if (taken <= 0)
            return FluidStack.EMPTY;
        if (action.execute())
            rules.onContentsChanged();
        return key.toStack(taken);
    }

    // --- persistence ---------------------------------------------------------------------------------------------

    /**
     * Writes the contents into {@code tag}: {@value #FLUID_TAG} as a count-less {@link FluidKey} and
     * {@value #AMOUNT_TAG} as an {@code int} of millibuckets. An empty bay writes <b>neither</b> key, so an empty tank
     * wall costs no save bytes and a bay placed before this method existed reads back exactly as empty.
     * <p>
     * Never throws and never truncates. {@code FluidStack.save} is not called anywhere — it throws
     * {@code IllegalStateException} on an empty stack — and a fluid that cannot be encoded at all writes no amount
     * either, so the two keys are never out of step.
     */
    public void writeTo(CompoundTag tag, HolderLookup.Provider registries) {
        FluidKey key = contents.stored();
        if (key == null || contents.count() <= 0)
            return;
        key.saveTo(tag, FLUID_TAG, registries);
        if (!tag.contains(FLUID_TAG))
            return; // the fluid could not be encoded; an amount without it would read back as a lost fluid of nothing
        tag.putInt(AMOUNT_TAG, contents.count());
    }

    /**
     * Reads what {@link #writeTo} wrote. Never throws, whatever the tag holds — save data is untrusted, because
     * {@code /data merge}, uploaded schematics and crafted block entity data all reach it:
     * <ul>
     * <li><b>neither</b> {@value #AMOUNT_TAG} <b>nor</b> {@value #FLUID_TAG}: the contents are left exactly as they
     * are, because such a tag says nothing about them at all — see below;</li>
     * <li>an {@value #AMOUNT_TAG} at or below 0, or a {@value #FLUID_TAG} that cannot be decoded (the fluid's mod was
     * removed): the bay is empty, which is vanilla's own answer for an unreadable container entry;</li>
     * <li>an amount above {@link FluidBayTier#MAX_CAPACITY_MILLIBUCKETS}, which <b>no</b> configuration can produce:
     * clamped, and logged as the fluid loss it is;</li>
     * <li>an amount above this bay's current capacity, which a lowered config legitimately produces: <b>kept in
     * full</b> and logged once. The bay accepts nothing until it drains, so lowering a capacity never destroys a drop
     * of fluid.</li>
     * </ul>
     * There is no tank list to size, so a crafted amount allocates nothing at all.
     *
     * <h2>Why a tag carrying neither key leaves a <b>standing</b> bay alone</h2>
     * This is {@link RackBayHandler#readFrom}'s guard, for the same reason and against the same caller. A world load
     * always reads into a freshly built, already empty handler, so "leave it alone" and "empty it" are the same answer
     * there — and an empty bay writes neither key, which is what a world from before M30 and every bay of an idle tank
     * wall look like. The difference is the one caller that reads a tag into a bay that is <b>already standing with
     * fluid in it</b>: {@code BlockHelper.placeSchematicBlock} writes the new block state and then calls
     * {@code loadWithComponents} on whatever block entity is there. The bay's own block entity survives that write
     * ({@code IBE.onRemove} returns before {@code destroy()} when the block stays the same), so a Create schematic
     * print — the creative instant print, or a schematicannon with <i>Replace Block Entities</i> on — would reach this
     * method with a tag that carries only the filter and the priority and empty a full brass bay <b>in place</b>: no
     * {@code destroy()}, so not even the log line that a lost fluid is owed. {@link #clear()} stays the one deliberate
     * way to empty a bay, which is what {@code /setblock}, {@code /fill}, {@code /clone} and structure placement reach
     * through {@code Clearable.tryClear}.
     */
    public void readFrom(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains(AMOUNT_TAG) && !tag.contains(FLUID_TAG))
            return; // says nothing about the contents: a schematic print over a standing bay must not empty it
        int saved = tag.getInt(AMOUNT_TAG);
        Optional<FluidKey> key = saved > 0 ? FluidKey.loadFrom(tag, FLUID_TAG, registries) : Optional.empty();
        if (key.isEmpty()) {
            contents.clear();
            return;
        }
        int amount = saved;
        if (amount > FluidBayTier.MAX_CAPACITY_MILLIBUCKETS) {
            LOGGER.warn("Fluid bay at {} says it holds {} mB of {}, which no configuration allows; {} mB are lost",
                    rules.position(), amount, key.get(), amount - FluidBayTier.MAX_CAPACITY_MILLIBUCKETS);
            amount = FluidBayTier.MAX_CAPACITY_MILLIBUCKETS;
        }
        contents.restore(key.get(), amount);
        long capacity = rules.capacity();
        if (amount > capacity)
            LOGGER.warn("Fluid bay at {} holds {} mB of {}, more than the {} mB it is configured for; it keeps all of "
                    + "it and accepts nothing until it has drained", rules.position(), amount, key.get(), capacity);
    }

    // --- client packet -------------------------------------------------------------------------------------------

    /**
     * Writes the contents for a <b>client packet</b>: the fluid's registry <b>id</b> as a string under
     * {@value #STORED_FLUID_TAG} and the millibuckets under {@value #AMOUNT_TAG}, and nothing at all while the bay is
     * empty.
     * <p>
     * <b>A {@link FluidKey} must never cross this path</b> ({@code docs/warehouse-system.md} §3.1.1): a block entity's
     * update tag is part of <b>every</b> chunk packet, which a client reads with a 2 MB NBT quota, and a fluid's
     * component patch is not bounded by anything — a named potion fluid carries its whole name. An id and an int are a
     * fixed, small size whatever is in the bay, which is what lets the fill level be synced on every change with no
     * throttle at all. The visible consequence is worth knowing rather than discovering: a fluid whose components
     * differ from the plain one shows the plain fluid's name and colour on the client.
     * <p>
     * The key is deliberately not {@value #FLUID_TAG}: that one holds a {@link FluidKey} compound in a <b>save</b>, and
     * one name for two NBT types is how a reader ends up looking at the wrong tag.
     */
    public void writeClientPacket(CompoundTag tag) {
        FluidKey key = contents.stored();
        if (key == null || contents.count() <= 0)
            return;
        tag.putString(STORED_FLUID_TAG, FluidTypeSummaries.fluidId(key.getFluid()));
        tag.putInt(AMOUNT_TAG, contents.count());
    }

    /**
     * Reads what {@link #writeClientPacket} wrote. Never throws and is bounded: an unknown fluid (a server with a mod
     * this client does not have), {@code minecraft:empty}, an absent or non-positive amount and an amount beyond
     * {@link FluidBayTier#MAX_CAPACITY_MILLIBUCKETS} all read as the empty bay or the bound, so nothing a server sends
     * can make a client draw or allocate something impossible.
     */
    public void readClientPacket(CompoundTag tag) {
        int amount = tag.getInt(AMOUNT_TAG);
        Optional<Fluid> fluid = amount > 0 ? FluidTypeSummaries.fluidById(tag.getString(STORED_FLUID_TAG))
                : Optional.empty();
        if (fluid.isEmpty()) {
            contents.clear();
            return;
        }
        contents.restore(FluidKey.of(fluid.get()),
                Math.min(amount, FluidBayTier.MAX_CAPACITY_MILLIBUCKETS));
    }

    @Override
    public String toString() {
        return "FluidBayHandler" + contents;
    }

    /**
     * What <b>pipes</b> reach: the bay's handler with {@code drain} behind {@code storage.fluidBayPipeExtraction}
     * (M30, issue #21, D4).
     * <p>
     * Filling is never gated — a pump may always fill a bay — and neither is reading: {@link #getFluidInTank} answers
     * the truth whatever the config says, which is what lets a fluid census see a bay that refuses extraction at all
     * (and is exactly how Create's own gate behaves, {@code SmartFluidTankBehaviour.InternalFluidHandler} overriding
     * {@code fill} and both {@code drain}s and nothing else).
     * <p>
     * The gate is read <b>per call</b> and never cached, so a {@code /reload} of a per-world override takes effect at
     * once and no bay has to be told about it. The bay's own operations — a player's bucket, and later the crane's
     * container exchange — go through the ungated handler, which is {@code forceFill}'s pattern.
     */
    public static final class PipeView implements IFluidHandler {
        private final FluidBayHandler handler;
        private final BooleanSupplier extractionAllowed;

        public PipeView(FluidBayHandler handler, BooleanSupplier extractionAllowed) {
            this.handler = Objects.requireNonNull(handler, "handler");
            this.extractionAllowed = Objects.requireNonNull(extractionAllowed, "extractionAllowed");
        }

        @Override
        public int getTanks() {
            return handler.getTanks();
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return handler.getFluidInTank(tank);
        }

        @Override
        public int getTankCapacity(int tank) {
            return handler.getTankCapacity(tank);
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return handler.isFluidValid(tank, stack);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return handler.fill(resource, action);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return extractionAllowed.getAsBoolean() ? handler.drain(resource, action) : FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return extractionAllowed.getAsBoolean() ? handler.drain(maxDrain, action) : FluidStack.EMPTY;
        }

        @Override
        public String toString() {
            return "FluidBayPipeView[extraction=" + extractionAllowed.getAsBoolean() + ", " + handler + "]";
        }
    }
}
