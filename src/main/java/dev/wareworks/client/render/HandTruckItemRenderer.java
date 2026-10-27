package dev.wareworks.client.render;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModelRenderer;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;

import dev.wareworks.content.handtruck.HandTruckItem;
import dev.wareworks.content.handtruck.HandTruckLoad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws the hand truck (issue #37, ADR-070): the hand-made model {@code models/item/hand_truck/item.json} and, when the
 * truck is loaded, the carried container standing on its nose plate.
 * <p>
 * The container is drawn with {@code BlockRenderDispatcher#renderSingleBlock}, the same call a falling block or an
 * enderman's carried block uses: a block with a baked model (a barrel) is drawn from that model, and a chest — whose
 * look is its block entity renderer — through its own item renderer, i.e. vanilla's chest renderer. Flywheel and
 * Vanillin only take over block entities placed in a level, never item rendering, so the truck looks the same with
 * Flywheel on and off; the {@code handtruck} visual scenario shoots both.
 * <p>
 * The carried state is <b>normalised for display</b> ({@link #displayState}): one chest of a former double chest is
 * drawn as a single chest, an open barrel closed, nothing waterlogged, and a block that faces in six directions (a
 * barrel) standing upright. A horizontal block with a baked model faces north, the side the nose plate points to. A
 * chest is drawn by its item renderer, which ignores the state and always draws it facing south
 * ({@code ChestRenderer} without a level): its latch faces the rails and the player pushing the truck.
 */
public class HandTruckItemRenderer extends CustomRenderedItemModelRenderer {
    /** Model pixels per block. */
    private static final float PIXEL = 1.0F / 16.0F;
    /**
     * Where the carried container's bottom centre stands, in model pixels: on the nose plate (y 1), centred between the
     * rails (x 8) and between the nose lip and the rails (z 5 to 12). The renderer's pose has its origin in the model's
     * centre ({@code CustomRenderedItemModelRenderer#renderByItem}), hence the {@code - 8}.
     */
    private static final float LOAD_X = (8.0F - 8.0F) * PIXEL;
    private static final float LOAD_Y = (1.0F - 8.0F) * PIXEL;
    private static final float LOAD_Z = (8.5F - 8.0F) * PIXEL;
    /** The carried container is drawn 7 pixels wide, deep and high: it fits between the nose lip and the rails. */
    private static final float LOAD_SCALE = 7.0F * PIXEL;

    /** Display states by carried state; the render thread is the only caller. */
    private final Map<BlockState, BlockState> displayStates = new HashMap<>();

    @Override
    protected void render(ItemStack stack, CustomRenderedItemModel model, PartialItemModelRenderer renderer,
            ItemDisplayContext transformType, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        renderer.render(model.getOriginalModel(), light);
        HandTruckLoad load = HandTruckItem.load(stack);
        if (load == null)
            return;
        BlockState shown = displayStates.computeIfAbsent(load.state(), HandTruckItemRenderer::displayState);
        ms.pushPose();
        ms.translate(LOAD_X, LOAD_Y, LOAD_Z);
        ms.scale(LOAD_SCALE, LOAD_SCALE, LOAD_SCALE);
        ms.translate(-0.5F, 0.0F, -0.5F);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(shown, ms, buffer, light, overlay,
                ModelData.EMPTY, null);
        ms.popPose();
    }

    /** {@code state} as it is drawn on the truck (class comment). */
    static BlockState displayState(BlockState state) {
        BlockState shown = state;
        if (shown.hasProperty(ChestBlock.TYPE))
            shown = shown.setValue(ChestBlock.TYPE, ChestType.SINGLE);
        if (shown.hasProperty(BlockStateProperties.OPEN))
            shown = shown.setValue(BlockStateProperties.OPEN, false);
        if (shown.hasProperty(BlockStateProperties.WATERLOGGED))
            shown = shown.setValue(BlockStateProperties.WATERLOGGED, false);
        if (shown.hasProperty(BlockStateProperties.FACING))
            shown = shown.setValue(BlockStateProperties.FACING, Direction.UP);
        if (shown.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            shown = shown.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        return shown;
    }
}
