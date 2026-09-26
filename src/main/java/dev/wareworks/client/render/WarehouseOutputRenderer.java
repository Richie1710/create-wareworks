package dev.wareworks.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import com.simibubi.create.infrastructure.config.AllConfigs;

import dev.wareworks.content.station.PortRankValueBox;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.core.port.PortSettings;
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
 * Draws the item in a warehouse port's request filter slot (Create's {@link SmartBlockEntityRenderer}) and, for an
 * accepting port, its <b>signed rank</b> on the plate on the back of the block (M17, issue #12).
 * <p>
 * <b>Why the number is drawn on the block.</b> Anything Create draws for a value box lives in the {@code Outliner} and
 * therefore exists only for the block under {@code mc.hitResult}: the rank would be invisible from two steps away, and
 * the visual harness — whose camera profile has zero interaction range and so never produces a hit result — could not
 * screenshot it at all. This is the same lesson ADR-028 learnt for the storage priority, and the same solution.
 * <p>
 * <b>Where it is drawn, and where not.</b> On the <b>back</b> plate only, the face a player stands at when they wire the
 * port up, and skipped entirely in the requesting direction, so an aisle of plain outputs costs nothing extra (mirroring
 * Create's own "skip an empty filter" rule). The <b>aisle</b> face gets no number at all: the crane's arm port owns it,
 * and what speaks there is the model's andesite ring ({@code WareworksBlockStateGen#warehousePortBlockProvider}).
 * <p>
 * The number is positioned against the <b>filter slot's</b> transform rather than the port's own wrench-only box, so it
 * uses the same font arithmetic the storage priority digit uses ({@link WarehouseInterfaceRenderer}); it sits above the
 * filter item in the recessed plate (y 5.5..13 px of {@code models/block/warehouse_output/block.json}) and never reaches
 * into the brass frame. The port's box is only 0.4 wide ({@link PortRankValueBox}) and its own text is drawn by Create
 * while a wrench is held.
 */
public class WarehouseOutputRenderer extends SmartBlockEntityRenderer<WarehouseOutputBlockEntity> {
    /** Never cull closer than this, whatever a client configured. */
    private static final int MIN_VIEW_DISTANCE = 1;
    /** Height of the drawn number in block pixels. */
    private static final float DIGIT_SCALE = 2.5F;
    /** Glyph height in font units, for centring the number on its target point. */
    private static final float GLYPH_HEIGHT = 8.0F;
    /** Font units per block pixel after {@code ValueBoxTransform#transform} and the font scale (4 / getScale()). */
    private static final float UNITS_PER_BLOCK_PIXEL = 8.0F;
    /** Centre of the filter slot's value box, in block pixels ({@code CenteredSideValueBoxTransform}). */
    private static final float BOX_CENTER_X_PIXELS = 8.0F;
    private static final float BOX_CENTER_Y_PIXELS = 8.0F;
    /** Centre of the number: above the filter item, inside the recessed plate. */
    private static final float RANK_X_PIXELS = 8.0F;
    private static final float RANK_Y_PIXELS = 11.25F;
    /** How far in front of the box the number floats, in block pixels. */
    private static final float RANK_DEPTH_PIXELS = 1.0F;
    /** Light andesite white, the colour Create draws its own value box numbers in. */
    private static final int RANK_COLOR = 0xEDEDED;

    public WarehouseOutputRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    /**
     * The distance {@code FilteringBehaviour#getRenderDistance()} itself enforces, rounded up to whole blocks: neither
     * the filter item nor the rank can be drawn further away, so nothing is lost by culling the block entity there.
     */
    @Override
    public int getViewDistance() {
        return Math.max(MIN_VIEW_DISTANCE, Mth.ceil(AllConfigs.client().filterItemRenderDistance.getF()));
    }

    @Override
    protected void renderSafe(WarehouseOutputBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
                              int light, int overlay) {
        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);
        renderRank(be, ms, buffer);
    }

    /** The signed rank on the back plate, skipped for a requesting port and beyond Create's filter item distance. */
    private static void renderRank(WarehouseOutputBlockEntity be, PoseStack ms, MultiBufferSource buffer) {
        int rank = be.portRank();
        if (rank == PortSettings.REQUEST_RANK)
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
        ValueBoxTransform slot = be.requestFilterSlot();
        if (slot instanceof ValueBoxTransform.Sided sided)
            sided.fromSide(be.facing().getOpposite()); // the back: FACING points into the aisle
        BlockState state = be.getBlockState();
        if (!slot.shouldRender(level, pos, state))
            return;

        Component text = Component.literal(PortSettings.formatRank(rank));
        Font font = Minecraft.getInstance().font;

        ms.pushPose();
        slot.transform(level, pos, state, ms);
        // Create's own convention (see ValueBox#render): the whole font scale is negative, because after the transform
        // the local frame has +X to the viewer's left, +Y up and +Z into the block — negating all three turns it into
        // the frame the font draws in (x right, y down, z towards the viewer).
        float fontScale = -slot.getFontScale();
        ms.scale(fontScale, fontScale, fontScale);
        ms.translate((RANK_X_PIXELS - BOX_CENTER_X_PIXELS) * UNITS_PER_BLOCK_PIXEL,
                -(RANK_Y_PIXELS - BOX_CENTER_Y_PIXELS) * UNITS_PER_BLOCK_PIXEL,
                RANK_DEPTH_PIXELS * UNITS_PER_BLOCK_PIXEL);
        ms.scale(DIGIT_SCALE, DIGIT_SCALE, DIGIT_SCALE);
        ms.translate(-font.width(text) / 2.0F, -GLYPH_HEIGHT / 2.0F, 0.0F);
        font.drawInBatch(text, 0.0F, 0.0F, RANK_COLOR, false, ms.last().pose(), buffer, Font.DisplayMode.NORMAL, 0,
                LightTexture.FULL_BRIGHT);
        ms.popPose();
    }
}
