package dev.wareworks.client.render;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.inventory.KeyCount;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Renders the moving stacker crane of a dock ({@code docs/stacker-crane.md} §7, ADR-007/ADR-013).
 * <p>
 * A plain {@link SafeBlockEntityRenderer} without a Flywheel visualizer, so the same code draws the crane with a Flywheel
 * backend, with {@code /flywheel backend off} and in Ponder. Everything is relative to the dock block: the chassis, wheels,
 * drive cog and mast travel {@code posX} blocks along the aisle direction, the carriage, hoist belt end and arm are lifted
 * by {@code posY}, the arm stages and the grabber extend towards the arm side, and the held item types lie on the inner
 * stage. The pose is interpolated between the previous and the current client tick ({@link StackerCraneBlockEntity#renderPose}).
 * <p>
 * The partials are authored with the aisle direction north ({@link WareworksPartialModels}); one rotation about the block
 * centre turns them to the dock's facing, and the arm is turned half around for the left side. Every part is lit with the
 * light of the block it is in; parts that enter a rack use the brighter of their own and the carriage's block, so they are
 * not black inside solid rack blocks. The drive cog turns with the kinetic angle through Create's static helpers (not
 * {@code KineticBlockEntityRenderer#renderSafe}, which draws nothing while Flywheel is active).
 * <p>
 * All block geometry goes into one {@link RenderType#cutoutMipped()} consumer first; held items are rendered last, because
 * item rendering requests other render types from the buffer source.
 */
public class StackerCraneRenderer extends SafeBlockEntityRenderer<StackerCraneBlockEntity> {
    /** Vanilla's block entity view distance, measured from the dock. */
    private static final int BASE_VIEW_DISTANCE = 64;
    private static final float HALF_TURN_DEGREES = 180.0F;
    private static final float QUARTER_TURN_DEGREES = 90.0F;
    private static final float QUARTER_TURN_RADIANS = (float) (Math.PI / 2.0);
    /** Hoist belt pieces shorter than this (blocks) are not drawn. */
    private static final double MIN_BELT_PIECE = 1.0E-3;
    /** Offset from a part's lower bound to the point whose block gives its light. */
    private static final double BLOCK_HALF = 0.5;
    private static final int ITEM_SEED = 0;
    private static final float[] WHEEL_AXLES_Z_PX = {CraneModelLayout.FRONT_WHEEL_Z_PX, CraneModelLayout.REAR_WHEEL_Z_PX};

    private final ItemRenderer itemRenderer;
    /** One display stack per held item type; the synced held data has item types only. Render thread only. */
    private final Map<Item, ItemStack> displayStacks = new IdentityHashMap<>();

    public StackerCraneRenderer(BlockEntityRendererProvider.Context context) {
        itemRenderer = context.getItemRenderer();
    }

    @Override
    protected void renderSafe(StackerCraneBlockEntity crane, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light, int overlay) {
        Level level = crane.getLevel();
        if (level == null)
            return;
        BlockState state = crane.getBlockState();
        Direction facing = crane.facing();
        CranePose pose = crane.renderPose(partialTicks);
        int mastHeight = crane.geometry().height();
        CraneLight lights = new CraneLight(level, crane.getBlockPos(), facing, pose);
        VertexConsumer geometry = buffer.getBuffer(RenderType.cutoutMipped());

        ms.pushPose();
        ms.translate(facing.getStepX() * pose.x(), 0.0, facing.getStepZ() * pose.x());
        TransformStack.of(ms).center().rotateToFace(facing).uncenter();

        renderBase(crane, state, pose, lights, ms, geometry);
        renderMast(state, pose, mastHeight, lights, ms, geometry);

        ms.pushPose();
        ms.translate(0.0, pose.y(), 0.0);
        int carriageLight = lights.carriage();
        part(WareworksPartialModels.CRANE_CARRIAGE, state).light(carriageLight).renderInto(ms, geometry);
        if (pose.side() == Side.LEFT)
            TransformStack.of(ms).center().rotateYDegrees(HALF_TURN_DEGREES).uncenter();
        float outer = (float) CraneModelLayout.outerStageOffset(pose.arm());
        float inner = (float) CraneModelLayout.innerStageOffset(pose.arm());
        int headLight = lights.head(inner, carriageLight);
        part(WareworksPartialModels.CRANE_ARM_OUTER, state).translate(outer, 0.0F, 0.0F)
                .light(lights.head(outer, carriageLight)).renderInto(ms, geometry);
        part(WareworksPartialModels.CRANE_ARM_INNER, state).translate(inner, 0.0F, 0.0F).light(headLight)
                .renderInto(ms, geometry);
        part(WareworksPartialModels.CRANE_GRABBER, state).translate(inner, 0.0F, 0.0F).light(headLight)
                .renderInto(ms, geometry);
        // Last: item rendering ends the shared cutout batch.
        renderHeldItems(level, crane.goggleInfo().held(), inner, headLight, ms, buffer);
        ms.popPose();

        ms.popPose();
    }

    /** Chassis, both wheels (turned by the travelled distance) and the drive cog (turned with the kinetic network). */
    private static void renderBase(StackerCraneBlockEntity crane, BlockState state, CranePose pose, CraneLight lights,
            PoseStack ms, VertexConsumer geometry) {
        int baseLight = lights.base();
        part(WareworksPartialModels.CRANE_BASE, state).light(baseLight).renderInto(ms, geometry);
        float wheelAngle = (float) CraneModelLayout.wheelAngle(pose.x());
        for (float wheelZ : WHEEL_AXLES_Z_PX) {
            part(WareworksPartialModels.CRANE_WHEEL, state)
                    .translate(0.0F, pixels(CraneModelLayout.WHEEL_AXLE_Y_PX - CraneModelLayout.BLOCK_CENTER_PX),
                            pixels(wheelZ - CraneModelLayout.BLOCK_CENTER_PX))
                    .rotateCentered(wheelAngle, Direction.EAST).light(baseLight).renderInto(ms, geometry);
        }
        // Create's cog turns about Y: scale it about its centre, stand it up facing the aisle direction, move it in front
        // of the chassis; the kinetic spin is applied to the model first.
        SuperByteBuffer cog = part(AllPartialModels.SHAFTLESS_COGWHEEL, state)
                .translate(0.0F, pixels(CraneModelLayout.DRIVE_COG_Y_PX - CraneModelLayout.BLOCK_CENTER_PX),
                        pixels(CraneModelLayout.DRIVE_COG_Z_PX - CraneModelLayout.BLOCK_CENTER_PX))
                .rotateCentered(QUARTER_TURN_RADIANS, Direction.EAST).center().scale(CraneModelLayout.DRIVE_COG_SCALE)
                .uncenter();
        float angle = KineticBlockEntityRenderer.getAngleForBe(crane, crane.getBlockPos(), Direction.Axis.Y);
        KineticBlockEntityRenderer.kineticRotationTransform(cog, crane, Direction.Axis.Y, angle, baseLight)
                .renderInto(ms, geometry);
    }

    /** Mast segments on the chassis, the cap on top and the hoist belt from the carriage up to the cap. */
    private static void renderMast(BlockState state, CranePose pose, int mastHeight, CraneLight lights, PoseStack ms,
            VertexConsumer geometry) {
        float mastBase = pixels(CraneModelLayout.MAST_BASE_Y_PX);
        for (int segment = 0; segment < mastHeight; segment++) {
            part(WareworksPartialModels.CRANE_MAST_SEGMENT, state).translate(0.0F, mastBase + segment, 0.0F)
                    .light(lights.column(mastBase + segment + BLOCK_HALF)).renderInto(ms, geometry);
        }
        float mastTop = (float) CraneModelLayout.mastTopY(mastHeight);
        part(WareworksPartialModels.CRANE_MAST_TOP, state).translate(0.0F, mastTop, 0.0F)
                .light(lights.column(mastTop + BLOCK_HALF)).renderInto(ms, geometry);

        double beltBottom = CraneModelLayout.hoistBeltBottomY(pose.y());
        double beltLength = CraneModelLayout.hoistBeltLength(pose.y(), mastHeight);
        int wholePieces = (int) Math.floor(beltLength);
        for (int piece = 0; piece <= wholePieces; piece++) {
            double pieceLength = Math.min(1.0, beltLength - piece);
            if (pieceLength < MIN_BELT_PIECE)
                break;
            double pieceBottom = beltBottom + piece;
            part(WareworksPartialModels.CRANE_HOIST_BELT, state).translate(0.0F, (float) pieceBottom, 0.0F)
                    .scale(1.0F, (float) pieceLength, 1.0F).light(lights.column(pieceBottom + pieceLength / 2.0))
                    .renderInto(ms, geometry);
        }
    }

    /**
     * Held item types on the inner stage (in the arm frame: extension towards +X, the stage already moved by {@code inner}):
     * up to one model per slot, and a second, turned copy on top for larger amounts.
     */
    private void renderHeldItems(Level level, List<KeyCount<Item>> held, float inner, int itemLight, PoseStack ms,
            MultiBufferSource buffer) {
        int slots = Math.min(held.size(), CraneModelLayout.ITEM_SLOT_X_PX.length);
        for (int slot = 0; slot < slots; slot++) {
            KeyCount<Item> entry = held.get(slot);
            ItemStack stack = displayStacks.computeIfAbsent(entry.key(), ItemStack::new);
            if (stack.isEmpty())
                continue;
            BakedModel model = itemRenderer.getModel(stack, level, null, ITEM_SEED);
            boolean blockModel = model.isGui3d();
            float halfHeight = (blockModel ? CraneModelLayout.BLOCK_ITEM_SIZE_PX : CraneModelLayout.FLAT_ITEM_THICKNESS_PX)
                    * CraneModelLayout.ITEM_SCALE / 2.0F;
            float stackStep = blockModel ? CraneModelLayout.BLOCK_ITEM_STACK_PX : CraneModelLayout.FLAT_ITEM_STACK_PX;
            int copies = entry.count() >= CraneModelLayout.SECOND_ITEM_COPY_MIN_COUNT ? 2 : 1;
            for (int copy = 0; copy < copies; copy++) {
                ms.pushPose();
                ms.translate(pixels(CraneModelLayout.ITEM_SLOT_X_PX[slot]) + inner,
                        pixels(CraneModelLayout.ITEM_REST_Y_PX + halfHeight + copy * stackStep),
                        pixels(CraneModelLayout.ITEM_Z_PX));
                ms.mulPose(Axis.YP.rotationDegrees(copy * CraneModelLayout.ITEM_STACK_YAW_DEGREES));
                if (!blockModel)
                    ms.mulPose(Axis.XP.rotationDegrees(QUARTER_TURN_DEGREES)); // lie flat on the stage
                ms.scale(CraneModelLayout.ITEM_SCALE, CraneModelLayout.ITEM_SCALE, CraneModelLayout.ITEM_SCALE);
                itemRenderer.render(stack, ItemDisplayContext.FIXED, false, ms, buffer, itemLight, OverlayTexture.NO_OVERLAY,
                        model);
                ms.popPose();
            }
        }
    }

    private static SuperByteBuffer part(PartialModel partial, BlockState state) {
        return CachedBuffers.partial(partial, state);
    }

    private static float pixels(float modelPixels) {
        return modelPixels / CraneModelLayout.PIXELS_PER_BLOCK;
    }

    /** The crane moves far from the dock: render it even when the dock's chunk section is culled. */
    @Override
    public boolean shouldRenderOffScreen(StackerCraneBlockEntity crane) {
        return true;
    }

    /**
     * Distance from the dock within which the crane renders: vanilla's 64 blocks beyond the farthest point the crane can
     * reach (the configured maximum aisle length and mast height). The render bounding box is the dock's
     * ({@code StackerCraneBlockEntity#createRenderBoundingBox}: every rack position, inflated by one block).
     */
    @Override
    public int getViewDistance() {
        return BASE_VIEW_DISTANCE + WareworksConfig.maxAisleLength() + WareworksConfig.maxMastHeight();
    }

    /**
     * Light of the blocks the crane parts are in at one pose. Positions are world positions; {@code along} is measured
     * along the aisle from the dock, {@code up} from the dock's floor.
     */
    private record CraneLight(Level level, BlockPos dock, Direction facing, CranePose pose) {
        /** Chassis and wheels: the aisle block the crane stands in. */
        int base() {
            return column(BLOCK_HALF);
        }

        /** Carriage and arm stages: the block of the carriage at its level. */
        int carriage() {
            return column(pose.y() + BLOCK_HALF);
        }

        /** A part of the crane column {@code up} blocks above the dock's floor. */
        int column(double up) {
            return at(0.0, up);
        }

        /**
         * A part of the arm moved {@code offset} blocks towards the arm side: the brighter of the carriage block
         * ({@code carriageLight}) and the block the moved part is in (a rack block may be solid and dark inside).
         */
        int head(double offset, int carriageLight) {
            return SuperByteBuffer.maxLight(carriageLight, at(pose.side().lateralOffset() * offset, pose.y() + BLOCK_HALF));
        }

        private int at(double lateral, double up) {
            Direction right = facing.getClockWise();
            double along = pose.x();
            BlockPos pos = BlockPos.containing(
                    dock.getX() + BLOCK_HALF + facing.getStepX() * along + right.getStepX() * lateral,
                    dock.getY() + up,
                    dock.getZ() + BLOCK_HALF + facing.getStepZ() * along + right.getStepZ() * lateral);
            return LevelRenderer.getLightColor(level, pos);
        }
    }
}
