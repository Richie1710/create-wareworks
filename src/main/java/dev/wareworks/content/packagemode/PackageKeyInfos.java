package dev.wareworks.content.packagemode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageItem;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.inventory.KeyCount;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * What a terminal reads from a package key to fold it into its address row (issue #40, design §6.1): the address, what
 * <b>one</b> such box holds and whether it belongs to a network order — and nothing for a key that is no package.
 * <p>
 * An item key never changes, so the answer for a key never does either; it is remembered, because a package-mode
 * terminal asks it of every stored box on every refresh of every open screen, and reading a box means copying its
 * stack and its contents. The memo is bounded: it is cleared whenever it holds more than twice the keys of the last
 * pass (and at least {@value #MINIMUM_BOUND}), so keys that left the index are forgotten and the map never outgrows
 * the warehouse. Server thread only; one instance per terminal, transient.
 */
public final class PackageKeyInfos {
    private static final int MINIMUM_BOUND = 256;

    private final Map<ItemKey, Optional<Info>> memo = new HashMap<>();
    private int bound = MINIMUM_BOUND;

    /**
     * One package key as a terminal reads it.
     *
     * @param address      the address, {@code ""} for a box without one
     * @param perBox       what one box holds, by content key, in slot order of first appearance
     * @param networkOrder whether the box carries Create's order data or order context
     */
    public record Info(String address, List<KeyCount<ItemKey>> perBox, boolean networkOrder) {
        public Info {
            Objects.requireNonNull(address, "address");
            perBox = List.copyOf(perBox);
        }
    }

    /** Sizes the memo for a pass over {@code keys} distinct keys; never forgets anything by itself. */
    public void expect(int keys) {
        bound = Math.max(bound, Math.max(MINIMUM_BOUND, 2 * Math.max(0, keys)));
    }

    /** The package facts of {@code key}, or empty for a key that is no package (or a terminal's group key). */
    public Optional<Info> of(ItemKey key) {
        if (key == null || !PackageModeGate.isPackage(key))
            return Optional.empty();
        Optional<Info> known = memo.get(key);
        if (known != null)
            return known;
        if (memo.size() >= bound)
            memo.clear();
        Optional<Info> read = read(key);
        memo.put(key, read);
        return read;
    }

    /** Reads {@code key}'s box once: never throws, a box that cannot be read is no package to fold. */
    static Optional<Info> read(ItemKey key) {
        if (PackageGroupKey.isGroup(key))
            return Optional.empty();
        ItemStack box = key.toStack();
        String address = PackageItem.getAddress(box);
        Map<ItemKey, Long> contents = new LinkedHashMap<>();
        ItemContainerContents held = box.getOrDefault(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.EMPTY);
        for (ItemStack stack : held.nonEmptyItems())
            contents.merge(ItemKey.of(stack), (long) stack.getCount(), Long::sum);
        List<KeyCount<ItemKey>> perBox = new ArrayList<>(contents.size());
        contents.forEach((content, count) -> perBox.add(new KeyCount<>(content, count)));
        // Read through Create's own helpers only: Wareworks never names the order components (ADR-065 D3).
        boolean networkOrder = PackageItem.hasOrderData(box) || PackageItem.getOrderContext(box) != null;
        return Optional.of(new Info(address == null ? "" : address, perBox, networkOrder));
    }
}
