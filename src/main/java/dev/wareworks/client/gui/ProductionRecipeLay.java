package dev.wareworks.client.gui;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.ProductionMenu;
import dev.wareworks.core.production.ProductionEntry;
import dev.wareworks.network.ProductionPatternLayPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The client half of laying a recipe viewer's recipe into a production station's pattern (ADR-076), free of every
 * viewer's own types, as {@link TerminalRecipeLay} is for the terminal. The lay goes into the pattern slot the screen
 * has selected, and what it overwrites there is the player's choice of tab. Nothing is decided here: the server picks
 * the items, checks a crafting recipe, writes the slot and pushes it back.
 * <p>
 * Client only: it is named from the recipe viewer integrations, which only a client loads.
 */
public final class ProductionRecipeLay {
    private ProductionRecipeLay() {
    }

    /** Whether the player still has {@code menu} open with its screen behind the viewer. Cheap: asked every tick. */
    public static boolean canLay(ProductionMenu menu) {
        Objects.requireNonNull(menu, "menu");
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.containerMenu == menu
                && WarehouseProductionScreen.forMenu(menu.containerId).isPresent();
    }

    /**
     * Asks the server to lay a recipe into the selected pattern.
     *
     * @param crafting whether {@code cells} are a crafting grid, which the server looks up and which names its result
     * @param cells    the nine cells row-major, each with its candidates
     * @param counts   per cell the items of one run
     * @param result   what one run makes; empty only with {@code crafting}
     * @return whether the lay was sent
     */
    public static boolean lay(ProductionMenu menu, boolean crafting, List<List<ItemKey>> cells, List<Integer> counts,
            Optional<ProductionEntry<ItemKey>> result) {
        Objects.requireNonNull(menu, "menu");
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        Optional<WarehouseProductionScreen> screen = WarehouseProductionScreen.forMenu(menu.containerId);
        if (!canLay(menu) || screen.isEmpty() || connection == null)
            return false;
        Optional<ProductionPatternLayPayload> payload = ProductionPatternLayPayload.fitted(menu.containerId,
                screen.get().selectedPattern(), crafting, cells, counts, result, connection.registryAccess(),
                connection.getConnectionType());
        payload.ifPresent(PacketDistributor::sendToServer);
        return payload.isPresent();
    }
}
