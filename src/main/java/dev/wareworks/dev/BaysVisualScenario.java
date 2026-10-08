package dev.wareworks.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicReference;

import com.tterrag.registrate.util.entry.BlockEntry;

import dev.wareworks.client.render.RackBayRenderer;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.PalletEntity;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.RackBayBlockEntity;
import dev.wareworks.core.storage.BayTier;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Scenario "bays": what a rack wall looks like and what it costs — the coarse fill silhouette of M28, the joined rack
 * frame of M29 step 12, and the stored item drawn on top of both by {@code client.render.RackBayRenderer}
 * (M29 steps 13 and 14, {@code docs/warehouse-system.md} §3.8, issue #20).
 * <p>
 * Seven things are shown, and one is measured.
 * <ul>
 * <li><b>A hundred-bay wall from the aisle</b> ({@code hundred-aisle}): twenty bays wide and five high, every one
 * holding the same item at a fill level that cycles along the row, so the only thing the next shot changes is whether
 * the item is drawn.</li>
 * <li><b>The same wall from eleven blocks</b> ({@code hundred-far}): the same camera direction, one block beyond
 * Create's {@code filterItemRenderDistance}. The fill silhouette is still there and the items are gone — which is the
 * whole design: the shape says how full from across the warehouse, the item says what from the aisle.</li>
 * <li><b>A wall of flat items</b> and <b>a wall of block items</b> ({@code flat-front}, {@code solid-front}): ten of
 * each, the bottom row full and the top row at the lowest fill step, because the placement of the drawn item has to
 * work at both — a full bay's cartons cover the pallet's whole footprint.</li>
 * <li><b>One bay per facing</b> ({@code facings-top}): the drawn item has to be turned by the same quarter turns the
 * multipart blockstate turns the bay's models by, so it stands at the open aisle side of each of the four and not at
 * the back or beside it.</li>
 * <li><b>A wall being built</b> ({@code grow-1}, {@code grow-2}, {@code grow-3}, {@code grow-6}): the same camera
 * after each bay is added to a run. This is the one picture the fourth reason of issue #20 is really about — "you
 * extend a wall by placing a bay beside an existing one, and the wall joins visually as it grows" — and it is a
 * comparison between frames rather than a claim: a lone bay has an upright at each end, and the second bay does not
 * bring a second one to stand beside the first, it takes over half of it.</li>
 * <li><b>The three materials in the one legal order</b> ({@code tiers-front}): brass on the ground, andesite over it,
 * wood on top, each row a different item, so one frame says whether the materials are told apart at a player's
 * distance and whether the drawn item reads against all three.</li>
 * <li><b>The column rule</b> ({@code column-front}): two wooden columns built and filled exactly alike, the
 * right-hand one with a brass bay set on top by a command — the only route into an illegal column, because the
 * placement itself is refused. The bays under that cap are overloaded and must look <b>exactly</b> like their twins
 * beside them (ADR-047); the scenario asserts that on the block states and the shot lets a person check it.</li>
 * </ul>
 * <b>The measurement</b> is the point of the step as much as the shots are. Registering a block entity renderer puts
 * every block of that type into its chunk section's per-frame render list whether anything is drawn or not, and that is
 * the regression this mod has already paid for once ({@code WarehouseInterfaceRenderer}). So at both hundred-bay
 * cameras {@link #measureBudget} counts, from the client's own per-frame list, how many bays are in it and how many of
 * them pass the renderer's culling — and times the culling work for one frame. It then <b>asserts</b> the two halves of
 * the claim: from eleven blocks not one of a hundred bays is drawn, and from the aisle at least one is, so the far shot
 * cannot be empty because the renderer never works.
 */
public final class BaysVisualScenario implements VisualScenario {
    public static final String NAME = "bays";

    private static final int SCENE_READY_TIMEOUT_TICKS = 600;
    private static final double BLOCK_CENTER = 0.5;
    private static final double EYE_HEIGHT = 1.7;

    /** The hundred-bay wall: the size a warehouse really places, and the size the budget claim is about. */
    private static final int WALL_WIDTH = 20;
    private static final int WALL_HEIGHT = 5;
    /** What every bay of that wall holds, so the near and far shots differ in exactly one thing. */
    private static final Item WALL_ITEM = Items.COBBLESTONE;
    /** Where the wall stands, relative to the origin: at the origin, facing north, its aisle towards {@code +Z}. */
    private static final BlockPos WALL_AT = BlockPos.ZERO;
    /** Camera in the aisle in front of the wall, at the height a player walks past it. */
    private static final double AISLE_DISTANCE = 2.5;
    private static final double AISLE_LOOK_HEIGHT = 1.6;
    /**
     * Camera one block beyond Create's default {@code filterItemRenderDistance} of ten blocks, measured from the bays'
     * own centres — the distance the design promises nothing is drawn at.
     */
    private static final double FAR_DISTANCE = 11.0;
    private static final double FAR_EYE_HEIGHT = 3.0;
    /**
     * And the same framing from just <b>inside</b> the cap, which is the only picture that shows the cap itself: the
     * bays straight ahead are within ten blocks of the camera and carry their item, the ones further along the wall are
     * not and do not. Walking away from a rack wall looks like this, and it is better said out loud in a shot than
     * discovered.
     */
    private static final double EDGE_DISTANCE = 9.5;

    /** The two small walls that show what the renderer tells apart; both two rows high. */
    private static final int SAMPLE_WIDTH = 5;
    private static final int SAMPLE_HEIGHT = 2;
    /** Where they stand: well beyond the far camera, so neither overview shot reaches them. */
    private static final int SAMPLE_Z = 26;
    private static final int FLAT_X = 0;
    private static final int SOLID_X = 8;
    private static final double SAMPLE_DISTANCE = 4.2;
    private static final double SAMPLE_EYE_HEIGHT = 1.9;
    private static final double SAMPLE_LOOK_HEIGHT = 1.4;

    /**
     * Ten items with a flat (generated) model: the case the bay stands up facing the aisle instead of laying down the
     * way the crane's arm does, because a 0.3 px sheet lying flat is an invisible line seen from an aisle.
     */
    private static final List<Item> FLAT_ITEMS = List.of(Items.PAPER, Items.REDSTONE, Items.WHEAT, Items.SUGAR,
            Items.GUNPOWDER, Items.STICK, Items.STRING, Items.IRON_INGOT, Items.DIAMOND, Items.COAL);
    /**
     * Ten items with a block model, the furnace and the chest among them on purpose: the furnace has a front face, so
     * it says whether the item is turned to the aisle, and a chest is drawn by a custom renderer rather than by a baked
     * model, which is the one item shape that could throw inside {@code ItemRenderer}.
     */
    private static final List<Item> SOLID_ITEMS = List.of(Items.STONE, Items.OAK_LOG, Items.BRICKS, Items.GLASS,
            Items.PUMPKIN, Items.HAY_BLOCK, Items.FURNACE, Items.BARREL, Items.CRAFTING_TABLE, Items.CHEST);

    /** One bay per facing, to check the rotation; spaced so they never join and never cover one another. */
    private static final int FACINGS_Z = 34;
    private static final int FACINGS_SPACING = 2;
    private static final List<Direction> FACINGS =
            List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    /** What each of them holds: a block item with a front face, so a wrong turn is visible rather than plausible. */
    private static final Item FACINGS_ITEM = Items.FURNACE;
    /** Almost straight down, because that is the one view in which all four open sides are unambiguous at once. */
    private static final double FACINGS_EYE_HEIGHT = 6.5;
    private static final double FACINGS_EYE_BACK = 0.6;
    private static final double FACINGS_LOOK_HEIGHT = 0.5;

    /**
     * The growth strip (M29 step 14): the picture the fourth reason of issue #20 is actually about — not a wall that
     * stands, but a wall <b>being built</b>. The same camera is shot after each bay is added, so the uprights merging
     * and the beam lines running on are a change between two frames rather than a claim in a sentence.
     * <p>
     * It stands far to the right of everything else, at the depth of the hundred-bay wall but twenty blocks past its
     * end, so no other camera of this scenario can see it and its own camera has nothing behind it.
     */
    private static final int GROWTH_X = 40;
    private static final int GROWTH_LENGTH = 6;
    private static final int GROWTH_HEIGHT = 2;
    /** How wide the strip is when each shot is taken; the last one is the whole run. */
    private static final int[] GROWTH_SHOTS = {1, 2, 3, GROWTH_LENGTH};
    /** One item everywhere, because the question is the frame and not the goods. */
    private static final Item GROWTH_ITEM = Items.BRICKS;
    /**
     * Close enough that the run fills the frame and far enough that all six bays fit it: at this distance the
     * horizontal half-width a 70° field of view covers is about {@code 1.24 · d} blocks, so the next exhibit along
     * has to start beyond {@code GROWTH_X + GROWTH_LENGTH + 1.24 · GROWTH_DISTANCE} — which is what sets
     * {@link #TIERS_X} and {@link #COLUMN_X}. Every exhibit of this scenario stands on the same line at {@code z = 0}
     * and is shot from {@code +Z}, so an exhibit too close to its neighbour simply walks into the shot.
     */
    private static final double GROWTH_DISTANCE = 4.2;
    private static final double GROWTH_EYE_HEIGHT = 1.62;
    private static final double GROWTH_LOOK_HEIGHT = 1.0;
    private static final int GROWTH_READY_TIMEOUT_TICKS = 200;

    /**
     * The tier wall: the three materials in the one order the column rule allows — brass on the ground, andesite over
     * it, wood on top — each row holding a different item, so one shot answers whether the materials are told apart
     * <b>and</b> whether the drawn item reads against all three.
     */
    private static final int TIERS_X = 54;
    private static final int TIERS_WIDTH = 4;
    private static final List<BlockEntry<RackBayBlock>> TIER_ROWS =
            List.of(WareworksBlocks.RACK_BAY_BRASS, WareworksBlocks.RACK_BAY_ANDESITE, WareworksBlocks.RACK_BAY_WOOD);
    /** One per row, bottom to top: a block, a flat item and a second block, at falling fill levels. */
    private static final List<Item> TIER_ITEMS = List.of(Items.COBBLESTONE, Items.IRON_INGOT, Items.OAK_LOG);
    private static final double TIERS_DISTANCE = 5.4;
    private static final double TIERS_EYE_HEIGHT = 2.4;
    private static final double TIERS_LOOK_HEIGHT = 1.6;

    /**
     * The column rule, as the only thing about it a <b>picture</b> can carry: two wooden columns built and filled
     * exactly alike, the right-hand one with a brass bay set on top of it by a command — the one route into an
     * illegal column, since placement itself is refused. Every wooden bay under that cap is {@code OVERLOADED}, and
     * the two columns have to be indistinguishable below the cap, because a bay that carries something stronger
     * above it looks exactly like one that does not (ADR-047). The scenario asserts that on the block states and the
     * shot lets a person check it.
     */
    private static final int COLUMN_X = 68;
    private static final int COLUMN_GAP = 3;
    private static final int COLUMN_HEIGHT = 3;
    private static final Item COLUMN_ITEM = Items.COBBLESTONE;
    private static final double COLUMN_DISTANCE = 5.0;
    private static final double COLUMN_EYE_HEIGHT = 2.2;
    private static final double COLUMN_LOOK_HEIGHT = 1.6;

    /** The area cleared before anything is built, around the whole scene. */
    private static final int CLEAR_MARGIN = 4;
    private static final int CLEAR_HEIGHT = 8;

    /** How often the culling work of one frame is repeated for the timing, so a single frame's noise averages out. */
    private static final int TIMING_PASSES = 500;

    /** One budget measurement, taken on the client at one camera. */
    private record Budget(String label, int inList, int wallInList, int drawn, long cullNanosPerFrame) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "%s: bays in the client's per-frame list=%d (of the hundred-bay wall: %d), drawn=%d, "
                            + "culling the wall=%d ns/frame",
                    label, inList, wallInList, drawn, cullNanosPerFrame);
        }
    }

    /** The last measurement, for the status line of the following shots. */
    private final AtomicReference<Budget> lastBudget = new AtomicReference<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void setup(VisualScript script) {
        script.server("bays: clear the area and build the walls", BaysVisualScenario::build)
                .until("bays: wait until the client has every bay with its contents", BaysVisualScenario::clientReady,
                        SCENE_READY_TIMEOUT_TICKS);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        double wallCenterX = WALL_AT.getX() + (WALL_WIDTH - 1) / 2.0 + BLOCK_CENTER;
        double wallFace = WALL_AT.getZ() + BLOCK_CENTER;
        // The wall from the aisle: items drawn, and the measurement that says how many.
        script.camera(CameraView.of("aisle", wallCenterX, EYE_HEIGHT, wallFace + AISLE_DISTANCE, wallCenterX,
                        AISLE_LOOK_HEIGHT, wallFace))
                .client("bays: measure the per-frame budget from the aisle", context -> measureBudget(context, "aisle"))
                .shot("hundred-aisle");
        // The same wall and framing from just inside the cap: the items thin out towards the ends of the wall, which
        // is the cap drawn rather than described.
        script.camera(CameraView.of("edge", wallCenterX, FAR_EYE_HEIGHT, wallFace + EDGE_DISTANCE, wallCenterX,
                        AISLE_LOOK_HEIGHT, wallFace))
                .client("bays: measure the per-frame budget at the edge of the cap",
                        context -> measureBudget(context, "edge"))
                .shot("hundred-edge");
        // The same wall, the same direction, one block past the cap: the silhouette stays, the items are gone.
        script.camera(CameraView.of("far", wallCenterX, FAR_EYE_HEIGHT, wallFace + FAR_DISTANCE, wallCenterX,
                        AISLE_LOOK_HEIGHT, wallFace))
                .client("bays: measure the per-frame budget from eleven blocks",
                        context -> measureBudget(context, "far"))
                .shot("hundred-far");

        double flatCenter = FLAT_X + (SAMPLE_WIDTH - 1) / 2.0 + BLOCK_CENTER;
        double solidCenter = SOLID_X + (SAMPLE_WIDTH - 1) / 2.0 + BLOCK_CENTER;
        double sampleFace = SAMPLE_Z + BLOCK_CENTER;
        script.shotFrom(CameraView.of("front", flatCenter, SAMPLE_EYE_HEIGHT, sampleFace + SAMPLE_DISTANCE, flatCenter,
                SAMPLE_LOOK_HEIGHT, sampleFace), "flat");
        script.shotFrom(CameraView.of("front", solidCenter, SAMPLE_EYE_HEIGHT, sampleFace + SAMPLE_DISTANCE,
                solidCenter, SAMPLE_LOOK_HEIGHT, sampleFace), "solid");

        double facingsCenter = (FACINGS.size() - 1) * FACINGS_SPACING / 2.0 + BLOCK_CENTER;
        double facingsFace = FACINGS_Z + BLOCK_CENTER;
        script.shotFrom(CameraView.of("top", facingsCenter, FACINGS_EYE_HEIGHT, facingsFace + FACINGS_EYE_BACK,
                facingsCenter, FACINGS_LOOK_HEIGHT, facingsFace), "facings");

        // The wall as it is built. The camera is the same for all four shots and frames the finished run, so the
        // only thing that changes between them is the rack — which is the comparison the shots exist for.
        double growthCenter = GROWTH_X + (GROWTH_LENGTH - 1) / 2.0 + BLOCK_CENTER;
        CameraView growthView = CameraView.of("aisle", growthCenter, GROWTH_EYE_HEIGHT, BLOCK_CENTER + GROWTH_DISTANCE,
                growthCenter, GROWTH_LOOK_HEIGHT, BLOCK_CENTER);
        for (int i = 0; i < GROWTH_SHOTS.length; i++) {
            int placed = GROWTH_SHOTS[i];
            int from = i == 0 ? 0 : GROWTH_SHOTS[i - 1];
            script.server("bays: grow the strip to " + placed + (placed == 1 ? " bay" : " bays"),
                            (server, context) -> growTo(server, context, from, placed))
                    .until("bays: wait until the client sees " + placed + " joined bays",
                            context -> growthReady(context, placed), GROWTH_READY_TIMEOUT_TICKS)
                    .camera(growthView)
                    .shot("grow-" + placed);
        }

        double tiersCenter = TIERS_X + (TIERS_WIDTH - 1) / 2.0 + BLOCK_CENTER;
        script.shotFrom(CameraView.of("front", tiersCenter, TIERS_EYE_HEIGHT, BLOCK_CENTER + TIERS_DISTANCE,
                tiersCenter, TIERS_LOOK_HEIGHT, BLOCK_CENTER), "tiers");
        double columnCenter = COLUMN_X + COLUMN_GAP / 2.0 + BLOCK_CENTER;
        script.shotFrom(CameraView.of("front", columnCenter, COLUMN_EYE_HEIGHT, BLOCK_CENTER + COLUMN_DISTANCE,
                columnCenter, COLUMN_LOOK_HEIGHT, BLOCK_CENTER), "column");
    }

    /**
     * Grows the strip from {@code from} to {@code to} bays wide, on the server thread, and asserts the derived join
     * flags of every bay in it afterwards — so a strip that happens to look right while the flags are wrong fails
     * the run rather than being photographed. The first step clears the strip, because the second render pass runs
     * the whole tour again over the world the first one left behind.
     */
    private static void growTo(MinecraftServer server, VisualContext context, int from, int to) {
        ServerLevel level = server.overworld();
        BlockPos foot = context.origin().offset(GROWTH_X, 0, 0);
        if (from == 0) {
            // Emptied before it is removed, and that is not tidiness: breaking a bay with goods in it RESETS it, so
            // the second render pass would clear away twelve full bays from the first one and leave twelve pallets
            // standing in the shot with their loads written over them (ADR-046). That really happened the first time
            // this step was run, which is the whole argument for shooting both passes.
            for (BlockPos pos : BlockPos.betweenClosed(foot, foot.offset(GROWTH_LENGTH - 1, GROWTH_HEIGHT - 1, 0))) {
                BlockPos at = pos.immutable();
                if (level.getBlockEntity(at) instanceof RackBayBlockEntity bay)
                    bay.clearContent();
                level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            }
            List<PalletEntity> left = level.getEntitiesOfClass(PalletEntity.class,
                    new AABB(foot).inflate(GROWTH_LENGTH + GROWTH_HEIGHT));
            if (!left.isEmpty())
                throw new VisualTestException(left.size() + " pallet(s) are standing in the growth strip at " + foot
                        + ", so a bay was broken with goods still in it");
        }
        for (int column = from; column < to; column++) {
            for (int row = 0; row < GROWTH_HEIGHT; row++)
                bay(level, foot.offset(column, row, 0), Direction.NORTH, GROWTH_ITEM,
                        1 + (column + row) % RackBayBlock.FILL_LEVELS);
        }
        for (int column = 0; column < to; column++) {
            for (int row = 0; row < GROWTH_HEIGHT; row++) {
                BlockPos at = foot.offset(column, row, 0);
                BlockState state = level.getBlockState(at);
                if (state.getValue(RackBayBlock.LEFT) != (column > 0)
                        || state.getValue(RackBayBlock.RIGHT) != (column < to - 1))
                    throw new VisualTestException("the bay at " + at + " of a strip " + to + " wide joined left="
                            + state.getValue(RackBayBlock.LEFT) + " right=" + state.getValue(RackBayBlock.RIGHT));
            }
        }
    }

    @Override
    public String status(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        StringJoiner joiner = new StringJoiner(" ");
        joiner.add("wall=" + WALL_WIDTH + "x" + WALL_HEIGHT);
        if (level == null) {
            joiner.add("client=none");
            return joiner.toString();
        }
        if (level.getBlockEntity(context.origin().offset(WALL_AT)) instanceof RackBayBlockEntity bay)
            joiner.add("first=" + bay.storedKey().map(ItemKey::getItem).map(String::valueOf).orElse("empty") + "x"
                    + bay.storedCount() + "/fill" + bay.fillStep());
        joiner.add("viewDistance=" + viewDistance(context));
        joiner.add(anchorDescription());
        Budget budget = lastBudget.get();
        if (budget != null)
            joiner.add("budget[" + budget + "]");
        return joiner.toString();
    }

    // --- build (server thread) -------------------------------------------------------------------------------------

    private static void build(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        BlockPos groundColumn = new BlockPos(0, level.getMinBuildHeight(), 0);
        if (!level.isLoaded(groundColumn))
            throw new VisualTestException("the chunk of the scene origin is not loaded");
        BlockPos origin = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
        context.setOrigin(origin);
        clear(level, origin);

        // The hundred-bay wall: one item type everywhere, the fill level cycling along each row so that the near and
        // the far shot both show every step of the silhouette.
        int steps = RackBayBlock.FILL_LEVELS + 1;
        for (int row = 0; row < WALL_HEIGHT; row++) {
            for (int column = 0; column < WALL_WIDTH; column++) {
                int fill = 1 + (row * WALL_WIDTH + column) % (steps - 1);
                bay(level, origin.offset(WALL_AT).offset(column, row, 0), Direction.NORTH, WALL_ITEM, fill);
            }
        }
        // The two sample walls: the bottom row full, the top row at the lowest step, because the drawn item has to be
        // visible at both — at fill 4 the cartons cover the pallet's whole footprint.
        sampleWall(level, origin.offset(FLAT_X, 0, SAMPLE_Z), FLAT_ITEMS);
        sampleWall(level, origin.offset(SOLID_X, 0, SAMPLE_Z), SOLID_ITEMS);
        // One bay per facing.
        for (int i = 0; i < FACINGS.size(); i++)
            bay(level, origin.offset(i * FACINGS_SPACING, 0, FACINGS_Z), FACINGS.get(i), FACINGS_ITEM, 1);
        tierWall(level, origin.offset(TIERS_X, 0, 0));
        columnPair(level, origin.offset(COLUMN_X, 0, 0));
    }

    /**
     * The tier wall: {@value #TIERS_WIDTH} bays wide, one row per material in the order the column rule allows, each
     * row one item at its own fill level. The legality is asserted rather than assumed — no bay of it may come out
     * overloaded, and the rule must refuse the same wall built the other way up.
     */
    private static void tierWall(ServerLevel level, BlockPos pos) {
        for (int row = 0; row < TIER_ROWS.size(); row++) {
            for (int column = 0; column < TIERS_WIDTH; column++)
                bay(level, pos.offset(column, row, 0), TIER_ROWS.get(row).getDefaultState(), Direction.NORTH,
                        TIER_ITEMS.get(row), TIER_ROWS.size() - row);
        }
        for (int row = 0; row < TIER_ROWS.size(); row++) {
            for (int column = 0; column < TIERS_WIDTH; column++) {
                BlockPos at = pos.offset(column, row, 0);
                if (RackBayBlock.isOverloaded(level.getBlockState(at)))
                    throw new VisualTestException("the tier wall's bay at " + at + " came out overloaded, so its rows "
                            + "are not in the order the column rule allows");
            }
        }
        BlockPos onTop = pos.above(TIER_ROWS.size());
        if (RackBayBlock.columnAccepts(level, onTop, BayTier.BRASS))
            throw new VisualTestException("the column rule allows a brass bay on top of the tier wall at " + onTop);
        if (!RackBayBlock.columnAccepts(level, onTop, BayTier.WOOD))
            throw new VisualTestException("the column rule refuses a wooden bay on top of the tier wall at " + onTop);
    }

    /**
     * The column pair: two identical wooden columns, and a brass bay set on the right-hand one with
     * {@code setBlockAndUpdate} — a command, which is the only way into an illegal column, because
     * {@code getStateForPlacement} refuses the click (ADR-044). Then the two things a picture cannot check are
     * checked here: the cap really overloads every bay under it, and below the cap the two columns carry the
     * <b>same block state apart from that flag</b>, which is what "an overloaded bay looks exactly like any other"
     * means in the world rather than in the generated blockstate file.
     */
    private static void columnPair(ServerLevel level, BlockPos pos) {
        BlockPos legal = pos;
        BlockPos overloaded = pos.offset(COLUMN_GAP, 0, 0);
        for (int row = 0; row < COLUMN_HEIGHT; row++) {
            int fill = RackBayBlock.FILL_LEVELS - row;
            for (BlockPos foot : List.of(legal, overloaded))
                bay(level, foot.above(row), WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), Direction.NORTH,
                        COLUMN_ITEM, fill);
        }
        BlockPos cap = overloaded.above(COLUMN_HEIGHT);
        if (RackBayBlock.columnAccepts(level, cap, BayTier.BRASS))
            throw new VisualTestException("the column rule allows a brass bay at " + cap + ", so a command is not "
                    + "needed to build the illegal column and the exhibit proves nothing");
        bay(level, cap, WareworksBlocks.RACK_BAY_BRASS.getDefaultState(), Direction.NORTH, COLUMN_ITEM, 1);
        for (int row = 0; row < COLUMN_HEIGHT; row++) {
            BlockState plain = level.getBlockState(legal.above(row));
            BlockState under = level.getBlockState(overloaded.above(row));
            if (RackBayBlock.isOverloaded(plain))
                throw new VisualTestException("the legal column's bay at " + legal.above(row) + " is overloaded");
            if (!RackBayBlock.isOverloaded(under))
                throw new VisualTestException("the bay at " + overloaded.above(row) + " under a brass cap is not "
                        + "overloaded, so the exhibit shows two legal columns");
            if (!plain.setValue(RackBayBlock.OVERLOADED, true).equals(under))
                throw new VisualTestException("the overloaded bay at " + overloaded.above(row) + " differs from the "
                        + "legal one beside it in more than the overload flag: " + under + " against " + plain);
        }
    }

    private static void clear(ServerLevel level, BlockPos origin) {
        BlockPos from = origin.offset(-CLEAR_MARGIN, 0, -CLEAR_MARGIN);
        BlockPos to = origin.offset(COLUMN_X + COLUMN_GAP + CLEAR_MARGIN, CLEAR_HEIGHT, FACINGS_Z + CLEAR_MARGIN);
        for (BlockPos pos : BlockPos.betweenClosed(from, to))
            level.setBlockAndUpdate(pos.immutable(), Blocks.AIR.defaultBlockState());
    }

    /** A wall {@value #SAMPLE_WIDTH} wide and {@value #SAMPLE_HEIGHT} high, one item per bay, bottom row full. */
    private static void sampleWall(ServerLevel level, BlockPos pos, List<Item> items) {
        if (items.size() != SAMPLE_WIDTH * SAMPLE_HEIGHT)
            throw new VisualTestException("a sample wall needs exactly " + SAMPLE_WIDTH * SAMPLE_HEIGHT + " items");
        for (int row = 0; row < SAMPLE_HEIGHT; row++) {
            for (int column = 0; column < SAMPLE_WIDTH; column++) {
                int fill = row == 0 ? RackBayBlock.FILL_LEVELS : 1;
                bay(level, pos.offset(column, row, 0), Direction.NORTH, items.get(row * SAMPLE_WIDTH + column), fill);
            }
        }
    }

    /**
     * One wooden bay at {@code pos}, turned onto {@code facing} and filled with {@code item} up to {@code fillStep} of
     * its own capacity. The joins are derived here rather than left to the scheduled repair tick, so a wall is right in
     * the server step it is built in, and both the fill step and the stored key are checked afterwards — a shot of a
     * half-built wall would be worse than no shot.
     */
    private static void bay(ServerLevel level, BlockPos pos, Direction facing, Item item, int fillStep) {
        bay(level, pos, WareworksBlocks.RACK_BAY_WOOD.getDefaultState(), facing, item, fillStep);
    }

    /** The same, for a bay of a named tier. */
    private static void bay(ServerLevel level, BlockPos pos, BlockState tier, Direction facing, Item item,
                            int fillStep) {
        BlockState state = tier.setValue(RackBayBlock.FACING, facing);
        level.setBlockAndUpdate(pos, RackBayBlock.withJoins(level, pos, state));
        if (!(level.getBlockEntity(pos) instanceof RackBayBlockEntity entity))
            throw new VisualTestException("no rack bay block entity at " + pos);
        ItemKey key = ItemKey.of(item);
        long capacity = entity.capacityFor(key);
        int amount = (int) Math.max(1, capacity * fillStep / RackBayBlock.FILL_LEVELS);
        ItemStack rest = entity.insert(key.toStack(amount), false);
        if (!rest.isEmpty())
            throw new VisualTestException("the bay at " + pos + " refused " + rest.getCount() + " " + item);
        int shown = level.getBlockState(pos).getValue(RackBayBlock.FILL);
        if (shown != fillStep)
            throw new VisualTestException("the bay at " + pos + " shows fill " + shown + " instead of " + fillStep);
    }

    // --- client -----------------------------------------------------------------------------------------------------

    /** Every bay of the scene has arrived on the client and carries the contents the server gave it. */
    private static boolean clientReady(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        for (BlockPos pos : scenePositions(context.origin())) {
            if (!(level.getBlockEntity(pos) instanceof RackBayBlockEntity bay) || bay.storedKey().isEmpty())
                return false;
        }
        return true;
    }

    /**
     * The client sees a strip exactly {@code width} bays wide, each with its contents <b>and</b> with the join flags
     * the wall calls for — the flags are what the shot is of, so waiting for them rather than for a tick count is
     * what makes the picture the thing that was asserted.
     */
    private static boolean growthReady(VisualContext context, int width) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return false;
        BlockPos foot = context.origin().offset(GROWTH_X, 0, 0);
        for (int column = 0; column < GROWTH_LENGTH; column++) {
            for (int row = 0; row < GROWTH_HEIGHT; row++) {
                BlockPos at = foot.offset(column, row, 0);
                BlockState state = level.getBlockState(at);
                boolean wanted = column < width;
                if (state.getBlock() instanceof RackBayBlock != wanted)
                    return false;
                if (!wanted)
                    continue;
                if (!(level.getBlockEntity(at) instanceof RackBayBlockEntity bay) || bay.storedKey().isEmpty())
                    return false;
                if (state.getValue(RackBayBlock.LEFT) != (column > 0)
                        || state.getValue(RackBayBlock.RIGHT) != (column < width - 1))
                    return false;
            }
        }
        return true;
    }

    /** Every bay this scenario builds, in build order. */
    private static List<BlockPos> scenePositions(BlockPos origin) {
        List<BlockPos> all = new ArrayList<>();
        for (int row = 0; row < WALL_HEIGHT; row++)
            for (int column = 0; column < WALL_WIDTH; column++)
                all.add(origin.offset(WALL_AT).offset(column, row, 0));
        for (int x : new int[] {FLAT_X, SOLID_X})
            for (int row = 0; row < SAMPLE_HEIGHT; row++)
                for (int column = 0; column < SAMPLE_WIDTH; column++)
                    all.add(origin.offset(x + column, row, SAMPLE_Z));
        for (int i = 0; i < FACINGS.size(); i++)
            all.add(origin.offset(i * FACINGS_SPACING, 0, FACINGS_Z));
        for (int row = 0; row < TIER_ROWS.size(); row++)
            for (int column = 0; column < TIERS_WIDTH; column++)
                all.add(origin.offset(TIERS_X + column, row, 0));
        for (int row = 0; row < COLUMN_HEIGHT; row++)
            for (int x : new int[] {COLUMN_X, COLUMN_X + COLUMN_GAP})
                all.add(origin.offset(x, row, 0));
        all.add(origin.offset(COLUMN_X + COLUMN_GAP, COLUMN_HEIGHT, 0));
        return all;
    }

    /**
     * What a wall of bays costs this frame, measured on the client's own per-frame render list rather than argued
     * about.
     * <p>
     * {@code LevelRenderer#iterateVisibleBlockEntities} walks exactly the lists {@code renderBlockEntities} walks:
     * every block entity of a visible chunk section whose type has a renderer at all
     * ({@code SectionCompiler#handleBlockEntity}). For each of them a frame pays a frustum test against the renderer's
     * bounding box ({@code ClientHooks#isBlockEntityRendererVisible}) and, inside the dispatcher, a squared distance
     * against {@code BlockEntityRenderer#getViewDistance}. <b>Those two are what is timed here</b>, repeated
     * {@value #TIMING_PASSES} times so one frame's figure is not one frame's noise — and they are the whole of the
     * frame's work only for a bay the frustum test rejects. For a bay that is in frustum and beyond the cap, which is
     * the case the {@code far} camera measures, a frame also pays the dispatcher's two {@code getRenderer} lookups, a
     * {@code pushPose}/{@code translate}/{@code popPose}, a {@code destructionProgress} map lookup and the
     * {@code hasLevel} and {@code getType().isValid} checks ({@code LevelRenderer#renderBlockEntities},
     * {@code BlockEntityRenderDispatcher#render}). That is a few tens of nanoseconds against the ~15 ns measured, so
     * the figure logged below <b>understates</b> the real cost a little and never overstates it.
     * <p>
     * What it <b>asserts</b> is the shape of the design's promise and not the three figures: beyond the cap not one
     * bay of a hundred may be drawn, from the aisle at least one must be, and the edge camera must catch the cap in
     * the act with some of the wall inside it and some outside. The counts and the nanoseconds themselves are
     * recorded — in the log line below and in the run's {@code index.txt} — for a person to read, not asserted, so a
     * camera moved by a block does not fail a run that still proves what it is for.
     */
    private void measureBudget(VisualContext context, String label) {
        Minecraft minecraft = context.minecraft();
        BlockPos wallFrom = context.origin().offset(WALL_AT);
        BlockPos wallTo = wallFrom.offset(WALL_WIDTH - 1, WALL_HEIGHT - 1, 0);
        List<RackBayBlockEntity> all = new ArrayList<>();
        List<RackBayBlockEntity> wall = new ArrayList<>();
        minecraft.levelRenderer.iterateVisibleBlockEntities(be -> {
            if (!(be instanceof RackBayBlockEntity bay))
                return;
            all.add(bay);
            BlockPos pos = bay.getBlockPos();
            // The timing is about the wall and nothing else, so the figure means what the sentence about it says.
            if (pos.getX() >= wallFrom.getX() && pos.getX() <= wallTo.getX() && pos.getY() >= wallFrom.getY()
                    && pos.getY() <= wallTo.getY() && pos.getZ() == wallFrom.getZ())
                wall.add(bay);
        });
        if (wall.size() != WALL_WIDTH * WALL_HEIGHT)
            throw new VisualTestException("only " + wall.size() + " of the " + WALL_WIDTH * WALL_HEIGHT
                    + " bays of the wall are in the client's per-frame list at the " + label + " camera");
        BlockEntityRenderer<RackBayBlockEntity> renderer =
                minecraft.getBlockEntityRenderDispatcher().getRenderer(wall.getFirst());
        if (renderer == null)
            throw new VisualTestException("the rack bay block entity type has no renderer");
        Frustum frustum = minecraft.levelRenderer.getFrustum();
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();

        int drawn = 0;
        for (RackBayBlockEntity bay : wall) {
            if (frustum.isVisible(renderer.getRenderBoundingBox(bay)) && renderer.shouldRender(bay, camera))
                drawn++;
        }
        // The same work a frame does for the bays it ends up drawing nothing for, timed. The counter is kept so the
        // loop cannot be optimised away.
        int sink = 0;
        long start = System.nanoTime();
        for (int pass = 0; pass < TIMING_PASSES; pass++) {
            for (RackBayBlockEntity bay : wall) {
                if (frustum.isVisible(renderer.getRenderBoundingBox(bay)) && renderer.shouldRender(bay, camera))
                    sink++;
            }
        }
        long nanosPerFrame = (System.nanoTime() - start) / TIMING_PASSES;
        if (sink != drawn * TIMING_PASSES)
            throw new VisualTestException("the timed culling disagreed with the counted one: " + sink + " vs " + drawn);

        Budget budget = new Budget(label, all.size(), wall.size(), drawn, nanosPerFrame);
        lastBudget.set(budget);
        VisualTestHarness.LOGGER.info(VisualTestHarness.PREFIX + "budget {} (view distance {} blocks)", budget,
                viewDistance(context));
        if (label.equals("far") && drawn != 0)
            throw new VisualTestException(drawn + " of " + wall.size() + " bays are still drawn at " + FAR_DISTANCE
                    + " blocks, so the view distance cap does not hold");
        if (label.equals("aisle") && drawn == 0)
            throw new VisualTestException("no bay is drawn from the aisle, so the far shot proves nothing");
        // The edge camera is the cap caught in the act: the bays straight ahead are inside it and the ones along the
        // wall are not, so a shot of it is only worth taking while both are true.
        if (label.equals("edge") && (drawn == 0 || drawn == wall.size()))
            throw new VisualTestException(drawn + " of " + wall.size() + " bays are drawn at " + EDGE_DISTANCE
                    + " blocks, so the shot shows no edge at all");
    }

    /** The cap the renderer reports, so the log says what the client's own configuration made of it. */
    private static int viewDistance(VisualContext context) {
        ClientLevel level = context.minecraft().level;
        if (level == null)
            return -1;
        BlockPos first = context.origin().offset(WALL_AT);
        if (!(level.getBlockEntity(first) instanceof RackBayBlockEntity bay))
            return -1;
        BlockEntityRenderer<RackBayBlockEntity> renderer =
                context.minecraft().getBlockEntityRenderDispatcher().getRenderer(bay);
        return renderer == null ? -1 : renderer.getViewDistance();
    }

    /** Keeps the renderer's own anchor constants in the dev harness's sight, so a shot can be read against them. */
    static String anchorDescription() {
        return String.format(Locale.ROOT, "item x=%.1f y=%.1f backZ=%.1f px", RackBayRenderer.ITEM_X_PX,
                RackBayRenderer.ITEM_BASE_Y_PX, RackBayRenderer.ITEM_BACK_Z_PX);
    }
}
