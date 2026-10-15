package dev.wareworks.client.gui;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.WarehouseTerminalBlockEntity;
import dev.wareworks.content.station.WarehouseTerminalMenu;
import dev.wareworks.core.crafting.IngredientPicker;
import dev.wareworks.network.TerminalCraftLayPayload;
import dev.wareworks.util.WareworksLang;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The client half of laying a recipe viewer's recipe into a warehouse terminal's crafting panel (issue #24, slice 4,
 * ADR-063, {@code docs/warehouse-system.md} §3.4.5), free of every viewer's own types. A viewer integration — the JEI
 * plugin in {@code client.compat.jei} — turns its recipe into nine lists of candidate items, one per grid cell, and
 * hands them over here; it also asks here whether its transfer button may be used at all.
 * <p>
 * <b>Nothing is decided here.</b> {@link #lay} only sends the candidates ({@link TerminalCraftLayPayload}). The server
 * picks one item per cell by what the terminal's buffer, the aisle's stock and its production stations say, checks the
 * picked grid against its own recipe manager and answers; the grid it accepted comes back with the crafting view, and
 * the panel takes it from there ({@link TerminalCraftPanel#adoptLay}). Laying moves no item and orders nothing: it only
 * gives the panel a grid, as the player's own clicks would.
 * <p>
 * <b>The fold is marked at the click.</b> The viewer's recipe screen covers the terminal's while the lay is sent, and
 * hands it back right after; {@link #lay} marks the panel folded out first
 * ({@link WarehouseTerminalScreen#foldOutForLay}), so the terminal comes back already as wide as the laid grid needs
 * and is laid out once, instead of popping open and moving under the cursor a tick later. A lay the server refuses
 * leaves the panel folded out with the grid it had and the refusal in the status row.
 * <p>
 * <b>The screen may be covered.</b> A viewer's recipe screen opens over the terminal's without closing the menu, so the
 * terminal screen asked here is found through {@link WarehouseTerminalScreen#forMenu}, not as the screen on top.
 * <p>
 * Client only: it is named from the recipe viewer integrations, which only a client loads.
 */
public final class TerminalRecipeLay {
    private TerminalRecipeLay() {
    }

    /**
     * Whether a recipe can be laid into {@code menu}'s panel at all: the player still has the menu open, and the client
     * knows its terminal block entity, whose position the payload names. Cheap: a recipe viewer asks every tick.
     */
    public static boolean canLay(WarehouseTerminalMenu menu) {
        Objects.requireNonNull(menu, "menu");
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.containerMenu == menu && menu.terminal() != null;
    }

    /**
     * Whether the terminal's open craft order locks the grid right now
     * ({@link WarehouseTerminalScreen#isCraftGridLocked}): the server refuses every lay until the order ends or is
     * given up. False when the screen cannot be found; the server decides then. Cheap: a recipe viewer asks every tick.
     */
    public static boolean gridLocked(WarehouseTerminalMenu menu) {
        Objects.requireNonNull(menu, "menu");
        return WarehouseTerminalScreen.forMenu(menu.containerId).map(WarehouseTerminalScreen::isCraftGridLocked)
                .orElse(false);
    }

    /**
     * What a viewer's transfer button says while the grid is locked: the panel's own sentence for an edit of the locked
     * grid, in red like a refusal. Translated when it is drawn, so one instance serves every language.
     */
    public static Component gridLockedLine() {
        return WareworksLang.translateDirect(WareworksLang.TERMINAL_CRAFT_GRID_LOCKED).withStyle(ChatFormatting.RED);
    }

    /**
     * Whether a recipe slot whose items a recipe viewer shows as {@code stacks} names an item at all: the cell
     * {@link #candidates} makes of it is not empty. A viewer asks this every tick for every slot of a recipe on screen,
     * so it stops at the first item; a slot that holds only other ingredients (a fluid, say) names none.
     */
    public static boolean namesAnItem(Stream<ItemStack> stacks) {
        Objects.requireNonNull(stacks, "stacks");
        return stacks.anyMatch(stack -> stack != null && !stack.isEmpty());
    }

    /**
     * One cell's candidates from the items a recipe viewer shows for it, in the viewer's order: every item once, as an
     * {@link ItemKey} without its count, empty stacks dropped, and no more than the payload carries
     * ({@value TerminalCraftLayPayload#MAX_CANDIDATES}, the most the picker considers), so a huge tag costs no more
     * than that. An empty list is an empty cell, exactly when {@link #namesAnItem} is false.
     */
    public static List<ItemKey> candidates(Stream<ItemStack> stacks) {
        Objects.requireNonNull(stacks, "stacks");
        return IngredientPicker.considered(stacks.map(ItemKey::fromStack).flatMap(Optional::stream).distinct()
                .limit(TerminalCraftLayPayload.MAX_CANDIDATES).toList());
    }

    /**
     * Asks the server to lay a recipe into {@code menu}'s crafting panel and to fold the panel out: {@code cells} are
     * the nine cells row-major, each with its candidates ({@link #candidates}), and {@code recipe} names the recipe the
     * viewer showed — a hint only, which the server tries first and never trusts alone. The payload is cut to what the
     * server takes ({@link TerminalCraftLayPayload#fitted}: at most {@value TerminalCraftLayPayload#MAX_BYTES} bytes,
     * which only items with large components ever reach), losing candidates at the end of the longest cells first.
     * <p>
     * A preview the panel still has waiting is sent first ({@link TerminalCraftPanel#flushPreview}), so the server
     * reads the grid on screen before the lay replaces it, and the panel is marked folded out
     * ({@link WarehouseTerminalScreen#foldOutForLay}), so a terminal screen the viewer covers comes back from it
     * already folded out. Nothing is sent, and nothing marked, when the recipe cannot be laid here ({@link #canLay}),
     * when the cells do not have the payload's shape, when no cell names an item — the server would only refuse it —
     * or when even one candidate per cell does not fit the payload.
     *
     * @return whether the lay was sent; the answer and the grid follow from the server
     */
    public static boolean lay(WarehouseTerminalMenu menu, Optional<ResourceLocation> recipe,
            List<List<ItemKey>> cells) {
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(cells, "cells");
        WarehouseTerminalBlockEntity terminal = menu.terminal();
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (!canLay(menu) || terminal == null || connection == null || !TerminalCraftLayPayload.wellFormed(cells)
                || cells.stream().allMatch(List::isEmpty))
            return false;
        Optional<TerminalCraftLayPayload> payload = TerminalCraftLayPayload.fitted(menu.containerId,
                terminal.getBlockPos(), recipe, cells, true, connection.registryAccess(),
                connection.getConnectionType());
        if (payload.isEmpty())
            return false;
        WarehouseTerminalScreen.forMenu(menu.containerId).ifPresent(screen -> {
            screen.craftPanel().flushPreview();
            screen.foldOutForLay();
        });
        PacketDistributor.sendToServer(payload.get());
        return true;
    }
}
