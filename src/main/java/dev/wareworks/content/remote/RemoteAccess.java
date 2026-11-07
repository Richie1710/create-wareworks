package dev.wareworks.content.remote;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.core.remote.RemoteLimits;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The server's rules for a Warehouse Remote (issue #44, ADR-077): linking it to a terminal, opening the linked
 * terminal's screen from afar, and whether a screen opened that way may stay open.
 * <p>
 * <b>Opening</b> ({@link #open}) checks, in this order, and answers the first refusal on the action bar and nothing
 * else: a link at all; the dimension ({@code remote.crossDimension}); that the dimension still exists; the range
 * within one dimension ({@code remote.range}, {@link RemoteLimits}); that the terminal's chunk is loaded and ticking;
 * that a terminal with the linked identity still stands there; that the player may use a block there at all
 * ({@code Level#mayInteract}: spawn protection and the world border, which every block click passes too). The chunk is
 * checked <b>before</b> the block entity is read, because {@code Level#getBlockEntity} loads a chunk that is not
 * loaded: a remote never loads one, not even when a Deployer fires it every cycle.
 * <p>
 * <b>Staying open</b> ({@link #stillHolds}) is the same rule minus the identity of the block entity, which the terminal
 * itself answers by being removed: the terminal's chunk must still be loaded and ticking, the hand the remote was used
 * from must still hold a remote linked to exactly that terminal, the player must still be within range (or the server
 * allow the other dimension), and still be allowed to use the block. It looks at one hand, never at the whole
 * inventory, and never reads a block entity, so asking it twice a tick — vanilla asks every open menu that often —
 * costs next to nothing and keeps no chunk loaded: {@code Level#getBlockEntity} on the server renews a chunk ticket
 * on every call, and a screen held open from afar would otherwise hold the terminal's chunk for as long as it is open.
 * <p>
 * Fake players never link and never open: a remote is a player's own hand at a screen.
 */
public final class RemoteAccess {
    /** Ticks a remote rests after it opened a screen, so a client cannot have the server rebuild the menu every tick. */
    public static final int OPEN_COOLDOWN_TICKS = 10;

    private RemoteAccess() {
    }

    /**
     * Server: links {@code remote} to the terminal at {@code pos}, or tells {@code player} why not. The caller has
     * already seen a terminal's block state there; a fake player is ignored without a word.
     *
     * @return whether the remote was linked
     */
    public static boolean link(ServerPlayer player, ItemStack remote, Level level, BlockPos pos) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        if (player instanceof FakePlayer || !(remote.getItem() instanceof WarehouseRemoteItem))
            return false;
        if (!level.mayInteract(player, pos)) {
            tell(player, WareworksLang.REMOTE_NOT_ALLOWED);
            return false;
        }
        if (!(level.getBlockEntity(pos) instanceof WarehouseTerminalBlockEntity terminal) || terminal.isRemoved())
            return false;
        WarehouseRemoteItem.setLink(remote,
                new RemoteLink(GlobalPos.of(level.dimension(), pos.immutable()), terminal.terminalId()));
        tell(player, WareworksLang.REMOTE_LINKED, pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    /**
     * Server: opens the screen of the terminal {@code remote} is linked to for {@code player}, held in {@code hand}, or
     * tells the player why not (see the class comment for the order). An opened screen puts the remote on a cooldown
     * of {@value #OPEN_COOLDOWN_TICKS} ticks.
     *
     * @return whether a screen was opened
     */
    public static boolean open(ServerPlayer player, InteractionHand hand, ItemStack remote) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(remote, "remote");
        if (player instanceof FakePlayer)
            return false;
        RemoteLink link = WarehouseRemoteItem.link(remote);
        if (link == null) {
            tell(player, WareworksLang.REMOTE_NOT_LINKED);
            return false;
        }
        ResourceKey<Level> dimension = link.terminal().dimension();
        BlockPos pos = link.terminal().pos();
        boolean sameDimension = player.level().dimension().equals(dimension);
        if (!sameDimension && !WareworksConfig.remoteCrossDimension()) {
            tell(player, WareworksLang.REMOTE_OTHER_DIMENSION);
            return false;
        }
        ServerLevel level = player.server.getLevel(dimension);
        if (level == null) {
            tell(player, WareworksLang.REMOTE_GONE);
            return false;
        }
        if (sameDimension) {
            double distance = distance(player, pos);
            int range = WareworksConfig.remoteRange();
            if (!RemoteLimits.withinRange(distance, range)) {
                tell(player, WareworksLang.REMOTE_OUT_OF_RANGE, RemoteLimits.shownBlocks(distance), range);
                return false;
            }
        }
        // Never read the block entity of a chunk that is not loaded: Level#getBlockEntity would load it.
        if (!level.isLoaded(pos) || !level.shouldTickBlocksAt(pos)) {
            tell(player, WareworksLang.REMOTE_NOT_LOADED);
            return false;
        }
        WarehouseTerminalBlockEntity terminal = terminalAt(level, pos);
        if (terminal == null || !terminal.terminalId().equals(link.terminalId())) {
            tell(player, WareworksLang.REMOTE_GONE);
            return false;
        }
        if (!level.mayInteract(player, pos)) {
            tell(player, WareworksLang.REMOTE_NOT_ALLOWED);
            return false;
        }
        if (!terminal.openRemoteScreen(player, hand))
            return false;
        player.getCooldowns().addCooldown(remote.getItem(), OPEN_COOLDOWN_TICKS);
        return true;
    }

    /**
     * Whether a screen of {@code terminal} that {@code player} opened from afar under {@code binding} may stay open: the
     * terminal's chunk is still loaded and ticking, the bound hand still holds a remote linked to this very terminal,
     * the player is still within reach of it and may still use the block. Reads no block entity (see the class
     * comment). Never throws; O(1).
     */
    public static boolean stillHolds(Player player, WarehouseTerminalBlockEntity terminal, RemoteBinding binding) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(binding, "binding");
        Level level = terminal.getLevel();
        if (level == null || level.isClientSide || terminal.isRemoved())
            return false;
        BlockPos pos = terminal.getBlockPos();
        // A terminal whose chunk stopped ticking closes the screen before it unloads, and nothing here holds it.
        if (!level.isLoaded(pos) || !level.shouldTickBlocksAt(pos))
            return false;
        if (!binding.terminalId().equals(terminal.terminalId()))
            return false;
        RemoteLink link = WarehouseRemoteItem.link(player.getItemInHand(binding.hand()));
        if (link == null || !link.terminalId().equals(binding.terminalId())
                || !link.terminal().equals(GlobalPos.of(level.dimension(), pos)))
            return false;
        return reaches(player, level, pos) && level.mayInteract(player, pos);
    }

    /**
     * Whether {@code player} reaches a terminal at {@code pos} in {@code level} with a remote: within
     * {@code remote.range} in the same dimension, or in another dimension where the server allows that.
     */
    public static boolean reaches(Player player, Level level, BlockPos pos) {
        if (!player.level().dimension().equals(level.dimension()))
            return WareworksConfig.remoteCrossDimension();
        return RemoteLimits.withinRange(distance(player, pos), WareworksConfig.remoteRange());
    }

    /** From the player's eyes to the centre of the block at {@code pos}, in blocks; same dimension only. */
    public static double distance(Player player, BlockPos pos) {
        return player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
    }

    /** The terminal standing at {@code pos} of a level whose chunk there is loaded, or {@code null}. */
    @Nullable
    private static WarehouseTerminalBlockEntity terminalAt(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof WarehouseTerminalBlockEntity terminal && !terminal.isRemoved()
                ? terminal : null;
    }

    /** Tells {@code player} why something did not happen, or that it did, on the action bar. */
    private static void tell(Player player, String relativeKey, Object... args) {
        player.displayClientMessage(WareworksLang.translateDirect(relativeKey, args), true);
    }
}
