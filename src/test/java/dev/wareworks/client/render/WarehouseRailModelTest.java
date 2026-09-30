package dev.wareworks.client.render;

import static dev.wareworks.client.render.ModelJson.array;
import static dev.wareworks.client.render.ModelJson.number;
import static dev.wareworks.client.render.ModelJson.object;
import static dev.wareworks.client.render.ModelJson.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The six hand-made warehouse rail models fit together (M21, issue #1, ADR-033).
 * <p>
 * Rails that touch connect, and the blockstate picks one of {@code block} (straight), {@code end}, {@code corner},
 * {@code tee}, {@code cross} or {@code closed} and turns it onto the sides the rail is connected on
 * ({@code WareworksBlockStateGen#railBlockProvider}). Nothing at load time checks that those six shapes are the same
 * rail: this does.
 * <ul>
 * <li>every connected side ends in the same rail cross-section at the block edge, so a corner meets a straight without
 * a step (the models are authored on fixed sides — {@code end} north, {@code corner} north and east, {@code tee}
 * everywhere but west — and only turned from there);</li>
 * <li>an unconnected side has <b>no</b> geometry at its edge, so a stub does not look like a straight;</li>
 * <li>no two boxes of one model overlap, which is what would z-fight;</li>
 * <li>everything stays inside the block and at or below the rail height, except the closed rail's brass stop, which
 * the block's own outline shape follows.</li>
 * </ul>
 * Runs without Minecraft; that every referenced Create texture exists is checked by {@code runClient} and the visual
 * smoke test.
 */
class WarehouseRailModelTest {
    private static final Path MODELS = Path.of("src/main/resources/assets/wareworks/models/block/warehouse_rail");
    /** Height of the rail bed in pixels, {@code WarehouseRailBlock.HEIGHT_PIXELS}. */
    private static final double RAIL_HEIGHT = 3.0;
    /** Top of the closed rail's brass stop, {@code WarehouseRailBlock.STOP_HEIGHT_PIXELS}. */
    private static final double STOP_HEIGHT = 6.0;
    private static final double BLOCK = 16.0;
    private static final double EPSILON = 1.0E-6;
    private static final int X = 0;
    private static final int Y = 1;
    private static final int Z = 2;

    /** Model name to the sides it is authored connected on. */
    private static final Map<String, Set<Side>> CONNECTED = Map.of(
            "block", Set.of(Side.NORTH, Side.SOUTH),
            "end", Set.of(Side.NORTH),
            "corner", Set.of(Side.NORTH, Side.EAST),
            "tee", Set.of(Side.NORTH, Side.EAST, Side.SOUTH),
            "cross", Set.of(Side.NORTH, Side.EAST, Side.SOUTH, Side.WEST),
            // The closed rail is drawn as a straight rail with a stop on it, so its bed still has to
            // line up with the rails beside it even though nothing runs through it any more.
            "closed", Set.of(Side.NORTH, Side.SOUTH));

    /** The four horizontal sides of a block, as the axis they are measured on and the coordinate of that edge. */
    private enum Side {
        NORTH(Z, 0.0),
        SOUTH(Z, BLOCK),
        WEST(X, 0.0),
        EAST(X, BLOCK);

        private final int axis;
        private final double edge;

        Side(int axis, double edge) {
            this.axis = axis;
            this.edge = edge;
        }

        /** The axis a rail crosses when it leaves through this side. */
        int crossAxis() {
            return axis == Z ? X : Z;
        }
    }

    private record Box(String name, double[] from, double[] to) {
        double min(int axis) {
            return from[axis];
        }

        double max(int axis) {
            return to[axis];
        }

        boolean touches(Side side) {
            return side.edge == 0.0 ? min(side.axis) <= EPSILON : max(side.axis) >= BLOCK - EPSILON;
        }
    }

    @Test
    void everyModelIsInsideItsBlock() throws IOException {
        for (String name : CONNECTED.keySet()) {
            double ceiling = name.equals("closed") ? STOP_HEIGHT : RAIL_HEIGHT;
            for (Box box : boxes(name)) {
                for (int axis : new int[]{X, Y, Z}) {
                    assertTrue(box.min(axis) >= -EPSILON && box.max(axis) <= BLOCK + EPSILON,
                            name + "/" + box.name() + " leaves the block on axis " + axis);
                    assertTrue(box.min(axis) < box.max(axis), name + "/" + box.name() + " is empty on axis " + axis);
                }
                assertTrue(box.max(Y) <= ceiling + EPSILON,
                        name + "/" + box.name() + " is taller than the rail bed: " + box.max(Y));
            }
        }
    }

    @Test
    void noTwoBoxesOfOneModelOverlap() throws IOException {
        for (String name : CONNECTED.keySet()) {
            List<Box> boxes = boxes(name);
            for (int a = 0; a < boxes.size(); a++) {
                for (int b = a + 1; b < boxes.size(); b++)
                    assertFalse(overlap(boxes.get(a), boxes.get(b)),
                            name + ": " + boxes.get(a).name() + " and " + boxes.get(b).name() + " are inside each other");
            }
        }
    }

    /**
     * The one thing a player would see: the rail head and its bed have to leave every connected side in the same
     * place, or a corner would not line up with the straight next to it.
     */
    @Test
    void everyConnectedSideEndsInTheSameCrossSection() throws IOException {
        double[] straightHead = null;
        double[] straightBed = null;
        for (Map.Entry<String, Set<Side>> entry : CONNECTED.entrySet()) {
            List<Box> boxes = boxes(entry.getKey());
            for (Side side : entry.getValue()) {
                double[] head = crossSection(boxes, side, RAIL_HEIGHT);
                double[] bed = crossSection(boxes, side, RAIL_HEIGHT - 1.0);
                if (straightHead == null) {
                    straightHead = head;
                    straightBed = bed;
                    continue;
                }
                assertEquals(straightHead[0], head[0], EPSILON, entry.getKey() + " head at " + side);
                assertEquals(straightHead[1], head[1], EPSILON, entry.getKey() + " head at " + side);
                assertEquals(straightBed[0], bed[0], EPSILON, entry.getKey() + " bed at " + side);
                assertEquals(straightBed[1], bed[1], EPSILON, entry.getKey() + " bed at " + side);
            }
        }
        assertTrue(straightHead != null && straightHead[1] > straightHead[0], "a rail head was found at all");
    }

    @Test
    void anUnconnectedSideCarriesNoRail() throws IOException {
        for (Map.Entry<String, Set<Side>> entry : CONNECTED.entrySet()) {
            List<Box> boxes = boxes(entry.getKey());
            for (Side side : Side.values()) {
                if (entry.getValue().contains(side))
                    continue;
                for (Box box : boxes)
                    assertFalse(box.touches(side) && box.max(Y) > 1.0 + EPSILON,
                            entry.getKey() + "/" + box.name() + " reaches the unconnected " + side + " side");
            }
        }
    }

    /** The closed rail is the straight rail plus a brass stop, so a player sees at a glance what it is. */
    @Test
    void theClosedRailCarriesAStopAboveTheBed() throws IOException {
        Map<String, Object> model = read(MODELS.resolve("closed.json"));
        assertTrue(object(model.get("textures")).containsValue("create:block/brass_block"), "the stop is brass");
        List<Box> above = boxes("closed").stream().filter(box -> box.min(Y) >= RAIL_HEIGHT - EPSILON).toList();
        assertEquals(1, above.size(), "exactly one stop, across the bed");
        assertEquals(STOP_HEIGHT, above.getFirst().max(Y), EPSILON, "as high as the block's own outline says");
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** The {@code [min, max]} of the rail geometry crossing {@code side}, at height {@code y}. */
    private static double[] crossSection(List<Box> boxes, Side side, double y) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Box box : boxes) {
            if (!box.touches(side) || box.max(Y) < y - EPSILON || box.min(Y) > y - 1.0 + EPSILON)
                continue;
            min = Math.min(min, box.min(side.crossAxis()));
            max = Math.max(max, box.max(side.crossAxis()));
        }
        assertTrue(min < max, "no rail at height " + y + " on the " + side + " side");
        return new double[]{min, max};
    }

    private static boolean overlap(Box a, Box b) {
        for (int axis : new int[]{X, Y, Z}) {
            if (a.max(axis) <= b.min(axis) + EPSILON || b.max(axis) <= a.min(axis) + EPSILON)
                return false;
        }
        return true;
    }

    private static List<Box> boxes(String name) throws IOException {
        Map<String, Object> model = read(MODELS.resolve(name + ".json"));
        List<Box> boxes = new ArrayList<>();
        for (Object element : array(model.get("elements"))) {
            Map<String, Object> fields = object(element);
            boxes.add(new Box(String.valueOf(fields.get("name")), vector(fields.get("from")), vector(fields.get("to"))));
        }
        assertFalse(boxes.isEmpty(), name + " has no elements");
        return boxes;
    }

    private static double[] vector(Object value) {
        List<Object> list = array(value);
        return new double[]{number(list.get(X)), number(list.get(Y)), number(list.get(Z))};
    }
}
