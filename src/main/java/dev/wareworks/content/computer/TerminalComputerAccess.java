package dev.wareworks.content.computer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.item.ItemTypeSummaries;
import dev.wareworks.content.station.TerminalDoorRow;
import dev.wareworks.content.station.TerminalStatus;
import dev.wareworks.content.station.TerminalStockEntry;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.core.computer.ResultPage;
import dev.wareworks.core.job.RetrievalRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * What a computer may do at one warehouse terminal (issue #9, ADR-074, {@code docs/computercraft.md}): the logic of
 * the {@code wareworks_terminal} peripheral, without one CC: Tweaked type, so the GameTests drive it without CC.
 * <p>
 * <b>Server thread only.</b> CC: Tweaked runs every method of the peripheral on the server thread
 * ({@code mainThread = true}); a call from any other thread is a programming error and throws
 * {@link IllegalStateException}. Every call first checks the server config ({@code computers.computerAccess}) and that
 * the terminal still exists, and answers both with a {@link ComputerArgumentException}.
 * <p>
 * <b>What it reads.</b> The stock is the terminal's own {@code stockSnapshot()} — the very list the screen gets, with
 * every rule the screen obeys — cached for {@link dev.wareworks.content.station.WarehouseTerminalMenu#REFRESH_INTERVAL_TICKS}
 * ticks ({@link WarehouseTerminalBlockEntity#computerStockSnapshot()}), so a computer that pages through it every tick
 * costs what one open screen costs. Requests, doors and the status are read live.
 * <p>
 * <b>What it does.</b> {@link #request} is {@link WarehouseTerminalBlockEntity#requestFromComputer}: an ordinary
 * automation request to this terminal or one of its package doors, planned by the controller and carried by the crane.
 * Nothing here moves an item.
 * <p>
 * Every result is built fresh from plain Java types (maps, lists, strings, numbers, booleans) that CC: Tweaked turns
 * into Lua tables; lists become 1-based arrays. No map holds a {@code null} value.
 */
public final class TerminalComputerAccess {
    /** The peripheral type a computer finds the terminal by. */
    public static final String PERIPHERAL_TYPE = "wareworks_terminal";
    /** Most rows {@link #requests()} returns. */
    public static final int MAX_REQUEST_ROWS = 256;
    /** {@code destination} of a request delivered into this terminal's buffer. */
    public static final String HERE = "here";

    private final WarehouseTerminalBlockEntity terminal;

    public TerminalComputerAccess(WarehouseTerminalBlockEntity terminal) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    /** The terminal this access is for. */
    public WarehouseTerminalBlockEntity terminal() {
        return terminal;
    }

    // --- methods ---------------------------------------------------------------------------------------------------

    /**
     * {@code getStatus()}: whether the terminal belongs to a loaded warehouse, its state as a word
     * ({@link ComputerReplies#status}), the largest request and the page size.
     */
    public Map<String, Object> status() {
        check();
        TerminalStatus status = terminal.status();
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("connected", status.hasController());
        table.put("status", ComputerReplies.status(status.hasController(), status.controllerStatus()));
        if (status.hasController())
            table.put("aisle", String.valueOf(status.aisleLetter()));
        table.put("itemTypes", status.itemTypes());
        table.put("totalItems", status.totalItems());
        table.put("openRequests", status.openRequests());
        table.put("requestsHere", status.requestsHere());
        table.put("maxRequest", maxRequest());
        table.put("pageSize", ResultPage.MAX_LIMIT);
        return table;
    }

    /** One page of the stock list, as {@link #stock} returns it. */
    public record StockPage(List<TerminalStockEntry> entries, OptionalInt nextStart, boolean detailed) {
    }

    /**
     * {@code getStock([options])}: one page of the terminal's stock list, in the screen's order.
     *
     * @param options {@code start} (1-based, default 1), {@code limit} (1 to {@value ResultPage#MAX_LIMIT}, default
     *                {@value ResultPage#MAX_LIMIT}), {@code name} (an item id: only its variants), {@code detailed}
     *                (whether the peripheral adds CC's item details); {@code null} for all defaults
     */
    public StockPage stock(Map<?, ?> options) {
        check();
        int start = 1;
        int limit = ResultPage.MAX_LIMIT;
        Optional<String> name = Optional.empty();
        boolean detailed = false;
        if (options != null) {
            start = optionalInt(options, "start", start);
            limit = optionalInt(options, "limit", limit);
            Object id = options.get("name");
            if (id != null) {
                if (!(id instanceof String text))
                    throw new ComputerArgumentException("name must be an item id string");
                name = Optional.of(ComputerItemSelector.parse(text).id());
            }
            Object detail = options.get("detailed");
            if (detail != null) {
                if (!(detail instanceof Boolean flag))
                    throw new ComputerArgumentException("detailed must be a boolean");
                detailed = flag;
            }
        }
        List<TerminalStockEntry> rows = terminal.computerStockSnapshot();
        if (name.isPresent()) {
            String wanted = name.get();
            rows = rows.stream().filter(entry -> ItemTypeSummaries.itemId(entry.key().getItem()).equals(wanted))
                    .toList();
        }
        ResultPage page;
        try {
            page = ResultPage.of(start, limit, rows.size());
        } catch (IllegalArgumentException e) {
            throw new ComputerArgumentException(e.getMessage());
        }
        return new StockPage(page.slice(rows), page.nextStart(), detailed);
    }

    /**
     * {@code getItem(item)}: the stock row of one item, or empty when the terminal neither holds nor can produce it.
     *
     * @throws ComputerArgumentException for an unknown or ambiguous item
     */
    public Optional<TerminalStockEntry> item(Object item) {
        check();
        Optional<ItemKey> key = select(item);
        if (key.isEmpty())
            return Optional.empty();
        for (TerminalStockEntry entry : terminal.computerStockSnapshot()) {
            if (entry.key().equals(key.get()))
                return Optional.of(entry);
        }
        return Optional.empty();
    }

    /**
     * {@code request(item, amount [, door])}: an automation request through this terminal
     * ({@link WarehouseTerminalBlockEntity#requestFromComputer}).
     *
     * @param amount the Lua number as CC hands it over (not narrowed by CC: a fraction or a value beyond the int range
     *               is an error here rather than silently truncated or wrapped)
     * @param door {@code {x, y, z}} of one of this terminal's package doors, or {@code null} for here
     * @return Lua's return values: {@code true, {id, granted, pending, producing, merged}} on success, or
     *         {@code false, reason} (with the id of the item in the way as a third value when a production chain was
     *         refused because of it)
     * @throws ComputerArgumentException for an unknown or ambiguous item, an amount that is no whole number or lies
     *                                   outside 1 to {@code maxTerminalRequestAmount}, or a malformed door
     */
    public List<Object> request(Object item, Object amountArgument, Map<?, ?> door) {
        check();
        int max = maxRequest();
        int amount = wholeNumber(amountArgument, "amount");
        if (amount < 1 || amount > max)
            throw new ComputerArgumentException("amount must be between 1 and " + max + ", got " + amount);
        Optional<BlockPos> target = door == null ? Optional.empty() : Optional.of(doorPosition(door));
        Optional<ItemKey> key = select(item);
        if (key.isEmpty())
            return List.of(false, ComputerReplies.rejection(RequestRejection.NOT_IN_STOCK));
        RequestResult result = terminal.requestFromComputer(key.get(), amount, target);
        if (result.rejection().isPresent()) {
            String reason = ComputerReplies.rejection(result.rejection().get());
            return result.about().map(about -> List.<Object>of(false, reason, ItemTypeSummaries.itemId(about.getItem())))
                    .orElseGet(() -> List.of(false, reason));
        }
        RetrievalRequest<ItemKey, BlockPos> request = result.request().orElseThrow();
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("id", request.id().toString());
        table.put("granted", result.granted());
        table.put("pending", result.pending());
        table.put("producing", result.producing());
        table.put("merged", result.merged());
        return List.of(true, table);
    }

    /**
     * {@code getRequests()}: the open requests delivered to this terminal or to one of the package doors its screen
     * lists, oldest first, at most {@value #MAX_REQUEST_ROWS}.
     */
    public List<Map<String, Object>> requests() {
        check();
        Optional<WarehouseControllerBlockEntity> controller = terminal.controller();
        if (controller.isEmpty())
            return List.of();
        Set<BlockPos> destinations = destinations();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RetrievalRequest<ItemKey, BlockPos> request : controller.get().openRequests()) {
            if (rows.size() >= MAX_REQUEST_ROWS)
                break;
            if (destinations.contains(request.destination()))
                rows.add(requestTable(request));
        }
        return rows;
    }

    /**
     * {@code getRequest(id)}: one open request of this terminal's warehouse, or empty when it is no longer open
     * (delivered and cancelled cannot be told apart). Unlike {@link #requests()} it does not filter by destination: a
     * request this terminal accepted for a package door its screen does not list (more doors than the screen's rows,
     * or a door that dropped out of a listing build) must stay visible until it is through, and the id is only known
     * to whoever placed or saw the request anyway.
     *
     * @throws ComputerArgumentException if {@code id} is not a UUID
     */
    public Optional<Map<String, Object>> openRequest(String id) {
        check();
        UUID uuid;
        try {
            uuid = UUID.fromString(Objects.requireNonNull(id, "id"));
        } catch (IllegalArgumentException e) {
            throw new ComputerArgumentException("not a request id: " + id);
        }
        Optional<WarehouseControllerBlockEntity> controller = terminal.controller();
        if (controller.isEmpty())
            return Optional.empty();
        for (RetrievalRequest<ItemKey, BlockPos> request : controller.get().openRequests()) {
            if (request.id().equals(uuid))
                return Optional.of(requestTable(request));
        }
        return Optional.empty();
    }

    /** {@code getDoors()}: the package doors the terminal's screen lists, with what each still waits for. */
    public List<Map<String, Object>> doors() {
        check();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TerminalDoorRow row : terminal.doorRows().rows()) {
            Map<String, Object> table = position(row.pos());
            table.put("label", row.label());
            table.put("rack", row.rackAddress());
            table.put("state", ComputerReplies.doorState(row.state()));
            table.put("waiting", row.waiting());
            table.put("delivered", row.delivered());
            rows.add(table);
        }
        return rows;
    }

    // --- tables ----------------------------------------------------------------------------------------------------

    /**
     * The table of one stock row: {@code name}, {@code fingerprint}, {@code displayName}, {@code count} (stored),
     * {@code available} (what a computer may still be granted from the racks: not promised to a request and not held
     * back by a stock rule's reserve), {@code heldBack} (the part of the unpromised stock a reserve keeps from
     * computers), {@code producible}, {@code producibleAmount} and {@code maxCount}.
     */
    public Map<String, Object> entryTable(TerminalStockEntry entry) {
        Map<String, Object> table = new LinkedHashMap<>();
        ItemKey key = entry.key();
        table.put("name", ItemTypeSummaries.itemId(key.getItem()));
        table.put("fingerprint", ComputerItemSelector.fingerprint(key, level().registryAccess()));
        table.put("displayName", key.toStack(1).getHoverName().getString());
        table.put("count", entry.total());
        table.put("available", entry.availableToAutomation());
        table.put("heldBack", entry.ruleReserved());
        table.put("producible", entry.producible());
        table.put("producibleAmount", entry.producibleAmount());
        table.put("maxCount", key.getMaxStackSize());
        return table;
    }

    private Map<String, Object> requestTable(RetrievalRequest<ItemKey, BlockPos> request) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("id", request.id().toString());
        table.put("name", ItemTypeSummaries.itemId(request.key().getItem()));
        table.put("fingerprint", ComputerItemSelector.fingerprint(request.key(), level().registryAccess()));
        table.put("requested", request.requested());
        table.put("remaining", request.remaining());
        table.put("delivered", request.delivered());
        table.put("destination", request.destination().equals(terminal.getBlockPos()) ? HERE
                : position(request.destination()));
        return table;
    }

    private static Map<String, Object> position(BlockPos pos) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("x", pos.getX());
        table.put("y", pos.getY());
        table.put("z", pos.getZ());
        return table;
    }

    // --- checks and arguments --------------------------------------------------------------------------------------

    /** This terminal's position and the doors its screen lists: where {@link #requests()} looks. */
    private Set<BlockPos> destinations() {
        Set<BlockPos> destinations = new HashSet<>();
        destinations.add(terminal.getBlockPos());
        for (TerminalDoorRow row : terminal.doorRows().rows())
            destinations.add(row.pos());
        return destinations;
    }

    private Optional<ItemKey> select(Object item) {
        ComputerItemSelector.ItemArgument argument = ComputerItemSelector.parse(item);
        List<ItemKey> listed = terminal.computerStockSnapshot().stream().map(TerminalStockEntry::key).toList();
        return ComputerItemSelector.select(argument, listed, level().registryAccess());
    }

    /** The access rules every method starts with. */
    private void check() {
        if (!WareworksConfig.computerAccess())
            throw new ComputerArgumentException("computer access is disabled on this server");
        if (!(terminal.getLevel() instanceof ServerLevel level) || terminal.isRemoved())
            throw new ComputerArgumentException("terminal is gone");
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("a warehouse terminal is only used by computers on the server thread");
    }

    private ServerLevel level() {
        return (ServerLevel) terminal.getLevel();
    }

    private static int maxRequest() {
        return Math.max(1, WareworksConfig.maxTerminalRequestAmount());
    }

    private static int optionalInt(Map<?, ?> options, String name, int fallback) {
        Object value = options.get(name);
        if (value == null)
            return fallback;
        return wholeNumber(value, name);
    }

    private static int wholeNumber(Object value, String name) {
        if (!(value instanceof Number number))
            throw new ComputerArgumentException(name + " must be a number");
        double raw = number.doubleValue();
        if (!Double.isFinite(raw) || raw != Math.floor(raw) || raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE)
            throw new ComputerArgumentException(name + " must be a whole number");
        return (int) raw;
    }

    /** A door argument {@code {x, y, z}}. */
    private static BlockPos doorPosition(Map<?, ?> door) {
        Object x = door.get("x");
        Object y = door.get("y");
        Object z = door.get("z");
        if (x == null || y == null || z == null)
            throw new ComputerArgumentException("door must be a table {x=..., y=..., z=...}");
        return new BlockPos(wholeNumber(x, "door x"), wholeNumber(y, "door y"), wholeNumber(z, "door z"));
    }
}
