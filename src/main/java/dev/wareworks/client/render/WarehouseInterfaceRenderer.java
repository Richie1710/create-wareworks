package dev.wareworks.client.render;

import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import com.simibubi.create.infrastructure.config.AllConfigs;

import dev.wareworks.content.storage.WarehouseInterfaceBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;

/**
 * Draws the store filter item of a warehouse interface (M8, ADR-021): Create's {@link SmartBlockEntityRenderer} with
 * the one change the mod's most mass-placed block needs.
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
 * The warehouse output keeps the stock renderer: an aisle has a handful of them, not a wall.
 */
public class WarehouseInterfaceRenderer extends SmartBlockEntityRenderer<WarehouseInterfaceBlockEntity> {
    /** Never cull closer than this, whatever a client configured. */
    private static final int MIN_VIEW_DISTANCE = 1;

    public WarehouseInterfaceRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    /** The distance {@code FilteringBehaviour#getRenderDistance()} itself enforces, rounded up to whole blocks. */
    @Override
    public int getViewDistance() {
        return Math.max(MIN_VIEW_DISTANCE, Mth.ceil(AllConfigs.client().filterItemRenderDistance.getF()));
    }
}
