package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.util.LogThrottle;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.createmod.catnip.data.WorldAttached;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.world.chunk.ForcedChunkManager;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;
import net.neoforged.neoforge.common.world.chunk.TicketSet;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The one place that takes and releases NeoForge chunk tickets for Wareworks aisles (M19, issue #10, ADR-031).
 * <b>Server only, server thread only</b>, and the single writer of every ticket the mod ever holds. None of the state
 * below is thread safe, so every entry point that a client could reach — the two {@link LevelEvent} handlers, which are
 * posted for {@code ClientLevel}s on the render thread as well, and {@link #releaseOnInvalidate} — tests for a
 * {@link ServerLevel} before it touches anything, exactly as {@code WarehouseRegistry} does.
 * <p>
 * <b>The API.</b> One {@link TicketController} with the frozen id {@code wareworks:aisle}, registered unconditionally on
 * the mod bus ({@link RegisterTicketControllersEvent}); an unregistered controller makes {@code forceChunk} throw and
 * makes NeoForge strip its saved tickets with a WARN, so the id must never be renamed lightly. Tickets are taken with
 * {@code forceChunk(level, owner, x, z, true, false)}: the owner is the <b>warehouse controller's</b> block position, and
 * {@code ticking = false} on purpose. That boolean only gates inhabited time, natural mob spawning and random ticks
 * ({@code ServerChunkCache#tickChunks}); block entities, entities and scheduled block ticks all run at the level-31
 * ticket regardless. So a held chunk runs the crane, the controller, furnaces, funnels, belts and dropped items, but grows
 * no crops and spawns no mobs — this ships a warehouse loader, not a farm loader.
 * <p>
 * <b>Why nothing leaks.</b> NeoForge persists these tickets whether we want it or not, and that persistence <i>is</i> the
 * feature (a restart must not throw away an in-progress job), so the safety lives in the load path instead of a release on
 * shutdown — which could not work anyway: {@code saveAllChunks} runs <b>before</b> {@code LevelEvent.Unload}, and a crash
 * writes nothing at all. Four layers:
 * <ol>
 * <li>the {@link #validateTickets validation callback}, the only place NeoForge ever names our owners: it keeps at most
 * <b>one seed chunk</b> per owner (the owner's own) and drops everything else, or drops everything when the feature is
 * off;</li>
 * <li>the {@link #onServerTick seed watchdog}, which releases a seed no controller claimed within
 * {@value #SEED_GRACE_TICKS} ticks — the case the callback structurally cannot see, because a controller broken while the
 * server was down cannot be asked;</li>
 * <li>the owner's own lifecycle: {@code remove()} releases in the same tick, {@code invalidate()} releases unless the
 * server or the level is going down (during shutdown it must <b>not</b>, or the persistence the feature depends on would
 * be destroyed);</li>
 * <li>this one record, rebuilt exactly from {@link TicketHelper#getBlockTickets()} in the callback, so it cannot drift
 * from NeoForge's own data at load.</li>
 * </ol>
 * <b>Cost.</b> Taking is capped at {@value #TAKE_BUDGET_PER_TICK} chunks per controller tick, because
 * {@code forceChunk(add = true)} calls {@code level.getChunk} synchronously and can generate terrain. Nothing here is
 * per-tick work: the only game-bus listener that does anything is the watchdog, and its whole body starts by returning
 * when no seed is pending.
 */
public final class AisleChunkTickets {
    /** The frozen ticket controller id. Renaming it makes NeoForge drop existing holds and in-progress work with them. */
    public static final String CONTROLLER_PATH = "aisle";
    /** Chunks one controller may start forcing per tick ({@code forceChunk} loads a chunk synchronously). */
    public static final int TAKE_BUDGET_PER_TICK = 2;
    /** How long a reinstated seed may stay unclaimed before the watchdog releases it. */
    public static final int SEED_GRACE_TICKS = 100;
    /**
     * Refused owners remembered per level for the cap wake-up. An owner over this limit is not remembered and is
     * therefore <b>not</b> woken when a slot frees: it re-decides on its own next event (its work changing, its layout
     * changing, a config reload) or when its chunk loads again, because an aisle that holds nothing schedules no
     * periodic re-check at all ({@code WarehouseControllerBlockEntity.CHUNK_KEEP_RECHECK_TICKS}). Reaching that needs
     * more than {@value} aisles refused at the same time in one dimension; each of them still works exactly as it did
     * before M19 ({@link #wakeRefused} keeps the set free of dead entries, so the limit cannot silently fill up).
     */
    private static final int MAX_REMEMBERED_REFUSALS = 64;
    /**
     * Never {@code true}. Wareworks holds non-ticking tickets: block entities, entities and scheduled ticks run anyway,
     * and {@code true} would additionally enable random ticks and mob spawning in the held chunks.
     */
    private static final boolean TICKING = false;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Set once from the mod bus; {@code null} only in a JVM that never fired the registration event (unit tests). */
    @Nullable
    private static TicketController controller;

    /** Holders per level: controller position → what it holds. Cleared with the level, exactly like {@link WarehouseRegistry}. */
    private static final WorldAttached<Map<BlockPos, Holder>> HOLDERS = new WorldAttached<>(level -> new LinkedHashMap<>());
    /** Controllers of a level that wanted to hold and were refused by the level cap, so a freed slot can wake them. */
    private static final WorldAttached<Set<BlockPos>> REFUSED = new WorldAttached<>(level -> new LinkedHashSet<>());
    /** Reinstated seeds waiting for their controller to claim them; empty except for the first seconds after a load. */
    private static final List<Seed> SEEDS = new ArrayList<>();
    /** Levels that are going down: their block entities must not release, or the save would lose the holds. */
    private static final Set<LevelAccessor> UNLOADING = Collections.newSetFromMap(new WeakHashMap<>());
    /** The whole server is going down (see {@link #UNLOADING}). */
    private static boolean shuttingDown;
    /**
     * Bumped whenever the server config is (re)loaded, so every controller re-decides on its next tick instead of waiting
     * for its own periodic re-check — which is what makes switching the feature off take effect at once.
     */
    private static int configGeneration;

    private static final LogThrottle TICKET_FAILURES = new LogThrottle();

    private AisleChunkTickets() {
    }

    /** What one controller holds. Purely in memory; the tickets themselves are NeoForge's saved data. */
    private static final class Holder {
        private final LongSet held = new LongOpenHashSet();
        private long heldSince;
        private ChunkKeepReason reason = ChunkKeepReason.NONE;
        /** Held only because a collecting port has something pending (counts against the separate cap). */
        private boolean collectOnly;
        /** Reinstated from the save and not yet claimed by its controller ({@link #SEEDS}). */
        private boolean seed;
        /**
         * Came out of the save, whether or not its controller has claimed it yet. {@link #seed} is cleared by
         * {@link #claim} at the top of the controller's evaluation, before it decides; this one survives until the hold
         * is really extended, which is the moment worth one line in the log.
         */
        private boolean reinstated;
    }

    private record Seed(ServerLevel level, BlockPos owner, long deadline) {
    }

    /** One holding aisle, for {@code /wareworks chunks}. */
    public record Entry(BlockPos owner, int chunks, ChunkKeepReason reason, long heldTicks, boolean unclaimed) {
    }

    /**
     * Why a hold ended, for the one {@code INFO} line a release writes. Five very different things release a hold, and a
     * log that cannot tell them apart makes an ordinary idle release look like a leak (M19 review).
     */
    enum Cause {
        /** The aisle became idle, or a cap no longer allows the hold ({@link ChunkKeepDecision}). */
        DECIDED("the aisle decided to"),
        /** {@code maxHoldTicks} ran out, or an operator released it: it gave up on this work. */
        GAVE_UP("it gave up on its work"),
        /** An operator's {@code /wareworks chunks release}. */
        COMMAND("an operator released it"),
        /** The owner block entity went away ({@code remove()} or {@code invalidate()}). */
        OWNER_GONE("its warehouse controller went away"),
        /** A ticket reinstated from the save that no controller claimed in time. */
        UNCLAIMED_SEED("no warehouse controller claimed it");

        private final String text;

        Cause(String text) {
            this.text = text;
        }
    }

    /** Removes tickets during {@link #validate}; the real one delegates to NeoForge's {@link TicketHelper}. */
    public interface Remover {
        void removeAll(BlockPos owner);

        void remove(BlockPos owner, long chunk, boolean ticking);
    }

    // --- registration --------------------------------------------------------------------------------------------

    /** Registers the ticket controller and the config-generation listener on the mod bus. Call once. */
    public static void register(IEventBus modBus) {
        modBus.addListener(AisleChunkTickets::onRegisterTicketControllers);
        modBus.addListener(AisleChunkTickets::onConfigLoaded);
        modBus.addListener(AisleChunkTickets::onConfigReloaded);
    }

    /** Registers the lifecycle hooks on the NeoForge game event bus. Call once. */
    public static void registerHooks(IEventBus gameBus) {
        gameBus.addListener(AisleChunkTickets::onServerAboutToStart);
        gameBus.addListener(AisleChunkTickets::onServerStopping);
        gameBus.addListener(AisleChunkTickets::onServerStopped);
        gameBus.addListener(AisleChunkTickets::onLevelLoad);
        gameBus.addListener(AisleChunkTickets::onLevelUnload);
        gameBus.addListener(AisleChunkTickets::onServerTick);
    }

    private static void onRegisterTicketControllers(RegisterTicketControllersEvent event) {
        // Registered unconditionally, whatever the config says: a controller that is not registered has its saved
        // tickets stripped from the level with a WARN, which would silently drop in-progress work of a server that had
        // the feature on and turned it off for one start (ForcedChunkManager#readModForcedChunks).
        controller = new TicketController(Wareworks.asResource(CONTROLLER_PATH), AisleChunkTickets::validateTickets);
        event.register(controller);
    }

    private static void onConfigLoaded(ModConfigEvent.Loading event) {
        configGeneration++;
    }

    private static void onConfigReloaded(ModConfigEvent.Reloading event) {
        configGeneration++;
    }

    /** The number every controller compares against to notice a config change within one tick. */
    public static int configGeneration() {
        return configGeneration;
    }

    // --- server and level lifecycle ------------------------------------------------------------------------------

    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        // A single-player client's JVM outlives every integrated server it starts, and everything here is static.
        reset();
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        // From here on nothing may release: the chunk data is saved after this point and the holds have to survive into
        // it, so an in-progress job is still in progress after the restart (MinecraftServer#stopServer).
        shuttingDown = true;
        SEEDS.clear();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        reset();
    }

    // Both events are posted for CLIENT levels too, on the render thread: ClientLevel's constructor posts Load, and
    // Minecraft#setLevel / #clearLevel / #disconnect post Unload while the integrated server is still ticking. Nothing
    // in this class is thread safe (a WeakHashMap-backed set, two WorldAttached maps whose get() puts on a miss, a plain
    // ArrayList), so a client level must never reach it - the same guard WarehouseRegistry has at every entry point.
    private static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel))
            return;
        UNLOADING.remove(event.getLevel());
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel))
            return;
        LevelAccessor level = event.getLevel();
        UNLOADING.add(level);
        HOLDERS.get(level).clear();
        REFUSED.get(level).clear();
        SEEDS.removeIf(seed -> seed.level() == level);
    }

    private static void reset() {
        shuttingDown = false;
        SEEDS.clear();
        UNLOADING.clear();
    }

    /** The seed watchdog: releases every reinstated ticket no warehouse controller claimed in time. */
    private static void onServerTick(ServerTickEvent.Post event) {
        if (SEEDS.isEmpty())
            return; // the whole listener self-disables a few seconds after a load
        Iterator<Seed> pending = SEEDS.iterator();
        while (pending.hasNext()) {
            Seed seed = pending.next();
            Holder holder = HOLDERS.get(seed.level()).get(seed.owner());
            if (holder == null || !holder.seed) {
                pending.remove(); // claimed by its controller, or already released
                continue;
            }
            if (seed.level().getGameTime() < seed.deadline())
                continue;
            // No controller ticked there: the block was removed while the server was down (world edit, another mod, a
            // rollback). This line is the evidence an operator needs.
            LOGGER.warn("Releasing the reinstated chunk ticket of {} in {}: no warehouse controller claimed it within "
                    + "{} ticks", seed.owner(), seed.level().dimension().location(), SEED_GRACE_TICKS);
            release(seed.level(), seed.owner(), Cause.UNCLAIMED_SEED, true);
            pending.remove();
        }
    }

    // --- the load path -------------------------------------------------------------------------------------------

    /** NeoForge hands us our saved tickets once per level, before any of their chunks is loaded. */
    private static void validateTickets(ServerLevel level, TicketHelper helper) {
        validate(level, helper.getBlockTickets(), new Remover() {
            @Override
            public void removeAll(BlockPos owner) {
                helper.removeAllTickets(owner);
            }

            @Override
            public void remove(BlockPos owner, long chunk, boolean ticking) {
                helper.removeTicket(owner, chunk, ticking);
            }
        });
    }

    /**
     * Decides which of the saved tickets survive a load, and rebuilds {@link #HOLDERS} from what does.
     * <p>
     * This runs <b>before</b> any chunk of ours is loaded, so it cannot ask a controller whether it still has work.
     * Therefore each owner keeps exactly <b>one</b> chunk — its own — as a seed: loading that one chunk loads the
     * controller, whose first tick either extends the hold to the full footprint (the work survived) or releases the seed
     * (the work is gone). Work that is gone costs one chunk for a handful of ticks and nothing after that.
     * <p>
     * Owners are sorted by {@code (x, z, y)}, so which owners survive a cap is reproducible across restarts.
     * <p>
     * Separated from {@link #validateTickets} on purpose: {@link TicketHelper}'s constructor is package-private, so this
     * seam is the only way a test can drive the load path without restarting a server.
     */
    public static void validate(ServerLevel level, Map<BlockPos, TicketSet> tickets, Remover remover) {
        int cap = WareworksConfig.maxTicketedAislesPerLevel();
        Map<BlockPos, Holder> holders = HOLDERS.get(level);
        holders.clear();
        REFUSED.get(level).clear();
        SEEDS.removeIf(seed -> seed.level() == level);
        if (tickets.isEmpty())
            return;
        List<BlockPos> owners = new ArrayList<>(tickets.keySet());
        owners.sort(Comparator.<BlockPos>comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)
                .thenComparingInt(BlockPos::getY));
        long now = level.getGameTime();
        int kept = 0;
        int dropped = 0;
        for (BlockPos owner : owners) {
            TicketSet set = tickets.get(owner);
            long seedChunk = ChunkPos.asLong(owner);
            // Feature off (also the fallback while the config is not loaded, which is the safe direction): turning the
            // setting off cleans the save.
            boolean keep = cap > 0 && kept < cap && set.nonTicking().contains(seedChunk);
            if (!keep) {
                remover.removeAll(owner);
                dropped++;
                continue;
            }
            LongIterator saved = set.nonTicking().iterator();
            while (saved.hasNext()) {
                long chunk = saved.nextLong();
                if (chunk != seedChunk)
                    remover.remove(owner, chunk, false);
            }
            // Wareworks never takes a ticking ticket, so one under our id is from a hand-edited save.
            LongIterator ticking = set.ticking().iterator();
            while (ticking.hasNext())
                remover.remove(owner, ticking.nextLong(), true);
            Holder holder = new Holder();
            holder.held.add(seedChunk);
            holder.heldSince = now;
            holder.seed = true;
            holder.reinstated = true;
            BlockPos key = owner.immutable();
            holders.put(key, holder);
            SEEDS.add(new Seed(level, key, now + SEED_GRACE_TICKS));
            kept++;
        }
        if (dropped > 0)
            LOGGER.warn("Dropped the saved chunk tickets of {} warehouse aisle(s) in {}: {}", dropped,
                    level.dimension().location(),
                    cap > 0 ? "over the configured limit of " + cap + " aisles" : "chunk loading is switched off");
        if (kept > 0)
            LOGGER.info("Reinstated one seed chunk for {} warehouse aisle(s) in {}; each one is released again within {} "
                    + "ticks unless its aisle still has work", kept, level.dimension().location(), SEED_GRACE_TICKS);
    }

    /** Whether the hold of {@code owner} came out of the save and was not claimed by its controller yet. */
    static boolean isUnclaimed(ServerLevel level, BlockPos owner) {
        Holder holder = HOLDERS.get(level).get(owner);
        return holder != null && holder.seed;
    }

    /** The controller at {@code owner} has decided about its hold: the seed is claimed, so the watchdog leaves it alone. */
    static void claim(ServerLevel level, BlockPos owner) {
        Holder holder = HOLDERS.get(level).get(owner);
        if (holder != null)
            holder.seed = false;
    }

    // --- taking and releasing ------------------------------------------------------------------------------------

    /**
     * Holds exactly {@code footprint} for {@code owner}: forces up to {@value #TAKE_BUDGET_PER_TICK} missing chunks and
     * unforces every held chunk that is no longer wanted (which is how a shrinking aisle shrinks its hold).
     *
     * @param footprint interleaved chunk {@code x, z} pairs in a stable order ({@link AisleChunkSpan#chunks})
     * @return {@code true} once the whole footprint is held
     */
    static boolean hold(ServerLevel level, BlockPos owner, int[] footprint, ChunkKeepReason reason, boolean collectOnly,
            long now) {
        if (footprint.length == 0) {
            release(level, owner, Cause.DECIDED, true);
            return false;
        }
        Map<BlockPos, Holder> holders = HOLDERS.get(level);
        BlockPos key = owner.immutable();
        Holder holder = holders.get(key);
        boolean fresh = holder == null;
        if (fresh) {
            holder = new Holder();
            holder.heldSince = now;
            holders.put(key, holder);
        }
        boolean claimedSeed = !fresh && holder.reinstated;
        holder.reinstated = false;
        holder.reason = reason;
        holder.collectOnly = collectOnly;
        holder.seed = false;
        REFUSED.get(level).remove(key);

        LongSet wanted = new LongOpenHashSet(footprint.length / 2);
        for (int i = 0; i < footprint.length; i += 2)
            wanted.add(ChunkPos.asLong(footprint[i], footprint[i + 1]));
        LongIterator held = holder.held.iterator();
        while (held.hasNext()) {
            long chunk = held.nextLong();
            if (!wanted.contains(chunk) && unforce(level, key, chunk))
                held.remove();
        }
        int budget = TAKE_BUDGET_PER_TICK;
        boolean complete = true;
        for (int i = 0; i < footprint.length; i += 2) {
            long chunk = ChunkPos.asLong(footprint[i], footprint[i + 1]);
            if (holder.held.contains(chunk))
                continue;
            if (budget <= 0) {
                complete = false;
                continue;
            }
            budget--;
            if (force(level, key, chunk))
                holder.held.add(chunk);
            else
                complete = false;
        }
        if (holder.held.isEmpty()) {
            holders.remove(key);
            return false;
        }
        // Every hold announces its whole footprint once, and the release line names the same number, so the two
        // reconcile. Not what is forced in this first tick: taking is budgeted over a few ticks and no later call is
        // "fresh", so the count of the moment would always understate the hold (M19 review). The second line is the
        // reinstated case, where the holder already exists as a one-chunk seed and "fresh" is false.
        if (fresh)
            LOGGER.info("Aisle at {} in {} starts holding {} chunk(s) loaded ({}, at most {} per tick)", key,
                    level.dimension().location(), footprint.length / 2, reason.name(), TAKE_BUDGET_PER_TICK);
        else if (claimedSeed)
            LOGGER.info("Aisle at {} in {} claimed its reinstated chunk ticket: its work survived, so it extends the "
                    + "hold to {} chunk(s) ({})", key, level.dimension().location(), footprint.length / 2,
                    reason.name());
        return complete;
    }

    /** Lets every chunk of {@code owner} go. Safe to call when it holds nothing. */
    static void release(ServerLevel level, BlockPos owner, Cause cause) {
        release(level, owner, cause, true);
    }

    /**
     * Lets every chunk of {@code owner} go.
     *
     * @param wake whether the aisles this level's cap refused may take the freed slot at once. {@code false} for the
     *             operator's {@code release all}, which has to leave the dimension with <b>nothing</b> held rather than
     *             handing the freed slots straight to the next aisles in the queue (M19 review).
     */
    private static void release(ServerLevel level, BlockPos owner, Cause cause, boolean wake) {
        Map<BlockPos, Holder> holders = HOLDERS.get(level);
        Holder holder = holders.get(owner);
        if (holder == null)
            return;
        int count = holder.held.size();
        LongIterator held = holder.held.iterator();
        while (held.hasNext())
            unforce(level, owner, held.nextLong());
        holders.remove(owner);
        LOGGER.info("Aisle at {} in {} released its {} held chunk(s): {}", owner, level.dimension().location(), count,
                cause.text);
        if (wake)
            wakeRefused(level);
    }

    /**
     * Release path of {@code invalidate()}: a block entity that is going away releases, <b>unless</b> the server or the
     * level is going down. A runtime chunk unload while we hold a ticket is pathological — our own ticket keeps the chunk
     * loaded — so this is a safety net; during a shutdown it must not fire, or the save would lose every hold and with it
     * every in-progress job.
     */
    static void releaseOnInvalidate(Level level, BlockPos owner) {
        // instanceof first: UNLOADING is not thread safe and a client block entity must not touch it at all.
        if (!(level instanceof ServerLevel serverLevel) || shuttingDown || UNLOADING.contains(level))
            return;
        release(serverLevel, owner, Cause.OWNER_GONE, true);
    }

    /** Real removal of the owner block: its tickets go in the same tick, whatever else is happening. */
    static void releaseOnRemove(Level level, BlockPos owner) {
        if (level instanceof ServerLevel serverLevel)
            release(serverLevel, owner, Cause.OWNER_GONE, true);
    }

    /** Remembers that the controller at {@code owner} wanted to hold and a cap said no. */
    static void refuse(ServerLevel level, BlockPos owner) {
        Set<BlockPos> refused = REFUSED.get(level);
        if (refused.size() < MAX_REMEMBERED_REFUSALS || refused.contains(owner))
            refused.add(owner.immutable());
    }

    /** The controller at {@code owner} no longer wants to hold. */
    static void forget(ServerLevel level, BlockPos owner) {
        REFUSED.get(level).remove(owner);
    }

    /** A slot came free: every controller that was refused re-decides on its next tick, instead of at some poll. */
    private static void wakeRefused(ServerLevel level) {
        Set<BlockPos> refused = REFUSED.get(level);
        if (refused.isEmpty())
            return;
        for (BlockPos pos : List.copyOf(refused)) {
            // A controller whose chunk is away is not ticking and cannot be woken - and it does not need to be: a block
            // entity decides again on its first tick after a load. Dropping it keeps the bounded set free for aisles
            // that are really waiting, instead of filling it with dead entries until the level unloads (M19 review).
            // isLoaded first: Level#getBlockEntity resolves its chunk, which would load (and generate) one from here.
            if (!level.isLoaded(pos)) {
                refused.remove(pos);
                continue;
            }
            if (level.getBlockEntity(pos) instanceof WarehouseControllerBlockEntity waiting && !waiting.isRemoved())
                waiting.markChunkKeepDirty();
            else
                refused.remove(pos);
        }
    }

    // --- queries -------------------------------------------------------------------------------------------------

    /** Whether the level cap leaves room for {@code owner} (a holder counts itself out, so it never releases itself). */
    static boolean levelCapAllows(ServerLevel level, BlockPos owner) {
        int cap = WareworksConfig.maxTicketedAislesPerLevel();
        if (cap <= 0)
            return false;
        Map<BlockPos, Holder> holders = HOLDERS.get(level);
        return holders.size() - (holders.containsKey(owner) ? 1 : 0) < cap;
    }

    /** Whether the separate collect-hold cap leaves room for {@code owner}. */
    static boolean collectCapAllows(ServerLevel level, BlockPos owner) {
        int cap = WareworksConfig.maxCollectHoldAislesPerLevel();
        if (cap <= 0)
            return false;
        int others = 0;
        for (Map.Entry<BlockPos, Holder> entry : HOLDERS.get(level).entrySet()) {
            if (entry.getValue().collectOnly && !entry.getKey().equals(owner))
                others++;
        }
        return others < cap;
    }

    /** How many chunks the aisle at {@code owner} holds right now (0 when it holds none, also on a client). */
    public static int heldChunkCount(Level level, BlockPos owner) {
        if (!(level instanceof ServerLevel))
            return 0;
        Holder holder = HOLDERS.get(level).get(owner);
        return holder == null ? 0 : holder.held.size();
    }

    /** The chunk keys the aisle at {@code owner} holds, for tests and the operator listing. */
    public static LongSet heldChunks(Level level, BlockPos owner) {
        if (!(level instanceof ServerLevel))
            return new LongOpenHashSet();
        Holder holder = HOLDERS.get(level).get(owner);
        return holder == null ? LongSet.of() : new LongOpenHashSet(holder.held);
    }

    /** The tick the hold of {@code owner} began, or {@link ChunkKeepDecision#NOT_SET}. */
    static long heldSince(ServerLevel level, BlockPos owner) {
        Holder holder = HOLDERS.get(level).get(owner);
        return holder == null ? ChunkKeepDecision.NOT_SET : holder.heldSince;
    }

    /** Every holding aisle of {@code level}, in the order they started holding. */
    public static List<Entry> entries(ServerLevel level) {
        long now = level.getGameTime();
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<BlockPos, Holder> entry : HOLDERS.get(level).entrySet()) {
            Holder holder = entry.getValue();
            entries.add(new Entry(entry.getKey(), holder.held.size(), holder.reason,
                    Math.max(0L, now - holder.heldSince), holder.seed));
        }
        return entries;
    }

    /**
     * How many distinct chunks are force-loaded by <b>block</b> tickets in {@code level}, over every mod
     * ({@code ForcedChunksSavedData#getBlockForcedChunks}) — <b>not</b> a total of everything force-loaded there, see
     * {@link #forcedChunkCount}. This is the number directly comparable with the mod's own record, because Wareworks
     * takes block tickets, and a discrepancy between the two is exactly what a leak looks like.
     */
    public static int rawBlockForcedChunkCount(ServerLevel level) {
        ForcedChunksSavedData data = level.getDataStorage().get(ForcedChunksSavedData.factory(), "chunks");
        if (data == null)
            return 0;
        LongSet chunks = new LongOpenHashSet();
        addAll(chunks, data.getBlockForcedChunks());
        return chunks.size();
    }

    /**
     * How many distinct chunks are force-loaded in {@code level} <b>in total</b>: block tickets
     * ({@link #rawBlockForcedChunkCount}), entity tickets and vanilla's own {@code /forceload} set. Those are three
     * independent stores in {@link ForcedChunksSavedData} — {@code getChunks()} is the vanilla {@code LongSet},
     * {@code getBlockForcedChunks()} and {@code getEntityForcedChunks()} are NeoForge's per-owner trackers — so the
     * block-ticket number above is emphatically not a total, and {@code /wareworks chunks} prints both (M19 review).
     */
    public static int forcedChunkCount(ServerLevel level) {
        ForcedChunksSavedData data = level.getDataStorage().get(ForcedChunksSavedData.factory(), "chunks");
        if (data == null)
            return 0;
        LongSet chunks = new LongOpenHashSet(data.getChunks());
        addAll(chunks, data.getBlockForcedChunks());
        addAll(chunks, data.getEntityForcedChunks());
        return chunks.size();
    }

    private static void addAll(LongSet chunks, ForcedChunkManager.TicketTracker<?> tracker) {
        for (LongSet owned : tracker.getChunks().values())
            chunks.addAll(owned);
        for (LongSet owned : tracker.getTickingChunks().values())
            chunks.addAll(owned);
    }

    /**
     * Releases the hold of one aisle by operator command and tells its controller to give up, so it does not take again
     * on its next tick. A controller in an unloaded chunk cannot be told; it re-decides when it loads.
     * <p>
     * Only an aisle that really held is told to give up: the coordinates come from a listing that spans every dimension
     * while the command acts on the sender's one, so a controller that happens to stand at the same coordinates in
     * another dimension must not be armed by mistake (M19 review).
     *
     * @return whether anything was held there
     */
    public static boolean releaseByCommand(ServerLevel level, BlockPos owner) {
        return releaseByCommand(level, owner, true);
    }

    private static boolean releaseByCommand(ServerLevel level, BlockPos owner, boolean wake) {
        if (!HOLDERS.get(level).containsKey(owner))
            return false;
        // isLoaded first, so asking does not load a chunk. A controller that is not loaded cannot be told; it decides
        // again when it loads, which is the documented limit of this command.
        if (level.isLoaded(owner) && level.getBlockEntity(owner) instanceof WarehouseControllerBlockEntity waiting)
            waiting.giveUpChunkKeep();
        release(level, owner, Cause.COMMAND, wake);
        return true;
    }

    /**
     * Releases every hold of {@code level} by operator command; returns how many aisles let go.
     * <p>
     * Unlike {@link #releaseByCommand}, this leaves the dimension with <b>nothing</b> held: the aisles a cap had refused
     * are told to give up as well, instead of being handed the freed slots within a tick or two — which would answer
     * "released N aisles" and then show N holders again under different owners (M19 review).
     */
    public static int releaseAllByCommand(ServerLevel level) {
        List<BlockPos> owners = new ArrayList<>(HOLDERS.get(level).keySet());
        for (BlockPos owner : owners)
            releaseByCommand(level, owner, false);
        Set<BlockPos> refused = REFUSED.get(level);
        for (BlockPos pos : List.copyOf(refused)) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof WarehouseControllerBlockEntity waiting
                    && !waiting.isRemoved())
                waiting.giveUpChunkKeep();
        }
        refused.clear();
        return owners.size();
    }

    /** How many aisles of {@code level} hold chunks right now. */
    public static int holdingAisleCount(ServerLevel level) {
        return HOLDERS.get(level).size();
    }

    /**
     * Every chunk the mod believes it holds in {@code level}, over all its aisles (a chunk two aisles both hold counts
     * once). Compared with {@link #rawBlockForcedChunkCount} this is the leak check: on a server where no other mod holds
     * block tickets the two numbers must be equal, and a ticket that outlived its owner shows up as a difference.
     */
    public static LongSet allHeldChunks(ServerLevel level) {
        LongSet chunks = new LongOpenHashSet();
        for (Holder holder : HOLDERS.get(level).values())
            chunks.addAll(holder.held);
        return chunks;
    }

    // --- the two calls that really touch NeoForge ----------------------------------------------------------------

    private static boolean force(ServerLevel level, BlockPos owner, long chunk) {
        TicketController ticketController = controller;
        if (ticketController == null)
            return false;
        try {
            ticketController.forceChunk(level, owner, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), true, TICKING);
            return true;
        } catch (RuntimeException e) {
            if (TICKET_FAILURES.tryLog(level.getGameTime()))
                LOGGER.error("Aisle at {} could not hold the chunk {}", owner, new ChunkPos(chunk), e);
            return false;
        }
    }

    private static boolean unforce(ServerLevel level, BlockPos owner, long chunk) {
        TicketController ticketController = controller;
        if (ticketController == null)
            return true; // nothing was ever taken
        try {
            ticketController.forceChunk(level, owner, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false, TICKING);
            return true;
        } catch (RuntimeException e) {
            if (TICKET_FAILURES.tryLog(level.getGameTime()))
                LOGGER.error("Aisle at {} could not release the chunk {}", owner, new ChunkPos(chunk), e);
            return false;
        }
    }
}
