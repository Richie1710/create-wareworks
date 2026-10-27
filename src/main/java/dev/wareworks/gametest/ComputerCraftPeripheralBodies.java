package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.lua.ObjectArguments;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.wareworks.Wareworks;
import dev.wareworks.compat.computercraft.TerminalPeripheral;
import dev.wareworks.content.computer.TerminalComputerAccess;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.item.Items;

/**
 * The bodies of {@link ComputerCraftPeripheralGameTests} (issue #9, ADR-074): the real {@code wareworks_terminal}
 * peripheral, fetched through CC: Tweaked's own block capability and called the way CC calls it, with
 * {@link ObjectArguments} and Lua's doubles. Only ever loaded when CC: Tweaked is installed.
 */
final class ComputerCraftPeripheralBodies {
    private static final String BATCH = "wareworkscomputercraft";
    private static final String AISLE_TEMPLATE = Wareworks.ID + ":" + AISLE_16X10X7;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int AISLE_Z = 3;
    private static final int RAILS = 5;
    private static final RackPosition TERMINAL_RACK = new RackPosition(0, 0, Side.RIGHT);
    private static final RackPosition STORAGE_RACK = new RackPosition(1, 0, Side.LEFT);
    private static final int DIAMONDS = 20;
    private static final double REQUESTED = 10.0;
    private static final ItemKey DIAMOND = ItemKey.of(Items.DIAMOND);

    private ComputerCraftPeripheralBodies() {
    }

    static Collection<TestFunction> tests() {
        return List.of(test("ccterminalperipheral", ComputerCraftPeripheralBodies::terminalPeripheral),
                test("ccperipheralonlyontheterminal", ComputerCraftPeripheralBodies::onlyTheTerminal));
    }

    private static TestFunction test(String name, Consumer<GameTestHelper> body) {
        return new TestFunction(BATCH, name, AISLE_TEMPLATE, TIMEOUT_TICKS, 0L, true, body);
    }

    /**
     * The terminal is a {@code wareworks_terminal} through CC's capability; its methods answer as documented, a bad
     * argument is a {@link LuaException} with the facade's text, {@code detailed} adds CC's item details, and a
     * request is carried by the crane with every item accounted for.
     */
    private static void terminalPeripheral(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS));
        aisle.terminal(TERMINAL_RACK);
        Map<ItemKey, Long> conserved = ItemCensus.of(DIAMOND, DIAMONDS);
        helper.onEachTick(() -> ItemCensus.assertEquals(helper, conserved, "while the peripheral's request is served"));
        String[] id = new String[1];

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    WarehouseTerminalBlockEntity terminal = aisle.terminalAt(TERMINAL_RACK);
                    TerminalPeripheral peripheral = peripheralAt(helper, terminal.getBlockPos());
                    helper.assertValueEqual(peripheral.getType(), TerminalComputerAccess.PERIPHERAL_TYPE, "type");
                    helper.assertTrue(peripheral.getTarget() == terminal, "its target is the terminal");
                    helper.assertTrue(peripheral.equals((IPeripheral) peripheralAt(helper, terminal.getBlockPos())),
                            "a repeated lookup is the same peripheral to CC");
                    try {
                        helper.assertValueEqual(peripheral.getStatus().get("status"), "ready", "getStatus");

                        Object[] stock = values(peripheral.getStock(new ObjectArguments(Map.of("detailed", true))));
                        helper.assertValueEqual(stock.length, 1, "one page, no nextStart");
                        List<?> entries = (List<?>) stock[0];
                        helper.assertValueEqual(entries.size(), 1, "one item type");
                        Map<?, ?> entry = (Map<?, ?>) entries.getFirst();
                        helper.assertValueEqual(entry.get("name"), "minecraft:diamond", "name");
                        helper.assertValueEqual(entry.get("count"), (long) DIAMONDS, "count");
                        helper.assertTrue(entry.get("details") instanceof Map<?, ?> details
                                && "minecraft:diamond".equals(details.get("name")), "CC's own item details: " + entry);

                        Object[] item = values(peripheral.getItem(new ObjectArguments("minecraft:diamond")));
                        helper.assertTrue(item.length == 1 && item[0] instanceof Map<?, ?>, "getItem");
                        helper.assertValueEqual(values(peripheral.getItem(new ObjectArguments("minecraft:emerald"))).length, 0,
                                "nil for an item it does not have");

                        expectLuaError(helper, () -> peripheral.request(new ObjectArguments("minecraft:diamond", 0.0)),
                                "amount must be between 1 and ");
                        expectLuaError(helper, () -> peripheral.request(new ObjectArguments("minecraft:diamond", 2.5)),
                                "amount must be a whole number");
                        expectLuaError(helper,
                                () -> peripheral.request(new ObjectArguments("minecraft:diamond", 4294967301.0)),
                                "amount must be a whole number");
                        expectLuaError(helper, () -> peripheral.getItem(new ObjectArguments("nope:nothing")),
                                "unknown item: nope:nothing");

                        Object[] answer = values(peripheral.request(new ObjectArguments("minecraft:diamond", REQUESTED)));
                        helper.assertValueEqual(answer[0], true, "accepted");
                        id[0] = (String) ((Map<?, ?>) answer[1]).get("id");
                        helper.assertValueEqual(peripheral.getRequests().size(), 1, "getRequests");
                        helper.assertValueEqual(values(peripheral.getRequest(id[0])).length, 1, "getRequest");
                        helper.assertTrue(peripheral.getDoors().isEmpty(), "no doors here");
                    } catch (LuaException e) {
                        helper.fail("unexpected Lua error: " + e.getMessage());
                    }
                })
                .thenWaitUntil(() -> helper.assertValueEqual(aisle.stationCount(TERMINAL_RACK, DIAMOND),
                        (long) REQUESTED, "delivered into the terminal buffer"))
                .thenExecute(() -> {
                    try {
                        MethodResult gone = peripheralAt(helper, aisle.terminalAt(TERMINAL_RACK).getBlockPos())
                                .getRequest(id[0]);
                        helper.assertValueEqual(values(gone).length, 0, "a delivered request is nil");
                    } catch (LuaException e) {
                        helper.fail("unexpected Lua error: " + e.getMessage());
                    }
                })
                .thenWaitUntil(aisle::assertIdleAndEmpty)
                .thenSucceed();
    }

    /** Wareworks registers CC's capability on the terminal and on no other block of the aisle. */
    private static void onlyTheTerminal(GameTestHelper helper) {
        AisleFixture aisle = new AisleFixture(helper, AISLE_Z, RAILS).build(true);
        aisle.storage(STORAGE_RACK, DIAMOND.toStack(DIAMONDS));
        aisle.terminal(TERMINAL_RACK);

        helper.startSequence()
                .thenWaitUntil(() -> aisle.assertReady(1, 0, 1))
                .thenExecute(() -> {
                    for (BlockPos pos : List.of(aisle.controllerPos(), aisle.dockPos(), aisle.rackPos(STORAGE_RACK))) {
                        IPeripheral found = helper.getLevel().getCapability(PeripheralCapability.get(),
                                helper.absolutePos(pos), null);
                        helper.assertTrue(found == null, "no Wareworks peripheral at " + pos + ": " + found);
                    }
                    peripheralAt(helper, aisle.terminalAt(TERMINAL_RACK).getBlockPos());
                })
                .thenSucceed();
    }

    private static TerminalPeripheral peripheralAt(GameTestHelper helper, BlockPos absolute) {
        IPeripheral found = helper.getLevel().getCapability(PeripheralCapability.get(), absolute, null);
        if (!(found instanceof TerminalPeripheral peripheral)) {
            helper.fail("no wareworks_terminal peripheral at the terminal: " + found);
            throw new IllegalStateException("unreachable");
        }
        return peripheral;
    }

    /** The values a method returns to Lua; CC's {@code MethodResult.of()} holds none at all. */
    private static Object[] values(MethodResult result) {
        Object[] values = result.getResult();
        return values == null ? new Object[0] : values;
    }

    private interface LuaCall {
        Object call() throws LuaException;
    }

    private static void expectLuaError(GameTestHelper helper, LuaCall call, String start) {
        try {
            call.call();
        } catch (LuaException e) {
            helper.assertTrue(e.getMessage() != null && e.getMessage().startsWith(start),
                    "Lua error '" + e.getMessage() + "' starts with '" + start + "'");
            return;
        }
        helper.fail("expected a Lua error starting with '" + start + "'");
    }
}
