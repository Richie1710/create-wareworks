package dev.wareworks.client.render;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import com.simibubi.create.infrastructure.config.AllConfigs;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.storage.RackBayBlockEntity;
import net.minecraft.Util;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Draws the item a rack bay stores, at the mouth of the bay in front of its load ({@code docs/warehouse-system.md}
 * §3.8, M29 step 13, issue #20, ADR-049).
 * <p>
 * <b>What this adds and what it does not.</b> The coarse fill level stays exactly where M28 put it — in the block state
 * property {@code RackBayBlock.FILL}, four steps of cartons on the pallet, baked into the chunk mesh and therefore free
 * at any distance (ADR-047). This renderer adds the one thing a silhouette cannot carry: <b>which</b> item those cartons
 * are. So the shape of the load says how full the bay is from across the warehouse, and the item says what is in it from
 * the aisle. Nothing here reads the count: the fill level already answers "how much", and a second answer drawn on top
 * of it would only be a worse one.
 * <p>
 * <b>An empty bay shows its filter</b> (issue #49): the same item in the same place, so a rack wall that is set up but
 * not yet filled reads as "this bay is for X". Stock always wins over the filter, and an empty bay without a filter
 * draws nothing ({@link BayDisplay}). A Create filter item (List, Attribute or Package Filter) is drawn as that
 * item, as Create's own filter slot draws it. Before the fix a bay returned as soon as it held nothing, so its filter
 * was invisible until the first item was stored, while the warehouse interface drew its filter all along.
 * <p>
 * <b>The view distance is the whole point of the design and is not negotiable.</b> Registering a block entity renderer
 * puts <i>every</i> block of that type into its chunk section's per-frame render list, whether anything is drawn or not
 * ({@code SectionCompiler#handleBlockEntity} adds a block entity exactly when its type has a renderer), and vanilla's
 * default bound is 64 blocks ({@code BlockEntityRenderer#getViewDistance}). A warehouse places bays by the hundred, so
 * {@link #getViewDistance()} is cut to Create's {@code filterItemRenderDistance} — the same cut, for the same reason, as
 * {@code WarehouseInterfaceRenderer#getViewDistance()} (see there, and §3.1.1). A bay beyond it costs one frustum test
 * and one squared distance per frame and draws nothing at all; what is left is a handful of bays an aisle wide.
 * Measured rather than claimed, on the client's own per-frame list by {@code dev.wareworks.dev.BaysVisualScenario}: of
 * a hundred-bay wall, 40 are drawn from the aisle, 28 from 9.5 blocks and 0 from eleven, and the frustum and distance
 * tests for the whole wall cost about 1.5 µs per frame once the loop is warm. What the scenario <b>asserts</b> is the
 * shape of that, not the three figures: beyond the cap not one bay of the hundred may be drawn, from the aisle at
 * least one must be, and at the edge camera some but not all — so widening the cap fails a run instead of costing
 * frames quietly. The counts and the nanoseconds are recorded in the run's {@code index.txt} for a person to read.
 * <p>
 * Where the item stands, how it is posed and how it is lit is {@link BayItemRenderer}, which an empty fluid bay uses
 * for its filter as well (issue #61).
 */
public class RackBayRenderer extends SafeBlockEntityRenderer<RackBayBlockEntity> {
    /** Never cull closer than this, whatever a client configured (the warehouse interface's own floor). */
    private static final int MIN_VIEW_DISTANCE = 1;

    /**
     * How many display stacks are kept before the cache is thrown away. A bay holds one item type and only bays within
     * {@link #getViewDistance()} are ever drawn, so a real warehouse never reaches this; it exists because the cache is
     * keyed by the full {@link ItemKey} rather than by the item, and a creative session could otherwise walk the cache
     * up without bound.
     */
    private static final int MAX_DISPLAY_STACKS = 256;
    /** Draws the item at the mouth of the bay, and reads its light; shared with {@link FluidBayRenderer}. */
    private final BayItemRenderer bayItem;
    /**
     * One display stack per stored key; a bay syncs an {@link ItemKey}, which carries no stack of its own, and
     * {@code ItemKey#toStack()} allocates — which a renderer of the mod's most mass-placed block must not do per frame.
     * Keyed by the whole key rather than by the item, so a stored potion or a dyed shulker box is drawn as the variant
     * it is. Read and written on the render thread only, exactly as {@code StackerCraneRenderer} keeps its own.
     */
    private final Map<ItemKey, ItemStack> displayStacks = new HashMap<>();

    public RackBayRenderer(BlockEntityRendererProvider.Context context) {
        bayItem = new BayItemRenderer(context.getItemRenderer());
    }

    /**
     * The distance Create cuts a filter item off at, rounded up to whole blocks — the bound a rack wall is culled by
     * before this renderer is entered. See the class comment: this is the budget, not a preference.
     */
    @Override
    public int getViewDistance() {
        return Math.max(MIN_VIEW_DISTANCE, Mth.ceil(AllConfigs.client().filterItemRenderDistance.getF()));
    }

    @Override
    protected void renderSafe(RackBayBlockEntity bay, float partialTicks, PoseStack ms, MultiBufferSource buffer,
                              int light, int overlay) {
        Level level = bay.getLevel();
        if (level == null)
            return;
        ItemStack display = displayedItem(bay);
        if (display.isEmpty())
            return;
        bayItem.draw(display, level, bay.getBlockPos(), bay.facing(), ms, buffer, light);
        // Recorded for the visual harness, which checks that this draw happened, not only what displayedItem decides.
        bay.recordDrawn(display, Util.getNanos());
    }

    /**
     * The stack this renderer draws for {@code bay}, by {@link BayDisplay#choose} (issue #49): the stored item
     * type, else the store filter item of an empty bay, else {@link ItemStack#EMPTY}. Allocates nothing: the stored
     * type comes from {@link #displayStacks}, the filter is the behaviour's own stack
     * ({@code RackBayBlockEntity#storeFilterView}), which is only read here. Public so the visual harness can check what
     * a bay really shows ({@code dev.wareworks.dev.BlocksVisualScenario}) instead of only taking a picture of it.
     */
    public ItemStack displayedItem(RackBayBlockEntity bay) {
        ItemKey stored = bay.storedKeyOrNull();
        ItemStack filter = bay.storeFilterView();
        return switch (BayDisplay.choose(stored != null, !filter.isEmpty())) {
            case STORED -> displayStack(stored);
            case FILTER -> filter;
            case NOTHING -> ItemStack.EMPTY;
        };
    }

    /** The cached one-item stack of {@code key}, built once and reused; see {@link #displayStacks}. */
    private ItemStack displayStack(ItemKey key) {
        ItemStack display = displayStacks.get(key);
        if (display != null)
            return display;
        if (displayStacks.size() >= MAX_DISPLAY_STACKS)
            displayStacks.clear();
        display = key.toStack();
        displayStacks.put(key, display);
        return display;
    }
}
