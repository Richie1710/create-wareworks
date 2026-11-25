package dev.wareworks.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Draws an item at the mouth of a bay: the stored item or the filter of an empty rack bay ({@link RackBayRenderer}), and
 * the filter of an empty fluid bay ({@link FluidBayRenderer}, issue #61). It also holds the two things both bay
 * renderers need beside the item: the turn onto the bay's facing and the light of an open bay front. A fluid bay's
 * vessel ends on the same plane as the pallet and the widest load step, so one set of anchors serves both families
 * ({@code CraneModelLayoutTest#theItemDrawnInAnEmptyFluidBayStandsClearOfTheArmAndTheVessel}).
 * <p>
 * <b>Where the item stands.</b> At the <b>front of the bay</b>, on the load beam, immediately in front of the pallet —
 * not on top of the load, which is where the design expected it. The reason is measured rather than chosen: the two top
 * fill steps spread their cartons over the pallet's whole footprint (x/z 3..13, i.e. the pallet exactly), so an item on
 * the pallet at those steps is <i>inside</i> the cartons; and above a full load there are 1.5 px left before
 * {@code CraneModelLayout}'s arm floor at y 9.5, where nothing in a bay may ever reach
 * ({@code CraneModelLayoutTest#aRackBaysLoadStaysUnderTheArmAndInsideItsWindow}). The 3 px between the load's front
 * plane and the aisle face is the only part of a bay that is free at every fill level, and it is the part a player in
 * the aisle looks straight at. {@code CraneModelLayoutTest#theItemDrawnInARackBayStandsClearOfTheArmAndTheLoad} pins
 * every one of those bounds against the model files, so a later edit to a load model or to the arm cannot silently
 * bury the item or push it into the crane's path.
 * <p>
 * <b>How it is drawn.</b> The way the crane draws what it carries ({@code docs/stacker-crane.md} §7):
 * {@link ItemDisplayContext#FIXED} at {@link CraneModelLayout#ITEM_SCALE}, so an item does not change size when the arm
 * sets it down. A block item stands, as it does on the arm. A flat item <b>stands up facing the aisle</b> instead of
 * lying down, which is the one place this differs from the arm: a bay is read face-on from the aisle, where a 0.34 px
 * sheet lying flat is an invisible line, and a flat item laid down needs 5.4 px of depth that the front of a bay does
 * not have. Lying flat is right on the arm, because the arm's stage is a shelf seen from above and from the side.
 * <p>
 * The item is lit by the brighter of the bay's own block and the aisle block in front of it, the rule the crane's arm
 * already uses for parts that reach into a rack: a bay in the middle of a wall attenuates light like any non-solid
 * block, and the goods face an open aisle.
 * <p>
 * One instance per renderer, used on the render thread only.
 */
public final class BayItemRenderer {
    /**
     * Where the drawn item's <b>back</b> face sits, in model pixels of a bay authored facing north: the pallet's front
     * edge, which is also the front plane of the widest load step ({@code models/block/rack_bay/load_base.json}) and of
     * a fluid bay's vessel ({@link FluidBayLayout#VESSEL_MAX_Z_PX}). So the item stands against the goods at a full bay
     * and a pixel or two in front of them at a low fill level, and never inside them.
     */
    public static final float ITEM_BACK_Z_PX = 13.0F;
    /**
     * Where the drawn item <b>stands</b>, in model pixels: the top of the load beam, the same plane the pallet itself
     * and a fluid bay's vessel stand on. One pixel below the pallet's deck, because the pallet's deck is covered by the
     * load at the two top fill steps and the item would be the only thing in the bay with a gap under it.
     */
    public static final float ITEM_BASE_Y_PX = 3.0F;
    /** Across the bay: its centre, between the two uprights. */
    public static final float ITEM_X_PX = CraneModelLayout.BLOCK_CENTER_PX;

    /**
     * The aisle face of a bay, in model pixels: the plane the drawn item must stay behind, so it never pokes into the
     * aisle the crane travels in. The {@value #ITEM_BACK_Z_PX}..{@value #AISLE_FACE_Z_PX} strip is the whole budget.
     */
    public static final float AISLE_FACE_Z_PX = 16.0F;

    /** Seed for the item model lookup, so a bay does not flicker between model variants. */
    private static final int ITEM_SEED = 0;
    private static final float QUARTER_TURN_DEGREES = 90.0F;
    private static final float HALF_TURN_DEGREES = 180.0F;

    private final ItemRenderer itemRenderer;
    /**
     * The aisle block a bay's light is read from, reused rather than allocated: a renderer of a mass-placed block
     * allocates nothing per bay per frame, and {@code BlockPos#relative} would hand back a fresh {@code BlockPos} on
     * every one. Render thread only.
     */
    private final BlockPos.MutableBlockPos lightCursor = new BlockPos.MutableBlockPos();

    BayItemRenderer(ItemRenderer itemRenderer) {
        this.itemRenderer = itemRenderer;
    }

    /** Draws {@code display} at the mouth of the bay at {@code pos}; {@code ownLight} is the dispatcher's light. */
    void draw(ItemStack display, Level level, BlockPos pos, Direction facing, PoseStack ms, MultiBufferSource buffer,
              int ownLight) {
        BakedModel model = itemRenderer.getModel(display, level, null, ITEM_SEED);
        boolean blockModel = model.isGui3d();
        // Half of what the item model covers in its fixed display transform: a block item is a cube of
        // BLOCK_ITEM_SIZE_PX, a flat item a FLAT_ITEM_THICKNESS_PX sheet one block across, and ItemRenderer centres
        // both on the pose origin — so the anchor planes above have to be met by moving the centre out by this much.
        float halfDepth = (blockModel ? CraneModelLayout.BLOCK_ITEM_SIZE_PX : CraneModelLayout.FLAT_ITEM_THICKNESS_PX)
                * CraneModelLayout.ITEM_SCALE / 2.0F;
        float halfHeight = (blockModel ? CraneModelLayout.BLOCK_ITEM_SIZE_PX : CraneModelLayout.PIXELS_PER_BLOCK)
                * CraneModelLayout.ITEM_SCALE / 2.0F;

        ms.pushPose();
        // Everything below is authored in the north frame the models are authored in.
        turnOnto(ms, facing);
        ms.translate(pixels(ITEM_X_PX) - 0.5F, pixels(ITEM_BASE_Y_PX + halfHeight),
                pixels(ITEM_BACK_Z_PX + halfDepth) - 0.5F);
        // The half turn an item frame applies on top of the fixed display transform, which is what makes an item in a
        // frame face the viewer: verified against the vanilla transforms rather than guessed. `item/generated` carries
        // `fixed: rotation [0, 180, 0]` and `block/block` carries none, and ItemFrameRenderer adds
        // `Axis.YP.rotationDegrees(180 - yRot)`, i.e. 180° for a frame on a south face. So this one turn presents both
        // kinds exactly as a frame on the bay's aisle face would: a block item shows the face a player knows from the
        // GUI, and a flat item its own sprite rather than the mirrored back of its two quads.
        ms.mulPose(Axis.YP.rotationDegrees(HALF_TURN_DEGREES));
        ms.scale(CraneModelLayout.ITEM_SCALE, CraneModelLayout.ITEM_SCALE, CraneModelLayout.ITEM_SCALE);
        // No pitch for a flat item: the fixed transform already stands a generated model up in the x/y plane, which is
        // the plane of the aisle face after the rotation above. The crane lays one down; a bay is read face-on.
        itemRenderer.render(display, ItemDisplayContext.FIXED, false, ms, buffer,
                openFrontLight(level, pos, facing, ownLight), OverlayTexture.NO_OVERLAY, model);
        ms.popPose();
    }

    /**
     * Moves the pose to the block's centre column and applies the rotation the multipart blockstate applies to a bay's
     * own models: quarter turns clockwise about the vertical centre axis. The origin is left on that axis, so a caller
     * subtracts half a block in x and z from a model coordinate.
     */
    static void turnOnto(PoseStack ms, Direction facing) {
        ms.translate(0.5F, 0.0F, 0.5F);
        ms.mulPose(Axis.YP.rotationDegrees(-QUARTER_TURN_DEGREES * quarterTurns(facing)));
    }

    /**
     * Quarter turns clockwise from north, i.e. the {@code y} rotation the generated blockstate gives a bay's models
     * ({@code WareworksBlockStateGen#rackBayBlockProvider}, {@code #fluidBayBlockProvider}). A bay whose state somehow
     * carries a vertical facing is drawn as if it faced north rather than not at all.
     */
    private static int quarterTurns(Direction facing) {
        return switch (facing) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    /**
     * The brighter of the bay's own light (passed in by the dispatcher) and the aisle block's. A bay is not a solid
     * block, but it still attenuates light like any non-solid one, so deep in a rack wall its own position is dim while
     * its front faces an open, lit aisle — the same reason {@code StackerCraneRenderer}'s arm takes the brighter of its
     * carriage and the rack block it reaches into. A fluid bay's {@code renderFluidBox} then raises the block light
     * further to the fluid's own luminosity, which is what makes lava light itself.
     */
    int openFrontLight(Level level, BlockPos pos, Direction facing, int ownLight) {
        lightCursor.setWithOffset(pos, facing.getOpposite());
        return SuperByteBuffer.maxLight(ownLight, LevelRenderer.getLightColor(level, lightCursor));
    }

    private static float pixels(float modelPixels) {
        return modelPixels / CraneModelLayout.PIXELS_PER_BLOCK;
    }
}
