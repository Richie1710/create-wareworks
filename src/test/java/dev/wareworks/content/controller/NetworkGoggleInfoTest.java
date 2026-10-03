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
import dev.wareworks.core.warehouse.AisleName;
import dev.wareworks.core.warehouse.AisleNames;
import dev.wareworks.core.warehouse.NetworkStop;
import dev.wareworks.core.warehouse.RailNetwork;

/**
 * {@link NetworkGoggleInfo}: what the controller's goggles say about the shape of the rails, and — the row that matters
 * most — what they do <b>not</b> say about a warehouse that never bends (M21, issue #1, ADR-033).
 * <p>
 * The whole record is absent from the synced tag of a straight warehouse whose rails simply end, which is every
 * warehouse built before M21: the goggle packet of such a build must not grow by a byte for a feature it does not use.
 * <p>
 * Since M25 (issue #15, ADR-038) it also carries the <b>names</b> a player gave those aisles, and the cases below are
 * the whole encoding: keyed by letter on the way in, read by aisle position on the way out, bounded, canonical and
 * re-sanitised, so no name can be read against the wrong aisle and none can reach a goggle line in a spelling the
 * rule would not allow.
 * <p>
 * The NBT round trip is <b>not</b> here: this source set is pure Java with no Minecraft on its classpath (ADR-013),
 * so {@code write} / {@code read} are checked in the world, by
 * {@code gametest.WarehouseNetworkGameTests#controllergogglesnamethenetwork} and
 * {@code gametest.WarehouseControllerGameTests#aisleNameSurfaces}.
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
        assertTrue(NetworkGoggleInfo.of(straight(16, NetworkStop.END), "A", new AisleNames()).isEmpty());
        // A rail a player closed on purpose is a real end as well, so it is not a report either.
        assertTrue(NetworkGoggleInfo.of(straight(16, NetworkStop.CLOSED), "A", new AisleNames()).isEmpty());
    }

    @Test
    void aStraightWarehouseThatStopsShortDoesSaySo() {
        // The case the record exists for: a straight aisle that ran into one of the server's maxima. Nothing bends,
        // and the player still has to be told where the warehouse stops and which number to raise.
        Optional<NetworkGoggleInfo> info = NetworkGoggleInfo.of(straight(16, NetworkStop.MAX_JUNCTIONS), "A", new AisleNames());
        assertTrue(info.isPresent());
        assertEquals(NetworkStop.MAX_JUNCTIONS, info.get().stop());
        assertTrue(info.get().stopsShort());
        assertEquals(1, info.get().aisleCount());
    }

    @Test
    void aWarehouseThatBendsNamesEveryAisleWithItsLetterAndLength() {
        NetworkGoggleInfo info = NetworkGoggleInfo.of(bent(16, 12, NetworkStop.END), "AB", new AisleNames()).orElseThrow();
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
        NetworkGoggleInfo info = new NetworkGoggleInfo(9, "A" + CraneGoggleInfo.NO_LETTER + "C", List.of(4, 3, 2), "",
                NetworkStop.END, 0, 0);
        assertEquals(Optional.of('A'), info.letterOf(0));
        assertEquals(Optional.empty(), info.letterOf(1));
        assertEquals(Optional.of('C'), info.letterOf(2));
    }

    @Test
    void thereIsExactlyOneLetterPerAisleWhateverArrives() {
        // Too few letters, too many letters and rubbish all have to leave one character per aisle standing, or a
        // letter could be read against the wrong aisle.
        assertEquals(3, new NetworkGoggleInfo(1, "A", List.of(1, 1, 1), "", NetworkStop.END, 0, 0)
                .aisleLetters().length());
        assertEquals(2, new NetworkGoggleInfo(1, "ABCDEF", List.of(1, 1), "", NetworkStop.END, 0, 0)
                .aisleLetters().length());
        assertEquals("A" + CraneGoggleInfo.NO_LETTER,
                new NetworkGoggleInfo(1, "A1", List.of(1, 1), "", NetworkStop.END, 0, 0).aisleLetters());
    }

    // --- the names a player gave the aisles (M25, issue #15, ADR-038) ------------------------------------------------

    @Test
    void aWarehouseNobodyNamedCarriesNoNamesAtAll() {
        // The row that matters: this record rides every chunk packet, so a warehouse without names must add nothing.
        NetworkGoggleInfo info = NetworkGoggleInfo.of(bent(16, 12, NetworkStop.END), "AB", new AisleNames())
                .orElseThrow();
        assertEquals("", info.aisleNames());
        assertFalse(info.hasNames());
        assertEquals(Optional.empty(), info.nameOf(0));
        assertEquals(Optional.empty(), info.nameOf(1));
        // And a null table is the same thing, not a crash: the controller asks before its first link.
        assertEquals("", NetworkGoggleInfo.names("AB", null));
    }

    @Test
    void aNameIsKeyedByLetterAndReadByAislePosition() {
        AisleNames names = new AisleNames();
        names.set('B', "Metals");
        NetworkGoggleInfo info = NetworkGoggleInfo.of(bent(16, 12, NetworkStop.END), "AB", names).orElseThrow();
        assertTrue(info.hasNames());
        // Aisle 0 carries the letter A, which has no name; aisle 1 carries B, which has one. The empty first field is
        // what keeps the two apart, so a name can never be read against the wrong aisle.
        assertEquals(Optional.empty(), info.nameOf(0));
        assertEquals(Optional.of("Metals"), info.nameOf(1));
        assertEquals("\nMetals", info.aisleNames());
    }

    @Test
    void theLettersDecideWhichNameLandsOnWhichAisle() {
        // The same table against swapped letters: the aisle that carries B carries B's name, wherever it stands.
        AisleNames names = new AisleNames();
        names.set('A', "Ores");
        names.set('B', "Metals");
        assertEquals("Ores\nMetals", NetworkGoggleInfo.names("AB", names));
        assertEquals("Metals\nOres", NetworkGoggleInfo.names("BA", names));
    }

    @Test
    void theEmptyFieldsAtTheEndAreDroppedSoTwoEqualWarehousesCompareEqual() {
        AisleNames names = new AisleNames();
        names.set('A', "Ores");
        // Three aisles, only the first named: the two trailing empty fields are not sent. Without this the record
        // would compare unequal to the same warehouse read back from a tag and sync a packet that changes nothing.
        assertEquals("Ores", NetworkGoggleInfo.names("ABC", names));
        assertEquals("Ores", new NetworkGoggleInfo(9, "ABC", List.of(4, 3, 2), "Ores\n\n", NetworkStop.END, 0, 0)
                .aisleNames());
        assertEquals(Optional.of("Ores"),
                new NetworkGoggleInfo(9, "ABC", List.of(4, 3, 2), "Ores\n\n", NetworkStop.END, 0, 0).nameOf(0));
    }

    @Test
    void atMostSixAislesCanCarryASyncedName() {
        AisleNames names = new AisleNames();
        for (char letter = 'A'; letter <= 'H'; letter++)
            names.set(letter, "name " + letter);
        String synced = NetworkGoggleInfo.names("ABCDEFGH", names);
        assertEquals(NetworkGoggleInfo.NAMES_LISTED, synced.split("\n", -1).length);
        // Nothing past the bound is sent, and nothing past it is readable either: no surface draws more than six, so
        // a seventh name would travel in every chunk packet and be shown nowhere.
        NetworkGoggleInfo info = new NetworkGoggleInfo(9, "ABCDEFGH", List.of(1, 1, 1, 1, 1, 1, 1, 1), synced,
                NetworkStop.END, 0, 0);
        assertEquals(Optional.of("name F"), info.nameOf(5));
        assertEquals(Optional.empty(), info.nameOf(6));
        assertEquals(Optional.empty(), info.nameOf(7));
    }

    @Test
    void noAisleCanCarryMoreNamesThanItHasAisles() {
        // A tag that offers four names to a warehouse of two aisles keeps two: a name read against an aisle that does
        // not exist is a name on the wrong line of the goggle list.
        NetworkGoggleInfo info = new NetworkGoggleInfo(9, "AB", List.of(4, 3), "Ores\nMetals\nWood\nStone",
                NetworkStop.END, 0, 0);
        assertEquals("Ores\nMetals", info.aisleNames());
        assertEquals(Optional.empty(), info.nameOf(2));
    }

    @Test
    void everyNameGoesThroughTheSpellingRuleOnTheWayIn() {
        // A hand-edited save, a malformed packet or an old client's tag must not be able to put a name on a goggle
        // line that AisleName.sanitize would not allow: 17 characters are cut to 16, and the section sign goes.
        String tooLong = "ABCDEFGHIJKLMNOPQ";
        NetworkGoggleInfo info = new NetworkGoggleInfo(9, "AB", List.of(4, 3), tooLong + "\n§cRed",
                NetworkStop.END, 0, 0);
        assertEquals(AisleName.MAX_LENGTH, info.nameOf(0).orElseThrow().length());
        assertEquals("ABCDEFGHIJKLMNOP", info.nameOf(0).orElseThrow());
        assertEquals(Optional.of("cRed"), info.nameOf(1));
        // Idempotent, which is what lets every surface re-sanitise on every read without changing anything.
        assertEquals(info, new NetworkGoggleInfo(9, "AB", List.of(4, 3), info.aisleNames(), NetworkStop.END, 0, 0));
    }

    @Test
    void readingANameOutsideTheWarehouseNeverThrows() {
        NetworkGoggleInfo info = new NetworkGoggleInfo(9, "AB", List.of(4, 3), "Ores", NetworkStop.END, 0, 0);
        assertEquals(Optional.empty(), info.nameOf(-1));
        assertEquals(Optional.empty(), info.nameOf(1));
        assertEquals(Optional.empty(), info.nameOf(99));
        assertEquals(Optional.empty(), info.nameOf(Integer.MAX_VALUE));
    }

}
