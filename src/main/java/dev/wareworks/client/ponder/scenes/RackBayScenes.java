package dev.wareworks.client.ponder.scenes;

import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.tterrag.registrate.util.entry.BlockEntry;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.PalletEntity;
import dev.wareworks.content.storage.RackBayBlock;
import dev.wareworks.content.storage.StorageFilterValueBox;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.storage.BayTier;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksEntityTypes;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Ponder scenes of the rack bay (M28, issue #20, ADR-048).
 * <p>
 * <b>The order of the three scenes is the decision, not an accident.</b> A wooden bay is the cheapest thing this mod
 * makes after a rail and nothing in its crafting chain needs a machine, so many players meet this block as a better
 * barrel long before they own a crane — which is why {@link #rackBay} teaches the block <b>standing alone</b>,
 * {@link #rackWall} the wall it grows into, and only {@link #baysInAnAisle} brings a warehouse to it. Every other scene
 * of this mod starts from the warehouse; this one is deliberately the other way round, and the registration order in
 * {@code WareworksPonderScenes} is what makes a player meet them in that order (scene order per component is
 * registration order).
 * <p>
 * As in {@link CraneScenes}, storyboards must stay level-free: they also run with {@code level == null} during lang
 * datagen, so everything that touches a real level goes inside an instruction. The order of the {@code .text(...)}
 * calls defines the {@code text_1 … text_n} lang keys of each scene id.
 *
 * <h2>Two things a Ponder level does not do for a bay</h2>
 * <ul>
 * <li><b>Neither derived block state follows the world.</b> {@link RackBayBlock#FILL} and
 * {@link RackBayBlock#OVERLOADED} are written by server-only code ({@code RackBayBlockEntity#onContentsChanged} and
 * {@code RackBayBlock#publishState} both return early for a client level), and a {@code PonderLevel} <i>is</i>
 * client-side. A scene therefore writes both into the block state itself — the same lesson
 * {@code PonderAisle#placePort} recorded for the port's two halves.</li>
 * <li><b>A pallet cannot be spawned.</b> {@code PalletEntity#spawn} refuses a client level outright, so the break beat
 * builds the entity through {@code createEntity} and gives it its load with the documented display hook
 * {@link PalletEntity#showClientLoad} — the pallet's counterpart of {@code StackerCraneBlockEntity#showClientPose}.</li>
 * </ul>
 *
 * <h2>Every caption idles at least as long as it is shown</h2>
 * A rule, not a habit: Ponder draws a caption until its own duration runs out, so a caption shown for longer than the
 * scene afterwards idles is still on screen when the next one appears, and two captions are drawn at the same anchor at
 * once. It is invisible on a scene whose captions point at different blocks and unmissable on one whose captions all
 * point at the same block — which is every caption of {@link #rackBay}. {@link #TEXT_IDLE} is therefore larger than
 * {@link #TEXT_TICKS} and {@link #SHORT_IDLE} larger than {@link #SHORT_TICKS}, and a beat that has to animate
 * something splits its <i>idle</i> instead of stretching its text.
 */
public final class RackBayScenes {
    private static final int TEXT_TICKS = 70;
    private static final int TEXT_IDLE = 80;
    private static final int SHORT_TICKS = 55;
    private static final int SHORT_IDLE = 65;
    private static final int FADE_IDLE = 15;
    private static final int CONTROL_TICKS = 40;
    /** Ticks between a {@code showControls} and the caption that explains it, so the icon is seen first. */
    private static final int CONTROL_LEAD = 7;
    /** How long the crane holds a fully extended arm while it transfers; long enough to read and to screenshot. */
    private static final int TRANSFER_TICKS = 40;
    /** Ticks each visible fill step is held while a bay fills up. */
    private static final int FILL_STEP_TICKS = 20;

    /** Y of everything standing on a base plate, as in {@link PonderAisle#FLOOR_Y}. */
    private static final int FLOOR_Y = PonderAisle.FLOOR_Y;

    /**
     * The face of a bay that the viewer sees in the two aisle-free scenes. Ponder draws a block's <b>north</b> and
     * <b>west</b> faces, so a bay whose readable front faces north is the only way the window, the arm port and the
     * filter slot are visible at all — and a bay's front is {@code FACING.getOpposite()}, so {@link #VIEWED_FACING}
     * points south, into the rack depth and away from the camera.
     */
    private static final Direction VIEWED_FRONT = Direction.NORTH;
    private static final Direction VIEWED_FACING = VIEWED_FRONT.getOpposite();

    /**
     * Base plate of the standalone scene, and the smallest of all the Wareworks scenes on purpose — see
     * {@link #SINGLE_SCALE}.
     */
    private static final int PLATE_SINGLE = 3;
    /**
     * How large one block is drawn in the standalone scene, and the one number that decides whether that scene works.
     * Ponder renders a scene at {@code 30 * scaleFactor} units per block <b>whatever the base plate is</b>
     * ({@code PonderScene.SceneTransform#refreshMatrix}); the plate size only centres it. The first cut used a square 5
     * plate at 1.2 and the second a square 3 plate at the default 1, and both drew the bay about the size of a
     * fingernail — its window, its fill level and the pallet it leaves were all unreadable on the one scene whose whole
     * job is to show what that block looks like. Create's own scenes sit between 0.5 and 0.95 because they show a whole
     * machine; this one shows a single block, so it is deliberately the largest scale in the mod, and the plate is cut
     * to 3 so the scene still fits the frame around it.
     */
    private static final float SINGLE_SCALE = 2.0f;
    /** Base plate of the wall scene: three bays wide and three high. */
    private static final int PLATE_WALL = 7;

    /** The bulk item every scene stores; cobblestone is what a bay is for. */
    private static final ItemStack BULK = new ItemStack(Items.COBBLESTONE);
    /** A second item type, for the beat that shows a bay holding one type only. */
    private static final ItemStack OTHER = new ItemStack(Items.DIRT);
    /** What the scene's wooden bay holds once it is full: 64 stacks of cobblestone, the shipped wooden capacity. */
    private static final int WOOD_LOAD = BayTier.WOOD.defaultStacks() * 64;
    /** Items the crane carries into a bay in {@link #baysInAnAisle}. */
    private static final int STORE_AMOUNT = 32;

    private RackBayScenes() {
    }

    // --- shared helpers ---------------------------------------------------------------------------------------------

    private static BlockEntry<RackBayBlock> bayBlock(BayTier tier) {
        return switch (tier) {
            case WOOD -> WareworksBlocks.RACK_BAY_WOOD;
            case ANDESITE -> WareworksBlocks.RACK_BAY_ANDESITE;
            case BRASS -> WareworksBlocks.RACK_BAY_BRASS;
        };
    }

    /** A bay of {@code tier} at fill step {@code fill}, with {@code facing} pointing into the rack depth. */
    private static BlockState bayState(BayTier tier, int fill, Direction facing) {
        return bayBlock(tier).getDefaultState()
                .setValue(HorizontalDirectionalBlock.FACING, facing)
                .setValue(RackBayBlock.OVERLOADED, false)
                .setValue(RackBayBlock.FILL, fill);
    }

    /**
     * The store filter slot of a bay as a scene vector: centred on its aisle face, but
     * {@link StorageFilterValueBox#CENTER_Y_PIXELS} px above the block's bottom edge instead of at the face's centre,
     * so an arrow or a caption points at the box a player really clicks. It is the same helper
     * {@code WarehouseScenes#filterSlot} is for an interface, because a bay carries literally the same behaviour on the
     * same transform (§3.1.1, ADR-021).
     */
    private static Vec3 filterSlot(SceneBuildingUtil util, BlockPos bay, Direction front) {
        double belowCenter = (8.0 - StorageFilterValueBox.CENTER_Y_PIXELS) / 16.0;
        return util.vector().blockSurface(bay, front).subtract(0, belowCenter, 0);
    }

    /**
     * A pallet standing on the bottom of {@code pos} carrying {@code load} items of {@code item}, for
     * {@code createEntity}. Built exactly as {@code PalletEntity#spawn} builds one, minus the part only a server can
     * do: {@link PalletEntity#showClientLoad} stands in for the tracked values a sync packet would have brought.
     */
    private static Entity pallet(Level level, BlockPos pos, ItemStack item, int load) {
        PalletEntity pallet = WareworksEntityTypes.PALLET.create(level);
        if (pallet == null)
            throw new IllegalStateException("the pallet entity type refused to create an entity");
        pallet.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
        pallet.showClientLoad(ItemKey.of(item), load);
        return pallet;
    }

    // --- 1. the bay on its own --------------------------------------------------------------------------------------

    /**
     * What a rack bay is, with no warehouse anywhere: one block that holds one item type and a great deal of it,
     * filled and emptied by hand, readable from the outside, and <b>reset</b> rather than emptied into the world when
     * it is broken.
     * <p>
     * This is the scene most players will see first, so it carries no controller, no crane, no rail and no interface at
     * all. That is not a simplification for teaching — it is what the block really does
     * ({@code docs/warehouse-system.md} §3.8, "Standalone, before any warehouse exists").
     */
    public static void rackBay(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("rack_bay", "Storing Bulk Goods in a Rack Bay");
        scene.configureBasePlate(0, 0, PLATE_SINGLE);
        scene.scaleSceneView(SINGLE_SCALE);

        BlockPos bay = util.grid().at(1, FLOOR_Y, 1);
        // Where the load lands once the bay is broken. Not the bay's own position, although that is where
        // PalletEntity#spawn really puts it: the empty bay comes back there, and a pallet is barely half a block
        // high, so the two would be drawn inside each other. West is the one free neighbour whose pallet cannot
        // stand in front of the window either — the window faces VIEWED_FRONT.
        BlockPos palletPos = bay.relative(Direction.WEST);
        Selection baySelection = util.select().position(bay);
        Vec3 front = util.vector().blockSurface(bay, VIEWED_FRONT);
        Vec3 onTop = util.vector().topOf(bay);
        Vec3 onThePallet = util.vector().centerOf(palletPos);

        scene.world().setBlock(bay, bayState(BayTier.WOOD, 0, VIEWED_FACING), false);

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(baySelection, Direction.DOWN);
        scene.idle(FADE_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("A Rack Bay is a storage location in a single block: nothing in front of it, nothing behind it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(front);
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("It holds one item type and a great deal of it: 64 stacks in a wooden bay")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(onTop);
        scene.idle(TEXT_IDLE);

        // --- filling and emptying it by hand ------------------------------------------------------------------------
        scene.overlay().showControls(front, Pointing.DOWN, CONTROL_TICKS)
                .rightClick()
                .withItem(BULK);
        scene.idle(CONTROL_LEAD);
        scene.world().modifyBlock(bay, state -> state.setValue(RackBayBlock.FILL, 1), false);
        scene.overlay().showText(TEXT_TICKS)
                .text("Right-Click with an item and one goes in; even the first item already shows on the front")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(front);
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(front, Pointing.DOWN, CONTROL_TICKS)
                .rightClick()
                .whileSneaking()
                .withItem(BULK);
        scene.idle(CONTROL_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("Hold Shift and a whole stack goes in at once")
                .placeNearTarget()
                .pointAt(front);
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(front, Pointing.DOWN, CONTROL_TICKS)
                .rightClick();
        scene.idle(CONTROL_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("An empty hand takes items back out: one of them, or a stack with Shift")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(front);
        scene.idle(TEXT_IDLE);

        // --- what the front says ------------------------------------------------------------------------------------
        scene.overlay().showText(TEXT_TICKS)
                .text("The load behind the window grows as the bay fills, so you read how full it is by walking past")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(front);
        for (int fill = 2; fill <= RackBayBlock.FILL_LEVELS; fill++) {
            int step = fill;
            scene.idle(FILL_STEP_TICKS);
            scene.world().modifyBlock(bay, state -> state.setValue(RackBayBlock.FILL, step), false);
        }
        scene.idle(TEXT_IDLE - (RackBayBlock.FILL_LEVELS - 1) * FILL_STEP_TICKS);

        scene.overlay().showControls(front, Pointing.RIGHT, CONTROL_TICKS)
                .withItem(AllItems.GOGGLES.asStack());
        scene.idle(CONTROL_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("Goggles read out the exact count, the capacity and the material the bay is built from")
                .placeNearTarget()
                .pointAt(front);
        scene.idle(TEXT_IDLE);

        // --- one type at a time -------------------------------------------------------------------------------------
        scene.overlay().showControls(front, Pointing.DOWN, CONTROL_TICKS)
                .rightClick()
                .withItem(OTHER);
        scene.idle(CONTROL_LEAD);
        scene.overlay().showOutline(PonderPalette.RED, "one type", baySelection, SHORT_TICKS);
        scene.overlay().showText(SHORT_TICKS)
                .text("A second item type is refused: a bay with no filter keeps the first type that arrived in it")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(front);
        scene.idle(SHORT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("Empty it and it forgets again, so you can put up a wall of bays and let it fill itself")
                .placeNearTarget()
                .pointAt(onTop);
        scene.idle(TEXT_IDLE);

        // --- breaking it --------------------------------------------------------------------------------------------
        scene.overlay().showText(TEXT_TICKS)
                .text("Breaking a full bay resets it: the empty bay comes back and its load stays in the world")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(front);
        scene.idle(TEXT_IDLE);

        scene.world().destroyBlock(bay);
        scene.idle(FADE_IDLE);
        // Both halves of the caption above, in one frame: the load on a pallet AND the empty bay standing again. A
        // frame holding nothing but a pallet showed the loss and not the reset, which is the half the issue cared
        // about most ("you get an empty bay block and a pallet lying on the floor").
        scene.world().createEntity(level -> pallet(level, palletPos, BULK, WOOD_LOAD));
        scene.world().setBlock(bay, bayState(BayTier.WOOD, 0, VIEWED_FACING), false);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("However much was inside, it lands on one Pallet, with the item and the count written above it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(onThePallet);
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(onThePallet, Pointing.DOWN, CONTROL_TICKS)
                .rightClick()
                .whileSneaking();
        scene.idle(CONTROL_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("You refill by hand with the bay's own gesture, and a Pallet cannot be picked up or carried away")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(onThePallet);
        scene.idle(TEXT_IDLE);

        // The one caption of this scene whose German could not be a translation: Minecraft's Hopper and Create's
        // Funnel are both "Trichter" in German, and this sentence turns on telling them apart. Both languages
        // therefore name the mod rather than relying on the block names (see de_de.json).
        scene.overlay().showText(TEXT_TICKS)
                .text("A plain Hopper drains a Pallet, and a Deployer can; Create's Belts, Chutes and Funnels cannot")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(onThePallet);
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }

    // --- 2. the wall ------------------------------------------------------------------------------------------------

    /**
     * How bays make a rack wall, and the one rule that wall has: a bay may carry <b>nothing stronger</b> above it, so
     * upgrading a wall is a rebuild from the bottom up (ADR-044).
     * <p>
     * The wall stands on the plate's far row, so nothing of the scene is between the camera and the bays' fronts —
     * the rule {@code WarehouseScenes#storageFilters} already follows for the same reason.
     */
    public static void rackWall(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("rack_wall", "Building a Rack Wall");
        scene.configureBasePlate(0, 0, PLATE_WALL);
        scene.scaleSceneView(0.95f);

        int wallZ = 4;
        int firstX = 2;
        int lastX = 4;
        BlockPos bottom = util.grid().at(firstX, FLOOR_Y, wallZ);
        BlockPos middle = bottom.above();
        BlockPos top = middle.above();
        Selection bottomRow = util.select().fromTo(firstX, FLOOR_Y, wallZ, lastX, FLOOR_Y, wallZ);
        Selection middleRow = util.select().fromTo(firstX, FLOOR_Y + 1, wallZ, lastX, FLOOR_Y + 1, wallZ);
        Selection topRow = util.select().fromTo(firstX, FLOOR_Y + 2, wallZ, lastX, FLOOR_Y + 2, wallZ);
        Selection carrying = util.select().fromTo(firstX, FLOOR_Y, wallZ, lastX, FLOOR_Y + 1, wallZ);
        Selection column = util.select().fromTo(firstX, FLOOR_Y, wallZ, firstX, FLOOR_Y + 2, wallZ);

        scene.world().setBlocks(bottomRow, bayState(BayTier.BRASS, 4, VIEWED_FACING), false);
        scene.world().setBlocks(middleRow, bayState(BayTier.ANDESITE, 2, VIEWED_FACING), false);
        scene.world().setBlocks(topRow, bayState(BayTier.WOOD, 1, VIEWED_FACING), false);

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(bottomRow, Direction.DOWN);
        scene.idle(FADE_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("Set bays beside each other and a rack wall grows; a click on the side of one copies its direction")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(util.grid().at(lastX, FLOOR_Y, wallZ), VIEWED_FRONT));
        scene.idle(TEXT_IDLE);

        scene.world().showSection(middleRow, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().showSection(topRow, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("They stack as high as you like; the crane's Mast Height is what decides how far up it reaches")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(top));
        scene.idle(TEXT_IDLE);

        scene.overlay().showOutline(PonderPalette.BLUE, "tiers", column, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("The material decides how much one bay holds: 64 stacks of wood, 256 of andesite, 1,024 of brass")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(middle, VIEWED_FRONT));
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(util.vector().topOf(top), Pointing.DOWN, CONTROL_TICKS)
                .rightClick()
                .withItem(WareworksBlocks.RACK_BAY_BRASS.asStack());
        scene.idle(CONTROL_LEAD);
        scene.overlay().showOutline(PonderPalette.RED, "refused", topRow, SHORT_TICKS);
        scene.overlay().showText(SHORT_TICKS)
                .text("But a bay may carry nothing stronger above it: a brass bay on top of wood is refused outright")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().topOf(top));
        scene.idle(SHORT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("The rack underneath would give way, so the strong material goes low and a wall is upgraded bottom up")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(bottom, VIEWED_FRONT));
        scene.idle(TEXT_IDLE);

        // A column a command broke. Both derived flags are written by hand here: a PonderLevel runs neither the
        // placement rule that refuses this nor the neighbour update that would carry the flag down (class comment).
        scene.world().setBlocks(topRow, bayState(BayTier.BRASS, 1, VIEWED_FACING), false);
        scene.world().modifyBlocks(carrying, state -> state.setValue(RackBayBlock.OVERLOADED, true), false);
        scene.idle(FADE_IDLE);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "overloaded", carrying, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A command or a schematic can still build the wrong column, and every bay underneath says so in gold")
                .attachKeyFrame()
                .colored(PonderPalette.OUTPUT)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(middle, VIEWED_FRONT));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("They keep every item and the crane still fetches from them; the warehouse only stores nothing there")
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(bottom, VIEWED_FRONT));
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }

    // --- 3. the warehouse arrives -----------------------------------------------------------------------------------

    /**
     * What changes when a crane's aisle reaches a wall of bays: every bay becomes an addressable storage location, with
     * no warehouse interface in front of it and nothing attached behind it.
     * <p>
     * The bays stand on the {@link Side#RIGHT} plane, where their aisle face is north and therefore readable (the rule
     * in {@code WarehouseScenes}' class comment), and the hand-filled bays are shown <b>before</b> the aisle: the
     * warehouse arrives at the bay, not the other way round.
     */
    public static void baysInAnAisle(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("bays_in_an_aisle", "Rack Bays in a Warehouse Aisle");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        // Position 1 is left empty: the parked crane stands in front of it and would hide whatever is there. The rest
        // of the row sits as close to the dock as that allows, and not at the far end of the aisle, because Ponder
        // draws a caption up and to the right of what it points at: anchored on the far blocks of a nine-plate, every
        // box of this scene landed on top of the very members it was naming.
        int inputPosition = 2;
        int filledPosition = 3;
        int emptyPosition = 4;
        int interfacePosition = 5;
        Direction aisleFace = PonderAisle.outward(Side.RIGHT).getOpposite();

        BlockPos filled = aisle.rack(util, filledPosition, 0, Side.RIGHT);
        BlockPos empty = aisle.rack(util, emptyPosition, 0, Side.RIGHT);
        BlockPos interfaceRack = aisle.rack(util, interfacePosition, 0, Side.RIGHT);
        BlockPos input = aisle.rack(util, inputPosition, 0, Side.RIGHT);

        Selection bays = util.select().fromTo(filled.getX(), filled.getY(), filled.getZ(),
                empty.getX(), empty.getY(), empty.getZ());
        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), FLOOR_Y, aisle.aisleZ());
        Selection inputSelection = util.select().position(input);
        Selection funnel = util.select().position(filled.above());
        Selection longTail = util.select().fromTo(interfaceRack.getX(), interfaceRack.getY(), interfaceRack.getZ(),
                interfaceRack.getX(), interfaceRack.getY(), interfaceRack.getZ() + 1);

        aisle.placeAisle(scene, util);
        aisle.placeInput(scene, util, inputPosition, 0, Side.RIGHT);
        scene.world().setBlock(filled, bayState(BayTier.ANDESITE, 2, PonderAisle.outward(Side.RIGHT)), false);
        scene.world().setBlock(empty, bayState(BayTier.ANDESITE, 0, PonderAisle.outward(Side.RIGHT)), false);
        aisle.placeStorage(scene, util, interfacePosition, 0, Side.RIGHT);
        aisle.placeInsertingFunnelAbove(scene, filled);
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(bays, Direction.DOWN);
        scene.idle(FADE_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("Two rack bays, filled by hand, with no warehouse anywhere near them")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(filled, aisleFace));
        scene.idle(TEXT_IDLE);

        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().showSection(inputSelection, Direction.NORTH);
        scene.idle(FADE_IDLE + 5);
        scene.overlay().showText(TEXT_TICKS)
                .text("Lay the rails of an aisle in front of them and every bay becomes an addressable storage location")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(aisle.dock(util)));
        scene.idle(TEXT_IDLE);

        scene.overlay().showOutline(PonderPalette.GREEN, "address", util.select().position(filled), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("What a bay already held by hand is counted the moment the warehouse reaches it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(filled, aisleFace));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("There is no interface in front of a bay and nothing behind it: a fifty-bay wall is fifty blocks")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(empty, aisleFace));
        scene.idle(TEXT_IDLE);

        // --- the crane stores into a bay ----------------------------------------------------------------------------
        CraneScript crane = CraneScript.parkedAt(scene, aisle.dock(util));
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(inputPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.COBBLESTONE, STORE_AMOUNT);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(emptyPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(emptyPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);
        scene.overlay().showText(TEXT_TICKS)
                .text("The crane reaches in through the slot above the window, exactly as it does on an interface")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(empty, aisleFace));
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        crane.release();
        scene.world().modifyBlock(empty, state -> state.setValue(RackBayBlock.FILL, 1), false);
        scene.effects().indicateSuccess(empty);
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(emptyPosition, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.RIGHT), CranePhase.IDLE);
        scene.idle(10);

        // --- the filter slot ----------------------------------------------------------------------------------------
        scene.overlay().showFilterSlotInput(filterSlot(util, empty, aisleFace), CONTROL_TICKS);
        scene.idle(CONTROL_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("The filter slot under the arm port says what belongs here; holding the click sets a priority")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(filterSlot(util, empty, aisleFace));
        scene.idle(TEXT_IDLE);

        // --- machines reach it too ------------------------------------------------------------------------------------
        scene.world().showSection(funnel, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "machines", funnel, SHORT_TICKS);
        scene.overlay().showText(SHORT_TICKS)
                .text("And a Funnel, a Chute, a Belt or a Hopper fills a bay directly, which no other location allows")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(filled.above()));
        scene.idle(SHORT_IDLE);

        // --- what a bay is not for ----------------------------------------------------------------------------------
        scene.world().showSection(longTail, Direction.NORTH);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("A chest behind an interface stays the right answer for the items you only own three of")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(interfaceRack, aisleFace));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("Both kinds of storage live in one aisle: a bay for bulk goods, an interface for the long tail")
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(empty, aisleFace));
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }
}
