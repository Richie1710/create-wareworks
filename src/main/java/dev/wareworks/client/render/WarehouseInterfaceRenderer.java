package dev.wareworks.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import com.simibubi.create.infrastructure.config.AllConfigs;

import dev.wareworks.content.storage.StorageFilterBehaviour;
import dev.wareworks.content.storage.StorageFilterValueBox;
import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the store filter item of a warehouse interface (M8, ADR-021) and its storage priority (M16, ADR-028): Create's
 * {@link SmartBlockEntityRenderer} with the two changes the mod's most mass-placed block needs.
 * <p>
 * <b>Why not the stock renderer.</b> Vanilla only adds a block entity to a chunk section's per-frame render list when
 * its type has a renderer at all, so before M8 a rack wall of interfaces cost nothing per frame. The stock renderer's
 * view distance is the vanilla default of 64 blocks, while what it can actually draw here — the filter item, through
 * {@code FilteringRenderer#renderOnBlockEntity} — is cut off at Create's client config
 * {@code filterItemRenderDistance} (default 10 blocks), and that cut-off is applied only <i>after</i> iterating the
 * block entity's behaviours and allocating a centre vector. Several hundred interfaces within 64 blocks is the normal
 * build for this mod, so the dispatcher is given the distance the renderer can use instead, and the rack wall is culled
 * before the renderer is entered.
 * <p>
 * <b>The priority digit</b> is drawn here, and only for {@code priority != 0} — so an unprioritised rack wall costs
 * nothing extra, mirroring the "skip an empty filter" rule of Create's own renderer. It has to be drawn on the block
 * because anything Create draws for a value box lives in the {@code Outliner} and therefore exists only for the block
 * under {@code mc.hitResult}: the number would be invisible from two steps away, and the visual harness — whose camera
 * profile has zero interaction range and so never produces a hit result on an interface — could not screenshot it at
 * all. This is the <b>only</b> place the number is drawn: Create's corner label on the same box would be drawn on top of
 * this digit for every interface the crosshair rests on, so
 * {@code StorageFilterBehaviour#getCountLabelForValueBox()} is empty (see there). The digit stays inside the andesite
 * plate (x 3..13, y 3..9 px) and never enters the crane's arm port at y 9..13
 * ({@code models/block/warehouse_interface/block.json}, {@code stacker-crane.md} §7.1); it moves to the plate's upper
 * right while a filter item occupies the middle of the box.
 * <p>
 * The warehouse output keeps the stock renderer: an aisle has a handful of them, not a wall.
 */
public class WarehouseInterfaceRenderer extends SmartBlockEntityRenderer<WarehouseInterfaceBlockEntity> {
    /** Never cull closer than this, whatever a client configured. */
    private static final int MIN_VIEW_DISTANCE = 1;
    /** Height of the drawn digit in block pixels; 8 font units tall at scale 1 are one block pixel. */
    private static final float DIGIT_SCALE = 3.0F;
    /** Glyph height in font units, for centring the digit on its target point. */
    private static final float GLYPH_HEIGHT = 8.0F;
    /** Font units per block pixel after {@code ValueBoxTransform#transform} and the font scale (1 / getFontScale / 8). */
    private static final float UNITS_PER_PIXEL = 8.0F;
    /** Centre of the digit while a filter item fills the middle of the box: the plate's upper right, clear of the port. */
    private static final float DIGIT_X_WITH_FILTER_PIXELS = 11.5F;
    private static final float DIGIT_Y_WITH_FILTER_PIXELS = 7.0F;
    /** Centre of the digit on an empty slot: the middle of the plate. */
    private static final float DIGIT_X_ALONE_PIXELS = StorageFilterValueBox.CENTER_X_PIXELS;
    private static final float DIGIT_Y_ALONE_PIXELS = 5.5F;
    /** How far in front of the box the digit floats, in block pixels (the box sits 0.5 px inside the plate surface). */
    private static final float DIGIT_DEPTH_PIXELS = 1.0F;
    /** Light andesite white, the colour Create draws its own value box numbers in. */
    private static final int DIGIT_COLOR = 0xEDEDED;

    public WarehouseInterfaceRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    /** The distance {@code FilteringBehaviour#getRenderDistance()} itself enforces, rounded up to whole blocks. */
    @Override
    public int getViewDistance() {
        return Math.max(MIN_VIEW_DISTANCE, Mth.ceil(AllConfigs.client().filterItemRenderDistance.getF()));
    }

    @Override
    protected void renderSafe(WarehouseInterfaceBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);
        renderPriority(be, ms, buffer);
    }

    /**
     * The priority digit on the aisle face, skipped entirely at priority 0 and beyond the same distance Create cuts the
     * filter item off at (the renderer's view distance is only a whole-block bound on that).
     */
    private static void renderPriority(WarehouseInterfaceBlockEntity be, PoseStack ms, MultiBufferSource buffer) {
        int priority = be.storePriority();
        if (priority == StorageFilterBehaviour.MIN_PRIORITY)
            return;
        Level level = be.getLevel();
        BlockPos pos = be.getBlockPos();
        if (level == null)
            return;
        if (!be.isVirtual()) {
            Entity camera = Minecraft.getInstance().cameraEntity;
            float max = AllConfigs.client().filterItemRenderDistance.getF();
            if (camera != null && level == camera.level()
                    && camera.position().distanceToSqr(VecHelper.getCenterOf(pos)) > max * max)
                return;
        }
        ValueBoxTransform slot = be.storeSettingsSlot();
        if (slot instanceof ValueBoxTransform.Sided sided)
            sided.fromSide(be.facing().getOpposite());
        BlockState state = be.getBlockState();
        if (!slot.shouldRender(level, pos, state))
            return;

        // hasStoreFilter(), not storeFilter(): the latter hands out a copy, because the server trims a filter item's
        // components in place — pure per-frame garbage in the renderer of the mod's most mass-placed block.
        boolean withFilter = be.hasStoreFilter();
        float x = withFilter ? DIGIT_X_WITH_FILTER_PIXELS : DIGIT_X_ALONE_PIXELS;
        float y = withFilter ? DIGIT_Y_WITH_FILTER_PIXELS : DIGIT_Y_ALONE_PIXELS;
        Component digit = Component.literal(String.valueOf(priority));
        Font font = Minecraft.getInstance().font;

        ms.pushPose();
        slot.transform(level, pos, state, ms);
        // Create's own convention (see ValueBox#render): the whole font scale is negative, because after the transform
        // the local frame has +X to the viewer's left, +Y up and +Z into the block — negating all three turns it into
        // the frame the font draws in (x right, y down, z towards the viewer).
        float fontScale = -slot.getFontScale();
        ms.scale(fontScale, fontScale, fontScale);
        ms.translate((x - StorageFilterValueBox.CENTER_X_PIXELS) * UNITS_PER_PIXEL,
                -(y - StorageFilterValueBox.CENTER_Y_PIXELS) * UNITS_PER_PIXEL, DIGIT_DEPTH_PIXELS * UNITS_PER_PIXEL);
        ms.scale(DIGIT_SCALE, DIGIT_SCALE, DIGIT_SCALE);
        ms.translate(-font.width(digit) / 2.0F, -GLYPH_HEIGHT / 2.0F, 0.0F);
        font.drawInBatch(digit, 0.0F, 0.0F, DIGIT_COLOR, false, ms.last().pose(), buffer, Font.DisplayMode.NORMAL, 0,
                LightTexture.FULL_BRIGHT);
        ms.popPose();
    }
}
