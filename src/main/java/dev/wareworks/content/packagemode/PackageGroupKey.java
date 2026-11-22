package dev.wareworks.content.packagemode;

import java.util.Optional;

import com.simibubi.create.content.logistics.box.PackageStyles;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.util.WareworksLang;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;

/**
 * The <b>synthetic key</b> of one address row of a package-mode terminal (issue #40, owner decision E5, design §6.1).
 * <p>
 * A terminal folds every stored box of an address into one row, and that row needs an item key like every other: the
 * screen draws it, its search matches the name, its sort orders by it and "most used" counts clicks on it, all without
 * knowing that it is a group. So the key is an item stack that is drawn as a box and named after the address:
 * <ul>
 * <li>Create's first package style ({@code PackageStyles#getDefaultBox}, the plain cardboard box), with <b>no</b>
 * contents, address or order data of its own;</li>
 * <li>a translatable item name ({@code minecraft:item_name}, so it is not drawn in italics), "Packages → Base North" ({@link WareworksLang#TERMINAL_PACKAGE_GROUP}, the address as
 * its argument) or "Packages without an address" ({@link WareworksLang#TERMINAL_PACKAGE_GROUP_NO_ADDRESS}). That name
 * is the marker as well: it is what the search and the name sort read, and it is what tells the key from a real box,
 * whose name is never one of these two lang keys.</li>
 * </ul>
 * Nothing is written into any component a box carries in the world (no {@code create:package_address}, no
 * {@code custom_data}): Wareworks never addresses or stamps a box (ADR-065 D3, {@code PackageOrderBoundaryTest}), and
 * this key is no box. It is never stored, carried or delivered: the server resolves a click on it into concrete boxes
 * of the address ({@code WarehouseTerminalBlockEntity#requestPackages}), and nothing ever matches it against an
 * inventory.
 */
public final class PackageGroupKey {
    /** Longest address a group key carries; Create's own address fields are far shorter. */
    private static final int MAX_ADDRESS = 256;

    private PackageGroupKey() {
    }

    /** The group key of {@code address} ({@code ""} for boxes without one). */
    public static ItemKey of(String address) {
        String name = address == null ? "" : address;
        if (name.length() > MAX_ADDRESS)
            name = name.substring(0, MAX_ADDRESS);
        ItemStack stack = PackageStyles.getDefaultBox();
        // The item name, not a custom name (review nit): a custom name is drawn in italics like an anvil rename.
        stack.set(DataComponents.ITEM_NAME, name.isEmpty()
                ? WareworksLang.translateDirect(WareworksLang.TERMINAL_PACKAGE_GROUP_NO_ADDRESS)
                : WareworksLang.translateDirect(WareworksLang.TERMINAL_PACKAGE_GROUP, name));
        return ItemKey.of(stack);
    }

    /** The address of a group key, or empty for every other key (a real box included). */
    public static Optional<String> addressOf(ItemKey key) {
        if (key == null || !PackageModeGate.isPackage(key))
            return Optional.empty();
        Component name = key.toStack().get(DataComponents.ITEM_NAME);
        if (name == null || !(name.getContents() instanceof TranslatableContents translatable))
            return Optional.empty();
        if (translatable.getKey().equals(WareworksLang.key(WareworksLang.TERMINAL_PACKAGE_GROUP_NO_ADDRESS)))
            return translatable.getArgs().length == 0 ? Optional.of("") : Optional.empty();
        if (!translatable.getKey().equals(WareworksLang.key(WareworksLang.TERMINAL_PACKAGE_GROUP))
                || translatable.getArgs().length != 1)
            return Optional.empty();
        Object argument = translatable.getArgs()[0];
        if (argument instanceof String text)
            return Optional.of(text);
        if (argument instanceof Component component)
            return Optional.of(component.getString());
        return Optional.empty();
    }

    /** Whether {@code key} is the synthetic key of an address row. */
    public static boolean isGroup(ItemKey key) {
        return addressOf(key).isPresent();
    }
}
