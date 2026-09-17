package dev.wareworks.data;

import com.tterrag.registrate.providers.DataGenContext;
import com.tterrag.registrate.providers.RegistrateBlockstateProvider;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;

import dev.wareworks.content.station.TerminalDisplaySide;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
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
