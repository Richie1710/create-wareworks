package dev.wareworks.content.remote;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksDataComponents;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The Warehouse Remote (issue #44, ADR-077): a held item that opens a linked warehouse terminal's screen from afar.
 * <ul>
 * <li><b>Sneak + right-click a terminal</b>: the remote is linked to it ({@link RemoteAccess#link}). Linking again
 * just links it to the new terminal.</li>
 * <li><b>Right-click</b> into the air, or on a block that does not take the click: the linked terminal's screen opens
 * ({@link RemoteAccess#open}), within the server's range.</li>
 * <li><b>Sneak + right-click</b> into the air: the link is cleared. Only into the air: a sneaking click on a block or
 * an entity that does not take it reaches {@link #use} as well, and wiping the link there would punish a link click
 * that missed the terminal, or one the game did not let through (adventure mode), and would keep the other hand from
 * placing its block.</li>
 * </ul>
 * The link click is answered in {@link #onItemUseFirst}, which NeoForge runs <b>before</b> the clicked block's own use
 * and also for a sneaking player. Client and server answer it alike from what both know — the clicked block's state
 * and the sneak state — so the client never goes on to {@link #use} for a click the server took as a link. Anything
 * else passes: a plain right-click on a terminal with the remote in hand opens its screen the ordinary way, in person.
 * <p>
 * <b>The remote moves no item.</b> Everything asked for from afar is delivered by the crane into the terminal's own
 * buffer or to a package door, exactly like a request made in person; the screen opened from afar cannot take items
 * out of the buffer or craft into the player's hands ({@code WarehouseTerminalMenu#isRemote}).
 */
public class WarehouseRemoteItem extends Item {
    public WarehouseRemoteItem(Properties properties) {
        super(properties);
    }

    /** The terminal {@code stack} is linked to, or {@code null} for an unlinked remote and anything else. */
    @Nullable
    public static RemoteLink link(ItemStack stack) {
        return stack.getItem() instanceof WarehouseRemoteItem ? stack.get(WareworksDataComponents.REMOTE_LINK.get())
                : null;
    }

    /** Server: links {@code stack} to {@code link}, replacing any earlier link. */
    static void setLink(ItemStack stack, RemoteLink link) {
        stack.set(WareworksDataComponents.REMOTE_LINK.get(), link);
    }

    /** Whether the click of {@code context} is a link: a sneaking player on a warehouse terminal. */
    private static boolean isLinkClick(UseOnContext context) {
        Player player = context.getPlayer();
        return player != null && player.isSecondaryUseActive()
                && context.getLevel().getBlockState(context.getClickedPos()).is(WareworksBlocks.WAREHOUSE_TERMINAL.get());
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (!isLinkClick(context) || context.getPlayer() instanceof FakePlayer)
            return InteractionResult.PASS;
        Level level = context.getLevel();
        if (context.getPlayer() instanceof ServerPlayer player)
            RemoteAccess.link(player, stack, level, context.getClickedPos());
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Right-click with the remote: sneaking into the air clears the link, otherwise the linked terminal's screen opens.
     * A sneaking click that points at a block or an entity passes, so the other hand is used as usual. The client
     * answers success for a linked remote it unlinks or opens with, so the other hand is not used as well; the server
     * decides everything.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof FakePlayer)
            return InteractionResultHolder.pass(stack);
        boolean linked = link(stack) != null;
        if (player.isSecondaryUseActive()) {
            if (!linked || !looksIntoTheAir(player))
                return InteractionResultHolder.pass(stack);
            if (player instanceof ServerPlayer serverPlayer) {
                stack.remove(WareworksDataComponents.REMOTE_LINK.get());
                serverPlayer.displayClientMessage(WareworksLang.translateDirect(WareworksLang.REMOTE_UNLINKED), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        if (player instanceof ServerPlayer serverPlayer)
            RemoteAccess.open(serverPlayer, hand, stack);
        return linked ? InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
                : InteractionResultHolder.pass(stack);
    }

    /**
     * Whether {@code player} points into the air: no block within their block interaction range and no entity within
     * their entity interaction range along their view, picked as the client's crosshair picks them
     * ({@code GameRenderer#pick}). Client and server answer it alike from the same position and view.
     */
    private static boolean looksIntoTheAir(Player player) {
        if (player.pick(player.blockInteractionRange(), 1.0F, false).getType() != HitResult.Type.MISS)
            return false;
        double reach = player.entityInteractionRange();
        Vec3 eyes = player.getEyePosition();
        Vec3 view = player.getViewVector(1.0F).scale(reach);
        AABB swept = player.getBoundingBox().expandTowards(view).inflate(1.0);
        return ProjectileUtil.getEntityHitResult(player, eyes, eyes.add(view), swept,
                entity -> !entity.isSpectator() && entity.isPickable(), reach * reach) == null;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        RemoteLink link = link(stack);
        if (link == null) {
            tooltip.add(WareworksLang.translateDirect(WareworksLang.REMOTE_TOOLTIP_UNLINKED)
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        BlockPos pos = link.terminal().pos();
        if (link.terminal().dimension().equals(Level.OVERWORLD))
            tooltip.add(WareworksLang.translateDirect(WareworksLang.REMOTE_TOOLTIP_LINKED, pos.getX(), pos.getY(),
                    pos.getZ()).withStyle(ChatFormatting.GRAY));
        else
            tooltip.add(WareworksLang.translateDirect(WareworksLang.REMOTE_TOOLTIP_LINKED_IN, pos.getX(), pos.getY(),
                    pos.getZ(), link.terminal().dimension().location().toString()).withStyle(ChatFormatting.GRAY));
    }
}
