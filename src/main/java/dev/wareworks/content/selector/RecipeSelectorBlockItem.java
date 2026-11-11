package dev.wareworks.content.selector;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.station.WarehouseProductionBlockEntity;
import dev.wareworks.core.production.SelectorMode;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The item of both Recipe Selector tiers (ADR-085, design §3.2 to §3.5): the link gestures and the placement rules.
 * <ul>
 * <li><b>Sneak-click a Production Station</b>: the item is linked to it ({@code wareworks:selector_link}). The Ender
 * item also switches a station in no selector mode to <b>recipe slips</b>, with the machine kind its machine link
 * declares (it needs one first).</li>
 * <li><b>Sneak-click a Warehouse Input</b>: binds it as the linked station's return input, through the station's
 * controller ({@link WarehouseControllerBlockEntity#bindSelectorReturnInput}).</li>
 * <li>Ender only: <b>sneak-click a saw or basin</b> (a Mixer or Press stands for the Basin two below it) links the item
 * to that machine; <b>sneak-click a placed Ender selector</b> with a machine-linked item gives it that link.</li>
 * <li><b>Placement</b>, local tier: against an up-facing Mechanical Saw or a Basin (never on its top), checked before
 * the block is placed: linked, near enough, the station loaded and there, a machine a selector serves, no other
 * selector on that machine (I8), and a station that takes a selector now. The placed block registers with the station
 * ({@code RecipeSelectorBlock#setPlacedBy}). Ender tier: <b>sneak-placed</b> on a factory's unpacking Packager (a plain
 * click is Create's: it unpacks or hands a box out), in the Packager's chunk, one per Packager, its machine link (if
 * any) within {@code selector.machineRange}.</li>
 * <li><b>Sneak-use into the air</b> clears the links.</li>
 * </ul>
 * The link clicks are answered in {@link #onItemUseFirst}, which NeoForge runs before the clicked block's own use, also
 * for a sneaking player; client and server answer alike from the block and the sneak state, so the client never places
 * a block on them. Any other click passes, so placement proceeds. Fake players (Deployers) never link.
 */
public class RecipeSelectorBlockItem extends BlockItem {
    public RecipeSelectorBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** What {@code stack} is linked to, or {@link SelectorLink#NONE}. */
    public static SelectorLink link(ItemStack stack) {
        SelectorLink link = stack.get(WareworksDataComponents.SELECTOR_LINK.get());
        return link == null ? SelectorLink.NONE : link;
    }

    private boolean isEnder() {
        return getBlock() instanceof RecipeSelectorBlock selector && selector.isEnder();
    }

    // --- link clicks -------------------------------------------------------------------------------------------------

    /** What a sneak-click with the item reaches. */
    private enum Target {
        STATION, INPUT, MACHINE, ENDER_SELECTOR, NONE
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || player instanceof FakePlayer)
            return InteractionResult.PASS;
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!player.isSecondaryUseActive()) {
            // A plain click on the Packager is Create's (it unpacks, or hands a box out): say how to place it.
            if (isEnder() && player instanceof ServerPlayer serverPlayer
                    && SlipVeto.isUnpackingPackager(level.getBlockEntity(pos)))
                tell(serverPlayer, WareworksLang.SELECTOR_SNEAK_TO_PLACE);
            return InteractionResult.PASS;
        }
        Target target = targetAt(level, pos);
        if (target == Target.NONE)
            return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer) {
            if (!level.mayInteract(serverPlayer, pos))
                tell(serverPlayer, WareworksLang.SELECTOR_NOT_ALLOWED);
            else
                switch (target) {
                    case STATION -> linkStation(serverPlayer, stack, level, pos);
                    case INPUT -> bindReturnInput(serverPlayer, stack, level, pos);
                    case MACHINE -> linkMachine(serverPlayer, stack, level, pos);
                    case ENDER_SELECTOR -> relink(serverPlayer, stack, level, pos);
                    case NONE -> {
                    }
                }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** What a sneak-click at {@code pos} reaches; the same answer on both sides. */
    private Target targetAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(WareworksBlocks.WAREHOUSE_PRODUCTION.get()))
            return Target.STATION;
        if (state.is(WareworksBlocks.WAREHOUSE_INPUT.get()))
            return Target.INPUT;
        if (!isEnder())
            return Target.NONE;
        if (state.getBlock() instanceof RecipeSelectorBlock selector && selector.isEnder())
            return Target.ENDER_SELECTOR;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SawBlockEntity || be instanceof BasinBlockEntity || be instanceof MechanicalMixerBlockEntity
                || be instanceof MechanicalPressBlockEntity)
            return Target.MACHINE;
        return Target.NONE;
    }

    /**
     * Server: links {@code stack} to the production station at {@code pos}. The Ender item also switches a station in
     * no selector mode to recipe slips (design §3.2).
     */
    private void linkStation(ServerPlayer player, ItemStack stack, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof WarehouseProductionBlockEntity station))
            return;
        SelectorLink link = link(stack).withStation(GlobalPos.of(level.dimension(), pos.immutable()));
        stack.set(WareworksDataComponents.SELECTOR_LINK.get(), link);
        if (!isEnder()) {
            tell(player, WareworksLang.SELECTOR_LINKED_STATION, label(pos));
            return;
        }
        if (station.selectorMode() == SelectorMode.LOCAL) {
            tell(player, WareworksLang.SELECTOR_REFUSED_SELECTOR_PRESENT,
                    label(station.linkedSelector().orElse(pos)));
            return;
        }
        if (link.kind().isEmpty() || link.machine().isEmpty()) {
            tell(player, station.selectorMode() == SelectorMode.SLIPS ? WareworksLang.SELECTOR_LINKED_STATION
                    : WareworksLang.SELECTOR_NEEDS_MACHINE_LINK, label(pos));
            return;
        }
        WarehouseProductionBlockEntity.SelectorModeChange change = station.setSelectorMode(SelectorMode.SLIPS,
                link.kind());
        switch (change) {
            case ACCEPTED -> tell(player, WareworksLang.SELECTOR_SLIPS_ON, label(pos));
            case UNCHANGED -> tell(player, WareworksLang.SELECTOR_LINKED_STATION, label(pos));
            case REFUSED_FEEDER -> tell(player, WareworksLang.SELECTOR_REFUSED_FEEDER);
            case REFUSED_OPEN_ORDERS -> tell(player, WareworksLang.SELECTOR_REFUSED_OPEN_ORDERS);
            case REFUSED_OTHER_MODE, REFUSED_SELECTOR_PRESENT -> tell(player,
                    WareworksLang.SELECTOR_REFUSED_SELECTOR_PRESENT, label(station.linkedSelector().orElse(pos)));
        }
    }

    /** Server: binds the Warehouse Input at {@code pos} as the return input of the station {@code stack} links to. */
    private static void bindReturnInput(ServerPlayer player, ItemStack stack, Level level, BlockPos pos) {
        Optional<GlobalPos> linked = link(stack).station();
        if (linked.isEmpty()) {
            tell(player, WareworksLang.SELECTOR_NOT_LINKED);
            return;
        }
        BlockPos stationPos = linked.get().pos();
        if (!linked.get().dimension().equals(level.dimension())) {
            tell(player, WareworksLang.SELECTOR_INPUT_NOT_IN_WAREHOUSE);
            return;
        }
        if (!level.isLoaded(stationPos)) {
            tell(player, WareworksLang.SELECTOR_STATION_NOT_LOADED, label(stationPos));
            return;
        }
        if (!(level.getBlockEntity(stationPos) instanceof WarehouseProductionBlockEntity station)
                || station.isRemoved()) {
            tell(player, WareworksLang.SELECTOR_STATION_MISSING, label(stationPos));
            return;
        }
        Optional<WarehouseControllerBlockEntity.ReturnInputBinding> binding = station.bindSelectorReturnInputAt(pos);
        String message = binding.map(found -> switch (found) {
            case BOUND -> WareworksLang.SELECTOR_BOUND_RETURN_INPUT;
            case REFUSED_OTHER_CONTROLLER -> WareworksLang.SELECTOR_REFUSED_OTHER_CONTROLLER;
            case REFUSED_INPUT_TAKEN -> WareworksLang.SELECTOR_REFUSED_INPUT_TAKEN;
            case REFUSED_ENGAGED -> WareworksLang.SELECTOR_REFUSED_ENGAGED;
            case REFUSED_NOT_HERE, REFUSED_NOT_AN_INPUT -> WareworksLang.SELECTOR_INPUT_NOT_IN_WAREHOUSE;
        }).orElse(WareworksLang.SELECTOR_INPUT_NOT_IN_WAREHOUSE);
        tell(player, message, label(stationPos));
    }

    /**
     * Server, Ender: links {@code stack} to the saw or basin at {@code pos}; a Mixer or Press stands for the Basin two
     * below it (design §4). A horizontal saw has no recipe filter and is refused.
     */
    private static void linkMachine(ServerPlayer player, ItemStack stack, Level level, BlockPos pos) {
        BlockPos machinePos = pos;
        if (level.getBlockEntity(pos) instanceof MechanicalMixerBlockEntity
                || level.getBlockEntity(pos) instanceof MechanicalPressBlockEntity)
            machinePos = pos.below(2);
        Optional<SelectorMachine> machine = level.isLoaded(machinePos) ? SelectorMachine.resolve(level, machinePos)
                : Optional.empty();
        if (machine.isEmpty()) {
            tell(player, SelectorMachine.refusalAt(level, pos) == SelectorMachine.Refusal.HORIZONTAL_SAW
                    ? WareworksLang.SELECTOR_HORIZONTAL_SAW : WareworksLang.SELECTOR_NO_MACHINE);
            return;
        }
        SelectorLink old = link(stack);
        stack.set(WareworksDataComponents.SELECTOR_LINK.get(), new SelectorLink(old.station(),
                Optional.of(GlobalPos.of(level.dimension(), machine.get().filterPos())),
                Optional.of(machine.get().kind())));
        tell(player, WareworksLang.SELECTOR_LINKED_MACHINE, machine.get().name(), label(machine.get().filterPos()));
    }

    /** Server, Ender: gives the placed Ender selector at {@code pos} the item's machine link (design §3.2). */
    private static void relink(ServerPlayer player, ItemStack stack, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof RecipeSelectorBlockEntity selector) || !selector.isEnder())
            return;
        Optional<GlobalPos> machine = link(stack).machine();
        if (machine.isEmpty() || !machine.get().dimension().equals(level.dimension())) {
            tell(player, WareworksLang.SELECTOR_NEEDS_MACHINE_LINK);
            return;
        }
        int range = WareworksConfig.selectorMachineRange();
        BlockPos machinePos = machine.get().pos();
        if (machinePos.getCenter().distanceTo(pos.getCenter()) > range) {
            tell(player, WareworksLang.SELECTOR_MACHINE_TOO_FAR, range);
            return;
        }
        selector.setMachineLink(Optional.of(machinePos));
        Component name = level.isLoaded(machinePos) ? level.getBlockState(machinePos).getBlock().getName()
                : kindName(link(stack).kind());
        tell(player, WareworksLang.SELECTOR_RELINKED, name, label(machinePos));
    }

    /**
     * Sneak-use into the air clears the links. Only into the air: a click on a block that did not take it reaches here
     * too, and must not wipe a link the player is about to use.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof FakePlayer || !player.isSecondaryUseActive() || link(stack).isEmpty()
                || player.pick(player.blockInteractionRange(), 1.0F, false).getType() != HitResult.Type.MISS)
            return super.use(level, player, hand);
        if (player instanceof ServerPlayer serverPlayer) {
            stack.remove(WareworksDataComponents.SELECTOR_LINK.get());
            tell(serverPlayer, WareworksLang.SELECTOR_CLEARED);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    // --- placement ---------------------------------------------------------------------------------------------------

    /**
     * Checks the placement rules before the block is placed (design §3.3, §3.4); a refusal keeps the item in the hand,
     * says why and plays Create's deny sound. The client only refuses what it can see for certain (an unlinked local
     * item, an Ender item not aimed at a Packager); the server decides the rest.
     */
    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            if (isEnder() ? !SlipVeto.isUnpackingPackager(level.getBlockEntity(machineOf(context)))
                    : link(context.getItemInHand()).station().isEmpty())
                return InteractionResult.FAIL;
            return super.place(context);
        }
        Refusal refusal = isEnder() ? enderRefusalOf(context) : refusalOf(context);
        if (refusal != null) {
            if (context.getPlayer() instanceof ServerPlayer player) {
                player.displayClientMessage(WareworksLang.translateDirect(refusal.key(), refusal.args()), true);
                AllSoundEvents.DENY.playOnServer(level, context.getClickedPos());
            }
            return InteractionResult.FAIL;
        }
        InteractionResult result = super.place(context);
        if (!isEnder() && result.consumesAction() && context.getPlayer() instanceof ServerPlayer player
                && level.getBlockEntity(context.getClickedPos()) instanceof RecipeSelectorBlockEntity selector
                && selector.linkedStation().isPresent())
            tell(player, WareworksLang.SELECTOR_PLACED, label(selector.linkedStation().get()));
        return result;
    }

    /** The block the selector would face: the clicked one. */
    private static BlockPos machineOf(BlockPlaceContext context) {
        return context.getClickedPos().relative(context.getClickedFace().getOpposite());
    }

    /** One refusal: the lang key and its arguments. */
    private record Refusal(String key, Object... args) {
    }

    /** Why the Ender selector must not be placed here (design §3.4), or {@code null}. */
    @Nullable
    private static Refusal enderRefusalOf(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockPos packager = machineOf(context);
        if (!level.isLoaded(packager) || !SlipVeto.isUnpackingPackager(level.getBlockEntity(packager)))
            return new Refusal(WareworksLang.SELECTOR_NEEDS_PACKAGER);
        // The capability provider only looks at loaded neighbours of the Packager: the selector must be loaded with it.
        if (!new ChunkPos(pos).equals(new ChunkPos(packager)))
            return new Refusal(WareworksLang.SELECTOR_REFUSED_OTHER_CHUNK);
        if (SlipVeto.selectorOn(level, packager).isPresent())
            return new Refusal(WareworksLang.SELECTOR_REFUSED_PACKAGER_TAKEN);
        Optional<GlobalPos> machine = link(context.getItemInHand()).machine();
        int range = WareworksConfig.selectorMachineRange();
        if (machine.isPresent() && (!machine.get().dimension().equals(level.dimension())
                || machine.get().pos().getCenter().distanceTo(pos.getCenter()) > range))
            return new Refusal(WareworksLang.SELECTOR_MACHINE_TOO_FAR, range);
        return null;
    }

    /** Why the local selector must not be placed here, or {@code null}; reads only the station and the machine. */
    @Nullable
    private static Refusal refusalOf(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction facing = context.getClickedFace().getOpposite();
        Optional<GlobalPos> linked = link(context.getItemInHand()).station();
        if (linked.isEmpty())
            return new Refusal(WareworksLang.SELECTOR_NOT_LINKED);
        BlockPos stationPos = linked.get().pos();
        int range = WareworksConfig.selectorLocalRange();
        if (!linked.get().dimension().equals(level.dimension())
                || stationPos.getCenter().distanceTo(pos.getCenter()) > range)
            return new Refusal(WareworksLang.SELECTOR_TOO_FAR, range);
        if (!level.isLoaded(stationPos))
            return new Refusal(WareworksLang.SELECTOR_STATION_NOT_LOADED, label(stationPos));
        if (!(level.getBlockEntity(stationPos) instanceof WarehouseProductionBlockEntity station)
                || station.isRemoved())
            return new Refusal(WareworksLang.SELECTOR_STATION_MISSING, label(stationPos));
        BlockPos machinePos = pos.relative(facing);
        Optional<SelectorMachine> machine = level.isLoaded(machinePos) ? SelectorMachine.resolve(level, machinePos)
                : Optional.empty();
        if (machine.isEmpty()) {
            if (!level.isLoaded(machinePos))
                return new Refusal(WareworksLang.SELECTOR_NO_MACHINE);
            return switch (SelectorMachine.refusalAt(level, machinePos)) {
                case HORIZONTAL_SAW -> new Refusal(WareworksLang.SELECTOR_HORIZONTAL_SAW);
                case OPERATOR -> new Refusal(WareworksLang.SELECTOR_PLACE_ON_BASIN,
                        level.getBlockState(machinePos).getBlock().getName());
                case NO_MACHINE -> new Refusal(WareworksLang.SELECTOR_NO_MACHINE);
            };
        }
        // Never on top: on a saw the top is the blade, on a basin it is where the items go in.
        if (facing == Direction.DOWN)
            return new Refusal(WareworksLang.SELECTOR_NO_MACHINE);
        if (machine.get().otherWriter(pos).isPresent())
            return new Refusal(WareworksLang.SELECTOR_REFUSED_MACHINE_TAKEN);
        Optional<WarehouseProductionBlockEntity.SelectorModeChange> refused = station.selectorRegistrationRefusal(pos);
        if (refused.isPresent())
            return switch (refused.get()) {
                case REFUSED_FEEDER -> new Refusal(WareworksLang.SELECTOR_REFUSED_FEEDER);
                case REFUSED_OTHER_MODE -> new Refusal(WareworksLang.SELECTOR_REFUSED_SLIPS_ACTIVE);
                case REFUSED_OPEN_ORDERS -> new Refusal(WareworksLang.SELECTOR_REFUSED_OPEN_ORDERS);
                case REFUSED_SELECTOR_PRESENT -> new Refusal(WareworksLang.SELECTOR_REFUSED_SELECTOR_PRESENT,
                        label(station.linkedSelector().orElse(stationPos)));
                case ACCEPTED, UNCHANGED -> null;
            };
        return null;
    }

    // --- tooltip and helpers -----------------------------------------------------------------------------------------

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        SelectorLink link = link(stack);
        if (link.station().isEmpty())
            tooltip.add(WareworksLang.translateDirect(WareworksLang.SELECTOR_TOOLTIP_UNLINKED)
                    .withStyle(ChatFormatting.GRAY));
        else
            tooltip.add(WareworksLang.translateDirect(WareworksLang.SELECTOR_TOOLTIP_STATION,
                    label(link.station().get().pos())).withStyle(ChatFormatting.GRAY));
        if (!isEnder())
            return;
        if (link.machine().isEmpty())
            tooltip.add(WareworksLang.translateDirect(WareworksLang.SELECTOR_TOOLTIP_NO_MACHINE)
                    .withStyle(ChatFormatting.GRAY));
        else
            tooltip.add(WareworksLang.translateDirect(WareworksLang.SELECTOR_TOOLTIP_MACHINE, kindName(link.kind()),
                    label(link.machine().get().pos())).withStyle(ChatFormatting.GRAY));
    }

    /** The machine's block name by its kind: a Mechanical Saw, or a Basin. */
    private static Component kindName(Optional<SelectorMachine.Kind> kind) {
        return kind.filter(SelectorMachine.Kind::isBasin).isPresent() ? AllBlocks.BASIN.get().getName()
                : AllBlocks.MECHANICAL_SAW.get().getName();
    }

    /** A block position as the messages show it: {@code x, y, z}. */
    static Component label(BlockPos pos) {
        return Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    private static void tell(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(WareworksLang.translateDirect(key, args), true);
    }
}
