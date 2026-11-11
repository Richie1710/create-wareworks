package dev.wareworks.client.render;

import java.util.Optional;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.PalletEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws a pallet: a low wooden deck with the item it carries standing on it and the count written above
 * ({@code docs/warehouse-system.md} §3.8, M28, issue #20).
 * <p>
 * <b>Why the renderer carries the numbers at all.</b> Create's goggles are a block entity feature
 * ({@code IHaveGoggleInformation}), so there is no goggle tooltip for an entity and no other place to say what a pallet
 * holds. A player has to be able to look at the load of a bay they just broke and see that all of it is still there —
 * which is also the only thing a player can check about item conservation with their own eyes.
 * <p>
 * <b>Why this is not subject to the regression that cut the warehouse interface's view distance to ten blocks.</b> That
 * one ({@code WarehouseInterfaceRenderer}) is about block entities: registering a renderer for a block entity type puts
 * every one of them into its chunk section's per-frame render list at the vanilla 64-block default, and a rack wall is
 * hundreds of blocks. An entity is already in the level's entity list and is culled by its own tracking range
 * ({@code WareworksEntityTypes.PALLET}, 8 chunks), so there is no list to grow here.
 * <p>
 * The deck is the hand-made {@code models/block/pallet/deck.json} ({@link WareworksPartialModels#PALLET_DECK}): wooden
 * top boards on three runners, in Create's own textures, drawn through Create's partial-model cache like every part of
 * the crane; deck and load are drawn by {@link PalletLook}, which the hand truck shares when it carries a pallet. Until
 * M28 step 9 it was a vanilla oak-planks block state squashed flat, which read as a slab and carried a texture from
 * outside Create's palette; the geometry is still deliberately plain and has nothing to keep in step with
 * {@code CraneModelLayout}, because the pallet-racking look — uprights, beams and a load that grows with the fill level
 * — is M29's (design steps 12–14).
 */
public class PalletRenderer extends EntityRenderer<PalletEntity> {
    /** Height above the entity origin at which the count is written. */
    private static final float LABEL_Y = 1.0F;
    /** Font scale of the count label, the same as a vanilla name tag's. */
    private static final float LABEL_SCALE = 0.025F;
    /** Light andesite white, the colour this mod writes its numbers on blocks in. */
    private static final int LABEL_COLOR = 0xEDEDED;
    /** Radius of the shadow under a pallet, in blocks. */
    private static final float SHADOW_RADIUS = 0.5F;

    public PalletRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = SHADOW_RADIUS;
    }

    /**
     * A pallet is drawn from a block state and an item model, both of which come out of the block atlas, so there is no
     * texture of its own to name. {@code ItemEntityRenderer} answers the same way.
     */
    @Override
    public ResourceLocation getTextureLocation(PalletEntity pallet) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    /**
     * The deck and the load turned to the pallet's yaw: a pallet a hand truck set down faces the player who set it
     * down (ADR-084); the load of a broken bay has yaw 0 and is drawn as it always was. The count faces the camera.
     */
    @Override
    public void render(PalletEntity pallet, float entityYaw, float partialTick, PoseStack ms,
                       MultiBufferSource buffer, int packedLight) {
        ms.pushPose();
        ms.mulPose(Axis.YP.rotationDegrees(-entityYaw));
        PalletLook.drawDeck(ms, buffer, packedLight);
        Optional<ItemKey> carried = pallet.carriedKey();
        if (carried.isPresent())
            PalletLook.drawLoad(carried.get().toStack(), pallet.level(), ms, buffer, packedLight);
        ms.popPose();
        if (carried.isPresent())
            renderCount(pallet.carriedCount(), ms, buffer, packedLight);
        super.render(pallet, entityYaw, partialTick, ms, buffer, packedLight);
    }

    /** The count above the pallet, turned to face the camera exactly as a name tag is. */
    private void renderCount(int count, PoseStack ms, MultiBufferSource buffer, int packedLight) {
        Component label = Component.literal(String.valueOf(count));
        Font font = getFont();
        ms.pushPose();
        ms.translate(0.0F, LABEL_Y, 0.0F);
        ms.mulPose(entityRenderDispatcher.cameraOrientation());
        ms.scale(LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE);
        Matrix4f pose = ms.last().pose();
        font.drawInBatch(label, -font.width(label) / 2.0F, 0.0F, LABEL_COLOR, false, pose, buffer,
                Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        ms.popPose();
    }
}
