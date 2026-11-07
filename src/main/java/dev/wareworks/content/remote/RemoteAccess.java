package dev.wareworks.content.remote;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.requester.NoTicket;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.core.remote.RemoteLimits;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
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
 * loaded: a remote never loads one, not even when a Deployer fires it every cycle. The terminal itself is then read
 * through {@link NoTicket}, which adds no chunk ticket either (issue #45).
 * <p>
 * <b>From a block</b> ({@link #checkFromBlock}, issue #45, ADR-080) is the same rule for a block that holds a remote, a
 * Schematicannon Requester: measured from the block's centre, asked before every order, and with the player check only
 * for an owner who is online. It answers what it found rather than telling anybody.
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
        RemoteCheck found = terminalNoTicket(level, pos, link);
        if (found instanceof RemoteCheck.Refused refused) {
            tell(player, refused.langKey(), refused.args());
            return false;
        }
        WarehouseTerminalBlockEntity terminal = ((RemoteCheck.Reached) found).terminal();
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
     * What {@link #checkFromBlock} found: the linked terminal, reached, or the refusal.
     */
    public sealed interface RemoteCheck {
        /**
         * The linked terminal stands there, its chunk is loaded and ticking, and the asker may use it.
         *
         * @param level    the terminal's level
         * @param terminal the terminal, read without a chunk ticket
         */
        record Reached(ServerLevel level, WarehouseTerminalBlockEntity terminal) implements RemoteCheck {
            public Reached {
                Objects.requireNonNull(level, "level");
                Objects.requireNonNull(terminal, "terminal");
            }
        }

        /**
         * Why the terminal was not reached: one of the remote's own refusals.
         *
         * @param langKey the {@code remote.*} key relative to {@code wareworks.} ({@link WareworksLang})
         * @param args    its arguments
         */
        record Refused(String langKey, Object... args) implements RemoteCheck {
            public Refused {
                Objects.requireNonNull(langKey, "langKey");
                args = args == null ? new Object[0] : args.clone();
            }

            /** The refusal as a line for the action bar or the goggles. */
            public MutableComponent message() {
                return WareworksLang.translateDirect(langKey, args);
            }
        }
    }

    /**
     * Server: the remote's rules measured from a block that holds a remote ({@code fromPos} in {@code from}) rather
     * than from a player's eyes (issue #45, ADR-080): what a Schematicannon Requester asks at insertion and before every
     * order. Never loads a chunk and never adds a ticket ({@link NoTicket}), so a block asking once a second leaves the
     * terminal's chunk free to unload.
     * <p>
     * Checked in the order {@link #open} checks, each its own refusal: a link at all ({@code remote.not_linked}); the
     * dimension ({@code remote.other_dimension} unless {@code remote.crossDimension}); that the dimension still exists
     * ({@code remote.gone}); within one dimension the range from the centre of {@code fromPos} to the centre of the
     * terminal ({@code remote.out_of_range} with the distance in whole blocks and the range); the terminal's chunk
     * loaded and ticking ({@code remote.not_loaded}); a terminal with the linked identity there ({@code remote.gone});
     * and, when there is an {@code actor}, that they may use a block there ({@code remote.not_allowed},
     * {@code Level#mayInteract}: spawn protection and the world border). Fake players are the caller's to refuse.
     *
     * @param actor the player to check {@code mayInteract} for, or {@code null} to skip that check (an owner who is
     *              offline: the insertion check stands)
     */
    public static RemoteCheck checkFromBlock(Level from, BlockPos fromPos, @Nullable RemoteLink link,
            @Nullable ServerPlayer actor) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(fromPos, "fromPos");
        if (link == null)
            return new RemoteCheck.Refused(WareworksLang.REMOTE_NOT_LINKED);
        ResourceKey<Level> dimension = link.terminal().dimension();
        BlockPos pos = link.terminal().pos();
        boolean sameDimension = from.dimension().equals(dimension);
        if (!sameDimension && !WareworksConfig.remoteCrossDimension())
            return new RemoteCheck.Refused(WareworksLang.REMOTE_OTHER_DIMENSION);
        MinecraftServer server = from.getServer();
        ServerLevel level = server == null ? null : server.getLevel(dimension);
        if (level == null)
            return new RemoteCheck.Refused(WareworksLang.REMOTE_GONE);
        if (sameDimension) {
            double distance = Vec3.atCenterOf(fromPos).distanceTo(Vec3.atCenterOf(pos));
            int range = WareworksConfig.remoteRange();
            if (!RemoteLimits.withinRange(distance, range))
                return new RemoteCheck.Refused(WareworksLang.REMOTE_OUT_OF_RANGE, RemoteLimits.shownBlocks(distance),
                        range);
        }
        RemoteCheck found = terminalNoTicket(level, pos, link);
        if (found instanceof RemoteCheck.Refused)
            return found;
        if (actor != null && !level.mayInteract(actor, pos))
            return new RemoteCheck.Refused(WareworksLang.REMOTE_NOT_ALLOWED);
        return found;
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

    /**
     * The terminal {@code link} names, read without loading or ticketing its chunk: the chunk at {@code pos} must be
     * loaded and ticking ({@code remote.not_loaded}), and a non-removed terminal with the linked identity must stand
     * there ({@code remote.gone}). The block entity is taken through {@link NoTicket}, never through
     * {@code Level#getBlockEntity}, which renews a chunk ticket on the server.
     */
    private static RemoteCheck terminalNoTicket(ServerLevel level, BlockPos pos, RemoteLink link) {
        // Never read the block entity of a chunk that is not loaded: Level#getBlockEntity would load it.
        if (!level.isLoaded(pos) || !level.shouldTickBlocksAt(pos))
            return new RemoteCheck.Refused(WareworksLang.REMOTE_NOT_LOADED);
        WarehouseTerminalBlockEntity terminal = NoTicket.blockEntity(level, pos, WarehouseTerminalBlockEntity.class);
        if (terminal == null || !terminal.terminalId().equals(link.terminalId()))
            return new RemoteCheck.Refused(WareworksLang.REMOTE_GONE);
        return new RemoteCheck.Reached(level, terminal);
    }

    /** Tells {@code player} why something did not happen, or that it did, on the action bar. */
    private static void tell(Player player, String relativeKey, Object... args) {
        player.displayClientMessage(WareworksLang.translateDirect(relativeKey, args), true);
    }
}
