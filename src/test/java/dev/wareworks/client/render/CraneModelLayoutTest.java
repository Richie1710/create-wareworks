package dev.wareworks.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The stacker crane partial models fit together with the renderer's layout ({@link CraneModelLayout}): valid vanilla block
 * models with Create textures, retracted parts inside the aisle block, wheels on the rail and inside the chassis at every
 * angle, carriage on the chassis and around the mast, no parts inside each other at any arm extension, no two parts with
 * faces in one plane (z-fighting), held items on the inner stage, a grabber that stops at the inventory beyond the rack
 * position at full reach, and an arm that enters interfaces and stations through their aisle-side port.
 * <p>
 * It also covers the one member model the arm's port is not the whole story for: the warehouse terminal is drawn from a
 * core column plus four interchangeable shells chosen by a <b>multipart</b> blockstate (ADR-022), so nothing at load
 * time checks that the twelve combinations tile the block — {@link #terminalShellsTileTheBlockAroundTheArmPort} does.
 * <p>
 * Reads the model JSON files with a minimal JSON reader (the test classpath has no JSON library); runs without Minecraft.
 * That every referenced Create texture exists is checked by {@code runClient} (missing texture warnings) and the visual
 * smoke test.
 */
class CraneModelLayoutTest {
    private static final Path MODELS = Path.of("src/main/resources/assets/wareworks/models/block/stacker_crane");
    private static final Path RAIL_MODEL = Path.of("src/main/resources/assets/wareworks/models/block/warehouse_rail/block.json");
    private static final List<String> PARTIALS = List.of("base", "wheel", "mast_segment", "mast_top", "hoist_belt",
            "carriage", "arm_outer", "arm_inner", "grabber");
    /** Parts that stand in the aisle block while the crane travels (the arm retracted). */
    private static final List<String> AISLE_PARTS = List.of("base", "mast_segment", "mast_top", "hoist_belt", "carriage",
            "arm_outer", "arm_inner", "grabber");
    private static final int X = 0;
    private static final int Y = 1;
    private static final int Z = 2;
    private static final float BLOCK = CraneModelLayout.PIXELS_PER_BLOCK;
    private static final float MIN_EXTENT = -16.0F;
    private static final float MAX_EXTENT = 32.0F;
    private static final Set<Double> ALLOWED_ANGLES = Set.of(-45.0, -22.5, 0.0, 22.5, 45.0);
    private static final float EPSILON = 1.0E-4F;
    /** The inventory beyond the rack position starts two blocks from the aisle block's west edge (model frame). */
    private static final float INVENTORY_FACE_PX = 2 * BLOCK;
    /** The grabber may stop up to this far before the inventory face. */
    private static final float MAX_GRABBER_GAP_PX = 1.0F;
    private static final double[] EXTENSIONS = {0.0, 0.25, 0.5, 0.75, 1.0};
    private static final Path BLOCK_MODELS = Path.of("src/main/resources/assets/wareworks/models/block");
    /** Depth of the aisle-side port recess of interfaces and stations, in pixels. */
    private static final float PORT_DEPTH_PX = 3.0F;
    /** The warehouse terminal's model folder: a core column plus the shells a multipart blockstate picks (ADR-022). */
    private static final Path TERMINAL_MODELS = BLOCK_MODELS.resolve("warehouse_terminal");
    /** The terminal's shells, all authored on the north face; the item model shows four of them at once. */
    private static final List<String> TERMINAL_SHELLS = List.of("shell_display", "shell_intake", "shell_plain");
    /** The terminal's pinwheel strip: 13 px wide, 3 px deep, full height, so four of them tile the ring. */
    private static final float TERMINAL_SHELL_WIDTH_PX = 13.0F;
    private static final float TERMINAL_SHELL_DEPTH_PX = 3.0F;
    /** The terminal's core column, which the four shells surround. */
    private static final float TERMINAL_CORE_MIN_PX = 3.0F;
    private static final float TERMINAL_CORE_MAX_PX = 13.0F;
    /** The arm port of the intake shell: the same 8 x 4 px opening the warehouse interface has, over the full depth. */
    private static final float[] TERMINAL_PORT_FROM = {4.0F, 9.0F, 0.0F};
    private static final float[] TERMINAL_PORT_TO = {12.0F, 13.0F, TERMINAL_SHELL_DEPTH_PX};
    /** The recessed display of the screen shell (M10 look pass): 8 x 8 px, one pixel deep. */
    private static final float[] TERMINAL_SCREEN_FROM = {4.0F, 6.0F, 0.0F};
    private static final float[] TERMINAL_SCREEN_TO = {12.0F, 14.0F, 1.0F};
    /** The take-out tray below it: 8 x 3 px, two pixels deep, so a player reads it as an opening. */
    private static final float[] TERMINAL_TRAY_FROM = {4.0F, 2.0F, 0.0F};
    private static final float[] TERMINAL_TRAY_TO = {12.0F, 5.0F, 2.0F};
    /**
     * The recessed machine panel of the two plain shells: a plain ribbed plate, no cog — one was tried and rejected in
     * the M10 look pass ({@code docs/warehouse-system.md} §3.4.3). Its 8 px are centred on the 16 px block face like
     * the screen and the arm port above, not on the shell's own 13 px strip (M10 review fix).
     */
    private static final float[] TERMINAL_PANEL_FROM = {4.0F, 3.0F, 0.0F};
    private static final float[] TERMINAL_PANEL_TO = {12.0F, 13.0F, 1.0F};
    /** Every opening of a shell is centred on the block face, so all four faces of the block read alike. */
    private static final float TERMINAL_OPENING_CENTER_PX = 8.0F;
    /** The shells of one block state, clockwise from the north face: display, plain, intake, plain (a pinwheel). */
    private static final List<String> TERMINAL_SHELLS_CLOCKWISE =
            List.of("shell_display", "shell_plain", "shell_intake", "shell_plain");
    /** Textures of Create; everything but the one exception below must come from here (ADR-017). */
    private static final String CREATE_TEXTURES = "create:block/";
    /** Our own texture namespace: the terminal screen, generated by {@code scripts/gen_textures.py} (ADR-023). */
    private static final String OWN_TEXTURES = "wareworks:block/";
    private static final Path OWN_TEXTURE_FILES = Path.of("src/main/resources/assets/wareworks/textures/block");
    /** Copies of a flat item the renderer stacks on the stage at most. */
    private static final int FLAT_ITEM_COPIES = 2;
    /** The direction each model face name points to: its axis and whether it is the box's upper bound on that axis. */
    private static final Map<String, FacePlane> FACE_PLANES = Map.of("west", new FacePlane(X, false),
            "east", new FacePlane(X, true), "down", new FacePlane(Y, false), "up", new FacePlane(Y, true),
            "north", new FacePlane(Z, false), "south", new FacePlane(Z, true));

    private record FacePlane(int axis, boolean upper) {
    }

    /** One model element as an axis-aligned box in model pixels, with the names of its faces. */
    private record Box(String model, String name, float[] from, float[] to, boolean rotated, Set<String> faces) {
        float min(int axis) {
            return from[axis];
        }

        float max(int axis) {
            return to[axis];
        }

        Box moved(float dx, float dy, float dz) {
            return new Box(model, name, new float[] {from[X] + dx, from[Y] + dy, from[Z] + dz},
                    new float[] {to[X] + dx, to[Y] + dy, to[Z] + dz}, rotated, faces);
        }

        /** Whether both boxes share a volume (touching faces do not count). */
        boolean overlaps(Box other) {
            for (int axis = X; axis <= Z; axis++) {
                if (Math.min(to[axis], other.to[axis]) - Math.max(from[axis], other.from[axis]) <= EPSILON)
                    return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return model + "/" + name;
        }
    }

    @Test
    void everyPartialIsAValidBlockModel() throws IOException {
        for (String name : PARTIALS)
            assertValidBlockModel(MODELS.resolve(name + ".json"), name);
        // The terminal's hand-made models are picked by a multipart blockstate and by the item model, never by
        // Registrate, so the same validity rules are checked here rather than at load time.
        for (String name : List.of("block", "item", "shell_display", "shell_intake", "shell_plain"))
            assertValidBlockModel(TERMINAL_MODELS.resolve(name + ".json"), "warehouse_terminal/" + name);
    }

    /** Vanilla block model rules: the shared parent, Create textures only, element extents, angles and UV ranges. */
    private static void assertValidBlockModel(Path path, String name) throws IOException {
        Map<String, Object> model = read(path);
        assertEquals("block/block", model.get("parent"), name + " parent");
        Map<String, Object> textures = object(model.get("textures"));
        for (Map.Entry<String, Object> texture : textures.entrySet()) {
            String reference = String.valueOf(texture.getValue());
            if (reference.startsWith(OWN_TEXTURES)) {
                // ADR-023: the warehouse terminal's screen is the one texture Create cannot supply. It must exist and
                // it must be one of the files scripts/gen_textures.py writes, so the exception cannot quietly grow.
                Path file = OWN_TEXTURE_FILES.resolve(reference.substring(OWN_TEXTURES.length()) + ".png");
                assertTrue(Files.isRegularFile(file), name + " references a missing own texture: " + file);
                continue;
            }
            assertTrue(reference.startsWith(CREATE_TEXTURES),
                    name + " uses Create block textures, or one of our own generated ones: " + texture);
        }
        List<Object> elements = array(model.get("elements"));
        assertFalse(elements.isEmpty(), name + " has elements");
        for (Object element : elements) {
            Map<String, Object> fields = object(element);
            String elementName = name + "/" + fields.get("name");
            for (String key : List.of("from", "to")) {
                for (Object value : array(fields.get(key))) {
                    double coordinate = number(value);
                    assertTrue(coordinate >= MIN_EXTENT && coordinate <= MAX_EXTENT, elementName + " extent " + coordinate);
                }
            }
            if (fields.containsKey("rotation")) {
                double angle = number(object(fields.get("rotation")).get("angle"));
                assertTrue(ALLOWED_ANGLES.contains(angle), elementName + " rotation angle " + angle);
            }
            for (Map.Entry<String, Object> face : object(fields.get("faces")).entrySet()) {
                Map<String, Object> faceFields = object(face.getValue());
                String textureKey = String.valueOf(faceFields.get("texture")).substring(1);
                assertTrue(textures.containsKey(textureKey), elementName + "." + face.getKey() + " texture #" + textureKey);
                for (Object uv : array(faceFields.get("uv"))) {
                    double value = number(uv);
                    assertTrue(value >= 0.0 && value <= BLOCK, elementName + "." + face.getKey() + " uv " + value);
                }
            }
        }
    }

    @Test
    void retractedPartsStayInsideTheAisleBlock() throws IOException {
        for (String name : AISLE_PARTS) {
            for (Box box : boxes(name)) {
                assertTrue(box.min(X) >= 0.0F && box.max(X) <= BLOCK, box + " x within the aisle block");
                assertTrue(box.min(Z) >= 0.0F && box.max(Z) <= BLOCK, box + " z within the aisle block");
            }
        }
        // Parts above the dock level must not share the plane of a controller's face behind the dock.
        for (String name : List.of("mast_segment", "mast_top", "hoist_belt", "carriage"))
            assertTrue(max(boxes(name), Z) < BLOCK, name + " keeps off the block boundary behind the crane");
    }

    @Test
    void grabberStopsAtTheInventoryAtFullReach() throws IOException {
        float front = max(boxes("grabber"), X);
        assertEquals(CraneModelLayout.GRABBER_FRONT_PX, front, EPSILON, "grabber front face");
        float reached = front + (float) CraneModelLayout.innerStageOffset(1.0) * BLOCK;
        assertTrue(reached < INVENTORY_FACE_PX && reached >= INVENTORY_FACE_PX - MAX_GRABBER_GAP_PX,
                "fully extended grabber front " + reached + " stops just before the inventory at " + INVENTORY_FACE_PX);
    }

    @Test
    void wheelsStandOnTheRailHead() throws IOException {
        float railTop = 0.0F;
        for (Object element : array(read(RAIL_MODEL).get("elements")))
            railTop = Math.max(railTop, (float) number(array(object(element).get("to")).get(Y)));
        assertEquals(railTop, CraneModelLayout.RAIL_TOP_PX, EPSILON, "rail top of warehouse_rail");
        Box disc = boxes("wheel").stream().filter(box -> !box.rotated() && box.name().equals("disc")).findFirst()
                .orElseThrow();
        assertEquals(CraneModelLayout.WHEEL_RADIUS_PX, disc.max(Y) - CraneModelLayout.BLOCK_CENTER_PX, EPSILON,
                "wheel radius");
        assertEquals(CraneModelLayout.RAIL_TOP_PX, CraneModelLayout.WHEEL_AXLE_Y_PX - CraneModelLayout.WHEEL_RADIUS_PX,
                EPSILON, "wheels touch the rail");
        assertTrue(CraneModelLayout.FRONT_WHEEL_Z_PX - CraneModelLayout.WHEEL_RADIUS_PX >= 0.0F
                && CraneModelLayout.REAR_WHEEL_Z_PX + CraneModelLayout.WHEEL_RADIUS_PX <= BLOCK, "wheels under the chassis");
        assertTrue(CraneModelLayout.WHEEL_AXLE_Y_PX + CraneModelLayout.WHEEL_RADIUS_PX <= CraneModelLayout.MAST_BASE_Y_PX,
                "the rear wheel stays below the mast");
    }

    /**
     * The wheels turn about their axles, so every corner of every wheel element (also the disc turned by 45°, which turns
     * about the block centre) sweeps a circle: it must stay below the chassis top and inside the chassis ends at every
     * angle, or it pokes through the chassis or shares the chassis top plane.
     */
    @Test
    void wheelsStayInsideTheChassisAtEveryAngle() throws IOException {
        float reach = 0.0F;
        for (Box box : boxes("wheel")) {
            for (float y : new float[] {box.min(Y), box.max(Y)}) {
                for (float z : new float[] {box.min(Z), box.max(Z)})
                    reach = Math.max(reach, (float) Math.hypot(y - CraneModelLayout.BLOCK_CENTER_PX,
                            z - CraneModelLayout.BLOCK_CENTER_PX));
            }
        }
        Box chassis = boxes("base").stream().filter(box -> box.name().equals("chassis")).findFirst().orElseThrow();
        float top = CraneModelLayout.WHEEL_AXLE_Y_PX + reach;
        assertTrue(top < CraneModelLayout.CHASSIS_TOP_PX - EPSILON,
                "turned wheels reach " + top + " px, below the chassis top " + CraneModelLayout.CHASSIS_TOP_PX);
        assertTrue(CraneModelLayout.FRONT_WHEEL_Z_PX - reach >= chassis.min(Z) - EPSILON,
                "the turned front wheel stays behind the chassis front");
        assertTrue(CraneModelLayout.REAR_WHEEL_Z_PX + reach <= chassis.max(Z) + EPSILON,
                "the turned rear wheel stays in front of the chassis back");
    }

    @Test
    void carriageRestsOnTheChassisAndHangsOnTheMast() throws IOException {
        assertEquals(CraneModelLayout.CHASSIS_TOP_PX, max(boxes("base"), Y), EPSILON, "chassis top");
        assertEquals(CraneModelLayout.CHASSIS_TOP_PX, min(boxes("carriage"), Y), EPSILON, "carriage bottom at level 0");
        assertEquals(CraneModelLayout.CHASSIS_TOP_PX, CraneModelLayout.MAST_BASE_Y_PX, EPSILON, "mast on the chassis");
        assertEquals(0.0F, min(boxes("mast_segment"), Y), EPSILON, "mast segment bottom");
        assertEquals(BLOCK, max(boxes("mast_segment"), Y), EPSILON, "mast segment is one block");
        assertEquals(BLOCK, max(boxes("hoist_belt"), Y) - min(boxes("hoist_belt"), Y), EPSILON, "belt piece is one block");
        assertEquals(0.0F, min(boxes("mast_top"), Y), EPSILON, "mast cap bottom");
        List<Box> carriage = boxes("carriage");
        assertTrue(CraneModelLayout.CARRIAGE_TOP_Y_PX > min(carriage, Y) && CraneModelLayout.CARRIAGE_TOP_Y_PX < max(carriage, Y),
                "the belt starts inside the carriage");
        assertTrue(max(carriage, Y) <= BLOCK, "carriage fits in its level");
        // The guide frame wraps the mast at every lift height: compare with a mast segment at the part's height.
        for (Box part : carriage) {
            for (Box mast : boxes("mast_segment")) {
                Box level = mast.moved(0.0F, part.min(Y) - mast.min(Y), 0.0F);
                assertFalse(part.overlaps(level), part + " is not inside " + mast);
            }
        }
    }

    @Test
    void armPartsNeverIntersectAtAnyExtension() throws IOException {
        List<Box> carriage = boxes("carriage");
        for (double extension : EXTENSIONS) {
            float outer = (float) CraneModelLayout.outerStageOffset(extension) * BLOCK;
            float inner = (float) CraneModelLayout.innerStageOffset(extension) * BLOCK;
            List<Box> moving = new ArrayList<>();
            boxes("arm_outer").forEach(box -> moving.add(box.moved(outer, 0.0F, 0.0F)));
            boxes("arm_inner").forEach(box -> moving.add(box.moved(inner, 0.0F, 0.0F)));
            boxes("grabber").forEach(box -> moving.add(box.moved(inner, 0.0F, 0.0F)));
            for (int i = 0; i < moving.size(); i++) {
                Box part = moving.get(i);
                for (Box fixed : carriage)
                    assertFalse(part.overlaps(fixed), part + " inside " + fixed + " at extension " + extension);
                for (int j = i + 1; j < moving.size(); j++) {
                    Box other = moving.get(j);
                    if (!other.model().equals(part.model()))
                        assertFalse(part.overlaps(other), part + " inside " + other + " at extension " + extension);
                }
            }
        }
        assertTrue(min(carriage, Y) >= max(boxes("base"), Y), "the carriage at level 0 is above the chassis");
    }

    /**
     * No two parts have faces that point the same way and overlap in one plane (z-fighting): the parked wheels against the
     * chassis, the hoist belt against the carriage's belt clamp, the carriage against the chassis and the mast, and the
     * arm parts against the carriage and each other at every extension.
     */
    @Test
    void noPartsShareAFacePlane() throws IOException {
        List<Box> base = boxes("base");
        List<Box> wheels = new ArrayList<>();
        for (float axle : new float[] {CraneModelLayout.FRONT_WHEEL_Z_PX, CraneModelLayout.REAR_WHEEL_Z_PX})
            wheels.addAll(moved(boxes("wheel"), 0.0F, CraneModelLayout.WHEEL_AXLE_Y_PX - CraneModelLayout.BLOCK_CENTER_PX,
                    axle - CraneModelLayout.BLOCK_CENTER_PX));
        assertNoSharedFacePlanes(base, wheels);
        List<Box> carriage = boxes("carriage");
        assertNoSharedFacePlanes(carriage, moved(boxes("hoist_belt"), 0.0F, CraneModelLayout.CARRIAGE_TOP_Y_PX, 0.0F));
        assertNoSharedFacePlanes(carriage, base);
        assertNoSharedFacePlanes(carriage, moved(boxes("mast_segment"), 0.0F, CraneModelLayout.MAST_BASE_Y_PX, 0.0F));
        for (double extension : EXTENSIONS) {
            float outer = (float) CraneModelLayout.outerStageOffset(extension) * BLOCK;
            float inner = (float) CraneModelLayout.innerStageOffset(extension) * BLOCK;
            List<Box> outerStage = moved(boxes("arm_outer"), outer, 0.0F, 0.0F);
            List<Box> innerStage = moved(boxes("arm_inner"), inner, 0.0F, 0.0F);
            List<Box> grabber = moved(boxes("grabber"), inner, 0.0F, 0.0F);
            for (List<Box> part : List.of(outerStage, innerStage, grabber))
                assertNoSharedFacePlanes(carriage, part);
            assertNoSharedFacePlanes(outerStage, innerStage);
            assertNoSharedFacePlanes(outerStage, grabber);
            assertNoSharedFacePlanes(innerStage, grabber);
        }
    }

    /**
     * The arm enters interfaces and stations through the port recess on their aisle side ({@value #PORT_DEPTH_PX} px deep):
     * within that depth, no stage, grabber or flat item touches an element of the block at any extension, so the arm goes
     * into an opening instead of through a solid face; beyond it the parts are hidden inside the block. Block items stand
     * {@code BLOCK_ITEM_SIZE_PX · ITEM_SCALE} px tall on the stage and pass the port's top edge (accepted,
     * {@code docs/stacker-crane.md} §7.1), so they are not checked.
     */
    @Test
    void armPassesThroughTheMemberPorts() throws IOException {
        float outerReach = (float) CraneModelLayout.outerStageOffset(1.0) * BLOCK;
        float innerReach = (float) CraneModelLayout.innerStageOffset(1.0) * BLOCK;
        List<Box> swept = new ArrayList<>();
        boxes("arm_outer").forEach(box -> swept.add(sweptAlongArm(box, outerReach)));
        boxes("arm_inner").forEach(box -> swept.add(sweptAlongArm(box, innerReach)));
        boxes("grabber").forEach(box -> swept.add(sweptAlongArm(box, innerReach)));
        float flatWidth = BLOCK * CraneModelLayout.ITEM_SCALE;
        float flatHeight = CraneModelLayout.FLAT_ITEM_THICKNESS_PX * CraneModelLayout.ITEM_SCALE;
        for (float x : CraneModelLayout.ITEM_SLOT_X_PX) {
            for (int copy = 0; copy < FLAT_ITEM_COPIES; copy++) {
                Box item = itemBox("flat item " + x + "/" + copy, x, flatWidth, flatHeight)
                        .moved(0.0F, copy * CraneModelLayout.FLAT_ITEM_STACK_PX, 0.0F);
                swept.add(sweptAlongArm(item, innerReach));
            }
        }
        // The interface's aisle side faces south in its model (port north); the stations' aisle opening faces north.
        assertPortClear(memberBoxes("warehouse_interface"), true, swept);
        assertPortClear(memberBoxes("warehouse_input"), false, swept);
        assertPortClear(memberBoxes("warehouse_output"), false, swept);
        assertPortClear(memberBoxes("warehouse_production"), false, swept);
        // The terminal's port lives in the shell the multipart blockstate puts on the aisle side, not in its block
        // model: the core carries no port, because any of the four faces can be the aisle side (ADR-022).
        assertPortClear(terminalBoxes("shell_intake"), false, swept);
    }

    /**
     * The terminal is drawn as a core column plus four 3 px shells, one per horizontal face, chosen by a multipart
     * blockstate (ADR-022). Nothing at load time checks that the twelve states tile the block, so it is checked here:
     * every shell is the same pinwheel strip (its 90° rotations tile the ring around the core without overlapping),
     * no two boxes of one shell share a volume, and core plus four shells fill the whole block except the openings
     * each shell declares — the arm port the crane reaches through (the opening the arm is swept through above), the
     * recessed display and the take-out tray of the screen side, and the recessed machine panel of the plain sides.
     */
    @Test
    void terminalShellsTileTheBlockAroundTheArmPort() throws IOException {
        List<Box> core = terminalBoxes("block");
        assertEquals(1, core.size(), "the terminal core is one box");
        Box column = core.getFirst();
        for (int axis : new int[] {X, Z}) {
            assertEquals(TERMINAL_CORE_MIN_PX, column.min(axis), EPSILON, "core min on axis " + axis);
            assertEquals(TERMINAL_CORE_MAX_PX, column.max(axis), EPSILON, "core max on axis " + axis);
        }
        assertEquals(0.0F, column.min(Y), EPSILON, "core bottom");
        assertEquals(BLOCK, column.max(Y), EPSILON, "core top");

        Map<String, List<Box>> openings = terminalOpenings();
        float shellVolume = TERMINAL_SHELL_WIDTH_PX * BLOCK * TERMINAL_SHELL_DEPTH_PX;
        float filled = volume(core);
        float plainVolume = 0.0F;
        float openVolume = 0.0F;
        for (String shell : TERMINAL_SHELLS) {
            List<Box> boxes = terminalBoxes(shell);
            assertFalse(boxes.isEmpty(), shell + " has elements");
            for (int i = 0; i < boxes.size(); i++) {
                Box box = boxes.get(i);
                assertTrue(box.min(X) >= 0.0F && box.max(X) <= TERMINAL_SHELL_WIDTH_PX + EPSILON,
                        box + " stays inside the pinwheel strip across the face");
                assertTrue(box.min(Y) >= 0.0F && box.max(Y) <= BLOCK, box + " stays inside the block height");
                assertTrue(box.min(Z) >= 0.0F && box.max(Z) <= TERMINAL_SHELL_DEPTH_PX + EPSILON,
                        box + " stays inside the shell depth");
                for (int j = i + 1; j < boxes.size(); j++)
                    assertFalse(box.overlaps(boxes.get(j)), box + " inside " + boxes.get(j));
                for (Box opening : openings.get(shell))
                    assertFalse(box.overlaps(opening), box + " reaches into " + opening);
            }
            float open = volume(openings.get(shell));
            assertEquals(shellVolume - open, volume(boxes), EPSILON, shell + " fills its strip except its openings");
            filled += volume(boxes);
            openVolume += open;
            if (shell.equals("shell_plain")) {
                plainVolume = volume(boxes);
                openVolume += open;
            }
        }
        // Every state shows one display shell, one intake shell and *two* plain ones, so the plain strip and its
        // opening count twice: core plus those four fill the whole block except the openings.
        assertEquals(BLOCK * BLOCK * BLOCK - openVolume, filled + plainVolume, EPSILON,
                "core and the four shells of a state fill the terminal except its openings");
    }

    /**
     * Every opening of every shell is centred on the 16 px block face, not on the shell's own 13 px strip. A shell
     * covers only x 0..13 — the last 3 px belong to the next shell of the pinwheel — so centring means an asymmetric
     * frame (4 px on one side, 1 px plus the neighbour's 3 px on the other), which {@code shell_display} and
     * {@code shell_intake} always had and which the plain shell was missing (M10 review fix): its panel sat 1.5 px
     * off-centre, so two of the four faces of every terminal did not line up with the other two.
     */
    @Test
    void everyTerminalOpeningIsCentredOnTheBlockFace() {
        for (Map.Entry<String, List<Box>> shell : terminalOpenings().entrySet()) {
            for (Box opening : shell.getValue())
                assertEquals(TERMINAL_OPENING_CENTER_PX, (opening.min(X) + opening.max(X)) / 2.0F, EPSILON,
                        shell.getKey() + "'s " + opening.name() + " is centred on the block face");
        }
    }

    /**
     * The hand-made {@code item.json} is a <b>bake of the same shells</b> the block uses: the core plus the four shells
     * of one state, written out at their turned positions because vanilla element rotations only allow 22.5 and 45
     * degrees (ADR-022). Nothing at load time ties the two together, and the look pass already had to re-bake the file
     * by hand once, so the expected geometry is derived here from the shells themselves — each turned a quarter around
     * the block's vertical axis, display → plain → intake → plain. Without this the held item could silently show a
     * different shape from the placed block (M10 review fix).
     */
    @Test
    void theTerminalItemModelIsTheSameShellsTurnedAroundTheBlock() throws IOException {
        List<Box> expected = new ArrayList<>(terminalBoxes("block"));
        for (int quarter = 0; quarter < TERMINAL_SHELLS_CLOCKWISE.size(); quarter++) {
            for (Box box : terminalBoxes(TERMINAL_SHELLS_CLOCKWISE.get(quarter)))
                expected.add(turnedAroundTheBlock(box, quarter));
        }
        assertEquals(extents(expected), extents(terminalBoxes("item")),
                "item.json must be the core plus the four shells of one state, each at its turned position");
    }

    /**
     * {@code box} of a north-authored shell, turned {@code quarters} times clockwise around the block's vertical axis
     * seen from above: {@code (x, z) -> (16 - z, x)}, the mapping the multipart blockstate's {@code rotationY} applies
     * at runtime ({@code WareworksBlockStateGen#rotationOnto}).
     */
    private static Box turnedAroundTheBlock(Box box, int quarters) {
        float[] from = box.from().clone();
        float[] to = box.to().clone();
        for (int quarter = 0; quarter < quarters; quarter++) {
            float[] turnedFrom = {BLOCK - to[Z], from[Y], from[X]};
            float[] turnedTo = {BLOCK - from[Z], to[Y], to[X]};
            from = turnedFrom;
            to = turnedTo;
        }
        return new Box(box.model(), box.name(), from, to, box.rotated(), box.faces());
    }

    /** The boxes as a sorted list of "from -> to", so two models compare as sets of shapes rather than of names. */
    private static List<String> extents(List<Box> boxes) {
        List<String> result = new ArrayList<>(boxes.size());
        for (Box box : boxes)
            result.add(Arrays.toString(box.from()) + " -> " + Arrays.toString(box.to()));
        result.sort(String::compareTo);
        return result;
    }

    /**
     * What each terminal shell deliberately leaves open, in that shell's own model frame. Every opening starts at the
     * outer face ({@code z = 0}), because all three are things a player or the crane's arm reaches into.
     */
    private static Map<String, List<Box>> terminalOpenings() {
        return Map.of(
                "shell_intake", List.of(terminalOpening("shell_intake", "arm port", TERMINAL_PORT_FROM,
                        TERMINAL_PORT_TO)),
                "shell_display", List.of(
                        terminalOpening("shell_display", "screen recess", TERMINAL_SCREEN_FROM, TERMINAL_SCREEN_TO),
                        terminalOpening("shell_display", "take-out tray", TERMINAL_TRAY_FROM, TERMINAL_TRAY_TO)),
                "shell_plain", List.of(terminalOpening("shell_plain", "machine panel", TERMINAL_PANEL_FROM,
                        TERMINAL_PANEL_TO)));
    }

    private static Box terminalOpening(String shell, String name, float[] from, float[] to) {
        return new Box("warehouse_terminal/" + shell, name, from.clone(), to.clone(), false, Set.of());
    }

    private static float volume(List<Box> boxes) {
        float total = 0.0F;
        for (Box box : boxes)
            total += (box.max(X) - box.min(X)) * (box.max(Y) - box.min(Y)) * (box.max(Z) - box.min(Z));
        return total;
    }

    /** {@code box} moved from its retracted position to {@code reach} pixels along the arm (+X), as one volume. */
    private static Box sweptAlongArm(Box box, float reach) {
        return new Box(box.model(), box.name(), box.from().clone(),
                new float[] {box.max(X) + reach, box.max(Y), box.max(Z)}, box.rotated(), box.faces());
    }

    /**
     * Fails if a swept arm volume (crane frame: +X into the rack block, which starts at x = 16; Z across the aisle) touches
     * an element of a member block within its port depth. The member stands in the rack block with its aisle side on the
     * south ({@code aisleSouth}) or north face of its model; across the aisle both mirror images are checked.
     */
    private static void assertPortClear(List<Box> member, boolean aisleSouth, List<Box> swept) {
        for (Box arm : swept) {
            float depthFrom = arm.min(X) - BLOCK;
            float depthTo = Math.min(arm.max(X) - BLOCK, PORT_DEPTH_PX);
            if (depthTo - Math.max(depthFrom, 0.0F) <= EPSILON)
                continue;
            depthFrom = Math.max(depthFrom, 0.0F);
            float zFrom = aisleSouth ? BLOCK - depthTo : depthFrom;
            float zTo = aisleSouth ? BLOCK - depthFrom : depthTo;
            for (boolean mirrored : new boolean[] {false, true}) {
                float lateralFrom = mirrored ? BLOCK - arm.max(Z) : arm.min(Z);
                float lateralTo = mirrored ? BLOCK - arm.min(Z) : arm.max(Z);
                Box inMember = new Box(arm.model(), arm.name(), new float[] {lateralFrom, arm.min(Y), zFrom},
                        new float[] {lateralTo, arm.max(Y), zTo}, false, arm.faces());
                for (Box element : member)
                    assertFalse(inMember.overlaps(element), arm + " touches " + element + " inside the port");
            }
        }
    }

    private static List<Box> moved(List<Box> boxes, float dx, float dy, float dz) {
        List<Box> result = new ArrayList<>(boxes.size());
        boxes.forEach(box -> result.add(box.moved(dx, dy, dz)));
        return result;
    }

    /**
     * Fails if a face of {@code first} and a face of {@code second} point the same way, lie in one plane and overlap there
     * (z-fighting). Faces pointing at each other in one plane are fine: back-face culling shows only one of them.
     */
    private static void assertNoSharedFacePlanes(List<Box> first, List<Box> second) {
        for (Box a : first) {
            for (Box b : second) {
                if (a.rotated() || b.rotated())
                    continue;
                for (Map.Entry<String, FacePlane> face : FACE_PLANES.entrySet()) {
                    if (!a.faces().contains(face.getKey()) || !b.faces().contains(face.getKey()))
                        continue;
                    int axis = face.getValue().axis();
                    float planeA = face.getValue().upper() ? a.max(axis) : a.min(axis);
                    float planeB = face.getValue().upper() ? b.max(axis) : b.min(axis);
                    if (Math.abs(planeA - planeB) > EPSILON)
                        continue;
                    boolean overlapping = true;
                    for (int other = X; other <= Z; other++) {
                        if (other != axis && Math.min(a.max(other), b.max(other))
                                - Math.max(a.min(other), b.min(other)) <= EPSILON)
                            overlapping = false;
                    }
                    assertFalse(overlapping, a + " and " + b + " share the " + face.getKey() + " face plane at " + planeA);
                }
            }
        }
    }

    @Test
    void heldItemsLieOnTheInnerStage() throws IOException {
        List<Box> inner = boxes("arm_inner");
        assertEquals(CraneModelLayout.ITEM_REST_Y_PX, max(inner, Y), EPSILON, "items lie on the inner stage");
        float scale = CraneModelLayout.ITEM_SCALE;
        // A flat item lies on the stage (wide, thin); a block item stands on it as a small cube.
        float flatWidth = BLOCK * scale;
        float flatHeight = CraneModelLayout.FLAT_ITEM_THICKNESS_PX * scale;
        float blockSize = CraneModelLayout.BLOCK_ITEM_SIZE_PX * scale;
        float[] slots = CraneModelLayout.ITEM_SLOT_X_PX;
        for (int slot = 0; slot < slots.length; slot++) {
            float x = slots[slot];
            assertTrue(x > min(inner, X) && x < max(inner, X), "item slot " + slot + " on the stage");
            Box flat = itemBox("flat item " + slot, x, flatWidth, flatHeight);
            Box block = itemBox("block item " + slot, x, blockSize, blockSize);
            for (Box part : boxes("grabber")) {
                assertFalse(flat.overlaps(part), flat + " inside " + part);
                assertFalse(block.overlaps(part), block + " inside " + part);
            }
            for (int other = slot + 1; other < slots.length; other++)
                assertTrue(Math.abs(x - slots[other]) >= flatWidth, "flat items in slots " + slot + " and " + other);
        }
    }

    /** The volume of an item model centred on {@code x} across the arm, lying on the inner stage. */
    private static Box itemBox(String name, float x, float width, float height) {
        float half = width / 2.0F;
        float y = CraneModelLayout.ITEM_REST_Y_PX;
        float z = CraneModelLayout.ITEM_Z_PX;
        return new Box("items", name, new float[] {x - half, y, z - half}, new float[] {x + half, y + height, z + half},
                false, Set.of());
    }

    @Test
    void poseMath() {
        assertEquals(CraneModelLayout.ARM_REACH_BLOCKS, CraneModelLayout.innerStageOffset(1.0), 1.0E-9);
        assertEquals(CraneModelLayout.innerStageOffset(0.6) * CraneModelLayout.OUTER_STAGE_SHARE,
                CraneModelLayout.outerStageOffset(0.6), 1.0E-9);
        assertEquals(0.0, CraneModelLayout.innerStageOffset(0.0), 1.0E-9);
        assertEquals(-BLOCK / CraneModelLayout.WHEEL_RADIUS_PX, CraneModelLayout.wheelAngle(1.0), 1.0E-6,
                "one block of travel turns the wheel by its arc");
        assertEquals(4.5, CraneModelLayout.mastTopY(4), 1.0E-9);
        assertEquals(4.5 - 15.0 / 16.0, CraneModelLayout.hoistBeltLength(0.0, 4), 1.0E-9);
        for (int height = 1; height <= 64; height++)
            assertTrue(CraneModelLayout.hoistBeltLength(height - 1, height) > 0.0, "belt at the top level of mast " + height);
        assertEquals(0.0, CraneModelLayout.hoistBeltLength(10.0, 4), 1.0E-9, "never negative");
    }

    // --- model reading ------------------------------------------------------------------------------------------------

    private static List<Box> boxes(String name) throws IOException {
        return boxes(MODELS.resolve(name + ".json"), name);
    }

    /** The elements of another block's model, {@code models/block/<block>/block.json}. */
    private static List<Box> memberBoxes(String block) throws IOException {
        return boxes(BLOCK_MODELS.resolve(block).resolve("block.json"), block);
    }

    /** The elements of one of the warehouse terminal's models, {@code models/block/warehouse_terminal/<file>.json}. */
    private static List<Box> terminalBoxes(String file) throws IOException {
        return boxes(TERMINAL_MODELS.resolve(file + ".json"), "warehouse_terminal/" + file);
    }

    private static List<Box> boxes(Path path, String name) throws IOException {
        List<Box> result = new ArrayList<>();
        for (Object element : array(read(path).get("elements"))) {
            Map<String, Object> fields = object(element);
            boolean rotated = fields.containsKey("rotation") && number(object(fields.get("rotation")).get("angle")) != 0.0;
            result.add(new Box(name, String.valueOf(fields.get("name")), vector(fields.get("from")), vector(fields.get("to")),
                    rotated, Set.copyOf(object(fields.get("faces")).keySet())));
        }
        return result;
    }

    private static float min(List<Box> boxes, int axis) {
        return (float) boxes.stream().filter(box -> !box.rotated()).mapToDouble(box -> box.min(axis)).min().orElseThrow();
    }

    private static float max(List<Box> boxes, int axis) {
        return (float) boxes.stream().filter(box -> !box.rotated()).mapToDouble(box -> box.max(axis)).max().orElseThrow();
    }

    private static float[] vector(Object value) {
        List<Object> list = array(value);
        return new float[] {(float) number(list.get(X)), (float) number(list.get(Y)), (float) number(list.get(Z))};
    }

    private static Map<String, Object> read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "missing model " + path);
        return object(new JsonReader(Files.readString(path, StandardCharsets.UTF_8)).readDocument());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        assertTrue(value instanceof Map, "JSON object expected: " + value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        assertTrue(value instanceof List, "JSON array expected: " + value);
        return (List<Object>) value;
    }

    private static double number(Object value) {
        assertTrue(value instanceof Double, "JSON number expected: " + value);
        return (Double) value;
    }

    /** Minimal JSON reader for the model files: objects, arrays, strings (simple escapes), numbers, booleans, null. */
    private static final class JsonReader {
        private final String text;
        private int position;

        JsonReader(String text) {
            this.text = text;
        }

        Object readDocument() {
            Object value = readValue();
            skipWhitespace();
            if (position != text.length())
                throw new IllegalArgumentException("trailing content at " + position);
            return value;
        }

        private Object readValue() {
            skipWhitespace();
            char next = peek();
            return switch (next) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f', 'n' -> readLiteral();
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue());
                skipWhitespace();
                if (peek() == ',') {
                    position++;
                    continue;
                }
                expect('}');
                return result;
            }
        }

        private List<Object> readArray() {
            List<Object> result = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (peek() == ',') {
                    position++;
                    continue;
                }
                expect(']');
                return result;
            }
        }

        private String readString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (peek() != '"') {
                char c = text.charAt(position++);
                if (c == '\\') {
                    char escaped = text.charAt(position++);
                    result.append(switch (escaped) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> escaped;
                    });
                } else {
                    result.append(c);
                }
            }
            position++;
            return result.toString();
        }

        private Object readLiteral() {
            for (Map.Entry<String, Object> literal : Map.<String, Object>of("true", Boolean.TRUE, "false", Boolean.FALSE)
                    .entrySet()) {
                if (text.startsWith(literal.getKey(), position)) {
                    position += literal.getKey().length();
                    return literal.getValue();
                }
            }
            if (text.startsWith("null", position)) {
                position += "null".length();
                return null;
            }
            throw new IllegalArgumentException("unexpected literal at " + position);
        }

        private Double readNumber() {
            int start = position;
            while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0)
                position++;
            if (start == position)
                throw new IllegalArgumentException("unexpected character '" + peek() + "' at " + position);
            return Double.parseDouble(text.substring(start, position));
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position)))
                position++;
        }

        private char peek() {
            if (position >= text.length())
                throw new IllegalArgumentException("unexpected end of JSON");
            return text.charAt(position);
        }

        private void expect(char expected) {
            if (peek() != expected)
                throw new IllegalArgumentException("expected '" + expected + "' at " + position + " but found '" + peek() + "'");
            position++;
        }
    }
}
