package dev.wareworks.content.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.warehouse.NetworkStop;
import dev.wareworks.core.warehouse.RailNetwork;

/**
 * {@link NetworkGoggleInfo}: what the controller's goggles say about the shape of the rails, and — the row that matters
 * most — what they do <b>not</b> say about a warehouse that never bends (M21, issue #1, ADR-033).
 * <p>
 * The whole record is absent from the synced tag of a straight warehouse whose rails simply end, which is every
 * warehouse built before M21: the goggle packet of such a build must not grow by a byte for a feature it does not use.
 * <p>
 * The NBT round trip is <b>not</b> here: this source set is pure Java with no Minecraft on its classpath (ADR-013),
 * so {@code write} / {@code read} are checked in the world, by
 * {@code gametest.WarehouseNetworkGameTests#controllergogglesnamethenetwork}.
 */
class NetworkGoggleInfoTest {
    private static final int HEIGHT = 4;

    private static RailNetwork straight(int length, NetworkStop stop) {
        return new RailNetwork(NetworkGeometry.single(Heading.EAST, length, HEIGHT), stop, length + 1, 0, length,
                false, false);
    }

    private static RailNetwork bent(int first, int second, NetworkStop stop) {
        NetworkGeometry geometry = new NetworkGeometry(List.of(BranchGeometry.first(Heading.EAST, first),
                new BranchGeometry(1, first, 0, Heading.SOUTH, second)), HEIGHT);
        return new RailNetwork(geometry, stop, first, second + 1, first + second, false, false);
    }

    @Test
    void aStraightWarehouseWhoseRailsEndSaysNothingAtAll() {
        assertTrue(NetworkGoggleInfo.of(straight(16, NetworkStop.END), "A").isEmpty());
        // A rail a player closed on purpose is a real end as well, so it is not a report either.
        assertTrue(NetworkGoggleInfo.of(straight(16, NetworkStop.CLOSED), "A").isEmpty());
    }

    @Test
    void aStraightWarehouseThatStopsShortDoesSaySo() {
        // The case the record exists for: a T laid on a straight aisle. Nothing bends, and the player still has to be
        // told where the warehouse stops and why.
        Optional<NetworkGoggleInfo> info = NetworkGoggleInfo.of(straight(16, NetworkStop.BRANCHED), "A");
        assertTrue(info.isPresent());
        assertEquals(NetworkStop.BRANCHED, info.get().stop());
        assertTrue(info.get().stopsShort());
        assertEquals(1, info.get().aisleCount());
    }

    @Test
    void aWarehouseThatBendsNamesEveryAisleWithItsLetterAndLength() {
        NetworkGoggleInfo info = NetworkGoggleInfo.of(bent(16, 12, NetworkStop.END), "AB").orElseThrow();
        assertEquals(2, info.aisleCount());
        assertEquals(28, info.rails());
        assertEquals(Optional.of('A'), info.letterOf(0));
        assertEquals(Optional.of('B'), info.letterOf(1));
        assertEquals(List.of(16, 12), info.aisleLengths());
        assertFalse(info.stopsShort());
    }

    @Test
    void anAisleWithoutALetterKeepsTheLettersBehindItReadable() {
        // A letter missing in the middle must shift nothing: the place in the string is what names the aisle.
        NetworkGoggleInfo info = new NetworkGoggleInfo(9, "A" + CraneGoggleInfo.NO_LETTER + "C", List.of(4, 3, 2),
                NetworkStop.END, 0, 0);
        assertEquals(Optional.of('A'), info.letterOf(0));
        assertEquals(Optional.empty(), info.letterOf(1));
        assertEquals(Optional.of('C'), info.letterOf(2));
    }

    @Test
    void thereIsExactlyOneLetterPerAisleWhateverArrives() {
        // Too few letters, too many letters and rubbish all have to leave one character per aisle standing, or a
        // letter could be read against the wrong aisle.
        assertEquals(3, new NetworkGoggleInfo(1, "A", List.of(1, 1, 1), NetworkStop.END, 0, 0)
                .aisleLetters().length());
        assertEquals(2, new NetworkGoggleInfo(1, "ABCDEF", List.of(1, 1), NetworkStop.END, 0, 0)
                .aisleLetters().length());
        assertEquals("A" + CraneGoggleInfo.NO_LETTER,
                new NetworkGoggleInfo(1, "A1", List.of(1, 1), NetworkStop.END, 0, 0).aisleLetters());
    }

}
