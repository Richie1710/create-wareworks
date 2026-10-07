package dev.wareworks.client.render;

import java.util.Optional;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.PalletEntity;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

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
 * the crane. Until M28 step 9 it was a vanilla oak-planks block state squashed flat, which read as a slab and carried a
 * texture from outside Create's palette; the geometry is still deliberately plain and has nothing to keep in step with
 * {@code CraneModelLayout}, because the pallet-racking look — uprights, beams and a load that grows with the fill level
 * — is M29's (design steps 12–14).
 */
public class PalletRenderer extends EntityRenderer<PalletEntity> {
    /**
     * The block state the deck's vertex data is built for. A pallet is not a block, so this only decides the tint and
     * the model seed a {@code BakedModel} would be asked for — the deck has neither, so the empty state is honest.
     */
    private static final BlockState DECK_REFERENCE = Blocks.AIR.defaultBlockState();
    /** How tall the deck model is, as a fraction of a block: 3 of 16 pixels ({@code models/block/pallet/deck.json}). */
    private static final float DECK_HEIGHT = 3.0F / 16.0F;

    /** Height above the entity origin at which the carried item sits, i.e. on top of the deck. */
    private static final float ITEM_Y = DECK_HEIGHT;
    /** How large the carried item is drawn. */
    private static final float ITEM_SCALE = 0.6F;
    /** Degrees the item is turned around Y, so a flat item is not seen edge-on from the aisle. */
    private static final float ITEM_YAW_DEGREES = 45.0F;
    /** Quarter turn that lays a flat item (a plate, a sheet) down on the deck instead of standing it upright. */
    private static final float FLAT_ITEM_PITCH_DEGREES = 90.0F;
    /** Seed for the item model lookup; any fixed value, so a pallet does not flicker between model variants. */
    private static final int ITEM_SEED = 0;

    /** Height above the entity origin at which the count is written. */
    private static final float LABEL_Y = 1.0F;
    /** Font scale of the count label, the same as a vanilla name tag's. */
    private static final float LABEL_SCALE = 0.025F;
    /** Light andesite white, the colour this mod writes its numbers on blocks in. */
    private static final int LABEL_COLOR = 0xEDEDED;
    /** Radius of the shadow under a pallet, in blocks. */
    private static final float SHADOW_RADIUS = 0.5F;

    private final ItemRenderer itemRenderer;

    public PalletRenderer(EntityRendererProvider.Context context) {
        super(context);
        itemRenderer = context.getItemRenderer();
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

    @Override
    public void render(PalletEntity pallet, float entityYaw, float partialTick, PoseStack ms,
                       MultiBufferSource buffer, int packedLight) {
        renderDeck(ms, buffer, packedLight);
        Optional<ItemKey> carried = pallet.carriedKey();
        if (carried.isPresent()) {
            renderLoad(pallet, carried.get(), ms, buffer, packedLight);
            renderCount(pallet.carriedCount(), ms, buffer, packedLight);
        }
        super.render(pallet, entityYaw, partialTick, ms, buffer, packedLight);
    }

    private void renderDeck(PoseStack ms, MultiBufferSource buffer, int packedLight) {
        ms.pushPose();
        // A partial model is drawn into [0,1]^3 from the pose origin; an entity's origin is the centre of its feet, and
        // the pallet is exactly one block wide, so the deck only has to be moved into its own corner.
        ms.translate(-0.5F, 0.0F, -0.5F);
        CachedBuffers.partial(WareworksPartialModels.PALLET_DECK, DECK_REFERENCE).light(packedLight)
                .renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));
        ms.popPose();
    }

    /**
     * The carried item on the deck, the way the crane draws what it holds ({@code docs/stacker-crane.md} §7): block
     * items stand, flat items lie down.
     */
    private void renderLoad(PalletEntity pallet, ItemKey key, PoseStack ms, MultiBufferSource buffer,
                            int packedLight) {
        ItemStack stack = key.toStack();
        if (stack.isEmpty())
            return;
        BakedModel model = itemRenderer.getModel(stack, pallet.level(), null, ITEM_SEED);
        ms.pushPose();
        ms.translate(0.0F, ITEM_Y, 0.0F);
        ms.mulPose(Axis.YP.rotationDegrees(ITEM_YAW_DEGREES));
        if (!model.isGui3d())
            ms.mulPose(Axis.XP.rotationDegrees(FLAT_ITEM_PITCH_DEGREES));
        ms.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
        itemRenderer.render(stack, ItemDisplayContext.FIXED, false, ms, buffer, packedLight,
                OverlayTexture.NO_OVERLAY, model);
        ms.popPose();
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
