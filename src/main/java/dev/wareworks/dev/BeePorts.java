package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.lwjgl.glfw.GLFW;

import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.packagePort.PackagePortScreen;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.gui.widget.IconButton;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Create Mobile Packages' bee ports set up the way a player does it (dev tooling, ADR-014, ADR-074; shared by
 * {@code robobee} and {@code selector-remote}): placed from one stack with real clicks and tuned with a click on the
 * first, the receive ports' addresses typed into their screens, the bees put into the send ports' bee slots and their
 * return mode switched on with its button, and the wiring rules of a working bee network checked. CMP is reached only
 * through {@link RoboBeeLedger} and by reflection; nothing here is compiled against it.
 */
final class BeePorts {
    /** Menu slot of the bee slot in CMP's bee port menu: 18 package slots, 36 player slots, then the bees. */
    static final int BEE_MENU_SLOT = 54;
    private static final int PLACE_TIMEOUT_TICKS = 100;
    private static final Field PORT_ADDRESS_BOX = RealClicks.field(PackagePortScreen.class, "addressBox");
    private static final Field PORT_CONFIRM = RealClicks.field(PackagePortScreen.class, "confirmButton");

    private final String prefix;
    private final Supplier<RoboBeeLedger> ledger;

    BeePorts(String prefix, Supplier<RoboBeeLedger> ledger) {
        this.prefix = prefix;
        this.ledger = ledger;
    }

    /** A right-click on the ground under {@code port} with the port stack in hand; waits until it stands. */
    void placePort(VisualScript script, String what, BlockPos port, Vec3 eyeOffset) {
        RealClicks.rightClick(script, prefix, RealClicks.click("the ground under the " + what, port.below(),
                Direction.UP, eyeOffset));
        script.serverUntil(prefix + ": the " + what + " stands", (server, context) -> server.overworld()
                .getBlockEntity(context.origin().offset(port)) instanceof PackagePortBlockEntity, PLACE_TIMEOUT_TICKS);
    }

    /**
     * Tunes the rest of the stack in hand with a right-click on the placed port at {@code port} (not sneaking) and
     * returns, through {@code network}, the network it carries.
     */
    void tuneOn(VisualScript script, String what, BlockPos port, Vec3 eyeOffset, java.util.function.Consumer<UUID> network) {
        RealClicks.rightClick(script, prefix, RealClicks.click("the " + what + " (tuning)", port, Direction.UP,
                eyeOffset));
        ArmVisualScenario.untilOrFail(script, prefix + ": the client's port stack is tuned",
                context -> LogisticallyLinkedBlockItem.isTuned(context.minecraft().player.getMainHandItem()),
                RealClicks.SYNC_TIMEOUT_TICKS, context -> "the stack in hand is "
                        + context.minecraft().player.getMainHandItem());
        script.server(prefix + ": CHECK the stack carries the first port's network", (server, context) -> {
            ServerPlayer player = context.serverPlayer(server);
            UUID tuned = LogisticallyLinkedBlockItem.networkFromStack(player.getMainHandItem());
            UUID placed = freq(server.overworld(), context.origin().offset(port));
            if (tuned == null || !tuned.equals(placed))
                throw new VisualTestException("the stack is tuned to " + tuned + ", the first port is on " + placed);
            network.accept(placed);
            LOGGER.info(PREFIX + "{}: CHECK PASS a right-click on the first port tuned the stack to network {}", prefix,
                    placed);
        });
    }

    /** A send port: its screen, the bees picked up from the hotbar and put into the bee slot, return mode on. */
    void sendPort(VisualScript script, String what, BlockPos port, Vec3 eyeOffset, int beeSlot, int bees,
            String shot) {
        openPortScreen(script, what, port, eyeOffset);
        RequesterVisualScenario.moveInMenu(script, "the bees", "the bee slot of the " + what,
                RequesterVisualScenario.hotbar(beeSlot), RequesterVisualScenario.menuSlot(BEE_MENU_SLOT));
        script.serverUntil(prefix + ": the " + what + " holds " + bees + " bees",
                (server, context) -> ledger.get().beesIn(context.origin().offset(port)) == bees,
                RealClicks.SYNC_TIMEOUT_TICKS);
        script.client(prefix + ": click the return mode button of the " + what,
                context -> ScreenInput.click(context.minecraft(), ScreenInput.centre(returnModeButton(context))));
        script.serverUntil(prefix + ": the " + what + " has return mode on", (server, context) -> server.overworld()
                .getBlockEntity(context.origin().offset(port)) instanceof PackagePortBlockEntity be
                && be.saveWithoutMetadata(server.registryAccess()).getBoolean("BeeReturnModeEnabled"),
                RealClicks.SYNC_TIMEOUT_TICKS);
        script.waitTicks(RealClicks.SETTLE_TICKS);
        if (shot != null) {
            script.client(prefix + ": rest the cursor on the bee slot",
                    context -> ScreenInput.hover(context.minecraft(), ScreenInput.menuSlot(
                            RequesterVisualScenario.containerScreen(context), BEE_MENU_SLOT)));
            script.shotWithGui(shot);
        }
        RequesterVisualScenario.closeScreen(script, "the " + what + "'s screen");
        script.server(prefix + ": CHECK the " + what, (server, context) -> LOGGER.info(PREFIX
                + "{}: CHECK PASS {} bees put into the {} with clicks, return mode switched on with its button",
                prefix, bees, what));
    }

    /** A receive port: its screen, the address typed into the address field, confirmed with the tick button. */
    void receivePort(VisualScript script, String what, BlockPos port, Vec3 eyeOffset, String address, String shot) {
        openPortScreen(script, what, port, eyeOffset);
        script.client(prefix + ": click the address field of the " + what, context -> {
            EditBox box = addressBox(context);
            ScreenInput.click(context.minecraft(), ScreenInput.centre(box));
        }).until(prefix + ": the address field of the " + what + " has the focus",
                context -> addressBox(context).isFocused(), RealClicks.SCREEN_TIMEOUT_TICKS);
        script.client(prefix + ": type '" + address + "'", context -> {
            Screen screen = Objects.requireNonNull(context.minecraft().screen, "screen");
            for (char c : address.toCharArray())
                screen.charTyped(c, 0);
            String typed = addressBox(context).getValue();
            if (!address.equals(typed))
                throw new VisualTestException("the address field reads '" + typed + "' after typing '" + address + "'");
        }).waitTicks(RealClicks.SETTLE_TICKS);
        if (shot != null) {
            script.client(prefix + ": unfocus the field with Enter", context -> ScreenInput.key(context.minecraft(),
                    GLFW.GLFW_KEY_ENTER));
            script.waitTicks(RealClicks.SETTLE_TICKS).shotWithGui(shot);
        }
        script.client(prefix + ": confirm the " + what + "'s address with its tick button",
                        context -> ScreenInput.click(context.minecraft(), ScreenInput.centre(confirmButton(context))))
                .until(prefix + ": the " + what + "'s screen is closed", context -> context.minecraft().screen == null,
                        RealClicks.SCREEN_TIMEOUT_TICKS)
                .serverUntil(prefix + ": the server has the address of the " + what, (server, context) -> server
                        .overworld().getBlockEntity(context.origin().offset(port)) instanceof PackagePortBlockEntity be
                        && address.equals(be.addressFilter), RealClicks.SYNC_TIMEOUT_TICKS)
                .server(prefix + ": CHECK the " + what, (server, context) -> LOGGER.info(PREFIX
                        + "{}: CHECK PASS the {} took the typed address '{}'", prefix, what, address));
    }

    void openPortScreen(VisualScript script, String what, BlockPos port, Vec3 eyeOffset) {
        RealClicks.rightClick(script, prefix, RealClicks.click("the " + what, port, Direction.UP, eyeOffset));
        ArmVisualScenario.untilOrFail(script, prefix + ": the " + what + "'s screen is open with its contents",
                context -> context.minecraft().screen instanceof PackagePortScreen screen
                        && screen.getMenu().getStateId() > 0, RealClicks.SCREEN_TIMEOUT_TICKS,
                context -> "the screen is " + context.minecraft().screen);
        script.waitTicks(RealClicks.SETTLE_TICKS);
    }

    /**
     * Every port's address, bees and return mode as the scenario's ledger sees them: a send port has a blank filter and
     * return mode on.
     */
    void checkPorts(net.minecraft.server.MinecraftServer server, int bees) {
        RoboBeeLedger running = ledger.get();
        for (RoboBeeLedger.Port port : running.ports()) {
            PackagePortBlockEntity be = Objects.requireNonNull(running.portEntity(port.pos()), port.name());
            boolean returning = be.saveWithoutMetadata(server.registryAccess()).getBoolean("BeeReturnModeEnabled");
            LOGGER.info(PREFIX + "{}: port {}: address '{}', {} bees, return mode {}", prefix, port.name(),
                    be.addressFilter, running.beesIn(port.pos()), returning);
            if (port.role() == RoboBeeLedger.Role.SEND && (!be.addressFilter.isBlank() || !returning))
                throw new VisualTestException("the send port " + port.name() + " has the address '"
                        + be.addressFilter + "' and return mode " + returning);
        }
        running.expectBees(bees);
        LOGGER.info(PREFIX + "{}: CHECK PASS addresses typed, {} bees in the send ports, return mode on, send ports "
                + "with a blank filter", prefix, bees);
    }

    /** Every port of the ledger is a Create package port on {@code network}. */
    void checkNetwork(ServerLevel level, UUID network) {
        for (RoboBeeLedger.Port port : ledger.get().ports()) {
            if (!(level.getBlockEntity(port.pos()) instanceof PackagePortBlockEntity be))
                throw new VisualTestException("the " + port.name() + " at " + port.pos()
                        + " is no Create package port: " + level.getBlockEntity(port.pos()));
            UUID freq = freq(level, port.pos());
            if (!network.equals(freq))
                throw new VisualTestException("the " + port.name() + " is on network " + freq + ", not " + network);
            LOGGER.info(PREFIX + "{}: port {} at {} is a {}", prefix, port.name(), port.pos(), be.getClass().getName());
        }
        LOGGER.info(PREFIX + "{}: CHECK PASS all {} bee ports share network {}", prefix, ledger.get().ports().size(),
                network);
    }

    /**
     * The rules a working bee network needs: send ports unpowered, receive ports powered, every port touching nothing
     * with an item capability but its own Packager(s) and no other port (world positions), unpacking Packagers
     * unpowered, packing ones powered, no Packager with a Stock Link. {@code extraNeighbours} lists inventories a port
     * may touch besides its Packagers on purpose (none in {@code robobee}).
     */
    void checkWiring(ServerLevel level, Map<BlockPos, Set<BlockPos>> ownPackagers, List<BlockPos> packing,
            List<BlockPos> unpacking) {
        for (RoboBeeLedger.Port port : ledger.get().ports()) {
            Set<BlockPos> touched = new LinkedHashSet<>();
            for (Direction side : Direction.values()) {
                BlockPos next = port.pos().relative(side);
                if (ownPackagers.containsKey(next))
                    throw new VisualTestException("the " + port.name() + " touches another bee port at " + next);
                if (level.getBlockEntity(next) != null
                        && level.getCapability(Capabilities.ItemHandler.BLOCK, next, side.getOpposite()) != null)
                    touched.add(next);
            }
            if (!touched.equals(ownPackagers.get(port.pos())))
                throw new VisualTestException("the " + port.name() + " touches the inventories " + touched
                        + ", expected only its Packager(s) " + ownPackagers.get(port.pos()));
            boolean powered = level.hasNeighborSignal(port.pos());
            if (powered != (port.role() == RoboBeeLedger.Role.RECEIVE))
                throw new VisualTestException("the " + port.role() + " port " + port.name() + " is "
                        + (powered ? "" : "not ") + "powered");
        }
        for (BlockPos pos : packing)
            if (!level.hasNeighborSignal(pos) || level.getBlockState(pos).getValue(PackagerBlock.LINKED))
                throw new VisualTestException("the packing Packager at " + pos + " is unpowered or linked");
        for (BlockPos pos : unpacking)
            if (level.hasNeighborSignal(pos) || level.getBlockState(pos).getValue(PackagerBlock.LINKED))
                throw new VisualTestException("the unpacking Packager at " + pos + " is powered or linked: it would "
                        + "pack its chest back up");
        LOGGER.info(PREFIX + "{}: CHECK PASS ports isolated (each touches only its own Packager(s), no port another), "
                + "send ports unpowered, receive ports powered by the player's levers, packing Packagers powered, "
                + "unpacking ones not, no Stock Link", prefix);
    }

    static UUID freq(ServerLevel level, BlockPos pos) {
        LogisticallyLinkedBehaviour behaviour = BlockEntityBehaviour.get(level, pos, LogisticallyLinkedBehaviour.TYPE);
        if (behaviour == null)
            throw new VisualTestException("the block at " + pos + " has no logistics network");
        return behaviour.freqId;
    }

    private static EditBox addressBox(VisualContext context) {
        return (EditBox) RealClicks.get(PORT_ADDRESS_BOX, portScreen(context));
    }

    private static IconButton confirmButton(VisualContext context) {
        return (IconButton) RealClicks.get(PORT_CONFIRM, portScreen(context));
    }

    /**
     * CMP's return mode button: the third button of the column left of the screen ({@code BeePortScreen}, read by name,
     * never compiled against), checked to stand where CMP 0.7.8 puts it.
     */
    private static IconButton returnModeButton(VisualContext context) {
        PackagePortScreen screen = portScreen(context);
        Object button;
        try {
            Field field = screen.getClass().getDeclaredField("returnModeButton");
            field.setAccessible(true);
            button = field.get(screen);
        } catch (ReflectiveOperationException error) {
            throw new VisualTestException("the port screen " + screen.getClass().getName()
                    + " has no return mode button: " + error);
        }
        if (!(button instanceof IconButton icon))
            throw new VisualTestException("the return mode button is " + button);
        if (icon.getX() != screen.getGuiLeft() - 22 || icon.getY() != screen.getGuiTop() - 10 + 36)
            throw new VisualTestException("the return mode button stands at " + icon.getX() + "," + icon.getY());
        return icon;
    }

    private static PackagePortScreen portScreen(VisualContext context) {
        if (context.minecraft().screen instanceof PackagePortScreen screen)
            return screen;
        throw new VisualTestException("no port screen is open: " + context.minecraft().screen);
    }
}
