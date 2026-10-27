package dev.wareworks.compat.computercraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dan200.computercraft.api.detail.VanillaDetailRegistries;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.wareworks.content.computer.ComputerArgumentException;
import dev.wareworks.content.computer.TerminalComputerAccess;
import dev.wareworks.content.station.TerminalStockEntry;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;

/**
 * The warehouse terminal as a CC: Tweaked peripheral, type {@value TerminalComputerAccess#PERIPHERAL_TYPE} (issue #9,
 * ADR-074, {@code docs/computercraft.md}).
 * <p>
 * A thin shell: each method reads its Lua arguments, calls {@link TerminalComputerAccess} — where the rules live,
 * free of CC types — and turns a {@link ComputerArgumentException} into a {@link LuaException}. The one thing only it
 * adds is CC's own item details ({@code detailed}), because they come from CC's API.
 * <p>
 * Every method runs on the server thread ({@code mainThread = true}), like Create's peripherals that touch a block
 * entity. The peripheral holds no state but its terminal and queues no events, so attaching and detaching need no
 * bookkeeping.
 */
public final class TerminalPeripheral implements IPeripheral {
    private final WarehouseTerminalBlockEntity terminal;
    private final TerminalComputerAccess access;

    public TerminalPeripheral(WarehouseTerminalBlockEntity terminal) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.access = new TerminalComputerAccess(terminal);
    }

    @Override
    public String getType() {
        return TerminalComputerAccess.PERIPHERAL_TYPE;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof TerminalPeripheral peripheral && peripheral.terminal == terminal;
    }

    @Override
    public Object getTarget() {
        return terminal;
    }

    /** {@code getStatus()}: see {@link TerminalComputerAccess#status()}. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() throws LuaException {
        try {
            return access.status();
        } catch (ComputerArgumentException e) {
            throw new LuaException(e.getMessage());
        }
    }

    /** {@code getStock([options])} returns {@code entries, nextStart}: see {@link TerminalComputerAccess#stock}. */
    @LuaFunction(mainThread = true)
    public final MethodResult getStock(IArguments arguments) throws LuaException {
        Map<?, ?> options = arguments.optTable(0).orElse(null);
        try {
            TerminalComputerAccess.StockPage page = access.stock(options);
            List<Map<String, Object>> entries = new ArrayList<>(page.entries().size());
            for (TerminalStockEntry entry : page.entries())
                entries.add(table(entry, page.detailed()));
            return page.nextStart().isPresent() ? MethodResult.of(entries, page.nextStart().getAsInt())
                    : MethodResult.of(entries);
        } catch (ComputerArgumentException e) {
            throw new LuaException(e.getMessage());
        }
    }

    /** {@code getItem(item [, detailed])} returns the item's row or nil: see {@link TerminalComputerAccess#item}. */
    @LuaFunction(mainThread = true)
    public final MethodResult getItem(IArguments arguments) throws LuaException {
        Object item = arguments.get(0);
        boolean detailed = arguments.optBoolean(1, false);
        try {
            Optional<TerminalStockEntry> entry = access.item(item);
            return entry.isPresent() ? MethodResult.of(table(entry.get(), detailed)) : MethodResult.of();
        } catch (ComputerArgumentException e) {
            throw new LuaException(e.getMessage());
        }
    }

    /**
     * {@code request(item, amount [, door])} returns {@code true, request} or {@code false, reason [, item]}: see
     * {@link TerminalComputerAccess#request}.
     */
    @LuaFunction(mainThread = true)
    public final MethodResult request(IArguments arguments) throws LuaException {
        Object item = arguments.get(0);
        // Raw, not getInt: CC's getInt truncates a fraction and wraps a value beyond the int range; the facade
        // rejects both.
        Object amount = arguments.get(1);
        Map<?, ?> door = arguments.optTable(2).orElse(null);
        try {
            return MethodResult.of(access.request(item, amount, door).toArray());
        } catch (ComputerArgumentException e) {
            throw new LuaException(e.getMessage());
        }
    }

    /** {@code getRequests()}: see {@link TerminalComputerAccess#requests()}. */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> getRequests() throws LuaException {
        try {
            return access.requests();
        } catch (ComputerArgumentException e) {
            throw new LuaException(e.getMessage());
        }
    }

    /** {@code getRequest(id)} returns the open request or nil: see {@link TerminalComputerAccess#openRequest}. */
    @LuaFunction(mainThread = true)
    public final MethodResult getRequest(String id) throws LuaException {
        try {
            Optional<Map<String, Object>> request = access.openRequest(id);
            return request.isPresent() ? MethodResult.of(request.get()) : MethodResult.of();
        } catch (ComputerArgumentException e) {
            throw new LuaException(e.getMessage());
        }
    }

    /** {@code getDoors()}: see {@link TerminalComputerAccess#doors()}. */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> getDoors() throws LuaException {
        try {
            return access.doors();
        } catch (ComputerArgumentException e) {
            throw new LuaException(e.getMessage());
        }
    }

    /** A stock row, with CC's own item details under {@code details} when asked for. */
    private Map<String, Object> table(TerminalStockEntry entry, boolean detailed) {
        Map<String, Object> table = access.entryTable(entry);
        if (detailed)
            table.put("details", VanillaDetailRegistries.ITEM_STACK.getDetails(entry.key().toStack(1)));
        return table;
    }
}
