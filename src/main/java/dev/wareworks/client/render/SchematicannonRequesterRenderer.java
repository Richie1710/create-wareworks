package dev.wareworks.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import dev.wareworks.content.requester.RequesterBackFace;
import dev.wareworks.content.requester.SchematicannonRequesterBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the Warehouse Remote standing in the cradle on the back of a Schematicannon Requester (issue #45, ADR-080), and
 * nothing at all while the slot is empty.
 * <p>
 * The remote is the synced slot stack, drawn the way Create draws an item into a value box
 * ({@code ValueBoxRenderer#renderItemIntoValueBox}, {@code ItemDisplayContext.FIXED}) at the cradle's spot
 * ({@link RequesterBackFace.Cradle}), which turns with the block's facing exactly as its model does. That keeps the
 * remote upright with its screen outwards in all six facings, and it is the same item a player sees in an item frame.
 * No Flywheel visual: one small item on a block that stands once per cannon, drawn the same with Flywheel on and off,
 * and in Ponder.
 */
public class SchematicannonRequesterRenderer extends SafeBlockEntityRenderer<SchematicannonRequesterBlockEntity> {
    private final RequesterBackFace.Cradle cradle = new RequesterBackFace.Cradle();

    public SchematicannonRequesterRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(SchematicannonRequesterBlockEntity be, float partialTicks, PoseStack ms,
            MultiBufferSource buffer, int light, int overlay) {
        ItemStack remote = be.cradleStack();
        if (remote.isEmpty())
            return;
        BlockState state = be.getBlockState();
        ms.pushPose();
        cradle.onBackOf(state).transform(be.getLevel(), be.getBlockPos(), state, ms);
        ValueBoxRenderer.renderItemIntoValueBox(remote, ms, buffer, light, overlay);
        ms.popPose();
    }
}
