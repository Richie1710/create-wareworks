package dev.wareworks.content.packagemode;

import com.simibubi.create.content.logistics.box.PackageItem;

import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.item.ItemKey;
import net.minecraft.world.item.ItemStack;

/**
 * The one test every entrance of a package-mode warehouse asks (issue #40, owner decisions E1/E2, 2026-11-22).
 * <p>
 * <b>The switch.</b> {@code [packageMode] enabled} in {@code wareworks-server.toml}, per world through
 * {@code <world>/serverconfig/}: one value for every warehouse of the world, read through {@link WareworksConfig}. It
 * is a {@code worldRestart} value, so NeoForge keeps it cached until the server stops and it never flips under running
 * work; a client is told the server's value at login ({@link #enabledOnClient}), so a goggle line can read it too. There is
 * no flag on any block entity and nothing is saved: with the switch off, every method here answers exactly what the
 * code before package mode did, and nothing in any save changes ({@code gametest.PackageModeGoldenGameTests}).
 * <p>
 * <b>The test.</b> {@link #isPackage} is Create's own: {@code stack.getItem() instanceof PackageItem}
 * ({@code PackageItem#isPackage}), the test every Create block uses. It also takes addon boxes and an <b>empty</b> box
 * (owner decision E10: empty packages count as packages).
 * <p>
 * Cheap on purpose: one config read and one {@code instanceof}, no lookup and no allocation, because a funnel that is
 * refused asks again every tick.
 */
public final class PackageModeGate {
    private PackageModeGate() {
    }

    /**
     * The switch as the server told this client at login ({@code PackageModeSwitchPayload}); off until it does and after
     * the client leaves the world. Written by the network thread's handler, read by the render thread.
     */
    private static volatile boolean clientView;

    /**
     * Server: whether this world's warehouses are in package mode. Never throws; off while the config is not loaded.
     * Every rule of package mode asks this, on the logical server only.
     */
    public static boolean enabled() {
        return WareworksConfig.packageModeEnabled();
    }

    /**
     * Client: whether the server this client plays on is in package mode, as the server told it at login
     * ({@code PackageModeSwitchPayload}). Screens, goggle lines and the recipe viewer ask this and not {@link #enabled}:
     * the synced config file can disagree with the value a running server holds (review fix, 2026-11-22).
     */
    public static boolean enabledOnClient() {
        return clientView;
    }

    /** Client network handler only: the server's switch, or {@code false} when the client leaves the world. */
    public static void setClientView(boolean enabled) {
        clientView = enabled;
    }

    /** Whether {@code stack} is a sealed Create package (Create's own test). An empty stack is not. */
    public static boolean isPackage(ItemStack stack) {
        return !stack.isEmpty() && PackageItem.isPackage(stack);
    }

    /** Whether the items of {@code key} are sealed Create packages. */
    public static boolean isPackage(ItemKey key) {
        return key.getItem() instanceof PackageItem;
    }

    /**
     * Whether an entrance of a warehouse takes {@code stack} without any further question: always with the switch off,
     * and only a package with it on. A loose stack refused here may still be let in by the drain rule
     * ({@code core.production.PackageModeDrain}), which only a warehouse input asks.
     */
    public static boolean admits(ItemStack stack) {
        return !enabled() || stack.isEmpty() || isPackage(stack);
    }

    /** {@link #admits(ItemStack)} for an item key. */
    public static boolean admits(ItemKey key) {
        return !enabled() || isPackage(key);
    }
}
