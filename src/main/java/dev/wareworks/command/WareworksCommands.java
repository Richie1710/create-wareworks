package dev.wareworks.command;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.wareworks.Wareworks;
import dev.wareworks.content.controller.AisleChunkTickets;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.util.WareworksLang;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /wareworks} — the operator's view of what the mod holds loaded ({@code docs/warehouse-system.md}, M19,
 * issue #10, ADR-031).
 * <p>
 * <b>This command is a hard requirement of the chunk-loading feature, not a convenience.</b> Vanilla cannot show these
 * tickets: {@code /forceload query} reads only the level's own {@code LongSet}
 * ({@code ForceLoadCommand} → {@code ServerLevel#getForcedChunks}), while a mod's tickets live in a separate tracker whose
 * owner type is package-private, so nothing outside NeoForge's own validation callback can name their owners. An operator
 * who cannot find every ticket a mod holds has no way to tell a working feature from a leak.
 * <ul>
 * <li>{@code /wareworks chunks} lists every holding aisle of <b>every</b> dimension with its chunk count, its reason and
 * how long it has been holding, then the totals, and per dimension two numbers: how many chunks are force-loaded there by
 * <b>block tickets</b> of any mod — the number directly comparable with the rows above, so a discrepancy is what a leak
 * looks like — and how many are force-loaded in total, entity tickets and vanilla {@code /forceload} included. A
 * dimension where the mod holds nothing is listed too as soon as anything is force-loaded in it.</li>
 * <li>{@code /wareworks chunks release <x y z>} acts on the <b>sender's</b> dimension, exactly as every subcommand of
 * vanilla {@code /forceload} does, and its answer names that dimension. {@code release all} acts on <b>all</b> of them,
 * so the scope of the word matches the scope of the listing.</li>
 * <li>Both are the emergency valve: the aisle lets go and gives up, so it does not take again until its work really
 * changes — and that bound is saved, so a restart does not undo it. {@code release all} additionally tells the aisles a
 * cap had refused to give up, so the dimension is left with <b>nothing</b> held rather than handing the freed slots to the
 * next aisles in the queue.</li>
 * </ul>
 * Permission level 2, the same as {@code /forceload}.
 */
public final class WareworksCommands {
    /** The same permission level vanilla requires for {@code /forceload}. */
    public static final int PERMISSION_LEVEL = 2;

    private WareworksCommands() {
    }

    /** Registers the command listener on the NeoForge game event bus. Call once. */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(WareworksCommands::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> chunks = Commands.literal("chunks")
                .executes(context -> list(context.getSource()))
                .then(Commands.literal("release")
                        .then(Commands.literal("all")
                                .executes(context -> releaseAll(context.getSource())))
                        // getBlockPos, not getLoadedBlockPos: releasing a hold must work for a controller whose chunk
                        // the hold itself is the only thing keeping loaded, and afterwards for one that is gone.
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> release(context.getSource(),
                                        BlockPosArgument.getBlockPos(context, "pos")))));
        dispatcher.register(Commands.literal(Wareworks.ID)
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(chunks));
    }

    private static int list(CommandSourceStack source) {
        int totalChunks = 0;
        int totalAisles = 0;
        int dimensions = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            List<AisleChunkTickets.Entry> entries = AisleChunkTickets.entries(level);
            int blockTickets = AisleChunkTickets.rawBlockForcedChunkCount(level);
            int forced = AisleChunkTickets.forcedChunkCount(level);
            // A dimension where the mod holds nothing but something else does still gets its numbers: "we hold nothing
            // here and NeoForge tracks four block tickets" is precisely what a leak looks like, and leaving the line out
            // in exactly that state hid it from the check the operator is told to make (M19 review).
            if (entries.isEmpty() && forced == 0)
                continue;
            if (!entries.isEmpty())
                dimensions++;
            source.sendSuccess(() -> WareworksLang.translateDirect(WareworksLang.COMMAND_CHUNKS_HEADER,
                    level.dimension().location().toString()), false);
            for (AisleChunkTickets.Entry entry : entries) {
                totalChunks += entry.chunks();
                totalAisles++;
                source.sendSuccess(() -> row(level, entry), false);
            }
            source.sendSuccess(() -> WareworksLang.translateDirect(WareworksLang.COMMAND_CHUNKS_RAW,
                    level.dimension().location().toString(), WareworksLang.number(blockTickets),
                    WareworksLang.number(forced)), false);
        }
        if (totalAisles == 0) {
            source.sendSuccess(() -> WareworksLang.translateDirect(WareworksLang.COMMAND_CHUNKS_NONE), false);
            return 0;
        }
        int chunkCount = totalChunks;
        int aisleCount = totalAisles;
        int dimensionCount = dimensions;
        source.sendSuccess(() -> WareworksLang.translateDirect(WareworksLang.COMMAND_CHUNKS_TOTAL,
                WareworksLang.number(chunkCount), WareworksLang.number(aisleCount),
                WareworksLang.number(dimensionCount)), false);
        return totalChunks;
    }

    private static net.minecraft.network.chat.Component row(ServerLevel level, AisleChunkTickets.Entry entry) {
        if (entry.unclaimed())
            return WareworksLang.translateDirect(WareworksLang.COMMAND_CHUNKS_ROW_UNCLAIMED,
                    format(entry.owner()), WareworksLang.number(entry.chunks()));
        // The aisle letter comes from the controller itself; its chunk is loaded by the very ticket this row is about,
        // so the lookup cannot fail for a hold that is really there.
        String letter = level.isLoaded(entry.owner())
                && level.getBlockEntity(entry.owner()) instanceof WarehouseControllerBlockEntity controller
                        ? String.valueOf(controller.aisleLetter()) : "?";
        return WareworksLang.translateDirect(WareworksLang.COMMAND_CHUNKS_ROW, format(entry.owner()), letter,
                WareworksLang.number(entry.chunks()),
                WareworksLang.translateDirect(entry.reason().langKey()),
                WareworksLang.number(entry.heldTicks() / 20));
    }

    /**
     * One aisle in the <b>sender's</b> dimension, the way vanilla {@code /forceload} scopes every one of its subcommands.
     * The answer names the dimension it looked in, because the listing spans all of them and a console operator is always
     * in the overworld: {@code /execute in the_nether run wareworks chunks release ...} is the form for another one
     * (M19 review).
     */
    private static int release(CommandSourceStack source, BlockPos pos) {
        ServerLevel level = source.getLevel();
        String dimension = level.dimension().location().toString();
        if (!AisleChunkTickets.releaseByCommand(level, pos)) {
            source.sendSuccess(() -> WareworksLang.translateDirect(WareworksLang.COMMAND_CHUNKS_NOT_HELD, format(pos),
                    dimension), false);
            return 0;
        }
        source.sendSuccess(() -> WareworksLang.translateDirect(WareworksLang.COMMAND_CHUNKS_RELEASED, format(pos)), true);
        return 1;
    }

    /**
     * Every aisle of <b>every</b> dimension, so the scope of the command spelled {@code all} is the scope of the listing
     * it answers. An operator who sees rows for three dimensions and runs it must not release one of them (M19 review).
     */
    private static int releaseAll(CommandSourceStack source) {
        int released = 0;
        int dimensions = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            int here = AisleChunkTickets.releaseAllByCommand(level);
            released += here;
            if (here > 0)
                dimensions++;
        }
        int aisleCount = released;
        int dimensionCount = dimensions;
        source.sendSuccess(() -> WareworksLang.translateDirect(WareworksLang.COMMAND_CHUNKS_RELEASED_ALL,
                WareworksLang.number(aisleCount), WareworksLang.number(dimensionCount)), true);
        return released;
    }

    private static String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
