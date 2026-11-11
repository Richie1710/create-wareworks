package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;

import dev.wareworks.content.item.ItemKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The Robo Bee flight ledger of the {@code robobee} scenario (dev tooling, ADR-014, ADR-074): the only class that touches
 * Create Mobile Packages (CMP), and only by reflection, so nothing in the mod or the harness is compiled against it.
 * <p>
 * A bee is virtual: CMP keeps every flying bee as a {@code VirtualRobo} in a per-level {@code RoboManager}, and the box
 * it carries lives only there — in no inventory, entity or block {@link SceneItemCensus} can see. This ledger therefore
 * <ul>
 *   <li>adds every box in flight (as its contents) and one {@code robo_bee} per live bee to a census
 *       ({@link #census});</li>
 *   <li>runs on <b>every server tick</b> (a {@link ServerTickEvent.Post} listener, so no flight can slip between two
 *       polls of the harness) and records each loaded bee's departure (home port, address, contents) and arrival (the
 *       port it landed on), with the checks of the scenario: a box leaves only from a send port with an address that
 *       port sends, lands only in a receive port whose filter matches, a bee never hovers without a target for long,
 *       never vanishes with its box, and the bees of the network are conserved (bee slots plus bees in the air);</li>
 *   <li>keeps the sums per address, so a box sent twice or lost shows up as a wrong sum.</li>
 * </ul>
 * A violation found in the tick listener cannot throw there (it would crash the integrated server); it is kept and
 * thrown by the next {@link #check} of the scenario, which every server poll of the run calls.
 */
final class RoboBeeLedger {
    static final String MOD_ID = "create_mobile_packages";
    /** The CMP version the reflection targets were reviewed against. */
    static final String REVIEWED_VERSION = "0.7.8";
    static final ResourceLocation BEE_PORT = ResourceLocation.fromNamespaceAndPath(MOD_ID, "bee_port");
    static final ResourceLocation ROBO_BEE = ResourceLocation.fromNamespaceAndPath(MOD_ID, "robo_bee");
    /** The bee slot in a port's item capability (filter mode ALL): 18 package slots, then the bees. */
    static final int BEE_SLOT = 18;
    /** Ticks a bee with a box may hover without any valid target before the run calls it a misdelivery. */
    private static final int MAX_HOVER_TICKS = 200;

    /** What a port does in the scenario's network. */
    enum Role {
        /** Unpowered: pulls boxes from its Packager and sends them by bee. */
        SEND,
        /** Powered: keeps the boxes its filter matches and pushes them into its Packager. */
        RECEIVE
    }

    /** One port of the network the ledger watches. */
    record Port(String name, BlockPos pos, Role role, Set<String> sends) {
    }

    /** One bee flight with a box: out from {@code home}, and, once it landed, in at {@code arrivedAt}. */
    static final class Trip {
        final int number;
        final UUID robo;
        final String home;
        final String address;
        final List<ItemStack> contents;
        final long departed;
        @Nullable
        String arrivedAt;
        long arrived = -1L;

        Trip(int number, UUID robo, String home, String address, List<ItemStack> contents, long departed) {
            this.number = number;
            this.robo = robo;
            this.home = home;
            this.address = address;
            this.contents = contents;
            this.departed = departed;
        }
    }

    private final ServerLevel level;
    private final Map<BlockPos, Port> ports = new LinkedHashMap<>();
    private final Map<UUID, Trip> open = new HashMap<>();
    private final Map<UUID, Long> hovering = new HashMap<>();
    /** Bees seen alive in the last tick, with whether they carried a box. */
    private final Map<UUID, Boolean> alive = new HashMap<>();
    private final List<Trip> trips = new ArrayList<>();
    private final Map<String, Map<ItemKey, Long>> sentTo = new LinkedHashMap<>();
    private final Map<String, Map<ItemKey, Long>> arrivedAt = new LinkedHashMap<>();
    private final Consumer<ServerTickEvent.Post> listener = this::onServerTick;
    private int emptyFlights;
    private int expectedBees = -1;
    private int maxInFlight;
    private boolean recording;
    @Nullable
    private String failure;
    @Nullable
    private Consumer<ServerLevel> watcher;

    private RoboBeeLedger(ServerLevel level) {
        this.level = level;
    }

    // --- reflection (resolved once by verify) -----------------------------------------------------------------------

    private static Method managerGet;
    private static Field managerRobos;
    private static Method roboItemStack;
    private static Method roboCurrentPos;
    private static Method roboTarget;
    private static Field roboHome;

    /**
     * Fails the run with a clear line unless CMP is loaded and is the CMP whose members this ledger was reviewed
     * against, and unless its server config is what the scenario relies on (port-to-port on, no distance limit below
     * {@code minDistance}). Never continues without the ledger: a census that cannot see a box in flight proves nothing.
     */
    static String verify(int minDistance) {
        if (!ModList.get().isLoaded(MOD_ID))
            throw new VisualTestException("the robobee scenario needs Create Mobile Packages in the game, and it is not "
                    + "loaded: run it with -Pwareworks.cmp (tested with 1.21.1-" + REVIEWED_VERSION + ")");
        String version = ModList.get().getModContainerById(MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString()).orElse("?");
        for (ResourceLocation id : List.of(BEE_PORT, ROBO_BEE))
            if (BuiltInRegistries.ITEM.get(id) == Items.AIR)
                throw new VisualTestException("CMP " + version + " registers no item " + id);
        if (!BuiltInRegistries.BLOCK.containsKey(BEE_PORT))
            throw new VisualTestException("CMP " + version + " registers no block " + BEE_PORT);
        try {
            Class<?> manager = Class.forName("de.theidler.create_mobile_packages.robo.RoboManager");
            Class<?> robo = Class.forName("de.theidler.create_mobile_packages.robo.VirtualRobo");
            managerGet = manager.getMethod("get", ServerLevel.class);
            managerRobos = manager.getField("robos");
            roboItemStack = robo.getMethod("getItemStack");
            roboCurrentPos = robo.getMethod("getCurrentPos");
            roboTarget = robo.getMethod("getTarget");
            roboHome = robo.getDeclaredField("homePortPos");
            roboHome.setAccessible(true);
            Class<?> configs = Class.forName("de.theidler.create_mobile_packages.index.config.CMPConfigs");
            Object server = configs.getMethod("server").invoke(null);
            if (server == null)
                throw new VisualTestException("CMP's server config is not loaded");
            boolean portToPort = (Boolean) configValue(server, "portToPort");
            int maxDistance = (Integer) configValue(server, "beeMaxDistance");
            int speed = (Integer) configValue(server, "beeSpeed");
            if (!portToPort)
                throw new VisualTestException("CMP's server config has portToPort = false, so no port sends to another");
            if (maxDistance != -1 && maxDistance < minDistance)
                throw new VisualTestException("CMP's server config limits bees to beeMaxDistance = " + maxDistance
                        + " blocks, below the " + minDistance + " the factories are away");
            return "CMP " + version + " (portToPort=" + portToPort + " beeMaxDistance=" + maxDistance + " beeSpeed="
                    + speed + ")";
        } catch (ReflectiveOperationException | ClassCastException error) {
            throw new VisualTestException("CMP " + version + " differs from the reviewed " + REVIEWED_VERSION + ": "
                    + error);
        }
    }

    private static Object configValue(Object server, String name) throws ReflectiveOperationException {
        Object value = server.getClass().getField(name).get(server);
        return value.getClass().getMethod("get").invoke(value);
    }

    /** Whether the jar-in-jar library CMP needs is in the game (FML extracts it from the CMP jar). */
    static boolean nestedLibraryLoaded() {
        return ModList.get().isLoaded("create_factory_abstractions");
    }

    static Item roboBeeItem() {
        return BuiltInRegistries.ITEM.get(ROBO_BEE);
    }

    static Item beePortItem() {
        return BuiltInRegistries.ITEM.get(BEE_PORT);
    }

    // --- lifecycle --------------------------------------------------------------------------------------------------

    /** Starts a ledger on {@code level} and its tick listener; {@link #stop} removes the listener again. */
    static RoboBeeLedger start(ServerLevel level) {
        RoboBeeLedger ledger = new RoboBeeLedger(level);
        NeoForge.EVENT_BUS.addListener(ledger.listener);
        return ledger;
    }

    void stop() {
        NeoForge.EVENT_BUS.unregister(listener);
        recording = false;
        watcher = null;
    }

    void addPort(Port port) {
        ports.put(port.pos().immutable(), port);
    }

    List<Port> ports() {
        return List.copyOf(ports.values());
    }

    /** From now on the bees of the network must add up to {@code total} on every tick. */
    void expectBees(int total) {
        expectedBees = total;
    }

    /** From now on every flight is recorded and checked. */
    void record() {
        recording = true;
    }

    /** An extra check run on every server tick (the scenario's own watcher); a throw is kept as the failure. */
    void watchEveryTick(Consumer<ServerLevel> check) {
        watcher = check;
    }

    /** Throws the first violation any tick found. */
    void check() {
        if (failure != null)
            throw new VisualTestException(failure);
    }

    // --- the tick ---------------------------------------------------------------------------------------------------

    private void onServerTick(ServerTickEvent.Post event) {
        if (failure != null || event.getServer().overworld() != level)
            return;
        try {
            tick();
            Consumer<ServerLevel> extra = watcher;
            if (extra != null)
                extra.accept(level);
        } catch (RuntimeException error) {
            failure = error instanceof VisualTestException ? error.getMessage() : String.valueOf(error);
            LOGGER.error(PREFIX + "robobee: the per-tick check failed: {}", failure, error);
        }
    }

    private void tick() {
        Map<UUID, Object> robos = robos();
        long now = level.getGameTime();
        maxInFlight = Math.max(maxInFlight, robos.size());
        for (Map.Entry<UUID, Object> entry : robos.entrySet()) {
            UUID id = entry.getKey();
            Object robo = entry.getValue();
            ItemStack stack = stackOf(robo);
            Boolean carriedBefore = alive.put(id, !stack.isEmpty());
            if (carriedBefore == null) {
                if (stack.isEmpty())
                    emptyFlights++;
                else if (recording)
                    depart(id, robo, stack, now);
            } else if (carriedBefore && stack.isEmpty()) {
                arrive(id, robo, now);
            } else if (!carriedBefore && !stack.isEmpty()) {
                throw new VisualTestException("an empty bee " + id + " picked a box up in flight: " + stack);
            }
            if (!stack.isEmpty() && invoke(roboTarget, robo) == null) {
                long since = hovering.computeIfAbsent(id, key -> now);
                if (now - since > MAX_HOVER_TICKS)
                    throw new VisualTestException("a bee with the box '" + PackageItem.getAddress(stack) + "' "
                            + contentsOf(stack) + " hovers without a valid target since " + (now - since)
                            + " ticks at " + invoke(roboCurrentPos, robo));
            } else {
                hovering.remove(id);
            }
        }
        for (UUID gone : new ArrayList<>(alive.keySet())) {
            if (robos.containsKey(gone))
                continue;
            boolean carried = alive.remove(gone);
            hovering.remove(gone);
            if (carried)
                throw new VisualTestException("the bee " + gone + " vanished with its box " + open.get(gone));
        }
        if (expectedBees >= 0) {
            int bees = beesInPorts() + robos.size();
            if (bees != expectedBees)
                throw new VisualTestException("the bees are not conserved: " + beesInPorts() + " in ports + "
                        + robos.size() + " in the air = " + bees + ", expected " + expectedBees);
        }
    }

    private void depart(UUID id, Object robo, ItemStack stack, long now) {
        BlockPos homePos = (BlockPos) get(roboHome, robo);
        Port home = homePos == null ? null : ports.get(homePos);
        String address = PackageItem.getAddress(stack);
        List<ItemStack> contents = contentsOf(stack);
        if (home == null)
            throw new VisualTestException("a bee left with the box '" + address + "' " + contents
                    + " from a port the run does not know: " + homePos);
        if (home.role() != Role.SEND || !home.sends().contains(address))
            throw new VisualTestException("the " + home.role() + " port " + home.name() + " sent a box addressed '"
                    + address + "' (it may send only " + home.sends() + "): " + contents);
        Trip trip = new Trip(trips.size() + 1, id, home.name(), address, contents, now);
        trips.add(trip);
        open.put(id, trip);
        add(sentTo, address, contents);
        LOGGER.info(PREFIX + "robobee: CHECK PASS bee trip {} out '{}' from {}: {}", trip.number, address, home.name(),
                describe(contents));
    }

    private void arrive(UUID id, Object robo, long now) {
        Trip trip = open.remove(id);
        if (trip == null)
            return; // a bee that left before the ledger recorded: not part of the run
        Vec3 pos = (Vec3) invoke(roboCurrentPos, robo);
        Port port = portUnder(pos);
        if (port == null)
            throw new VisualTestException("bee trip " + trip.number + " ('" + trip.address + "') put its box down at "
                    + pos + ", which is no port of the run");
        PackagePortBlockEntity be = portEntity(port.pos());
        String filter = be == null ? "?" : be.addressFilter;
        if (port.role() != Role.RECEIVE || be == null || !PackageItem.matchAddress(trip.address, filter))
            throw new VisualTestException("misdelivery: bee trip " + trip.number + " with '" + trip.address + "' "
                    + describe(trip.contents) + " landed in the " + port.role() + " port " + port.name()
                    + " whose filter is '" + filter + "'");
        trip.arrivedAt = port.name();
        trip.arrived = now;
        add(arrivedAt, trip.address, trip.contents);
        LOGGER.info(PREFIX + "robobee: CHECK PASS bee trip {} arrived at {} after {} ticks ('{}' {})", trip.number,
                port.name(), now - trip.departed, trip.address, describe(trip.contents));
    }

    @Nullable
    private Port portUnder(Vec3 pos) {
        BlockPos at = BlockPos.containing(pos);
        for (int down = 0; down <= 2; down++) {
            Port port = ports.get(at.below(down));
            if (port != null)
                return port;
        }
        return null;
    }

    // --- reading ----------------------------------------------------------------------------------------------------

    /** Every box in flight as its contents, plus one {@code robo_bee} per bee in the air. */
    Map<ItemKey, Long> census() {
        Map<ItemKey, Long> counts = new HashMap<>();
        Map<UUID, Object> robos = robos();
        List<ItemStack> boxes = new ArrayList<>();
        for (Object robo : robos.values()) {
            ItemStack stack = stackOf(robo);
            if (!stack.isEmpty())
                boxes.add(stack.copy());
        }
        counts = SceneItemCensus.plusAll(counts, boxes);
        if (!robos.isEmpty())
            counts = SceneItemCensus.plus(counts, ItemKey.of(roboBeeItem()), robos.size());
        return counts;
    }

    int inFlight() {
        return robos().size();
    }

    int carrying() {
        int carrying = 0;
        for (Object robo : robos().values())
            if (!stackOf(robo).isEmpty())
                carrying++;
        return carrying;
    }

    int beesInPorts() {
        int bees = 0;
        for (Port port : ports.values())
            bees += beesIn(port.pos());
        return bees;
    }

    int beesIn(BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null || handler.getSlots() <= BEE_SLOT)
            return 0;
        ItemStack bees = handler.getStackInSlot(BEE_SLOT);
        return bees.is(roboBeeItem()) ? bees.getCount() : 0;
    }

    @Nullable
    PackagePortBlockEntity portEntity(BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PackagePortBlockEntity port ? port : null;
    }

    List<Trip> trips() {
        return List.copyOf(trips);
    }

    int openTrips() {
        return open.size();
    }

    int emptyFlights() {
        return emptyFlights;
    }

    int maxInFlight() {
        return maxInFlight;
    }

    /** What was sent to {@code address} so far, as contents. */
    Map<ItemKey, Long> sentTo(String address) {
        return Map.copyOf(sentTo.getOrDefault(address, Map.of()));
    }

    /** What arrived for {@code address} so far, as contents. */
    Map<ItemKey, Long> arrivedFor(String address) {
        return Map.copyOf(arrivedAt.getOrDefault(address, Map.of()));
    }

    private static void add(Map<String, Map<ItemKey, Long>> sums, String address, List<ItemStack> contents) {
        Map<ItemKey, Long> sum = sums.computeIfAbsent(address, key -> new HashMap<>());
        for (ItemStack stack : contents)
            sum.merge(ItemKey.of(stack), (long) stack.getCount(), Long::sum);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Object> robos() {
        Object manager = invoke(managerGet, null, level);
        return Map.copyOf((Map<UUID, Object>) Objects.requireNonNull(get(managerRobos, manager), "robos"));
    }

    private static ItemStack stackOf(Object robo) {
        ItemStack stack = (ItemStack) invoke(roboItemStack, robo);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    /** A box's contents, through the census's own rule (never {@code PackageItem#getContents}). */
    static List<ItemStack> contentsOf(ItemStack box) {
        List<ItemStack> contents = new ArrayList<>();
        Map<ItemKey, Long> counted = SceneItemCensus.plusAll(Map.of(), List.of(box));
        for (Map.Entry<ItemKey, Long> entry : counted.entrySet())
            contents.add(entry.getKey().toStack(entry.getValue().intValue()));
        return contents;
    }

    static String describe(List<ItemStack> contents) {
        Map<ItemKey, Long> counts = new HashMap<>();
        for (ItemStack stack : contents)
            counts.merge(ItemKey.of(stack), (long) stack.getCount(), Long::sum);
        return SceneItemCensus.describe(counts);
    }

    private static Object invoke(Method method, @Nullable Object owner, Object... arguments) {
        try {
            return method.invoke(owner, arguments);
        } catch (ReflectiveOperationException error) {
            throw new VisualTestException("cannot call CMP's " + method + ": " + error);
        }
    }

    private static Object get(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException error) {
            throw new VisualTestException("cannot read CMP's " + field + ": " + error);
        }
    }
}
