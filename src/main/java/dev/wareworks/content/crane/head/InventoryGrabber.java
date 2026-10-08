package dev.wareworks.content.crane.head;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.inventory.CapacityMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The MVP handling head: a grabber that carries item stacks between item handlers ({@code docs/stacker-crane.md} §6).
 * <p>
 * <b>Holding.</b> Items are kept as item keys with {@code int} amounts, not as {@code ItemStack}s, so amounts above one
 * stack (several {@code grabberStacks}) and above 99 need no special handling. Transfers split into stacks of at most the
 * key's max stack size.
 * <p>
 * <b>Transfers.</b> {@link #pick} extracts stack by stack with real calls until the amount is reached or the source gives
 * nothing, and verifies every extracted stack against the key; {@link #drop} inserts stack by stack until the target
 * refuses, and only the accepted amount leaves the head. Both loops are bounded ({@value #MAX_TRANSFER_CALLS} calls).
 * {@link #exchange} is neither: it gives up containers of one key and receives that many of another in a single
 * all-or-nothing step, and its own javadoc walks the conservation of that swap moment by moment.
 * <p>
 * <b>Persistence.</b> {@code {Items: [{Item: <ItemKey>, Count: int}]}}; {@code ItemStack.save} is never called and
 * saving never throws. Loading never throws and is bounded against crafted data (block entity data on items,
 * {@code /data}, structures, schematics): at most {@value #MAX_LOADED_ENTRIES} keys and {@value #MAX_LOADED_ITEMS} items
 * in total, far above any legitimate load (the carry limit is at most 1728).
 * <p>
 * Server thread only; clients receive a bounded summary of item ids from the crane.
 */
public final class InventoryGrabber implements HandlingHead {
    /** Upper bound for the distinct keys one {@link #load} creates. */
    public static final int MAX_LOADED_ENTRIES = 16;
    /** Upper bound for the items one {@link #load} creates. */
    public static final int MAX_LOADED_ITEMS = 4096;
    /** Upper bound for inventory calls of one pick or drop. */
    private static final int MAX_TRANSFER_CALLS = 1024;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ITEMS = "Items";
    private static final String ITEM = "Item";
    private static final String COUNT = "Count";

    private final Map<ItemKey, Integer> held = new LinkedHashMap<>();
    private final Runnable onChanged;

    /** @param onChanged called after every change through a transfer, spill or clear (not on {@link #load}) */
    public InventoryGrabber(Runnable onChanged) {
        this.onChanged = Objects.requireNonNull(onChanged, "onChanged");
    }

    /**
     * The carry limit from the server config: {@code min(grabberStacks · maxStackSize, grabberMaxItems)}
     * ({@code docs/warehouse-system.md} §7.1). Used by the grabber and by the controller's planner.
     */
    public static int carryLimitFor(ItemKey key) {
        return CapacityMath.carryLimit(Math.max(1, key.getMaxStackSize()), Math.max(1, WareworksConfig.grabberStacks()),
                Math.max(1, WareworksConfig.grabberMaxItems()));
    }

    @Override
    public int carryLimit(ItemKey key) {
        return carryLimitFor(Objects.requireNonNull(key, "key"));
    }

    @Override
    public int pick(TransferContext source, ItemKey key, int amount) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(key, "key");
        int picked = 0;
        for (int calls = 0; picked < amount && calls < MAX_TRANSFER_CALLS; calls++) {
            int want = Math.min(amount - picked, key.getMaxStackSize());
            ItemStack taken;
            try {
                taken = source.extract(key, want, false);
            } catch (RuntimeException e) {
                // A foreign inventory threw: keep what was really taken so far, so head and job stay in step.
                LOGGER.warn("Inventory at {} failed while extracting {}", source.position(), key, e);
                break;
            }
            if (taken.isEmpty())
                break;
            if (!key.matches(taken)) {
                returnOrSpill(source, taken);
                break;
            }
            if (taken.getCount() > want)
                returnOrSpill(source, taken.split(taken.getCount() - want));
            add(key, taken.getCount());
            picked += taken.getCount();
        }
        if (picked > 0)
            onChanged.run();
        return picked;
    }

    @Override
    public int drop(TransferContext target, ItemKey key, int amount) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(key, "key");
        int available = Math.min(amount, count(key));
        int delivered = 0;
        for (int calls = 0; delivered < available && calls < MAX_TRANSFER_CALLS; calls++) {
            int chunk = Math.min(available - delivered, key.getMaxStackSize());
            ItemStack remainder;
            try {
                remainder = target.insert(key.toStack(chunk), false);
            } catch (RuntimeException e) {
                // A foreign inventory threw: count only what was really accepted before.
                LOGGER.warn("Inventory at {} failed while inserting {}", target.position(), key, e);
                break;
            }
            int accepted = chunk - Math.min(chunk, Math.max(0, remainder.getCount()));
            if (accepted <= 0)
                break;
            delivered += accepted;
            if (accepted < chunk)
                break; // the target is full
        }
        if (delivered > 0) {
            remove(key, delivered);
            onChanged.run();
        }
        return delivered;
    }

    /**
     * The <b>container exchange</b>: {@code amount} held containers of {@code from} are given up at {@code target} and
     * that many of {@code to} are taken back, in one stop and one synchronous call (M30, issue #21, D1).
     *
     * <h2>The walk, moment by moment</h2>
     * One lava bucket on the head, a copper fluid bay holding {@code n} mB of lava, nothing else in play. The joint
     * census ({@code gametest.FluidCensus#assertConserved}) reads <i>one lava bucket</i> on the item side and
     * <i>1 000 + n</i> mB on the fluid side — 1 000 of them <b>inside the bucket</b>, because a container is a carrier
     * and not a place of its own.
     * <ol>
     * <li><b>The gates.</b> {@link ExchangeDecision#mayAsk} checks that the head really holds the containers it is
     * about to offer and that the swap is a swap. Nothing has moved; a refusal here is free.</li>
     * <li><b>The simulated answer.</b> {@link TransferContext#exchange} with {@code simulate} measures the container,
     * reads what emptying it yields and measures the bay's room. Measuring the container means <b>really draining a
     * throw-away probe</b>, because a simulated drain never updates {@code getContainer()} and would answer
     * "a lava bucket becomes a lava bucket" — the head would then swap a bucket for itself while the bay kept the
     * lava, i.e. 1 000 mB out of nothing. The probe is a fresh stack inside {@code FluidContainers} and is discarded;
     * the containers on this head are a key and a count and are never an {@code ItemStack} at all, so
     * <b>nothing in the world or on the head moves in this step</b>. {@link ExchangeDecision#planAgrees} then enforces
     * all-or-nothing while that is still free.</li>
     * <li><b>The real answer — the fluid moves.</b> The same measurement, then the bay's real fill. <b>This is the one
     * instant at which the invariant does not hold</b>: 1 000 mB is in the tank and the head still holds a <i>filled</i>
     * bucket. Nothing can observe it — there is no tick boundary, no save point, no chunk check and no effect queue
     * between that fill returning and step 4; the bay's own change notification runs inside it and reads the bay, never
     * the head. And a bay that accepted less than the whole amount drains exactly that much back out and answers
     * "refused" instead, so the window is closed by the location rather than left to this method.</li>
     * <li><b>The empty container arrives — the invariant holds again.</b> {@link ExchangeDecision#judge} on the real
     * answer, then {@code remove(from)} and {@code add(to)} with nothing in between and {@code onChanged} after both,
     * so the head is never seen having lost the filled bucket without having gained the empty one. The item census is
     * now one lava bucket poorer and one bucket richer, which is exactly what {@code ItemCensus#exchange} declares
     * (and verifies against the very routine used above, so a test cannot declare a swap the game would not make).
     * The fluid census is <b>unchanged</b>: the container half lost 1 000 mB and the tank half gained it.</li>
     * </ol>
     *
     * <h2>Every way it could create or destroy something, and what closes it</h2>
     * <ul>
     * <li><b>A tick boundary inside the swap.</b> There is none. Steps 1–4 are one synchronous call chain on the
     * server thread; this method neither queues an effect nor yields.</li>
     * <li><b>A simulate that lies about the container.</b> Closed by draining a real probe (step 2) — the single most
     * important line of the whole operation.</li>
     * <li><b>A simulate that lies about the room.</b> A bay's fill is a pure function of its contents and its
     * capacity and nothing runs between the two calls, so it cannot; where it did, step 3's roll-back refuses.</li>
     * <li><b>A full bay, a wrong fluid, a filter that refuses, a bay with less than one whole container of room.</b>
     * All of them are an empty simulated answer, i.e. a refusal before anything moved. The head keeps the filled
     * container and the caller reroutes it like any other undeliverable carry.</li>
     * <li><b>A container that refuses.</b> An item with no fluid capability, an <i>empty</i> container (which closes
     * the churn loop: an empty bucket is never exchanged into a bay), a per-call cap that hands back less than the
     * whole contents, a container that is not empty afterwards, a consumable whose empty form is nothing at all, a
     * handler answering with a stack of two — every one of them is refused by {@code FluidContainers} before the real
     * fill.</li>
     * <li><b>A location or a modded container that throws.</b> Caught. Out of the <b>simulated</b> call nothing had
     * moved. Out of the <b>real</b> call we do not know what moved, so the head is left exactly as it was: that cannot
     * lose an item, and if such a location really did take the fluid the joint census is what reports it. The bay in
     * this mod cannot reach this branch — its handler is ours and {@code FluidContainers} never throws.</li>
     * <li><b>An interrupted transfer.</b> A chunk unload, a save or a broken block cannot land between the two halves,
     * because there is no between. Afterwards the head holds the empty container and the bay holds the fluid, which is
     * a consistent state that survives a save as it stands.</li>
     * <li><b>Two keys in one head.</b> Prevented by all-or-nothing, and by {@link ExchangeDecision.Action#SALVAGE}
     * spilling the received containers at the location instead of holding them when a location contradicts its own
     * plan. Items end up on the ground, never deleted, and the head keeps exactly one key — which matters because
     * {@code CraneExecution.reconcileHeadWithJob} would otherwise throw the stranger on the ground at the dock
     * instead.</li>
     * <li><b>A location claiming more containers than the head holds.</b> Clamped, logged and salvaged; see
     * {@link ExchangeDecision#judge} for why no bookkeeping can repair that case and the census has to report it.</li>
     * </ul>
     */
    @Override
    public int exchange(TransferContext target, ItemKey from, ItemKey to, int amount) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!ExchangeDecision.mayAsk(amount, count(from), !from.equals(to)))
            return 0;
        TransferContext.ContainerExchange planned = ask(target, from, amount, true);
        if (planned == null
                || !ExchangeDecision.planAgrees(amount, planned.containers(), to.equals(planned.result())))
            return 0;
        // An empty answer means the location refused, and by its contract a refusal moved nothing. A location that
        // THREW answers the same way, and then nothing is known: the head is left as it was, which cannot lose an
        // item, and the joint census is what reports a location that really did take the fluid anyway.
        TransferContext.ContainerExchange done = ask(target, from, amount, false);
        if (done == null)
            return 0;
        ExchangeDecision.Outcome outcome = ExchangeDecision.judge(amount, count(from), done.containers(),
                to.equals(done.result()));
        return switch (outcome.action()) {
            case REFUSE -> 0;
            case EXCHANGE -> {
                remove(from, outcome.containers());
                add(to, outcome.containers());
                onChanged.run();
                yield outcome.containers();
            }
            case SALVAGE -> {
                LOGGER.warn("Location at {} simulated an exchange of {} x {} for {} and then really did {} x {} for "
                        + "{}; giving up the {} containers the head can account for and dropping what came back there,"
                        + " so the head keeps one key", target.position(), amount, from, to, done.containers(), from,
                        done.result(), outcome.containers());
                remove(from, outcome.containers());
                target.spill(done.result().toStack(outcome.containers()));
                onChanged.run();
                yield 0;
            }
        };
    }

    /**
     * One {@link TransferContext#exchange} call whose exceptions never reach the crane. A failure of the
     * <b>simulated</b> call means nothing moved; a failure of the <b>real</b> one means we do not know, and the caller
     * then leaves the head untouched (see {@link #exchange}).
     */
    @Nullable
    private static TransferContext.ContainerExchange ask(TransferContext target, ItemKey from, int amount,
            boolean simulate) {
        try {
            return target.exchange(from, amount, simulate).orElse(null);
        } catch (RuntimeException e) {
            LOGGER.warn("Location at {} failed while {} an exchange of {} x {}", target.position(),
                    simulate ? "simulating" : "performing", amount, from, e);
            return null;
        }
    }

    @Override
    public HeldItems held() {
        if (held.isEmpty())
            return HeldItems.EMPTY;
        List<HeldItems.Entry> entries = new ArrayList<>(held.size());
        held.forEach((key, count) -> entries.add(new HeldItems.Entry(key, count)));
        return new HeldItems(entries);
    }

    @Override
    public int count(ItemKey key) {
        return held.getOrDefault(Objects.requireNonNull(key, "key"), 0);
    }

    @Override
    public boolean isEmpty() {
        return held.isEmpty();
    }

    @Override
    public long spill(Level level, BlockPos pos, Predicate<ItemKey> which) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(which, "which");
        long dropped = 0;
        for (Map.Entry<ItemKey, Integer> entry : List.copyOf(held.entrySet())) {
            if (!which.test(entry.getKey()))
                continue;
            held.remove(entry.getKey());
            dropped += TransferContexts.spillAt(level, pos, entry.getKey().toStack(entry.getValue()));
        }
        if (dropped > 0)
            onChanged.run();
        return dropped;
    }

    @Override
    public void clear() {
        if (held.isEmpty())
            return;
        held.clear();
        onChanged.run();
    }

    @Override
    public CompoundTag save(HolderLookup.Provider registries) {
        ListTag items = new ListTag();
        for (Map.Entry<ItemKey, Integer> entry : held.entrySet()) {
            try {
                Tag keyTag = entry.getKey().save(registries);
                if (keyTag instanceof CompoundTag compound && compound.isEmpty()) {
                    LOGGER.warn("Could not save held items {} x{}", entry.getKey(), entry.getValue());
                    continue;
                }
                CompoundTag item = new CompoundTag();
                item.put(ITEM, keyTag);
                item.putInt(COUNT, entry.getValue());
                items.add(item);
            } catch (RuntimeException e) {
                LOGGER.warn("Could not save held items {}", entry.getKey(), e);
            }
        }
        CompoundTag tag = new CompoundTag();
        tag.put(ITEMS, items);
        return tag;
    }

    @Override
    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        held.clear();
        ListTag list = tag.getList(ITEMS, Tag.TAG_COMPOUND);
        int budget = MAX_LOADED_ITEMS;
        boolean truncated = false;
        for (int i = 0; i < list.size(); i++) {
            if (held.size() >= MAX_LOADED_ENTRIES || budget == 0) {
                truncated = true; // the rest is not even decoded
                break;
            }
            CompoundTag item = list.getCompound(i);
            try {
                int count = item.getInt(COUNT);
                if (count <= 0)
                    continue;
                Optional<ItemKey> key = ItemKey.load(registries, item.get(ITEM));
                if (key.isEmpty())
                    continue;
                int accepted = Math.min(count, budget);
                truncated |= accepted < count;
                budget -= accepted;
                held.merge(key.get(), accepted, InventoryGrabber::saturatedAdd);
            } catch (RuntimeException e) {
                LOGGER.warn("Skipping unreadable held items {}", item, e);
            }
        }
        if (truncated)
            LOGGER.warn("Handling head save holds more than {} item types or {} items; the rest was not loaded",
                    MAX_LOADED_ENTRIES, MAX_LOADED_ITEMS);
    }

    private void add(ItemKey key, int amount) {
        held.merge(key, amount, InventoryGrabber::saturatedAdd);
    }

    private void remove(ItemKey key, int amount) {
        int left = count(key) - amount;
        if (left > 0)
            held.put(key, left);
        else
            held.remove(key);
    }

    /** Gives a stack back to the source it came from; whatever does not fit is spilled there, never deleted. */
    private static void returnOrSpill(TransferContext source, ItemStack stack) {
        ItemStack rest;
        try {
            rest = source.insert(stack.copy(), false);
        } catch (RuntimeException e) {
            LOGGER.warn("Inventory at {} failed while taking back {}", source.position(), stack, e);
            rest = stack;
        }
        if (!rest.isEmpty()) {
            LOGGER.warn("Inventory at {} handed out {} that could not be given back; dropping it", source.position(),
                    rest);
            source.spill(rest);
        }
    }

    private static int saturatedAdd(int a, int b) {
        long sum = (long) a + b;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    @Override
    public String toString() {
        return "InventoryGrabber" + held;
    }
}
