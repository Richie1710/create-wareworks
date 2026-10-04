package dev.wareworks.content.station;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.packager.unpacking.UnpackingHandler;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Create's unpacking handler for the warehouse input ({@code docs/warehouse-system.md} §3.2.5, M26, issue #18):
 * <b>it adds no behaviour at all</b> and exists only so a refused package can be read somewhere.
 * <p>
 * <b>What a player builds.</b> A Create Packager whose <b>back</b> touches a warehouse input unpacks into it, with no
 * Wareworks code involved: {@code PackagerItemHandler#insertItem} runs {@code PackagerBlockEntity#unwrapBox}, which
 * resolves {@code UnpackingHandler.REGISTRY.get(targetState)} for the block behind it
 * ({@code PackagerBlockEntity.java:377-383}) and falls back to {@link UnpackingHandler#DEFAULT}. Registering anything
 * here therefore <b>replaces</b> the whole way in, which is why this class delegates verbatim and returns the
 * delegate's exact result. Deleting the registration restores Create's behaviour byte for byte.
 * <p>
 * <b>Why it is worth a dependency on an {@code @Experimental} API.</b> Without it the refusal is completely invisible:
 * {@code unwrapBox} returns false, the Packager does not animate, the package sits in the funnel, and no block in the
 * game says anything. The two numbers recorded here are the only diagnosis of the all-or-nothing cliff — Create
 * consumes a package whole or not at all ({@code DefaultUnpackingHandler}'s simulate pass returns false as soon as one
 * stack is left over), so an input holding one stray stack refuses a nine-type package until it has room for all nine
 * of its stacks at once — an empty slot each, or a slot already holding that very item with space left, which the
 * delegate's simulate pass merges into.
 * <p>
 * <b>Which pass records what.</b> {@code PackagerItemHandler#insertItem} always runs a simulate pass first and only
 * then the real one, so:
 * <ul>
 * <li>a <b>failed simulate</b> is the refusal, and what is recorded is how many stacks the package <b>brought</b>,
 *     counted off the list <b>before</b> the delegate touches it. The delegate mutates the list it is passed
 *     ({@code UnpackingHandler#unpack} documents that it may, and {@code DefaultUnpackingHandler} does
 *     {@code items.set(...)} as it places each stack), so afterwards the list only says what was left over — a number
 *     that cannot be read beside the free-slot count, because the placement it is left over from was greedy. The list
 *     is still not copied: {@code unwrapBox} builds a fresh one per call, so the mutation is discarded anyway;</li>
 * <li>a <b>successful real pass</b> is one package opened, and it clears the refusal, because the cliff a player was
 *     looking at is over.</li>
 * </ul>
 * A funnel retries every tick while it is refused, so nothing here saves, syncs or marks the block changed: the
 * numbers are plain fields that the throttled goggle path reads when somebody looks
 * ({@code WarehouseStationBlockEntity#onGoggleObserved}).
 * <p>
 * Registered exactly once, in {@code WareworksUnpackingHandlers}.
 */
public final class WarehouseInputUnpackingHandler implements UnpackingHandler {
    /** The single instance; Create's own handlers are singletons too ({@code AllUnpackingHandlers}). */
    public static final WarehouseInputUnpackingHandler INSTANCE = new WarehouseInputUnpackingHandler();

    private WarehouseInputUnpackingHandler() {
    }

    @Override
    public boolean unpack(Level level, BlockPos pos, BlockState state, Direction side, List<ItemStack> items,
            @Nullable PackageOrderWithCrafts orderContext, boolean simulate) {
        // Counted before the delegate, because the delegate empties the entries it finds room for: afterwards the
        // list no longer says how big the package was.
        int packageStacks = simulate ? stacks(items) : 0;
        boolean unpacked = DEFAULT.unpack(level, pos, state, side, items, orderContext, simulate);
        // The diagnosis is server state read by a goggle tooltip. A client-side unpack would only desync the numbers.
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof WarehouseInputBlockEntity input))
            return unpacked;
        if (simulate) {
            if (!unpacked)
                input.onPackageRefused(packageStacks);
        } else if (unpacked) {
            input.onPackageOpened();
        }
        return unpacked;
    }

    /** Stacks the package brought, i.e. the room it needs all at once. */
    private static int stacks(List<ItemStack> items) {
        int count = 0;
        for (ItemStack stack : items)
            if (!stack.isEmpty())
                count++;
        return count;
    }
}
