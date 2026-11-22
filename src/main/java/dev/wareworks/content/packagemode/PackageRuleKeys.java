package dev.wareworks.content.packagemode;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.filter.PackageFilterItem;

import dev.wareworks.content.item.ItemKey;

/**
 * What package mode's stock rules read from items (issue #40, slice S5, design §7): the address of a keeper row's
 * Package Filter ghost, the address of a stored box, and Create's own address match.
 * <p>
 * Everything goes through Create's helpers: the filter and the box keep their address in the same
 * {@code create:package_address} component ({@code PackageItem#getAddress}), and two addresses match by
 * {@code PackageItem#matchAddress}, the test every Frogport, chain conveyor and train station uses, wildcards included.
 */
public final class PackageRuleKeys {
    private PackageRuleKeys() {
    }

    /** Whether {@code key} is a Create Package Filter (whatever its address). */
    public static boolean isPackageFilter(ItemKey key) {
        return key != null && key.getItem() instanceof PackageFilterItem;
    }

    /** The address a Package Filter carries, empty for any other item and for a filter with a blank address. */
    public static Optional<String> filterAddress(ItemKey key) {
        if (!isPackageFilter(key))
            return Optional.empty();
        String address = PackageItem.getAddress(key.toStack());
        return address == null || address.isBlank() ? Optional.empty() : Optional.of(address);
    }

    /** Whether {@code key} is a Package Filter with a blank address, which a package rule cannot use. */
    public static boolean isBlankPackageFilter(ItemKey key) {
        return isPackageFilter(key) && filterAddress(key).isEmpty();
    }

    /** Create's address match: {@code boxAddress} of a box against {@code ruleAddress} of a filter. */
    public static boolean matches(String boxAddress, String ruleAddress) {
        return PackageItem.matchAddress(boxAddress, ruleAddress);
    }

    /**
     * Addresses of stored boxes, read once per key: the component lookup copies the stack, and the rule questions ask
     * for the same boxes again and again. Bounded; server thread only.
     */
    public static final class AddressMemo {
        private static final int BOUND = 8192;
        private final Map<ItemKey, String> memo = new HashMap<>();

        /** The address of the box {@code key} ({@code ""} without one). Only meaningful for a package key. */
        public String of(ItemKey key) {
            String known = memo.get(key);
            if (known != null)
                return known;
            String address = PackageItem.getAddress(key.toStack());
            if (address == null)
                address = "";
            if (memo.size() >= BOUND)
                memo.clear();
            memo.put(key, address);
            return address;
        }
    }
}
