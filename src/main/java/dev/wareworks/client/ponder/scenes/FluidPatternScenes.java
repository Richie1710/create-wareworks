package dev.wareworks.client.ponder.scenes;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.funnel.AbstractDirectionalFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.core.address.Side;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Ponder scene of a production pattern with a <b>fluid cell</b> (issue #55, ADR-089): the crane brings the fluid as a
 * filled bucket, and the player's own machine, built with <b>no Mechanical Arm</b>, turns it into water in a Basin.
 * <p>
 * The machine is the same idea as the one the visual scenario {@code fluidpattern} runs for real (simplified here: one
 * sink, the pump beside the drain, station level 1; the scenario's exact positions are the tested ones): an extracting
 * brass funnel with the
 * filled bucket as its filter directly behind the station, an Item Drain under the funnel, a pump and a pipe from the
 * drain into the Basin, a Mechanical Mixer over the Basin, and an andesite funnel over a chest as the way out of the empty
 * bucket. The station stands at rack level 1 over a casing pillar, so that the drain fits under the funnel on the plate.
 * <p>
 * <b>What the scene must not imply.</b> The warehouse carries buckets; it never moves a millibucket by itself and it never
 * mixes anything. Two warnings are part of the story because both are real (spike of issue #55): an arm that can also
 * reach the Basin puts filled buckets into the Basin's item inventory, and a depot as the way out of the empty buckets
 * jams after the first one. Storyboards stay level-free (they also run during lang datagen); the order of the
 * {@code .text(...)} calls defines the {@code text_n} keys.
 */
public final class FluidPatternScenes {
    private static final int TEXT_TICKS = 70;
    private static final int TEXT_IDLE = 80;
    private static final int FADE_IDLE = 15;
    private static final int CONTROL_TICKS = 40;
    private static final int CLICK_LEAD = 7;
    private static final int POSITION = 3;
    /** The face of the machine behind the station that the camera reads: the back of the rack wall. */
    private static final Direction FRONT = Direction.NORTH;

    private FluidPatternScenes() {
    }

    /** A pattern with a fluid cell and the arm-free machine that uses it (issue #55). */
    public static void fluidPattern(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_fluid_pattern", "Fluid in a Production Pattern");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        BlockPos pillar = aisle.rack(util, POSITION, 0, Side.LEFT);
        BlockPos station = aisle.rack(util, POSITION, 1, Side.LEFT);
        BlockPos funnel = aisle.inventory(util, POSITION, 1, Side.LEFT);
        BlockPos drain = funnel.below();
        BlockPos sinkFunnel = drain.relative(Direction.NORTH);
        BlockPos chest = sinkFunnel.below();
        BlockPos pump = drain.east();
        BlockPos pipe = pump.east();
        BlockPos basin = pipe.east();
        BlockPos mixer = basin.above(2);
        BlockPos stock = aisle.rack(util, POSITION + 2, 0, Side.RIGHT);

        aisle.placeAisle(scene, util);
        scene.world().setBlock(pillar, AllBlocks.ANDESITE_CASING.getDefaultState(), false);
        aisle.placeProduction(scene, util, POSITION, 1, Side.LEFT);
        aisle.placeStorage(scene, util, POSITION + 2, 0, Side.RIGHT);
        scene.world().setBlock(funnel, AllBlocks.BRASS_FUNNEL.getDefaultState()
                .setValue(AbstractDirectionalFunnelBlock.FACING, Direction.NORTH)
                .setValue(FunnelBlock.EXTRACTING, true), false);
        scene.world().setBlock(drain, AllBlocks.ITEM_DRAIN.getDefaultState(), false);
        scene.world().setBlock(chest, Blocks.CHEST.defaultBlockState(), false);
        scene.world().setBlock(sinkFunnel, AllBlocks.ANDESITE_FUNNEL.getDefaultState()
                .setValue(AbstractDirectionalFunnelBlock.FACING, Direction.UP), false);
        scene.world().setBlock(pump, AllBlocks.MECHANICAL_PUMP.getDefaultState()
                .setValue(BlockStateProperties.FACING, Direction.EAST), false);
        scene.world().setBlock(pipe, AllBlocks.FLUID_PIPE.getDefaultState(), false);
        scene.world().setBlock(basin, AllBlocks.BASIN.getDefaultState(), false);
        scene.world().setBlock(mixer, AllBlocks.MECHANICAL_MIXER.getDefaultState(), false);
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ());
        Selection stationSelection = util.select().position(pillar).add(util.select().position(station));
        Selection funnelSelection = util.select().position(funnel);
        Selection drainSelection = util.select().position(drain).add(util.select().position(sinkFunnel))
                .add(util.select().position(chest));
        Selection pumpSelection = util.select().position(pump).add(util.select().position(pipe));
        Selection basinSelection = util.select().position(basin).add(util.select().position(mixer));

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.world().showSection(util.select().position(stock).add(util.select().position(
                aisle.inventory(util, POSITION + 2, 0, Side.RIGHT))), Direction.DOWN);
        scene.idle(FADE_IDLE + 5);
        scene.world().showSection(stationSelection, Direction.DOWN);
        scene.idle(FADE_IDLE);

        scene.overlay().showOutline(PonderPalette.GREEN, "station", util.select().position(station), TEXT_TICKS);
        scene.overlay().showControls(util.vector().topOf(station), Pointing.DOWN, CONTROL_TICKS).rightClick()
                .withItem(new ItemStack(Items.WATER_BUCKET));
        scene.idle(CLICK_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("A pattern may hold two fluid cells: click a filled bucket on one, the wheel sets the millibuckets")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(station));
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(util.vector().topOf(station), Pointing.DOWN, CONTROL_TICKS)
                .withItem(new ItemStack(Items.WATER_BUCKET));
        scene.idle(CLICK_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("The crane brings fluid in whole filled buckets, so choose amounts that divide 1000 mB")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(station, Direction.WEST));
        scene.idle(TEXT_IDLE);

        scene.world().showSection(funnelSelection, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showOutline(PonderPalette.INPUT, "funnel", funnelSelection, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("No arm needed: an extracting brass funnel filtered to the filled bucket takes it out of the station")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(funnel, FRONT));
        scene.idle(TEXT_IDLE);

        scene.world().showSection(drainSelection, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "sink", util.select().position(sinkFunnel)
                .add(util.select().position(chest)), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("The drain empties it; the empty bucket needs a real way out on every free side of the drain: a funnel over a chest, a belt or an input")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(drain, FRONT));
        scene.idle(TEXT_IDLE);

        scene.world().showSection(pumpSelection, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().showSection(basinSelection, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showOutline(PonderPalette.GREEN, "basin", basinSelection, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A pump and a pipe carry the water into the Basin under your Mixer")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(basin, FRONT));
        scene.idle(TEXT_IDLE);

        scene.overlay().showOutline(PonderPalette.RED, "arm", util.select().position(basin), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("Keep Mechanical Arms away from the Basin: one that reaches it puts filled buckets into its slots")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(basin, FRONT));
        scene.idle(TEXT_IDLE);

        scene.overlay().showOutline(PonderPalette.RED, "depot", util.select().position(sinkFunnel), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A depot is no way out for empty buckets: it holds one and the drain jams behind it")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(sinkFunnel, FRONT));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("Wareworks only carries the bucket: your Mixer makes the product")
                .attachKeyFrame()
                .colored(PonderPalette.BLUE)
                .placeNearTarget()
                .pointAt(util.vector().topOf(mixer));
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }
}
