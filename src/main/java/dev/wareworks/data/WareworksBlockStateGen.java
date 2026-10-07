package dev.wareworks.data;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.foundation.block.ProperWaterloggedBlock;
import com.tterrag.registrate.providers.DataGenContext;
import com.tterrag.registrate.providers.RegistrateBlockstateProvider;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;

import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.content.station.TerminalDisplaySide;
import dev.wareworks.content.station.WarehouseHomePointBlock;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.station.WarehouseProductionBlock;
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.content.storage.RackBayBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.client.model.generators.MultiPartBlockStateBuilder;

/**
 * Blockstate generators that Create's {@code BlockStateGen} does not cover. Datagen only: the returned consumers are
 * stored by Registrate and run exclusively during {@code runData}, so no client class is loaded on a server (the same
 * pattern as Create's own {@code BlockStateGen}).
 */
public final class WareworksBlockStateGen {
    /** Y rotation of a model authored on the north face, so that its content ends up on {@code facing}. */
    private static final int NORTH_AUTHORED_OFFSET = 180;
    private static final int FULL_TURN = 360;

    private WareworksBlockStateGen() {
    }

    /**
     * The warehouse terminal's <b>multipart</b> blockstate ({@code docs/warehouse-system.md} §3.4.3, ADR-022).
     * <p>
     * The terminal carries two independent horizontal directions — the intake port ({@code FACING}) and the screen
     * ({@code DISPLAY}, relative to the port) — which a variant blockstate cannot express, because a variant may only
     * rotate <b>one</b> model. The block is therefore built from a core column plus four interchangeable 3 px shells
     * that tile the ring around it (a pinwheel, so no two shells overlap and each owns its outer faces):
     * <ul>
     * <li>{@code block} — the core, always drawn;</li>
     * <li>{@code shell_display} — the screen and the take-out tray, on the screen face;</li>
     * <li>{@code shell_intake} — the crane's arm port, on the {@code FACING} face;</li>
     * <li>{@code shell_plain} — brass, on the two faces that are neither.</li>
     * </ul>
     * Every one of the twelve states therefore renders exactly one core and four shells, with the shell models rotated
     * from the north face they are authored on. {@code CraneModelLayoutTest} pins both the tiling and the port.
     */
    public static <T extends Block> NonNullBiConsumer<DataGenContext<Block, T>, RegistrateBlockstateProvider>
            terminalBlockProvider() {
        return (context, provider) -> {
            String folder = "block/" + context.getName() + "/";
            ModelFile core = provider.models().getExistingFile(provider.modLoc(folder + "block"));
            ModelFile screenShell = provider.models().getExistingFile(provider.modLoc(folder + "shell_display"));
            ModelFile intakeShell = provider.models().getExistingFile(provider.modLoc(folder + "shell_intake"));
            ModelFile plainShell = provider.models().getExistingFile(provider.modLoc(folder + "shell_plain"));

            MultiPartBlockStateBuilder builder = provider.getMultipartBuilder(context.getEntry());
            builder.part().modelFile(core).addModel().end();
            for (Direction port : Direction.Plane.HORIZONTAL) {
                for (TerminalDisplaySide display : TerminalDisplaySide.values()) {
                    Direction screen = display.of(port);
                    shell(builder, screenShell, screen, port, display);
                    shell(builder, intakeShell, port, port, display);
                    for (Direction plain : Direction.Plane.HORIZONTAL) {
                        if (plain != port && plain != screen)
                            shell(builder, plainShell, plain, port, display);
                    }
                }
            }
        };
    }

    /**
     * The warehouse stock keeper's blockstate ({@code docs/warehouse-system.md} §3.6, M15): the hand-made
     * {@code block} model turned onto {@code FACING}, with {@code block_lit} in its place while the lamp burns.
     * <p>
     * Create's {@code BlockStateGen.horizontalBlockProvider} would give every {@code LIT} value the same model — which
     * is right for the warehouse output, whose {@code POWERED} is only a stored edge, and wrong here, where the lamp
     * is the whole point of the property.
     */
    public static <T extends Block> NonNullBiConsumer<DataGenContext<Block, T>, RegistrateBlockstateProvider>
            stockKeeperBlockProvider() {
        return (context, provider) -> {
            String folder = "block/" + context.getName() + "/";
            ModelFile dark = provider.models().getExistingFile(provider.modLoc(folder + "block"));
            ModelFile lit = provider.models().getExistingFile(provider.modLoc(folder + "block_lit"));
            ModelFile paused = provider.models().getExistingFile(provider.modLoc(folder + "block_paused"));
            provider.getVariantBuilder(context.getEntry()).forAllStates(state -> ConfiguredModel.builder()
                    // The safety stop outranks the ordinary lamp, exactly as it does in every other surface: a paused
                    // rule is the one state a player has to act on (M15 part 2).
                    .modelFile(state.getValue(WarehouseStockKeeperBlock.PAUSED) ? paused
                            : state.getValue(WarehouseStockKeeperBlock.LIT) ? lit : dark)
                    .rotationY(rotationOnto(state.getValue(WarehouseStockKeeperBlock.FACING)))
                    .build());
        };
    }

    /**
     * The warehouse home point's <b>multipart</b> blockstate ({@code docs/stacker-crane.md} §4.7, M21, ADR-034): the
     * hand-made {@code block} model turned onto {@code FACING}, with {@code block_active} in its place while the crane
     * really waits here and {@code block_refused} while something keeps it from being used — plus, over the refused
     * one, a brass {@code stop} crossed over its plate.
     * <p>
     * <b>The colour alone is not enough</b>, and that is why this block is not a three-variant provider. The three body
     * models differ in one texture, the lamp bar across the plate, and they speak the lamp language the rest of the mod
     * already does: dim rose quartz for "nothing is happening", lit for "this is working" and the <b>powered</b> lamp of
     * the safety stop for "a player has to do something". Lit and powered rose quartz are a shade apart, though — which
     * is right on a stock keeper, where both states are warnings, and wrong here, where the two states are "this is your
     * crane's home" and "this block does nothing". The crossed brass stop is the same answer the closed rail gives:
     * <b>readable at a glance, across the room and in the dark</b>, and readable without colour at all.
     * <p>
     * Create's {@code BlockStateGen.horizontalBlockProvider} would give every lamp value the same model, which is right
     * for a stored edge nobody can see and wrong for a lamp; a variant blockstate could not draw the stop without a
     * second copy of the whole geometry, which is what the multipart avoids (the terminal's pattern).
     */
    public static <T extends Block> NonNullBiConsumer<DataGenContext<Block, T>, RegistrateBlockstateProvider>
            homePointBlockProvider() {
        return (context, provider) -> {
            String folder = "block/" + context.getName() + "/";
            ModelFile dark = provider.models().getExistingFile(provider.modLoc(folder + "block"));
            ModelFile active = provider.models().getExistingFile(provider.modLoc(folder + "block_active"));
            ModelFile refused = provider.models().getExistingFile(provider.modLoc(folder + "block_refused"));
            ModelFile stop = provider.models().getExistingFile(provider.modLoc(folder + "stop"));
            MultiPartBlockStateBuilder builder = provider.getMultipartBuilder(context.getEntry());
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                int rotation = rotationOnto(facing);
                // Refused outranks lit, exactly as every "you have to do something" lamp of this mod does.
                homePointPart(builder, refused, rotation, facing, true, null);
                homePointPart(builder, stop, rotation, facing, true, null);
                homePointPart(builder, active, rotation, facing, false, true);
                homePointPart(builder, dark, rotation, facing, false, false);
            }
        };
    }

    /** One part of the home point: {@code model} turned onto {@code facing}, for one refused (and lit) state. */
    private static void homePointPart(MultiPartBlockStateBuilder builder, ModelFile model, int rotation,
                                      Direction facing, boolean refused, @Nullable Boolean lit) {
        MultiPartBlockStateBuilder.PartBuilder part = builder.part().modelFile(model).rotationY(rotation).addModel()
                .condition(WarehouseHomePointBlock.FACING, facing)
                .condition(WarehouseHomePointBlock.REFUSED, refused);
        if (lit != null)
            part.condition(WarehouseHomePointBlock.LIT, lit);
        part.end();
    }

    /**
     * The warehouse production station's blockstate (M20, issue #4, ADR-032): the hand-made {@code block} model turned
     * onto {@code FACING}, with {@code block_stopped} in its place while the <b>safety stop</b> holds something this
     * station makes ({@link WarehouseProductionBlock#STOPPED}).
     * <p>
     * The two models are the same geometry and differ in one texture: the ring around the aisle opening and the one
     * around the machine opening are a lit <b>rose quartz lamp</b> instead of brass — the very texture the stock
     * keeper's own pause lamp uses, so the safety stop looks the same wherever a player meets it. The cue therefore reads
     * from inside the aisle, where no value box and no drawn digit may go, and from the machine side, which is where a
     * player stands when they come to fix the machine.
     * <p>
     * Create's {@code BlockStateGen.horizontalBlockProvider} would give both {@code STOPPED} values the same model, which
     * is right for a stored edge nobody can see and wrong for a lamp.
     */
    public static <T extends Block> NonNullBiConsumer<DataGenContext<Block, T>, RegistrateBlockstateProvider>
            productionBlockProvider() {
        return (context, provider) -> {
            String folder = "block/" + context.getName() + "/";
            ModelFile working = provider.models().getExistingFile(provider.modLoc(folder + "block"));
            ModelFile stopped = provider.models().getExistingFile(provider.modLoc(folder + "block_stopped"));
            provider.getVariantBuilder(context.getEntry()).forAllStates(state -> ConfiguredModel.builder()
                    .modelFile(state.getValue(WarehouseProductionBlock.STOPPED) ? stopped : working)
                    .rotationY(rotationOnto(state.getValue(WarehouseProductionBlock.FACING)))
                    .build());
        };
    }

    /**
     * The warehouse port's blockstate ({@code docs/warehouse-system.md} §3.2, M17, issue #12; M18, issue #13): the
     * hand-made {@code block} model turned onto {@code FACING}, with {@code block_accept} in its place while the port
     * accepts items instead of requesting them and {@code block_collect} while it collects them out of a machine.
     * <p>
     * The three models are the same geometry and differ in one texture: the ring around the aisle opening and the spout on
     * the back are <b>andesite</b> (accept) or <b>copper</b> (collect) instead of brass, which is ADR-017's material
     * language applied to the direction — andesite is the dumb intake, brass the smart filtered output, "whatever the
     * warehouse cannot keep" is the dumb direction, and copper is Create's "moves things through itself" material, which is
     * exactly what a collecting port does. The cue therefore reads both from <b>inside the aisle</b>, where no value box
     * and no drawn digit may go, and from outside, where the funnel is.
     * <p>
     * Create's {@code BlockStateGen.horizontalBlockProvider} would give every {@code accepting} value the same model —
     * which is right for {@code POWERED}, a stored edge nobody can see, and wrong here.
     */
    public static <T extends Block> NonNullBiConsumer<DataGenContext<Block, T>, RegistrateBlockstateProvider>
            warehousePortBlockProvider() {
        return (context, provider) -> {
            String folder = "block/" + context.getName() + "/";
            ModelFile request = provider.models().getExistingFile(provider.modLoc(folder + "block"));
            ModelFile accept = provider.models().getExistingFile(provider.modLoc(folder + "block_accept"));
            ModelFile collect = provider.models().getExistingFile(provider.modLoc(folder + "block_collect"));
            provider.getVariantBuilder(context.getEntry()).forAllStates(state -> ConfiguredModel.builder()
                    .modelFile(modelFor(state, request, accept, collect))
                    .rotationY(rotationOnto(state.getValue(WarehouseOutputBlock.FACING)))
                    .build());
        };
    }

    /**
     * The model of one port state (M18, issue #13): the accepting one, the collecting one, or the requesting one for both
     * of the states that are not a direction — the default, and the illegal "accepting and collecting at once" that only a
     * {@code /setblock} can produce and that the block entity corrects on the next load
     * ({@code WarehouseOutputBlock#directionOf}).
     */
    private static ModelFile modelFor(BlockState state, ModelFile request, ModelFile accept, ModelFile collect) {
        return switch (WarehouseOutputBlock.directionOf(state)) {
            case ACCEPT -> accept;
            case COLLECT -> collect;
            case REQUEST -> request;
        };
    }

    /**
     * A rack bay's <b>multipart</b> blockstate ({@code docs/warehouse-system.md} §3.8, M28 step 9, issue #20): the
     * tier's hand-made {@code block} shell turned onto {@link net.minecraft.world.level.block.HorizontalDirectionalBlock#FACING},
     * plus up to two shared load parts chosen by {@code RackBayBlock.FILL}.
     * <p>
     * <b>The load is shared by all three tiers and not turned.</b> Goods are goods: what the material decides is how
     * much a bay holds and what it may carry above it, not what a pallet of cardboard boxes looks like — so the four
     * steps live once in {@code models/block/rack_bay/} rather than three times over. They are authored centred on the
     * block and are symmetric about its vertical axis, so a rotation would move nothing; leaving it off keeps the
     * blockstate at <b>eight</b> parts instead of twenty.
     * <p>
     * The steps stack instead of repeating themselves: {@code load_1} and {@code load_2} are the two small loads,
     * {@code load_base} is the full footprint shown for the <b>last two</b> steps, and {@code load_cap} is the crate
     * that goes on top of it at the last one. A bay at step 3 and one at step 4 therefore differ by exactly the crate
     * a player can see arrive, and no geometry is written twice.
     * <p>
     * <b>{@code OVERLOADED} is deliberately not a condition anywhere</b>, so a bay that carries something stronger
     * above it looks exactly like one that does not (ADR-044): it is a warning for the goggles and the job planner,
     * not a look. A variant blockstate would have had to spell out every combination of it instead.
     */
    public static <T extends Block> NonNullBiConsumer<DataGenContext<Block, T>, RegistrateBlockstateProvider>
            rackBayBlockProvider() {
        return (context, provider) -> {
            ModelFile shell = provider.models().getExistingFile(provider.modLoc("block/" + context.getName() + "/block"));
            MultiPartBlockStateBuilder builder = provider.getMultipartBuilder(context.getEntry());
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                builder.part().modelFile(shell).rotationY(rotationOnto(facing)).addModel()
                        .condition(RackBayBlock.FACING, facing).end();
            }
            loadPart(builder, provider, "load_1", 1);
            loadPart(builder, provider, "load_2", 2);
            loadPart(builder, provider, "load_base", 3, 4);
            loadPart(builder, provider, "load_cap", 4);
        };
    }

    /** One shared load part of a rack bay, shown at the named fill steps ({@link #rackBayBlockProvider()}). */
    private static void loadPart(MultiPartBlockStateBuilder builder, RegistrateBlockstateProvider provider,
                                 String model, Integer... fillSteps) {
        ModelFile file = provider.models().getExistingFile(provider.modLoc("block/rack_bay/" + model));
        builder.part().modelFile(file).addModel().condition(RackBayBlock.FILL, fillSteps).end();
    }

    /**
     * The warehouse rail's blockstate (M21, issue #1, ADR-033): one of five hand-made shapes, turned onto the sides the
     * rail is connected on, plus the closed rail's own model.
     * <p>
     * Rails that touch connect, so a player has to be able to read "the crane can turn here" from across the room. The
     * four connection flags are <b>derived and cosmetic</b> — the warehouse itself never looks at them — and they pick
     * {@code block} (a straight rail, and a lone one with no connection at all, which is the only case
     * {@link WarehouseRailBlock#AXIS} still decides), {@code end}, {@code corner}, {@code tee} or {@code cross}. A rail
     * the wrench has closed shows {@code closed}, whatever its neighbours do, because it belongs to no warehouse.
     * <p>
     * The shapes are authored on fixed sides — {@code end} to the north, {@code corner} north and east, {@code tee}
     * everywhere but west — and turned from there, the same way every other model of this mod is authored on the north
     * face. A multipart blockstate (the terminal's pattern) is deliberately <b>not</b> used: a corner and a tee are not
     * the sum of independent arms, they are their own shapes.
     */
    public static <T extends Block> NonNullBiConsumer<DataGenContext<Block, T>, RegistrateBlockstateProvider>
            railBlockProvider() {
        return (context, provider) -> {
            String folder = "block/" + context.getName() + "/";
            ModelFile straight = provider.models().getExistingFile(provider.modLoc(folder + "block"));
            ModelFile end = provider.models().getExistingFile(provider.modLoc(folder + "end"));
            ModelFile corner = provider.models().getExistingFile(provider.modLoc(folder + "corner"));
            ModelFile tee = provider.models().getExistingFile(provider.modLoc(folder + "tee"));
            ModelFile cross = provider.models().getExistingFile(provider.modLoc(folder + "cross"));
            ModelFile closed = provider.models().getExistingFile(provider.modLoc(folder + "closed"));
            provider.getVariantBuilder(context.getEntry())
                    .forAllStatesExcept(state -> railModel(state, straight, end, corner, tee, cross, closed),
                            ProperWaterloggedBlock.WATERLOGGED);
        };
    }

    private static ConfiguredModel[] railModel(BlockState state, ModelFile straight, ModelFile end, ModelFile corner,
                                               ModelFile tee, ModelFile cross, ModelFile closed) {
        if (state.getValue(WarehouseRailBlock.CLOSED))
            return turned(closed, alongAxis(state));
        List<Direction> connected = new ArrayList<>(4);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (state.getValue(WarehouseRailBlock.connection(direction)))
                connected.add(direction);
        }
        return switch (connected.size()) {
            case 0 -> turned(straight, alongAxis(state));
            case 1 -> turned(end, rotationOnto(connected.getFirst()));
            case 2 -> connected.get(0) == connected.get(1).getOpposite()
                    ? turned(straight, connected.getFirst().getAxis() == Direction.Axis.Z ? 0 : FULL_TURN / 4)
                    : turned(corner, rotationOnto(cornerSlot(connected)));
            case 3 -> turned(tee, rotationOnto(missingOf(connected)) - rotationOnto(Direction.WEST) + FULL_TURN);
            default -> turned(cross, 0);
        };
    }

    /** The rotation a model authored along the Z axis needs for the (now purely cosmetic) axis of a lone rail. */
    private static int alongAxis(BlockState state) {
        return state.getValue(WarehouseRailBlock.AXIS) == Direction.Axis.Z ? 0 : FULL_TURN / 4;
    }

    /**
     * The side the {@code corner} model's north arm has to end up on: of its two connected sides, the one whose
     * <b>clockwise</b> neighbour is the other, because the model is authored connecting north and east.
     */
    private static Direction cornerSlot(List<Direction> connected) {
        Direction first = connected.getFirst();
        return connected.contains(first.getClockWise()) ? first : connected.getLast();
    }

    /** The one side a three-way rail is not connected on; the {@code tee} model is authored missing its west side. */
    private static Direction missingOf(List<Direction> connected) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!connected.contains(direction))
                return direction;
        }
        throw new IllegalArgumentException("a three-way rail leaves one side free: " + connected);
    }

    private static ConfiguredModel[] turned(ModelFile model, int rotationY) {
        return ConfiguredModel.builder().modelFile(model).rotationY(rotationY % FULL_TURN).build();
    }

    /** One shell of one state: {@code model} turned onto the {@code shellFace} of the block. */
    private static void shell(MultiPartBlockStateBuilder builder, ModelFile model, Direction shellFace,
                              Direction port, TerminalDisplaySide display) {
        builder.part().modelFile(model).rotationY(rotationOnto(shellFace)).addModel()
                .condition(WarehouseTerminalBlock.FACING, port)
                .condition(WarehouseTerminalBlock.DISPLAY, display)
                .end();
    }

    /** Y rotation (a multiple of 90°) that turns a north-authored model onto {@code facing}. */
    private static int rotationOnto(Direction facing) {
        return ((int) facing.toYRot() + NORTH_AUTHORED_OFFSET) % FULL_TURN;
    }
}
