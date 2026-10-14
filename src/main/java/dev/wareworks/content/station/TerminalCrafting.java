package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import dev.wareworks.Wareworks;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.crafting.CraftGrid;
import dev.wareworks.core.crafting.SlotPlan;
import dev.wareworks.core.inventory.SlotView;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.EventHooks;

/**
 * The one place in Wareworks where a recipe runs (ADR-059, M32, issue #24): a player's own click at a warehouse
 * terminal, crafting from what the crane delivered into that terminal's buffer ({@code docs/warehouse-system.md}
 * §3.4.5).
 * <p>
 * <b>Why this class stands alone.</b> ADR-024 keeps Wareworks out of crafting: no station crafts by itself, no order
 * completes through a virtual crafting step, and the controller has no recipe layer. ADR-059 corrects one sentence of
 * it — nothing in Wareworks executes a recipe <i>on its own</i> — and this class is the whole of the exception. It is
 * reached only through {@code WarehouseTerminalBlockEntity#craftFromBuffer}, which requires the terminal menu the
 * acting player really has open. {@code RecipeExecutionBoundaryTest} fails the build when anything else in the mod
 * looks a recipe up, makes its result, asks it for its remainders or fires the crafting event, and when anything but
 * that chain — the payload, {@code WarehouseTerminalMenu#submitCraft}, the menu, {@code craftFromBuffer} — calls into
 * this class. No tick, order, pattern, rule or port reaches it: without a click nothing happens.
 * <p>
 * <b>Vanilla crafting only.</b> The grid is matched against {@link RecipeType#CRAFTING} through the server's own
 * recipe manager, the way a crafting table matches it; the client never runs recipe code. A cell is an exact
 * {@link ItemKey}, so the recipe's own ingredient test decides whether the grid matches, and a craft consumes exactly
 * the keys the grid shows — never a tag resolved behind the player's back. Special recipes (fireworks, dyeing, map and
 * book cloning, repair) work the same way, because they too only ever see the grid.
 * <p>
 * <b>One craft is a transaction that refuses before anything moves</b> ({@link #craft}), in this order:
 * <ol>
 * <li><b>limited crafting:</b> for a {@link ServerPlayer}, one fresh {@link ResultContainer} per craft must accept the
 * recipe ({@code RecipeCraftingHolder#setRecipeUsed}), otherwise {@link TerminalCraftResult#LOCKED};</li>
 * <li><b>the result,</b> assembled exactly as vanilla fills its result slot, otherwise
 * {@link TerminalCraftResult#NO_RECIPE};</li>
 * <li><b>the remainders,</b> from the <b>resolved</b> recipe and with the crafting player set
 * ({@link CommonHooks#setCraftingPlayer}). The recipe manager's own remainder lookup is not used: it looks the recipe
 * up again without the hint, and when nothing matches it hands the inputs back as "remainders";</li>
 * <li><b>the plan</b> ({@link SlotPlan}) over the buffer slots the player can see and their 36 main inventory slots:
 * take one item per occupied cell from the buffer, put the remainders buffer first, check that the result fits
 * inventory first. Anything short or without room is refused <b>here</b>, naming the item: nothing has moved, no event
 * has fired and no statistic has been awarded;</li>
 * <li><b>commit</b> the takes and the remainders with plain slot writes: {@link Inventory#setItem}, then every buffer
 * slot at once ({@link StationBuffer#setStacks}). No third-party code runs between the plan and the last write — the
 * buffer's change notification, which reaches the neighbouring blocks, fires once, after it — so the plan is what
 * happens;</li>
 * <li><b>vanilla's side effects, in vanilla's order, on the real result</b> before it is placed:
 * {@link ItemStack#onCraftedBy} (the statistic, a map's post-processing), the NeoForge crafting event, then the recipe
 * award and its advancement trigger;</li>
 * <li><b>place the result as it is now,</b> because a listener may have changed it: planned again on the live slots,
 * inventory first. A result nobody changed always fits, because step 4 checked it. The one way left for an item to
 * reach the world is a logged drop at the player's feet, reachable only when a listener changed the result or the
 * inventory in step 6 — and "dropped in the world" is one of the four places the item-conservation invariant allows.
 * </li>
 * </ol>
 * <b>What it never uses:</b> {@code PlayerMainInvWrapper} (its insert broadcasts the open menu from inside the craft),
 * {@code ItemHandlerHelper#giveItemToPlayer} (drops what does not fit), vanilla's result slot (drops remainders) and a
 * menu's shift-click. Items only ever move between this terminal's buffer and the crafting player's own inventory
 * (apart from step 7's logged drop), in one call made by that player at a screen they have open: nothing moves between
 * warehouse locations, storage is never touched, and nothing crafted enters the stock index.
 * <p>
 * <b>The preview</b> ({@link #preview}) is the same resolution without any of the above: what the grid makes and
 * whether the player may make it, for the crafting panel. It moves nothing and fires nothing.
 * <p>
 * Server thread only.
 */
public final class TerminalCrafting {
    /**
     * The menu behind the event's crafting matrix: it has no slots and ignores every change, so a listener that edits
     * the matrix touches nothing of ours and causes no broadcast (Create's {@code BlueprintCraftingInventory} pattern).
     */
    private static final AbstractContainerMenu DETACHED_MENU = new AbstractContainerMenu(null, -1) {
        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return false;
        }

        @Override
        public void slotsChanged(Container container) {
        }
    };

    /**
     * The server's reading of a laid grid, for the crafting panel.
     *
     * @param recipe the id of the crafting recipe the grid matches; empty when it matches none. The client sends it
     *               back as the next request's hint, so the server keeps choosing the same one of two matching recipes
     * @param result what one craft makes; an empty stack exactly when there is no recipe. Do not modify it
     * @param locked whether limited crafting keeps this player from the recipe; the panel still shows the result
     */
    public record Preview(Optional<ResourceLocation> recipe, ItemStack result, boolean locked) {
        /** A grid that matches no recipe, and the empty grid. */
        public static final Preview NONE = new Preview(Optional.empty(), ItemStack.EMPTY, false);

        public Preview {
            Objects.requireNonNull(recipe, "recipe");
            result = Objects.requireNonNull(result, "result").copy();
        }
    }

    private TerminalCrafting() {
    }

    /**
     * What {@code grid} makes for {@code player}, without crafting anything ({@code §3.4.5}, the server preview).
     * <p>
     * One recipe lookup for a grid that names an item, none for an empty one. The hint is tried first and looked up in
     * the <b>current</b> recipe manager, so the answer neither flips between two matching recipes nor survives a
     * {@code /reload} that removed the recipe.
     * <p>
     * Package-private, like {@link #craft}: only {@code WarehouseTerminalMenu} asks. That narrows who could call it but
     * is not the guard — the production station and the stock keeper share this package and hold a buffer too — the
     * source scan of {@code RecipeExecutionBoundaryTest} is.
     *
     * @param hint the recipe the client last showed for this grid; a hint only, never trusted
     */
    static Preview preview(Level level, Player player, CraftGrid<ItemKey> grid,
            Optional<ResourceLocation> hint) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(hint, "hint");
        if (level.isClientSide || grid.isEmpty())
            return Preview.NONE;
        CraftingInput input = input(grid);
        Optional<RecipeHolder<CraftingRecipe>> found = resolve(level, input, hint);
        if (found.isEmpty())
            return Preview.NONE;
        RecipeHolder<CraftingRecipe> recipe = found.get();
        ItemStack result = assemble(recipe, input, level);
        if (result.isEmpty())
            return Preview.NONE;
        // The same question step 1 of a craft asks, with a container of its own that is thrown away: asking it records
        // nothing on the player and awards nothing.
        boolean locked = player instanceof ServerPlayer serverPlayer
                && !new ResultContainer().setRecipeUsed(level, serverPlayer, recipe);
        return new Preview(Optional.of(recipe.id()), result, locked);
    }

    /**
     * Crafts {@code grid} from the terminal's {@code buffer} for {@code player}: once, or with {@code all} as often as
     * the delivered ingredients allow (see the class comment for one craft).
     * <p>
     * The recipe is resolved once for the whole click. With {@code all} the number of crafts is fixed <b>before</b> the
     * first one — what the buffer holds for the grid at the click ({@link CraftGrid#craftsFrom}) — and the loop stops
     * at the first refusal. That bound is what ends the loop for a recipe that gives an ingredient back (map and book
     * cloning): such a recipe simply crafts a little less per click. It is at most the buffer's slot count times a
     * stack, and no other cap is needed. A buffer that holds not even one craft still gets one attempt, which is
     * refused and names the missing ingredient.
     * <p>
     * The caller has already checked who is asking ({@code WarehouseTerminalBlockEntity#craftFromBuffer}, the only
     * caller {@code RecipeExecutionBoundaryTest} allows); this method checks only the grid and the recipe.
     * Package-private for the reason {@link #preview} gives.
     *
     * @param bufferSlots the buffer slots the player's menu shows; slots past it, or past the buffer's own size, are
     *                    neither taken from nor put into, because the player's hand cannot reach them either
     * @param hint        the recipe the client showed for this grid; a hint only, never trusted
     * @param all         Shift-click: craft as often as the buffer allows instead of once
     */
    static TerminalCraftOutcome craft(Level level, Player player, StationBuffer buffer, int bufferSlots,
            CraftGrid<ItemKey> grid, Optional<ResourceLocation> hint, boolean all) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(hint, "hint");
        if (level.isClientSide)
            return TerminalCraftOutcome.of(TerminalCraftResult.OUT_OF_REACH);
        if (grid.isEmpty())
            return TerminalCraftOutcome.of(TerminalCraftResult.GRID_EMPTY);
        Optional<RecipeHolder<CraftingRecipe>> found = resolve(level, input(grid), hint);
        if (found.isEmpty())
            return TerminalCraftOutcome.of(TerminalCraftResult.NO_RECIPE);
        RecipeHolder<CraftingRecipe> recipe = found.get();
        int slots = Math.max(0, Math.min(bufferSlots, buffer.getSlots()));
        int crafts = all ? Math.max(1, grid.craftsFrom(bufferCounts(buffer, slots, grid))) : 1;
        long made = 0L;
        Optional<ItemKey> result = Optional.empty();
        for (int craft = 0; craft < crafts; craft++) {
            TerminalCraftOutcome once = craftOnce(level, player, buffer, slots, grid, recipe);
            if (!once.isSuccess())
                return new TerminalCraftOutcome(once.result(), (int) Math.min(Integer.MAX_VALUE, made), once.about());
            made += once.crafted();
            if (result.isEmpty())
                result = once.about();
        }
        return new TerminalCraftOutcome(TerminalCraftResult.CRAFTED, (int) Math.min(Integer.MAX_VALUE, made), result);
    }

    /**
     * One craft, as the class comment lists it.
     *
     * @return {@link TerminalCraftResult#CRAFTED} with the count and key of the result as it was placed, or the
     *         refusal; a refusal has moved nothing
     */
    private static TerminalCraftOutcome craftOnce(Level level, Player player, StationBuffer buffer, int slots,
            CraftGrid<ItemKey> grid, RecipeHolder<CraftingRecipe> recipe) {
        // 1. Limited crafting, with one holder per craft: awarding the recipe reads it and resets it to null, so a
        // holder shared between crafts would award only the first. A plain Player (only a GameTest's mock player) has
        // no recipe book to check and nothing to award.
        ResultContainer used = null;
        if (player instanceof ServerPlayer serverPlayer) {
            used = new ResultContainer();
            if (!used.setRecipeUsed(level, serverPlayer, recipe))
                return TerminalCraftOutcome.of(TerminalCraftResult.LOCKED);
        }
        // 2. The result. A fresh input per craft, so a recipe that changes the stacks it is handed cannot change the
        // next craft.
        CraftingInput input = input(grid);
        ItemStack result = assemble(recipe, input, level);
        if (result.isEmpty())
            return TerminalCraftOutcome.of(TerminalCraftResult.NO_RECIPE);
        // 3. The remainders. Third-party code (the recipe, an item's remainder hook) runs here and in step 2, before
        // the slots are read, so nothing can change them between the plan and the commit.
        List<SlotPlan.Stack<ItemKey>> remainders = remainders(recipe, input, player);

        // 4. The plan, in SlotPlan#craft's order: takes, remainders buffer first, the result inventory first. The
        // writes are taken before the result is planned, because the result is placed only after the events (step 7).
        Inventory inventory = player.getInventory();
        SlotPlan<ItemKey> plan = SlotPlan.over(bufferViews(buffer, slots), inventoryViews(inventory));
        for (Map.Entry<ItemKey, Integer> need : grid.perCraft().entrySet()) {
            if (!plan.take(need.getKey(), need.getValue()))
                return refused(TerminalCraftResult.NOT_IN_BUFFER, need.getKey());
        }
        for (SlotPlan.Stack<ItemKey> remainder : remainders) {
            if (plan.put(remainder, SlotPlan.Side.BUFFER) > 0)
                return refused(TerminalCraftResult.NO_ROOM, remainder.key());
        }
        List<SlotPlan.Write<ItemKey>> takesAndRemainders = plan.writes();
        SlotPlan.Stack<ItemKey> planned = stack(result);
        if (plan.put(planned, SlotPlan.Side.INVENTORY) > 0)
            return refused(TerminalCraftResult.NO_ROOM, planned.key());

        // 5. Commit the takes and the remainders, 6. vanilla's side effects on the real result, in vanilla's order
        // (ResultSlot#checkTakeAchievements), and 7. the result placed as it is afterwards. The placement is in a
        // finally: code that throws must not take the result with it once its ingredients are gone. That includes the
        // commit's own last step, the buffer's change notification, which runs the neighbouring blocks' code — but
        // only after every write, so once the commit has started, the craft's ingredients are gone in full.
        ItemStack placed;
        try {
            commit(takesAndRemainders, buffer, inventory);
            result.onCraftedBy(level, player, result.getCount());
            TransientCraftingContainer matrix = matrix(grid);
            EventHooks.firePlayerCraftingEvent(player, result, matrix);
            if (used != null)
                used.awardUsedRecipes(player, matrix.getItems());
        } finally {
            placed = place(player, buffer, slots, result);
        }
        return new TerminalCraftOutcome(TerminalCraftResult.CRAFTED, placed.getCount(), ItemKey.fromStack(placed));
    }

    /**
     * Step 7: puts {@code result} down as it is now, planned on the live slots — the inventory first, then the buffer
     * — and drops what fits nowhere, which only a listener that changed the result or the inventory can cause.
     *
     * @return a copy of what was placed (or dropped); empty when a listener emptied the result
     */
    private static ItemStack place(Player player, StationBuffer buffer, int slots, ItemStack result) {
        if (result.isEmpty())
            return ItemStack.EMPTY;
        ItemStack made = result.copy();
        SlotPlan.Stack<ItemKey> stack = stack(made);
        Inventory inventory = player.getInventory();
        SlotPlan<ItemKey> plan = SlotPlan.over(bufferViews(buffer, slots), inventoryViews(inventory));
        int left = plan.put(stack, SlotPlan.Side.INVENTORY);
        commit(plan.writes(), buffer, inventory);
        if (left > 0) {
            Wareworks.LOGGER.warn("No room left for {} x{} crafted at a warehouse terminal by {}: a crafting listener "
                    + "changed the result or the inventory; dropped at the player", stack.key(), left,
                    player.getName().getString());
            while (left > 0) {
                int part = Math.min(left, stack.maxStackSize());
                player.drop(stack.key().toStack(part), false);
                left -= part;
            }
        }
        return made;
    }

    /** The recipe the grid matches: the hint first, from the current recipe manager, then the first match. */
    private static Optional<RecipeHolder<CraftingRecipe>> resolve(Level level, CraftingInput input,
            Optional<ResourceLocation> hint) {
        return level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level, hint.orElse(null));
    }

    /** The result as vanilla fills its result slot: empty when the recipe makes nothing or an item this world lacks. */
    private static ItemStack assemble(RecipeHolder<CraftingRecipe> recipe, CraftingInput input, Level level) {
        ItemStack result = recipe.value().assemble(input, level.registryAccess());
        return result.isEmpty() || !result.isItemEnabled(level.enabledFeatures()) ? ItemStack.EMPTY : result;
    }

    /** What the recipe leaves behind, in grid order, with the crafting player set for the remainder hooks. */
    private static List<SlotPlan.Stack<ItemKey>> remainders(RecipeHolder<CraftingRecipe> recipe, CraftingInput input,
            Player player) {
        NonNullList<ItemStack> left;
        CommonHooks.setCraftingPlayer(player);
        try {
            left = recipe.value().getRemainingItems(input);
        } finally {
            CommonHooks.setCraftingPlayer(null);
        }
        List<SlotPlan.Stack<ItemKey>> stacks = new ArrayList<>();
        for (ItemStack stack : left) {
            if (!stack.isEmpty())
                stacks.add(stack(stack));
        }
        return stacks;
    }

    /**
     * The grid as a recipe sees it: one item of each cell's exact key, trimmed to the occupied rows and columns the way
     * a crafting table trims its grid.
     */
    private static CraftingInput input(CraftGrid<ItemKey> grid) {
        List<ItemStack> stacks = new ArrayList<>(CraftGrid.SIZE);
        for (Optional<ItemKey> cell : grid.cells())
            stacks.add(cell.map(ItemKey::toStack).orElse(ItemStack.EMPTY));
        return CraftingInput.ofPositioned(CraftGrid.WIDTH, CraftGrid.WIDTH, stacks).input();
    }

    /**
     * The crafting matrix the event and the recipe award are given: copies of the items one craft consumed, in their
     * cells, over {@link #DETACHED_MENU}. A listener may change it freely; nothing reads it back.
     */
    private static TransientCraftingContainer matrix(CraftGrid<ItemKey> grid) {
        NonNullList<ItemStack> items = NonNullList.withSize(CraftGrid.SIZE, ItemStack.EMPTY);
        for (int cell = 0; cell < CraftGrid.SIZE; cell++) {
            Optional<ItemKey> key = grid.cell(cell);
            if (key.isPresent())
                items.set(cell, key.get().toStack());
        }
        return new TransientCraftingContainer(DETACHED_MENU, CraftGrid.WIDTH, CraftGrid.WIDTH, items);
    }

    /** How many of each of the grid's keys the first {@code slots} buffer slots hold; reads every slot once. */
    private static Map<ItemKey, Long> bufferCounts(StationBuffer buffer, int slots, CraftGrid<ItemKey> grid) {
        Set<ItemKey> keys = grid.perCraft().keySet();
        Map<ItemKey, Long> counts = new HashMap<>();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = buffer.getStackInSlot(slot);
            if (stack.isEmpty())
                continue;
            for (ItemKey key : keys) {
                if (key.matches(stack)) {
                    counts.merge(key, (long) stack.getCount(), Long::sum);
                    break;
                }
            }
        }
        return counts;
    }

    /**
     * The first {@code slots} buffer slots as the plan reads them, built from the live stacks for exactly one plan:
     * one view per slot, no stack copied.
     */
    private static List<SlotView<ItemKey>> bufferViews(StationBuffer buffer, int slots) {
        List<SlotView<ItemKey>> views = new ArrayList<>(slots);
        for (int slot = 0; slot < slots; slot++)
            views.add(view(buffer.getStackInSlot(slot), buffer.getSlotLimit(slot)));
        return views;
    }

    /** The player's 36 main inventory slots as the plan reads them; armour and the offhand are never touched. */
    private static List<SlotView<ItemKey>> inventoryViews(Inventory inventory) {
        List<SlotView<ItemKey>> views = new ArrayList<>(inventory.items.size());
        for (ItemStack stack : inventory.items)
            views.add(view(stack, inventory.getMaxStackSize()));
        return views;
    }

    private static SlotView<ItemKey> view(ItemStack stack, int slotLimit) {
        return stack.isEmpty() ? SlotView.empty(slotLimit)
                : SlotView.of(ItemKey.of(stack), stack.getCount(), slotLimit, stack.getMaxStackSize());
    }

    /**
     * Writes exactly the slots of {@code writes}, and only through plain setters: the inventory's first, vanilla's own
     * slot write, which runs nothing else and which the open menu then syncs like any other change; then every buffer
     * slot at once ({@link StationBuffer#setStacks}), which stores copies and marks the terminal changed <b>once</b>,
     * after the last write. That notification reaches the neighbouring blocks, i.e. other mods' code, which must never
     * run between two writes: each write is the planned contents of its slot, and a neighbour that took from a slot
     * still to be written would have its take written over. The plan names only slots of the views it was built over,
     * so no write is refused and nothing before that notification can throw.
     */
    private static void commit(List<SlotPlan.Write<ItemKey>> writes, StationBuffer buffer, Inventory inventory) {
        Map<Integer, ItemStack> bufferWrites = new LinkedHashMap<>();
        boolean inventoryChanged = false;
        for (SlotPlan.Write<ItemKey> write : writes) {
            SlotView<ItemKey> after = write.after();
            ItemStack stack = after.isEmpty() ? ItemStack.EMPTY : after.key().toStack(after.count());
            if (write.side() == SlotPlan.Side.BUFFER) {
                bufferWrites.put(write.slot(), stack);
            } else {
                inventory.setItem(write.slot(), stack);
                inventoryChanged = true;
            }
        }
        if (inventoryChanged)
            inventory.setChanged();
        buffer.setStacks(bufferWrites);
    }

    private static SlotPlan.Stack<ItemKey> stack(ItemStack stack) {
        return new SlotPlan.Stack<>(ItemKey.of(stack), stack.getCount(), stack.getMaxStackSize());
    }

    private static TerminalCraftOutcome refused(TerminalCraftResult result, ItemKey about) {
        return new TerminalCraftOutcome(result, 0, Optional.of(about));
    }
}
