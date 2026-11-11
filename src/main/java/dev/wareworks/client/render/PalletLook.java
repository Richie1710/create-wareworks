package dev.wareworks.client.render;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.wareworks.content.handtruck.HandTruckItem;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a pallet looks like, drawn wherever one is shown: lying in the world ({@link PalletRenderer}) and on a hand
 * truck that carries it ({@link HandTruckItemRenderer}, ADR-084). One place, so the two never drift apart.
 * <p>
 * The deck is the hand-made {@code models/block/pallet/deck.json} ({@link WareworksPartialModels#PALLET_DECK}), drawn
 * through Create's partial-model cache into one block centred on the pose origin's feet; the carried item stands on
 * it the way the crane draws what it holds ({@code docs/stacker-crane.md} §7): block items stand, flat items lie down.
 */
public final class PalletLook {
    /**
     * The block state the deck's vertex data is built for. A pallet is not a block, so this only decides the tint and
     * the model seed a {@code BakedModel} would be asked for — the deck has neither, so the empty state is honest.
     */
    private static final BlockState DECK_REFERENCE = Blocks.AIR.defaultBlockState();
    /** How tall the deck model is, as a fraction of a block: 3 of 16 pixels ({@code models/block/pallet/deck.json}). */
    public static final float DECK_HEIGHT = 3.0F / 16.0F;

    /** Height above the deck's origin at which the carried item sits, i.e. on top of the deck. */
    private static final float ITEM_Y = DECK_HEIGHT;
    /** How large the carried item is drawn. */
    private static final float ITEM_SCALE = 0.6F;
    /** Degrees the item is turned around Y, so a flat item is not seen edge-on from the aisle. */
    private static final float ITEM_YAW_DEGREES = 45.0F;
    /** Quarter turn that lays a flat item (a plate, a sheet) down on the deck instead of standing it upright. */
    private static final float FLAT_ITEM_PITCH_DEGREES = 90.0F;
    /** Seed for the item model lookup; any fixed value, so a pallet does not flicker between model variants. */
    private static final int ITEM_SEED = 0;

    private PalletLook() {
    }

    /** The deck, one block wide, standing on the pose origin (the centre of its bottom face). */
    public static void drawDeck(PoseStack ms, MultiBufferSource buffer, int light) {
        ms.pushPose();
        // A partial model is drawn into [0,1]^3 from the pose origin; the deck is exactly one block wide, so it only
        // has to be moved into its own corner.
        ms.translate(-0.5F, 0.0F, -0.5F);
        CachedBuffers.partial(WareworksPartialModels.PALLET_DECK, DECK_REFERENCE).light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));
        ms.popPose();
    }

    /**
     * The carried item on the deck. A hand truck as the carried item is not drawn: it could carry a pallet again, and
     * a creative client can write any key.
     */
    public static void drawLoad(ItemStack stack, @Nullable Level level, PoseStack ms, MultiBufferSource buffer,
            int light) {
        if (stack.isEmpty() || stack.getItem() instanceof HandTruckItem)
            return;
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, level, null, ITEM_SEED);
        ms.pushPose();
        ms.translate(0.0F, ITEM_Y, 0.0F);
        ms.mulPose(Axis.YP.rotationDegrees(ITEM_YAW_DEGREES));
        if (!model.isGui3d())
            ms.mulPose(Axis.XP.rotationDegrees(FLAT_ITEM_PITCH_DEGREES));
        ms.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
        itemRenderer.render(stack, ItemDisplayContext.FIXED, false, ms, buffer, light, OverlayTexture.NO_OVERLAY,
                model);
        ms.popPose();
    }
}
