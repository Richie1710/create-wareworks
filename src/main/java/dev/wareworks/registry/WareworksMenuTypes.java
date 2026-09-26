package dev.wareworks.registry;

import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.MenuEntry;

import dev.wareworks.Wareworks;
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.content.station.StockKeeperMenu;
import dev.wareworks.content.station.WarehouseTerminalMenu;

/**
 * Menu type registrations ({@code REGISTRATE.menu(name, factory, screenFactory).register()}).
 * <p>
 * Registrate builds the {@code MenuType} with {@code IMenuTypeExtension.create}, so the client factory receives the
 * extra data the server writes in {@code ServerPlayer#openMenu}, and registers the screen through
 * {@code RegisterMenuScreensEvent} <b>only on the client</b> ({@code RegistrateDistExecutor}). The screen supplier is
 * therefore the one place where common code may name a client class: its body never runs on a dedicated server.
 * <p>
 * Initialised through {@link #register()} after {@link WareworksBlockEntityTypes#register()}.
 */
public final class WareworksMenuTypes {
    private static final CreateRegistrate REGISTRATE = Wareworks.registrate();

    /** The warehouse terminal screen ({@code docs/warehouse-system.md} §3.4.2, ADR-019). */
    public static final MenuEntry<WarehouseTerminalMenu> WAREHOUSE_TERMINAL = REGISTRATE
            .menu("warehouse_terminal", WarehouseTerminalMenu::new,
                    () -> dev.wareworks.client.gui.WarehouseTerminalScreen::new)
            .register();

    /** The warehouse production station's pattern screen ({@code docs/warehouse-system.md} §3.5, ADR-024). */
    public static final MenuEntry<ProductionMenu> WAREHOUSE_PRODUCTION = REGISTRATE
            .menu("warehouse_production", ProductionMenu::new,
                    () -> dev.wareworks.client.gui.WarehouseProductionScreen::new)
            .register();

    /** The warehouse stock keeper's rule screen ({@code docs/warehouse-system.md} §3.6, M15). */
    public static final MenuEntry<StockKeeperMenu> WAREHOUSE_STOCK_KEEPER = REGISTRATE
            .menu("warehouse_stock_keeper", StockKeeperMenu::new,
                    () -> dev.wareworks.client.gui.WarehouseStockKeeperScreen::new)
            .register();

    private WareworksMenuTypes() {
    }

    /** Loads this class so that its static entries are built. */
    public static void register() {
        Wareworks.LOGGER.debug("Registering {} menu types", REGISTRATE.getModid());
    }
}
