package dev.wareworks.content.station;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.IInteractionChecker;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.controller.WarehouseMember;
import dev.wareworks.content.controller.WarehouseRegistry;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.stock.RestockOutcome;
import dev.wareworks.core.stock.StockLevels;
import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleAdjustment;
import dev.wareworks.core.stock.StockRuleEvaluation;
import dev.wareworks.core.stock.StockRuleStatus;
import dev.wareworks.core.warehouse.LocationKind;
import dev.wareworks.util.GoggleObservers;
import dev.wareworks.util.SyncThrottle;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity of the warehouse stock keeper ({@code docs/warehouse-system.md} §3.6, M15, issue #3): the aisle member
 * that holds the warehouse's <b>stock rules</b>.
 * <p>
 * <b>One block, a list of rules.</b> A rule is one item plus three numbers, and each number governs a different
 * direction: the <i>minimum</i> what comes in (the keeper's comparator calls for the item), the <i>maximum</i> what may
 * be stored (above it the crane stops accepting the item and a warehouse input backs up on purpose), the
 * <i>reserve</i> what may go out to the warehouse's own automation (a redstone request stops at it, a player at a
 * terminal may take it and is told so). An aisle may hold several keepers; a keeper with a single rule is the cheap
 * per-item variant, which is why there is no second block type.
 * <p>
 * <b>It holds no items.</b> No buffer, no item capability, no belt input, no mechanical arm point, and the crane never
 * has anything to do here — the rows are ghost items like a production pattern's cells, so editing a rule can never
 * consume, duplicate or swallow anything. That is also why it is a {@link LocationKind#KEEPER} and not a station.
 * <p>
 * <b>The controller owns the copy that is enforced.</b> This block entity is where a player edits the rules and where
 * they are saved, but everything that gates item movement asks the controller's own copy
 * ({@code WarehouseControllerBlockEntity#stockRules()}), which is saved with the controller and is therefore readable
 * before the first plan after a world load even when this keeper's chunk is not loaded (the M8 cold-cache lesson).
 * Every edit tells the controller at once ({@link WarehouseRegistry#stockRulesChanged}), so the two are never out of
 * step while both are loaded.
 * <p>
 * <b>No ticker.</b> The rules are edited in the screen ({@link #openScreen}); the lamp and the comparator value are
 * refreshed by the controller's own rule tick ({@link #refreshRuleState}), at most every
 * {@code stockRuleIntervalTicks} and only when something really changed, so a crane delivering a stack produces one
 * neighbour update rather than one per item.
 */
public class WarehouseStockKeeperBlockEntity extends SmartBlockEntity
        implements WarehouseMember, IHaveGoggleInformation, GoggleObservers.Observable, Clearable, IInteractionChecker {
    /** NBT key of the goggle summary in client packets. */
    public static final String SUMMARY_TAG = "GoggleSummary";

    /** Largest comparator signal a keeper can emit. */
    public static final int MAX_SIGNAL = 15;

    private final StockKeeperRules rules;

    /** Derived goggle state; synced to clients, never authoritative. */
    private StockKeeperGoggleSummary summary = StockKeeperGoggleSummary.NONE;
    private final SyncThrottle summarySync = new SyncThrottle(GoggleObservers.SUMMARY_SYNC_MIN_INTERVAL_TICKS);

    /**
     * Governing rules of this keeper below their minimum, i.e. the comparator value.
     * <p>
     * Derived and deliberately <b>not saved</b>: its only authority is the controller's rule tick, which judges the real
     * stock within {@code stockRuleIntervalTicks} of a load. A saved value is the one way a keeper could keep calling for
     * an item after the warehouse that asked for it was taken apart while this keeper's chunk was unloaded — a redstone
     * signal nothing enforces, surviving every restart (M15 review fix). A load therefore starts at 0 and the first rule
     * tick establishes the truth.
     */
    private int belowMinimum;

    public WarehouseStockKeeperBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        rules = new StockKeeperRules(WareworksConfig.stockKeeperRows());
    }

    /** No behaviours: the keeper is operated through its screen, and like every member block it has no ticker. */
    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    // --- membership --------------------------------------------------------------------------------------------

    @Override
    public LocationKind locationKind() {
        return LocationKind.KEEPER;
    }

    /** Direction from the keeper towards the aisle, like every other member that faces it. */
    @Override
    public Direction facing() {
        return getBlockState().getOptionalValue(HorizontalDirectionalBlock.FACING).orElse(Direction.NORTH);
    }

    /** The controller of the aisle this keeper is an aligned member of (server). */
    public Optional<WarehouseControllerBlockEntity> controller() {
        if (level == null || level.isClientSide || isRemoved())
            return Optional.empty();
        return WarehouseRegistry.findController(level, worldPosition);
    }

    // --- rules -------------------------------------------------------------------------------------------------

    /** The keeper's rule rows (server). */
    public StockKeeperRules rules() {
        return rules;
    }

    /** How many rows this keeper offers, which both sides of the menu have to agree on. */
    public int rowCount() {
        return rules.size();
    }

    /**
     * Server: applies one edit of a validated configuration payload, saves it and tells the controller of this aisle
     * at once, so the next plan and the next request already obey the new rule.
     *
     * @return what the edit did, including the one correction the stored numbers needed
     */
    public StockKeeperRules.Edit editRule(int row, int field, @Nullable ItemKey key, long value) {
        if (level == null || level.isClientSide || isRemoved())
            return StockKeeperRules.Edit.NONE;
        if (field == StockKeeperRules.FIELD_RESUME)
            return resumeRule(row);
        // The item the row governed before the edit: re-editing a rule is one of the two ways to lift its safety stop
        // (the other is deleting it), so both the old and the new item are considered below.
        ItemKey before = rules.ruleAt(row).map(StockRule::key).orElse(null);
        StockKeeperRules.Edit edit = rules.apply(row, field, key, value);
        if (!edit.changed())
            return edit;
        setChanged();
        // The controller learns of the edit first: whether this row is the one that governs its item is its answer, and
        // it has to be given about the rules as they now stand.
        notifyRulesChanged();
        boolean resumed = resumeAfterEdit(row, before);
        resumed |= resumeAfterEdit(row, rules.ruleAt(row).map(StockRule::key).orElse(null));
        return resumed ? edit.asResumed() : edit;
    }

    /**
     * Server: the player's explicit "order this again" on a paused row (M15 part 2, issue #3) — the way back from the
     * safety stop, and the reason it is a click rather than a timer: the warehouse cannot tell a fixed machine from a
     * broken one, so only a player may say that it is worth another batch.
     *
     * @return an edit that reports whether a pause was really lifted (nothing is written into the rules themselves)
     */
    private StockKeeperRules.Edit resumeRule(int row) {
        Optional<StockRule<ItemKey>> rule = rules.ruleAt(row);
        WarehouseControllerBlockEntity controller = controller().orElse(null);
        if (rule.isEmpty() || controller == null || !governsAt(controller, row, rule.get().key())
                || !controller.resumeStockRule(rule.get().key()))
            return StockKeeperRules.Edit.NONE;
        return new StockKeeperRules.Edit(true, StockRuleAdjustment.NONE, true);
    }

    /**
     * Lifts the safety stop of an item whose rule a player has just rewritten, <b>from the row that really governs
     * it</b>; does nothing when it is not paused.
     * <p>
     * The governance check is the point (M15 review fix). A pause is held per item, so without it a number scrolled on
     * a shadowed duplicate row — a row that applies nothing at all — re-armed automatic ordering into the very machine
     * that had swallowed a batch, within one {@code stockRuleIntervalTicks} and with nothing said anywhere.
     *
     * @return whether a pause was really lifted
     */
    private boolean resumeAfterEdit(int row, @Nullable ItemKey key) {
        if (key == null)
            return false;
        WarehouseControllerBlockEntity controller = controller().orElse(null);
        return controller != null && governsAt(controller, row, key) && controller.resumeStockRule(key);
    }

    /**
     * Whether the rule this keeper holds at {@code row} is the one that governs {@code key} in its aisle.
     * <p>
     * Asked of the controller's rule set and not of its per-tick evaluation: an edit happens in the middle of a tick
     * that may still change the levels, and building the evaluation here would freeze the answer every other surface of
     * the same tick then reads.
     */
    private boolean governsAt(WarehouseControllerBlockEntity controller, int row, ItemKey key) {
        int index = ruleIndexOf(row);
        return index >= 0 && controller.stockRuleGoverns(worldPosition, index, key);
    }

    /**
     * Where {@code row} stands in the aisle's rule list: its position with the empty rows before it skipped, which is
     * the order a controller keeps its copy in. -1 for a row that holds no rule.
     */
    private int ruleIndexOf(int row) {
        if (rules.ruleAt(row).isEmpty())
            return -1;
        int index = 0;
        for (int earlier = 0; earlier < row; earlier++) {
            if (rules.ruleAt(earlier).isPresent())
                index++;
        }
        return index;
    }

    /** Server: the controllers whose aisle contains this keeper re-read its rules. */
    private void notifyRulesChanged() {
        if (level instanceof ServerLevel)
            WarehouseRegistry.stockRulesChanged(level, worldPosition);
    }

    // --- what the rules are doing --------------------------------------------------------------------------------

    /**
     * Server: what the aisle's rule set says about this keeper's rules right now, as one bounded summary.
     * <p>
     * The status of a rule is <b>not</b> decided here: it is decided by the controller's copy, because only that copy
     * knows the whole aisle — whether an earlier rule already governs the same item (shadowed) and whether the rule is
     * beyond the aisle's rule cap (inert). A keeper without a loaded controller reports
     * {@link StockRuleStatus#NO_WAREHOUSE} for every rule and applies nothing.
     */
    public StockKeeperGoggleSummary evaluateWith(@Nullable WarehouseControllerBlockEntity controller) {
        return evaluateWith(controller, WarehouseRegistry.assignmentOf(level, worldPosition, this));
    }

    /**
     * {@link #evaluateWith(WarehouseControllerBlockEntity)} with the aisle address already known.
     * <p>
     * The address is the one expensive part of the summary — it is a scan over every registered controller of the level
     * — and it only ever changes when the warehouse around this keeper changes, so the rule tick reuses the address of
     * the last observation and only a player actually looking through goggles pays for a fresh one (M15 review fix).
     */
    private StockKeeperGoggleSummary evaluateWith(@Nullable WarehouseControllerBlockEntity controller,
            AisleAssignment assignment) {
        int configured = rules.ruleCount();
        if (controller == null || controller.isRemoved())
            return new StockKeeperGoggleSummary(assignment, configured, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
        // The evaluations, not just the statuses: each one carries what the rule COUNTS as (its three numbers) and
        // what restocking is doing about it, and the two are deliberately different numbers — a paused rule keeps
        // calling for its item exactly as it did before it was paused (M15 part 2).
        List<StockRuleEvaluation<ItemKey>> views = controller.stockRuleViewsAt(worldPosition);
        int governing = 0;
        int below = 0;
        int atMaximum = 0;
        int atReserve = 0;
        int withoutEffect = 0;
        int ordering = 0;
        int waiting = 0;
        int paused = 0;
        int pausedBelowMinimum = 0;
        int index = 0;
        for (int row = 0; row < rules.size(); row++) {
            if (rules.ruleAt(row).isEmpty())
                continue;
            int ruleIndex = index++;
            // Beyond the controller's list only for a keeper it holds no rules for (yet): such a rule governs nothing
            // and is no duplicate either, so it counts nowhere.
            if (ruleIndex >= views.size())
                continue;
            StockRuleEvaluation<ItemKey> view = views.get(ruleIndex);
            switch (view.status()) {
                case BELOW_MINIMUM -> {
                    governing++;
                    below++;
                }
                case AT_MAXIMUM -> {
                    governing++;
                    atMaximum++;
                }
                case AT_RESERVE -> {
                    governing++;
                    atReserve++;
                }
                case SATISFIED -> governing++;
                case SHADOWED, INERT -> withoutEffect++;
                default -> {
                    // NO_LIMITS: a row with an item and no number yet. It governs nothing, so it counts nowhere.
                }
            }
            if (!view.governs())
                continue;
            if (view.restock() == RestockOutcome.PAUSED) {
                paused++;
                // A paused rule still counts as whatever its three numbers say, so most of them are counted in
                // belowMinimum as well - which is the comparator value and must not change (M15 part 2). The overlap is
                // recorded instead, so the tooltip can print the paused line as the refinement it is rather than as a
                // second group a player adds on top (M15 review fix).
                if (view.status() == StockRuleStatus.BELOW_MINIMUM)
                    pausedBelowMinimum++;
            } else if (view.displayStatus() == StockRuleStatus.ORDERING) {
                ordering++;
            } else if (view.displayStatus() == StockRuleStatus.WAITING_FOR_INGREDIENTS) {
                waiting++;
            }
        }
        return new StockKeeperGoggleSummary(assignment, configured, governing, below, atMaximum, atReserve,
                withoutEffect, ordering, waiting, paused, pausedBelowMinimum, true);
    }

    /**
     * Server: the rows as the screen shows them — the stored numbers, what the warehouse holds and what each rule is
     * doing. Built by the menu for the one player who has the screen open, so the items travel in a menu payload and
     * never in a chunk packet.
     *
     * @param adjustment  what the last accepted edit had to correct
     * @param adjustedRow the row that correction happened in
     */
    public StockKeeperScreenState screenState(StockRuleAdjustment adjustment, int adjustedRow) {
        return screenState(adjustment, adjustedRow, false);
    }

    /**
     * {@link #screenState(StockRuleAdjustment, int)} that also reports whether the last accepted edit lifted a rule's
     * safety stop (M15 part 2), which the screen says in its status line.
     */
    public StockKeeperScreenState screenState(StockRuleAdjustment adjustment, int adjustedRow, boolean resumed) {
        WarehouseControllerBlockEntity controller = controller().orElse(null);
        // One pass for the whole keeper: the aisle's own evaluation of this tick already carries every row's status,
        // the levels it was judged against and what restocking is doing about it, so no row costs a second walk of
        // the open requests (M15 review fix) and none costs a stock lookup of its own.
        List<StockRuleEvaluation<ItemKey>> evaluations = controller == null ? List.of()
                : controller.stockRuleViewsAt(worldPosition);
        List<StockKeeperScreenState.RowView> views = new ArrayList<>(rules.size());
        int index = 0;
        for (int row = 0; row < rules.size(); row++) {
            Optional<StockRule<ItemKey>> rule = rules.ruleAt(row);
            if (rule.isEmpty()) {
                views.add(StockKeeperScreenState.RowView.empty(row));
                continue;
            }
            StockRule<ItemKey> configured = rule.get();
            int ruleIndex = index++;
            StockRuleEvaluation<ItemKey> evaluation = ruleIndex < evaluations.size() ? evaluations.get(ruleIndex)
                    : null;
            StockRuleStatus status = evaluation == null ? StockRuleStatus.NO_WAREHOUSE : evaluation.displayStatus();
            StockLevels levels = evaluation == null ? StockLevels.NONE : evaluation.levels();
            // Only for a paused row, and only then is the controller asked: the number is the whole reason the screen
            // can say what the safety stop cost (M15 part 2).
            long unrecovered = controller != null && status.isPaused()
                    ? controller.stockRulePause(configured.key()).map(pause -> pause.unrecovered()).orElse(0L) : 0L;
            // What automatic restocking last decided, and the ingredient it is waiting for: the status alone folds
            // several of those answers back into a bare "below the minimum", and the outcome is the line that says
            // which one it is (M15 review fix).
            RestockOutcome restock = evaluation == null ? RestockOutcome.NOT_GOVERNING : evaluation.restock();
            Optional<ItemKey> missing = controller == null
                    || restock != RestockOutcome.WAITING_FOR_INGREDIENTS ? Optional.empty()
                    : controller.restockMissingIngredient(configured.key());
            views.add(new StockKeeperScreenState.RowView(row, Optional.of(configured.key()), configured.minimum(),
                    configured.maximum(), configured.reserve(), status, levels.stocked(), levels.available(),
                    configured.heldBack(levels.available()), configured.shortfall(levels), unrecovered, restock,
                    missing));
        }
        return new StockKeeperScreenState(views, controller != null, adjustment, adjustedRow, resumed);
    }

    /**
     * Server: the controller's rule tick refreshed the aisle's rule set — take the new lamp state and comparator value
     * from it. Writes the block state and pushes neighbour updates <b>only when something really changed</b>, so a
     * crane delivering a stack of 64 produces at most one update, not 64.
     */
    public void refreshRuleState(WarehouseControllerBlockEntity controller) {
        Objects.requireNonNull(controller, "controller");
        // The aisle address of the last observation is reused: only a player looking through goggles needs a fresh one,
        // and resolving it here scanned every controller of the level 20 times a second (M15 review fix).
        applyRuleState(evaluateWith(controller, summary.assignment()));
    }

    /**
     * Server: this keeper is not part of a warehouse any more — its controller was broken, its aisle was lost, or it
     * was turned away from the aisle — so it stops saying anything about stock: the comparator drops to 0 and the lamp
     * goes out.
     * <p>
     * Both are derived state whose only other writer is {@link #refreshRuleState}, which the controller's rule tick
     * calls and which never runs again once this keeper is not a member. Without this counterpart a keeper kept calling
     * for an item for ever, across saves, for a warehouse that enforces nothing — while its own goggles already said
     * "not part of a warehouse" (M15 review fix). Called by the controller only on a <b>real</b> loss: a chunk unload
     * leaves everything as it is (ADR-013).
     */
    public void clearRuleState() {
        applyRuleState(evaluateWith(null, summary.assignment()));
    }

    private void applyRuleState(StockKeeperGoggleSummary next) {
        if (level == null || level.isClientSide || isRemoved())
            return;
        if (!next.equals(summary)) {
            summary = next;
            summarySync.markPending();
        }
        if (next.belowMinimum() != belowMinimum) {
            belowMinimum = next.belowMinimum();
            setChanged();
            // The comparator is the only redstone output of a keeper, so this is the one neighbour update it makes.
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        }
        BlockState state = getBlockState();
        boolean lit = next.bites();
        boolean paused = next.isPaused();
        BlockState wanted = state;
        if (state.hasProperty(WarehouseStockKeeperBlock.LIT))
            wanted = wanted.setValue(WarehouseStockKeeperBlock.LIT, lit);
        // The second lamp: the safety stop burns in its own colour, because "a rule is biting" and "a machine ate a
        // batch and the warehouse stopped ordering" are not the same message (M15 part 2, issue #3).
        if (state.hasProperty(WarehouseStockKeeperBlock.PAUSED))
            wanted = wanted.setValue(WarehouseStockKeeperBlock.PAUSED, paused);
        if (wanted != state)
            // UPDATE_CLIENTS and nothing else: the lamp is what a player sees, not something neighbours react to.
            level.setBlock(worldPosition, wanted, Block.UPDATE_CLIENTS);
    }

    /** The comparator value: governing rules of this keeper that are below their minimum, at most {@value #MAX_SIGNAL}. */
    public int comparatorSignal() {
        return Math.max(0, Math.min(belowMinimum, MAX_SIGNAL));
    }

    // --- screen ------------------------------------------------------------------------------------------------

    /**
     * Opens the rule screen for {@code player} (server). The extra data carries the block position and the row count,
     * exactly as the terminal and the production station do, so both sides build the same layout even when the
     * client's block entity is missing.
     *
     * @return whether a screen was opened
     */
    public boolean openScreen(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (level == null || level.isClientSide || isRemoved())
            return false;
        return player.openMenu(new SimpleMenuProvider(
                (id, inventory, owner) -> StockKeeperMenu.create(id, inventory, this),
                getBlockState().getBlock().getName()), extra -> {
                    extra.writeBlockPos(worldPosition);
                    extra.writeVarInt(rules.size());
                }).isPresent();
    }

    /**
     * Whether {@code player} may use this keeper, by the same rule vanilla containers use
     * ({@code Container#stillValidBlockEntity}). Create's {@code MenuBase} asks it every tick, so a broken, replaced or
     * unloaded keeper closes its screen by itself. Never throws.
     */
    @Override
    public boolean canPlayerUse(Player player) {
        Objects.requireNonNull(player, "player");
        return !isRemoved() && Container.stillValidBlockEntity(this, player);
    }

    // --- goggles -----------------------------------------------------------------------------------------------

    /** The goggle data as of the last observation (server) or sync (client). */
    public StockKeeperGoggleSummary summary() {
        return summary;
    }

    /** A player looks at the keeper through goggles (server): rebuild the summary and sync a change (throttled). */
    @Override
    public void onGoggleObserved() {
        if (level == null || level.isClientSide || isVirtual() || isRemoved())
            return;
        StockKeeperGoggleSummary next = evaluateWith(controller().orElse(null));
        if (!next.equals(summary)) {
            summary = next;
            summarySync.markPending();
        }
        if (summarySync.tryConsume(level.getGameTime()))
            sendData(); // otherwise throttled: a later observation sends it
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        StockKeeperGoggleSummary shown = summary;
        WareworksLang.translate(WareworksLang.GOGGLES_WAREHOUSE_STOCK_KEEPER).forGoggles(tooltip);
        shown.assignment().addGoggleLines(tooltip, WareworksLang.GOGGLES_STATION_MISALIGNED_HINT, 1);
        if (shown.rules() == 0) {
            WareworksLang.translate(WareworksLang.GOGGLES_KEEPER_NO_RULES).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
            return true;
        }
        WareworksLang.countLine(WareworksLang.GOGGLES_KEEPER_RULES, shown.rules()).forGoggles(tooltip, 1);
        if (!shown.linked()) {
            WareworksLang.translate(WareworksLang.GOGGLES_KEEPER_NO_WAREHOUSE).style(ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
            return true;
        }
        if (shown.belowMinimum() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_KEEPER_BELOW_MINIMUM, shown.belowMinimum())
                    .style(ChatFormatting.GOLD).forGoggles(tooltip, 1);
        if (shown.atMaximum() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_KEEPER_AT_MAXIMUM, shown.atMaximum())
                    .style(ChatFormatting.GOLD).forGoggles(tooltip, 1);
        if (shown.atReserve() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_KEEPER_AT_RESERVE, shown.atReserve())
                    .forGoggles(tooltip, 1);
        // What the warehouse is doing about the shortfall, under the number that names it (M15 part 2, issue #3).
        if (shown.ordering() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_KEEPER_ORDERING, shown.ordering())
                    .style(ChatFormatting.AQUA).forGoggles(tooltip, 2);
        if (shown.waiting() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_KEEPER_WAITING, shown.waiting())
                    .style(ChatFormatting.GOLD).forGoggles(tooltip, 2);
        // Red, and split exactly the way the rules are: the ones that are also counted under "below minimum" are
        // printed as a refinement of that line, the rest as a group of their own, so a player never adds the same rule
        // twice (M15 review fix). The safety stop is the one line they have to act on, and it says what to do.
        if (shown.paused() > 0) {
            int indent = 1;
            if (shown.pausedBelowMinimum() > 0) {
                WareworksLang.countLine(WareworksLang.GOGGLES_KEEPER_PAUSED, shown.pausedBelowMinimum())
                        .style(ChatFormatting.RED).forGoggles(tooltip, 2);
                indent = 2;
            }
            int elsewhere = shown.paused() - shown.pausedBelowMinimum();
            if (elsewhere > 0) {
                WareworksLang.countLine(WareworksLang.GOGGLES_KEEPER_PAUSED, elsewhere)
                        .style(ChatFormatting.RED).forGoggles(tooltip, 1);
                indent = 1;
            }
            WareworksLang.translate(WareworksLang.GOGGLES_KEEPER_PAUSED_HINT).style(ChatFormatting.GRAY)
                    .forGoggles(tooltip, indent + 1);
        }
        if (shown.withoutEffect() > 0)
            WareworksLang.countLine(WareworksLang.GOGGLES_KEEPER_WITHOUT_EFFECT, shown.withoutEffect())
                    .style(ChatFormatting.GOLD).forGoggles(tooltip, 1);
        if (!shown.bites() && shown.withoutEffect() == 0)
            WareworksLang.translate(WareworksLang.GOGGLES_KEEPER_SATISFIED).style(ChatFormatting.GREEN)
                    .forGoggles(tooltip, 1);
        return true;
    }

    // --- lifecycle ---------------------------------------------------------------------------------------------

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            WarehouseRegistry.memberChanged(level, worldPosition);
            notifyRulesChanged();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setBlockState(BlockState state) {
        Direction before = facing();
        super.setBlockState(state);
        if (facing() != before && level instanceof ServerLevel) {
            WarehouseRegistry.memberChanged(level, worldPosition); // rotated: aligned and misaligned swap
            notifyRulesChanged();
        }
    }

    /**
     * Real removal. Chunk unloads do not notify: an unloaded position keeps its record, and the controller keeps its
     * copy of this keeper's rules until the keeper really leaves the aisle ({@code warehouse-system.md} §4).
     */
    @Override
    public void remove() {
        super.remove();
        if (level instanceof ServerLevel) {
            WarehouseRegistry.memberChanged(level, worldPosition);
            WarehouseRegistry.stockRulesChanged(level, worldPosition);
        }
    }

    /**
     * {@link Clearable}: commands and structure placement replace the block. A keeper holds <b>no items</b>, so there
     * is nothing to clear — and its rules are configuration, not contents: deleting them here would throw away exactly
     * what a {@code /clone ... move} is supposed to carry along.
     */
    @Override
    public void clearContent() {
    }

    // --- persistence and sync ------------------------------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (clientPacket) {
            // Only the bounded numbers: a rule's item never goes into a chunk packet (see StockKeeperGoggleSummary).
            CompoundTag summaryTag = new CompoundTag();
            summary.write(summaryTag);
            tag.put(SUMMARY_TAG, summaryTag);
            return;
        }
        writeRules(tag, registries);
    }

    /**
     * What a <b>schematic</b> carries along ({@code PartialSafeNBT}, the path Create's schematicannon and printer take
     * for a block that is not in {@code create:safe_nbt}): the rules a player wrote, and nothing else.
     * <p>
     * Deliberately <b>not</b> the comparator value: that is derived from the warehouse this keeper stands in, and a copy
     * printed somewhere else must not start out calling for items nothing is missing (M15 review fix). A wrench pickup
     * or a broken block does not come through here at all — it drops through the loot table, so it keeps no rules, the
     * same as a production station's patterns and a warehouse interface's store filter.
     */
    @Override
    public void writeSafe(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeSafe(tag, registries);
        writeRules(tag, registries);
    }

    private void writeRules(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put(StockKeeperRules.RULES_TAG, rules.save(registries));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            summary = StockKeeperGoggleSummary.read(tag.getCompound(SUMMARY_TAG));
            return;
        }
        rules.load(tag.getList(StockKeeperRules.RULES_TAG, Tag.TAG_COMPOUND), registries);
        if (level instanceof ServerLevel && !isRemoved())
            notifyRulesChanged(); // data changed on a live block entity (e.g. /data merge): keep the copy in step
    }
}
