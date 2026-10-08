package dev.wareworks.client.render;

import static dev.wareworks.client.render.ModelJson.array;
import static dev.wareworks.client.render.ModelJson.number;
import static dev.wareworks.client.render.ModelJson.object;
import static dev.wareworks.client.render.ModelJson.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

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
 * Reads the model JSON files with {@link ModelJson} (the test classpath has no JSON library); runs without Minecraft.
 * That every referenced Create texture exists is checked by {@code runClient} (missing texture warnings) and the visual
 * smoke test — except for the rack bay and the pallet, whose textures this test opens itself to check what their UVs
 * actually sample ({@link #theRackIsCutFromTexelsThatAreReallyThere}).
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
    /** Texture key the two warehouse port models differ in: brass for a requesting port, andesite for an accepting one. */
    private static final String PORT_ACCENT = "accent";
    /**
     * The surfaces that carry the port's direction cue: the ring around the aisle opening, seen standing <b>in</b> the
     * aisle, and the spout on the back, seen from outside where the funnel is.
     */
    private static final Set<String> PORT_ACCENT_FACES = Set.of("aisle_frame_top/north", "aisle_frame_bottom/north",
            "aisle_frame_west/north", "aisle_frame_east/north", "port_spout/south", "port_spout/east", "port_spout/west");
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
    /** The three rack bays, which are the same rack in three materials ({@code docs/warehouse-system.md} §3.8). */
    private static final List<String> RACK_BAY_TIERS =
            List.of("rack_bay_wood", "rack_bay_andesite", "rack_bay_brass");
    /** The frame texture of each tier, in the order of {@link #RACK_BAY_TIERS}: the one thing the three differ in. */
    private static final List<String> RACK_BAY_FRAMES = List.of("create:block/bracket_plate_wooden",
            "create:block/andesite_block", "create:block/brass_block");
    /** Texture keys a rack bay's tier decides; everything else about the three models has to be identical. */
    private static final Set<String> RACK_BAY_TIER_TEXTURES = Set.of("frame", "particle");
    /**
     * The upright frame a bay's blockstate stands at each end of a rack wall (M29 step 12), and the half of one that
     * two joined bays share. Both are per tier, like the shell, because an upright is made of the bay's own material.
     */
    private static final List<String> RACK_BAY_UPRIGHTS = List.of("upright", "upright_half");
    /** How wide an upright is, and half of it: two halves on a seam have to make exactly one upright. */
    private static final float RACK_BAY_UPRIGHT_PX = 3.0F;
    private static final float RACK_BAY_UPRIGHT_HALF_PX = 1.5F;
    /** The thin skin that closes a bay at the back; the one element the arm is allowed to reach into. */
    private static final String RACK_BAY_BACK = "back";
    private static final float RACK_BAY_BACK_DEPTH_PX = 2.0F;
    /** The window the arm reaches through, in the aisle face of every member: x 4..12, y 9..13. */
    private static final float PORT_WINDOW_MIN_X_PX = 4.0F;
    private static final float PORT_WINDOW_MAX_X_PX = 12.0F;
    private static final float PORT_WINDOW_MIN_Y_PX = 9.0F;
    private static final float PORT_WINDOW_MAX_Y_PX = 13.0F;
    /** Half a turn around the block's vertical axis: what turns the left upright into the right one. */
    private static final int HALF_TURN_QUARTERS = 2;
    /** Where {@code runData} writes the blockstates this test reads back. */
    private static final Path GENERATED_BLOCKSTATES =
            Path.of("src/generated/resources/assets/wareworks/blockstates");
    /** The load models all three tiers share, drawn by the multipart blockstate from {@code RackBayBlock.FILL}. */
    private static final List<String> RACK_BAY_LOADS = List.of("load_1", "load_2", "load_base", "load_cap");
    /** Where those shared load models live: one folder for all three tiers, because goods are goods. */
    private static final Path RACK_BAY_LOAD_MODELS = BLOCK_MODELS.resolve("rack_bay");
    /** The element of a bay's shell that its load stands on. */
    private static final String RACK_BAY_PALLET = "pallet";
    /** The element of a bay's shell the pallet itself stands on: the load beam across the full width of the block. */
    private static final String RACK_BAY_BEAM = "deck";
    /** The window a rack bay's load is read through: inside these x/z pixels, so it never pokes out of the frame. */
    private static final float RACK_BAY_WINDOW_MIN_PX = 3.0F;
    private static final float RACK_BAY_WINDOW_MAX_PX = 13.0F;
    /** The pallet a load stands on is 3..4 px tall, so no load may reach below its top. */
    private static final float RACK_BAY_PALLET_TOP_PX = 4.0F;

    /** The two fluid bays, which are the same tank in two materials ({@code docs/warehouse-system.md} §3.9). */
    private static final List<String> FLUID_BAY_TIERS = List.of("fluid_bay_copper", "fluid_bay_brass");
    /** The frame texture of each of them, in that order: the one thing the two models differ in. */
    private static final List<String> FLUID_BAY_FRAMES =
            List.of("create:block/fluid_tank", "create:block/brass_block");
    /** The elements of a fluid bay's shell that are the vessel, as against the rack frame it stands in. */
    private static final Set<String> FLUID_BAY_VESSEL = Set.of("sump", "wall_west", "wall_east", "wall_back");
    /** The elements of a fluid bay's shell that are the rack around it, and are the rack bay's own, pixel for pixel. */
    private static final Set<String> FLUID_BAY_RACK = Set.of(RACK_BAY_BEAM, RACK_BAY_BACK);
    /** The rack bay whose frame a fluid bay has to match, because a wall of both shares its uprights (ADR-050). */
    private static final String FLUID_BAY_FRAME_REFERENCE = "rack_bay_brass";
    /** Fill levels the drawn fluid is checked at, as a share of the bay's capacity. */
    private static final double[] FLUID_FILL_SHARES = {0.0, 0.01, 0.1, 0.25, 0.5, 0.75, 0.999, 1.0};
    /** A capacity to measure the drawn fluid against: a copper bay's 64 buckets, in millibuckets. */
    private static final long FLUID_BAY_CAPACITY_MB = 64_000L;

    /** Textures of Create; everything but the one exception below must come from here (ADR-017). */
    private static final String CREATE_TEXTURES = "create:block/";
    /** Our own texture namespace: the terminal screen, generated by {@code scripts/gen_textures.py} (ADR-023). */
    private static final String OWN_TEXTURES = "wareworks:block/";
    private static final Path OWN_TEXTURE_FILES = Path.of("src/main/resources/assets/wareworks/textures/block");
    /** Our own model namespace, the only parent {@link #flattened} follows. */
    private static final String OWN_MODELS = "wareworks:block/";
    /** A model's parent chain is a handful of links at most; past this it is a cycle. */
    private static final int MAX_PARENT_DEPTH = 8;
    private static final char FILE_SEPARATOR = java.io.File.separatorChar;
    private static final String JSON_SUFFIX = ".json";
    /**
     * The block models that are drawn <b>outside</b> the solid layer, where an empty texel is a hole rather than
     * black, and which {@link #everyBlockModelIsCutFromTexelsThatAreReallyThere} therefore does not sweep. All three
     * are the crane's mast: the rack's teeth and the pulley belt's gaps are what those textures are for.
     */
    private static final Map<String, String> CUTOUT_BLOCK_MODELS = Map.of(
            "stacker_crane/hoist_belt", "minecraft:cutout",
            "stacker_crane/mast_segment", "minecraft:cutout",
            "stacker_crane/mast_top", "minecraft:cutout");
    /** Texture files already read by {@link #theRackIsCutFromTexelsThatAreReallyThere}, by their reference. */
    private static final Map<String, BufferedImage> TEXTURE_FILES = new HashMap<>();
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
        // The dock's own two hand-made models: the block a player places and the bake its item icon shows.
        for (String name : List.of("block", "item"))
            assertValidBlockModel(MODELS.resolve(name + ".json"), "stacker_crane/" + name);
        // The terminal's hand-made models are picked by a multipart blockstate and by the item model, never by
        // Registrate, so the same validity rules are checked here rather than at load time.
        for (String name : List.of("block", "item", "shell_display", "shell_intake", "shell_plain"))
            assertValidBlockModel(TERMINAL_MODELS.resolve(name + ".json"), "warehouse_terminal/" + name);
        // The rack bays (M28 step 9): their shells are picked by a multipart blockstate and their load models by its
        // fill conditions, so, like the terminal's, nothing at load time checks that they are valid block models with
        // Create textures.
        for (String tier : RACK_BAY_TIERS) {
            for (String part : List.of("block", "item", "upright", "upright_half"))
                assertValidBlockModel(BLOCK_MODELS.resolve(tier).resolve(part + ".json"), tier + "/" + part);
        }
        for (String load : RACK_BAY_LOADS)
            assertValidBlockModel(RACK_BAY_LOAD_MODELS.resolve(load + ".json"), "rack_bay/" + load);
        // The fluid bays (M30 step 5): the same three models per tier, picked by the same kind of multipart
        // blockstate, so nothing at load time checks them either.
        for (String tier : FLUID_BAY_TIERS) {
            for (String part : List.of("block", "item", "upright", "upright_half"))
                assertValidBlockModel(BLOCK_MODELS.resolve(tier).resolve(part + ".json"), tier + "/" + part);
        }
        // The pallet's deck is reached through a PartialModel from an entity renderer, which no blockstate names at
        // all, so this is the only place it is checked (M28 step 9, WareworksPartialModels#PALLET_DECK).
        assertValidBlockModel(BLOCK_MODELS.resolve("pallet").resolve("deck.json"), "pallet/deck");
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

    /**
     * <b>The turn sweep.</b> Since M21 the machine turns corners (ADR-033): the whole tower rotates about the centre of
     * the block it stands on, so every corner of every part travels a circle of its own distance from that centre.
     * Half a block is 8 px and the blocks beside a corner are rack positions holding a player's chests, so a part that
     * reaches further than {@link CraneModelLayout#TURN_SWEEP_RADIUS_PX} visibly cuts through them for the few ticks
     * the swing takes.
     * <p>
     * Checked for every part that stands in the aisle block with the arm retracted — which is every part there is
     * while a turn runs, because {@code CraneMotion} pulls the arm in before anything else moves — plus the wheels at
     * both axles (the disc turned by 45° reaches {@code √2} times as far in Z) and the drive cog, which is Create's
     * model and is therefore measured from the layout constants rather than from a file.
     * <p>
     * This is the invariant the M21 model pass exists for: the chassis, the bogies, the buffers and the mast all came
     * in from the block edge to satisfy it. Without it a later edit could widen any of them by a pixel and nobody
     * would notice until a chest looked chewed.
     */
    @Test
    void theMachineSweepStaysInsideItsBlockWhileItTurns() throws IOException {
        float limit = CraneModelLayout.TURN_SWEEP_RADIUS_PX;
        for (String name : AISLE_PARTS) {
            for (Box box : boxes(name))
                assertSweepWithin(box, limit);
        }
        for (Box wheel : placedWheels())
            assertSweepWithin(wheel, limit);
        assertTrue(CraneModelLayout.driveCogSweepRadiusPx() <= limit + EPSILON,
                "the drive cog sweeps " + CraneModelLayout.driveCogSweepRadiusPx() + " px, over the limit " + limit);
        // The carried items ride the same transform on the retracted stage, the second copy turned about its centre.
        for (Box item : heldItemBoxes())
            assertSweepWithin(item, limit);
    }

    /**
     * The dock's item model is a hand-made bake of the dock with its crane parked on it ({@code item.json}; nothing at
     * load time ties it to the animated parts). Every one of its elements above the rail head is a piece of that
     * machine, so all of them must fit the same sweep as the parts they stand for — which is what says the icon shows
     * the machine that really turns corners, and not the wider one from before the M21 model pass, which reached
     * 9.80 px and would not have fitted its own block.
     */
    @Test
    void theCraneBakedIntoTheDockItemIsTheMachineThatFitsItsBlock() throws IOException {
        int machineParts = 0;
        for (Box box : boxes(MODELS.resolve("item.json"), "stacker_crane/item")) {
            // Below the rail head is the dock's own rail bed, which is the block and may fill it.
            if (box.min(Y) < CraneModelLayout.RAIL_TOP_PX)
                continue;
            machineParts++;
            assertSweepWithin(box, CraneModelLayout.TURN_SWEEP_RADIUS_PX);
        }
        assertTrue(machineParts >= PARTIALS.size(),
                "the item bakes the whole machine, not only the dock: " + machineParts + " parts");
    }

    /**
     * <b>The rails the machine swings over.</b> The blocks around a corner along the aisles are rails, and a rail is
     * solid from the floor up to its head ({@link CraneModelLayout#RAIL_TOP_PX}) right across the block. So a part
     * that reaches below the rail head must not leave its own block while the machine turns, or it swings through the
     * bed of the next rail.
     * <p>
     * It holds for the simplest possible reason, and this test pins that reason rather than a number: the only parts
     * that reach down to the rail at all are the <b>wheels</b>, which stand on it and stay well inside the block at
     * every angle; everything else — chassis, bogies, buffers, mast, carriage, arm — sits at or above the rail head
     * and therefore sweeps through the air over the neighbouring rails, wherever the accepted graze of
     * {@link CraneModelLayout#TURN_SWEEP_RADIUS_PX} takes it.
     */
    @Test
    void nothingBelowTheRailHeadLeavesItsOwnBlockWhileItTurns() throws IOException {
        for (String name : AISLE_PARTS) {
            float base = lowestPlacedY(name);
            for (Box box : boxes(name))
                assertTrue(base + box.min(Y) >= CraneModelLayout.RAIL_TOP_PX - EPSILON,
                        box + " reaches down to " + (base + box.min(Y)) + " px, into the bed of a rail it swings over");
        }
        boolean touchesTheRail = false;
        for (Box wheel : placedWheels()) {
            // Half a block, not the accepted graze: down here the neighbour is not air but a rail.
            assertSweepWithin(wheel, CraneModelLayout.BLOCK_CENTER_PX);
            touchesTheRail |= wheel.min(Y) <= CraneModelLayout.RAIL_TOP_PX + EPSILON;
        }
        assertTrue(touchesTheRail, "the wheels are the parts that reach the rail");
    }

    /**
     * An extended arm may never turn with the machine: it reaches a whole block into a rack position, so swinging it
     * would drag the grabber and everything on it through two rack blocks and the rails between them. {@code
     * CraneMotion} pulls the arm in before anything else moves, and this says what that rule is worth — the swept
     * shape of an extended machine is nowhere near a block it could turn in, so the retracted machine really is the
     * only shape the sweep limit has to hold for.
     */
    @Test
    void anExtendedArmCouldNotPossiblyTurnWithTheMachine() throws IOException {
        float inner = (float) CraneModelLayout.innerStageOffset(1.0) * BLOCK;
        double reach = 0.0;
        for (Box box : boxes("grabber")) {
            Box extended = box.moved(inner, 0.0F, 0.0F);
            for (float x : new float[] {extended.min(X), extended.max(X)}) {
                for (float z : new float[] {extended.min(Z), extended.max(Z)})
                    reach = Math.max(reach, CraneModelLayout.sweepRadiusPx(x, z));
            }
        }
        assertTrue(reach > 2 * CraneModelLayout.TURN_SWEEP_RADIUS_PX, "a fully extended grabber sweeps " + reach
                + " px, far past the limit " + CraneModelLayout.TURN_SWEEP_RADIUS_PX + ": it must be in before a turn");
    }

    /** Both wheels where the renderer puts them, the 45° disc as the envelope it really occupies. */
    private static List<Box> placedWheels() throws IOException {
        List<Box> wheels = new ArrayList<>();
        float dy = CraneModelLayout.WHEEL_AXLE_Y_PX - CraneModelLayout.BLOCK_CENTER_PX;
        for (float axle : new float[] {CraneModelLayout.FRONT_WHEEL_Z_PX, CraneModelLayout.REAR_WHEEL_Z_PX}) {
            float dz = axle - CraneModelLayout.BLOCK_CENTER_PX;
            for (Box box : boxes("wheel")) {
                // The 45° disc turns about the X axis through the block centre, so its Z half-extent grows by √2.
                Box placed = box.rotated() ? turnedAboutTheAxle(box) : box;
                wheels.add(placed.moved(0.0F, dy, dz));
            }
        }
        return wheels;
    }

    /**
     * Where the renderer puts the bottom of a part, in pixels above the floor of the crane's block, at the lowest
     * carriage level: the mast stands on the chassis, the cap on the mast, the belt on the carriage, and the carriage
     * and arm models are authored at their level-0 height already.
     */
    private static float lowestPlacedY(String part) {
        return switch (part) {
            case "mast_segment" -> CraneModelLayout.MAST_BASE_Y_PX;
            case "mast_top" -> (float) CraneModelLayout.mastTopY(1) * BLOCK;
            case "hoist_belt" -> CraneModelLayout.CARRIAGE_TOP_Y_PX;
            default -> 0.0F;
        };
    }

    /** The held item models on the retracted stage: flat and block items in every slot, both stacked copies. */
    private static List<Box> heldItemBoxes() {
        List<Box> items = new ArrayList<>();
        float flatWidth = BLOCK * CraneModelLayout.ITEM_SCALE;
        float flatHeight = CraneModelLayout.FLAT_ITEM_THICKNESS_PX * CraneModelLayout.ITEM_SCALE;
        float blockSize = CraneModelLayout.BLOCK_ITEM_SIZE_PX * CraneModelLayout.ITEM_SCALE;
        for (float x : CraneModelLayout.ITEM_SLOT_X_PX) {
            for (int copy = 0; copy < FLAT_ITEM_COPIES; copy++) {
                Box flat = itemBox("flat item " + x + "/" + copy, x, flatWidth, flatHeight)
                        .moved(0.0F, copy * CraneModelLayout.FLAT_ITEM_STACK_PX, 0.0F);
                Box block = itemBox("block item " + x + "/" + copy, x, blockSize, blockSize)
                        .moved(0.0F, copy * CraneModelLayout.BLOCK_ITEM_STACK_PX, 0.0F);
                // The second copy is turned about its own centre (ITEM_STACK_YAW_DEGREES), so take its envelope.
                items.add(copy == 0 ? flat : turnedAboutItsCentre(flat));
                items.add(copy == 0 ? block : turnedAboutItsCentre(block));
            }
        }
        return items;
    }

    /** {@code box} turned about its own vertical axis by any angle, as the envelope it can occupy. */
    private static Box turnedAboutItsCentre(Box box) {
        float centreX = (box.min(X) + box.max(X)) / 2.0F;
        float centreZ = (box.min(Z) + box.max(Z)) / 2.0F;
        float reach = (float) Math.hypot(box.max(X) - centreX, box.max(Z) - centreZ);
        return new Box(box.model(), box.name() + " (turned)", new float[] {centreX - reach, box.min(Y), centreZ - reach},
                new float[] {centreX + reach, box.max(Y), centreZ + reach}, false, box.faces());
    }

    /** Fails if any corner of {@code box} is further than {@code limit} px from the block centre in the XZ plane. */
    private static void assertSweepWithin(Box box, float limit) {
        for (float x : new float[] {box.min(X), box.max(X)}) {
            for (float z : new float[] {box.min(Z), box.max(Z)}) {
                double radius = CraneModelLayout.sweepRadiusPx(x, z);
                assertTrue(radius <= limit + EPSILON,
                        box + " sweeps " + radius + " px from the block centre, over the limit " + limit);
            }
        }
    }

    /**
     * The wheel element that is turned by 45° about its axle, as the envelope it really occupies: the rotation is about
     * the X axis through the block centre, so X is untouched and the Z half-extent of the turned box grows to the
     * half-diagonal of its Y/Z cross-section.
     */
    private static Box turnedAboutTheAxle(Box box) {
        float halfY = (box.max(Y) - box.min(Y)) / 2.0F;
        float halfZ = (box.max(Z) - box.min(Z)) / 2.0F;
        float centreZ = (box.max(Z) + box.min(Z)) / 2.0F;
        float reach = (float) Math.hypot(halfY, halfZ);
        return new Box(box.model(), box.name() + " (turned)", new float[] {box.min(X), box.min(Y), centreZ - reach},
                new float[] {box.max(X), box.max(Y), centreZ + reach}, false, box.faces());
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
        // A rack bay carries the interface's own 8 x 4 px port on its aisle side, and that side faces south in its
        // model exactly as the interface's does. Every tier and every load step is swept, because the blockstate draws
        // a shell and up to two load parts at once and the arm has to pass all of them (M28 step 9).
        for (String tier : RACK_BAY_TIERS) {
            assertPortClear(memberBoxes(tier), true, swept);
            // Since M29 the uprights are models of their own, and each is drawn at both ends of the block: once as
            // authored and once turned half a turn. All four variants are swept, which is what keeps a frame that
            // grew towards the middle of the block out of the arm's way.
            for (String upright : RACK_BAY_UPRIGHTS) {
                List<Box> left = bayBoxes(tier, upright);
                assertPortClear(left, true, swept);
                assertPortClear(turnedHalfTurn(left), true, swept);
            }
        }
        for (String load : RACK_BAY_LOADS)
            assertPortClear(bayLoadBoxes(load), true, swept);
        // A fluid bay carries the same port on the same side, and its vessel stands below the port's sill rather
        // than in it (M30 step 5) — so the whole block is swept exactly as a rack bay's is, uprights and all.
        for (String tier : FLUID_BAY_TIERS) {
            assertPortClear(memberBoxes(tier), true, swept);
            for (String upright : RACK_BAY_UPRIGHTS) {
                List<Box> left = bayBoxes(tier, upright);
                assertPortClear(left, true, swept);
                assertPortClear(turnedHalfTurn(left), true, swept);
            }
        }
    }

    /**
     * <b>The crane's arm reaches through a rack bay from end to end, so the bay's load has to stay out of its way over
     * the whole block and not merely inside the port recess.</b> The inner stage moves one full block
     * ({@code CraneModelLayout.ARM_REACH_BLOCKS}), so a fully extended arm stands in every pixel of depth of the block
     * at the rack position — which is harmless in a warehouse interface, whose body hides it behind the dark back wall
     * of its port, and would be a grabber visibly travelling through a pallet of goods in a bay, whose aisle side is a
     * window onto exactly that load.
     * <p>
     * {@link #armPassesThroughTheMemberPorts} cannot see this: it sweeps only the {@value #PORT_DEPTH_PX} px of the
     * port recess, and a bay's load sits behind it. So this is the test that goes red when a fill step grows: every
     * box of every load model, and the pallet they stand on, stays <b>below</b> the lowest point the arm or anything
     * it carries ever reaches, and inside the window in x and z so that it never pokes out of the frame.
     * <p>
     * The bound is read off the crane rather than written down, so that lowering the arm one day lowers it here too.
     * The bay's frame itself is deliberately not checked: its side slabs, shelves and back panel are either outside
     * the arm's lateral channel or behind the port's back wall, which is the warehouse interface's accepted case
     * ({@code docs/stacker-crane.md} §7.1).
     */
    @Test
    void aRackBaysLoadStaysUnderTheArmAndInsideItsWindow() throws IOException {
        float armFloor = armFloorPx();
        List<Box> load = new ArrayList<>();
        for (String name : RACK_BAY_LOADS)
            load.addAll(bayLoadBoxes(name));
        int pallets = 0;
        for (String tier : RACK_BAY_TIERS) {
            for (Box box : memberBoxes(tier)) {
                if (box.name().equals(RACK_BAY_PALLET)) {
                    load.add(box);
                    pallets++;
                }
            }
        }
        assertEquals(RACK_BAY_TIERS.size(), pallets, "every tier's shell carries the pallet its load stands on");
        for (Box box : load) {
            assertTrue(box.max(Y) <= armFloor, box + " reaches into the arm's path, which starts at y " + armFloor);
            assertTrue(box.name().equals(RACK_BAY_PALLET) || box.min(Y) >= RACK_BAY_PALLET_TOP_PX - EPSILON,
                    box + " floats below the pallet it should stand on");
            for (int axis : new int[] {X, Z}) {
                assertTrue(box.min(axis) >= RACK_BAY_WINDOW_MIN_PX - EPSILON
                        && box.max(axis) <= RACK_BAY_WINDOW_MAX_PX + EPSILON,
                        box + " leaves the bay's window on axis " + axis);
            }
        }
    }

    /**
     * <b>The stored item a {@code RackBayRenderer} draws into a bay stands in the one part of the bay that is free at
     * every fill level, and it stands there clear of the crane.</b> M29 step 13 (ADR-049) adds the only thing a
     * silhouette cannot carry — <i>which</i> item the cartons are — and it is the half of the bay's look that no model
     * file guards, because a renderer is not a model. This is that guard.
     * <p>
     * Four bounds, all read off the models rather than written down, so an edit to a load step, to the pallet, to the
     * shell or to the arm moves them with it:
     * <ul>
     * <li>the drawn item stays <b>below the arm's floor</b>, the same bound
     * {@link #aRackBaysLoadStaysUnderTheArmAndInsideItsWindow} puts on the load — otherwise a grabber reaching into the
     * bay travels through it;</li>
     * <li>it stays <b>in front of every load box and the pallet</b>, which is what makes it visible at all: the two top
     * fill steps spread their cartons over the pallet's whole footprint, so an item on the pallet would be inside
     * them;</li>
     * <li>it stays <b>inside the block</b>, between the bay's window in x and behind its aisle face in z, so it never
     * pokes into the aisle the crane travels through;</li>
     * <li>it <b>stands on</b> the load beam, the same plane the pallet stands on, rather than floating.</li>
     * </ul>
     * Both item shapes are checked, because they have different extents: a block item is a cube of
     * {@code BLOCK_ITEM_SIZE_PX} and a flat one a sheet a whole block across, and the depth of a flat item laid down
     * the way the crane lays one is exactly what does <b>not</b> fit here — which is why the bay stands it up instead,
     * and why the laid-down case is asserted to be impossible rather than merely not done.
     */
    @Test
    void theItemDrawnInARackBayStandsClearOfTheArmAndTheLoad() throws IOException {
        float armFloor = armFloorPx();
        float loadFront = 0.0F;
        float beamTop = 0.0F;
        for (String name : RACK_BAY_LOADS)
            for (Box box : bayLoadBoxes(name))
                loadFront = Math.max(loadFront, box.max(Z));
        for (String tier : RACK_BAY_TIERS) {
            for (Box box : memberBoxes(tier)) {
                if (box.name().equals(RACK_BAY_PALLET))
                    loadFront = Math.max(loadFront, box.max(Z));
                if (box.name().equals(RACK_BAY_BEAM))
                    beamTop = Math.max(beamTop, box.max(Y));
            }
        }
        assertEquals(beamTop, RackBayRenderer.ITEM_BASE_Y_PX, EPSILON,
                "the drawn item stands on the load beam the pallet stands on");
        assertEquals(loadFront, RackBayRenderer.ITEM_BACK_Z_PX, EPSILON,
                "the drawn item's back face is the front plane of the pallet and of the widest load step");
        assertEquals(BLOCK, RackBayRenderer.AISLE_FACE_Z_PX, EPSILON, "the aisle face is the block's own face");

        // The two shapes an item model has in its fixed display transform, as the renderer measures them: a block item
        // is a cube, a flat one a sheet one block across standing in the plane of the aisle face.
        float blockEdge = CraneModelLayout.BLOCK_ITEM_SIZE_PX * CraneModelLayout.ITEM_SCALE;
        float sheetEdge = BLOCK * CraneModelLayout.ITEM_SCALE;
        float sheetThickness = CraneModelLayout.FLAT_ITEM_THICKNESS_PX * CraneModelLayout.ITEM_SCALE;
        for (Box item : List.of(drawnBayItem("block item", blockEdge, blockEdge, blockEdge),
                drawnBayItem("standing flat item", sheetEdge, sheetEdge, sheetThickness))) {
            assertTrue(item.max(Y) <= armFloor,
                    item + " reaches into the arm's path, which starts at y " + armFloor);
            assertTrue(item.min(Z) >= loadFront - EPSILON,
                    item + " stands inside the load, whose front plane is z " + loadFront);
            assertTrue(item.max(Z) <= RackBayRenderer.AISLE_FACE_Z_PX + EPSILON,
                    item + " pokes out of the bay's aisle face");
            assertTrue(item.min(X) >= RACK_BAY_WINDOW_MIN_PX - EPSILON
                    && item.max(X) <= RACK_BAY_WINDOW_MAX_PX + EPSILON,
                    item + " leaves the bay's window on axis x");
            assertTrue(item.min(Y) >= RackBayRenderer.ITEM_BASE_Y_PX - EPSILON, item + " floats below the load beam");
        }
        // And the reason a flat item is stood up rather than laid down the way the arm lays one: laid down it is a
        // whole block deep and the bay has loadFront..16 px to give it. If this ever stops being true, the bay may
        // follow the crane's convention again.
        assertTrue(loadFront + sheetEdge > RackBayRenderer.AISLE_FACE_Z_PX,
                "a flat item laid down would fit in front of the load after all: " + sheetEdge + " px into "
                        + (RackBayRenderer.AISLE_FACE_Z_PX - loadFront) + " px");
    }

    /** The lowest point the arm or anything it carries ever reaches, in model pixels. */
    private static float armFloorPx() throws IOException {
        List<Box> arm = new ArrayList<>();
        for (String part : List.of("arm_outer", "arm_inner", "grabber"))
            arm.addAll(boxes(part));
        float flatHeight = CraneModelLayout.FLAT_ITEM_THICKNESS_PX * CraneModelLayout.ITEM_SCALE;
        for (float x : CraneModelLayout.ITEM_SLOT_X_PX)
            arm.add(itemBox("flat item " + x, x, BLOCK * CraneModelLayout.ITEM_SCALE, flatHeight));
        return min(arm, Y);
    }

    /**
     * The box the stored item of a bay covers, from the renderer's own anchor planes: centred in x on the window,
     * standing on {@code RackBayRenderer.ITEM_BASE_Y_PX} and with its back face on {@code RackBayRenderer.ITEM_BACK_Z_PX}
     * — exactly the three translations {@code RackBayRenderer#renderSafe} applies.
     */
    private static Box drawnBayItem(String name, float width, float height, float depth) {
        float halfWidth = width / 2.0F;
        return new Box("rack bay renderer", name,
                new float[] {RackBayRenderer.ITEM_X_PX - halfWidth, RackBayRenderer.ITEM_BASE_Y_PX,
                        RackBayRenderer.ITEM_BACK_Z_PX},
                new float[] {RackBayRenderer.ITEM_X_PX + halfWidth, RackBayRenderer.ITEM_BASE_Y_PX + height,
                        RackBayRenderer.ITEM_BACK_Z_PX + depth},
                false, Set.of());
    }

    /**
     * <b>The three rack bays are one rack in three materials.</b> What the tier decides is how much a bay holds and
     * what it may carry above it, never how it is built, so the three model files must differ in exactly one texture
     * entry and in nothing else: not a pixel of geometry, not a UV, not a face, not a cullface.
     * <p>
     * A whole-model comparison with that entry removed covers all of it in one go, the way the warehouse port's two
     * directions are already pinned, so an edit to one of the three can never leave the other two behind. The frame
     * textures themselves are asserted separately, so the material a player reads a wall by cannot quietly change.
     */
    @Test
    void theThreeRackBaysAreTheSameRackInThreeMaterials() throws IOException {
        Map<String, Object> reference = null;
        for (int tier = 0; tier < RACK_BAY_TIERS.size(); tier++) {
            String name = RACK_BAY_TIERS.get(tier);
            Map<String, Object> model = read(BLOCK_MODELS.resolve(name).resolve("block.json"));
            Map<String, Object> textures = object(model.get("textures"));
            assertEquals(RACK_BAY_FRAMES.get(tier), textures.get("frame"), name + " is built of its own material");
            assertEquals(RACK_BAY_FRAMES.get(tier), textures.get("particle"), name + " breaks in its own material");
            Map<String, Object> bare = withoutTextures(model, RACK_BAY_TIER_TEXTURES);
            if (reference == null)
                reference = bare;
            else
                assertEquals(reference, bare, name + " is the same rack as " + RACK_BAY_TIERS.getFirst());
        }
        // The same for every other model a tier owns: the uprights a wall's ends are built of, and the item icon.
        for (String part : List.of("item", "upright", "upright_half")) {
            Map<String, Object> first = null;
            for (String name : RACK_BAY_TIERS) {
                Map<String, Object> bare = withoutTextures(read(BLOCK_MODELS.resolve(name).resolve(part + ".json")),
                        RACK_BAY_TIER_TEXTURES);
                if (first == null)
                    first = bare;
                else
                    assertEquals(first, bare, name + "/" + part + " is the same rack as " + RACK_BAY_TIERS.getFirst());
            }
        }
    }

    /**
     * <b>A rack bay is a shell between two uprights, and that is what makes a wall of them read as racking</b> (M29
     * step 12, issue #20). Nothing at load time checks that those three models fit together, so it is checked here,
     * the way the terminal's four shells are.
     * <p>
     * Five things have to hold at once, and each of them is one way the wall stops being a wall:
     * <ul>
     * <li>The shell and both uprights stay inside the block and <b>share no volume</b> - the blockstate draws all
     * three at once, so an overlap is z-fighting on every bay in the warehouse.</li>
     * <li>An upright is <b>symmetric about the block's depth</b>. The right-hand one is the very same model turned
     * half a turn ({@code WareworksBlockStateGen#rackBayBlockProvider}), so an asymmetric frame would silently put
     * the rear post of one end in front of the front post of the other.</li>
     * <li>Half an upright is the full one <b>narrowed to exactly half its width</b> and identical in depth and
     * height, so the two halves that two joined bays contribute make one upright on the seam rather than a gap or a
     * doubled post.</li>
     * <li>The <b>arm port stays clear</b>: nothing but the back skin reaches into x 4..12 / y 9..13, over the whole
     * depth the arm travels rather than only the port recess {@link #armPassesThroughTheMemberPorts} sweeps. The
     * back skin is the warehouse interface's accepted case - the arm ends behind it - and it has to stay as thin as
     * it claims to be, which is why its depth is pinned rather than excused.</li>
     * <li>The <b>item</b> is the shell between both uprights: a bay standing on its own, which is what a crafted one
     * is. Without this a wall of bays would read as racking and the thing in the player's hand as a crate.</li>
     * </ul>
     */
    @Test
    void aRackBayIsAShellBetweenTwoUprights() throws IOException {
        for (String tier : RACK_BAY_TIERS) {
            List<Box> shell = memberBoxes(tier);
            List<Box> left = bayBoxes(tier, "upright");
            List<Box> right = turnedHalfTurn(left);
            assertEquals(extents(mirroredInX(left)), extents(right),
                    tier + "/upright must be symmetric about the block's depth: the blockstate turns it, not you");

            List<Box> whole = new ArrayList<>(shell);
            whole.addAll(left);
            whole.addAll(right);
            for (int i = 0; i < whole.size(); i++) {
                Box box = whole.get(i);
                for (int axis = X; axis <= Z; axis++)
                    assertTrue(box.min(axis) >= -EPSILON && box.max(axis) <= BLOCK + EPSILON,
                            box + " leaves its own block on axis " + axis);
                for (int j = i + 1; j < whole.size(); j++)
                    assertFalse(box.overlaps(whole.get(j)), box + " shares a volume with " + whole.get(j));
            }

            List<Box> half = bayBoxes(tier, "upright_half");
            assertEquals(left.size(), half.size(), tier + " draws the same frame at a seam, only half of it");
            for (int i = 0; i < left.size(); i++) {
                Box full = left.get(i);
                Box part = half.get(i);
                assertEquals(full.name(), part.name(), tier + " half upright element " + i);
                assertEquals(0.0F, full.min(X), EPSILON, full + " starts at the block's edge");
                assertEquals(0.0F, part.min(X), EPSILON, part + " starts at the block's edge");
                assertEquals(RACK_BAY_UPRIGHT_PX, full.max(X), EPSILON, full + " is a whole upright");
                assertEquals(RACK_BAY_UPRIGHT_HALF_PX, part.max(X), EPSILON, part + " is half of one");
                for (int axis : new int[] {Y, Z}) {
                    assertEquals(full.min(axis), part.min(axis), EPSILON, part + " min on axis " + axis);
                    assertEquals(full.max(axis), part.max(axis), EPSILON, part + " max on axis " + axis);
                }
            }

            Box port = new Box(tier, "arm port",
                    new float[] {PORT_WINDOW_MIN_X_PX, PORT_WINDOW_MIN_Y_PX, RACK_BAY_BACK_DEPTH_PX},
                    new float[] {PORT_WINDOW_MAX_X_PX, PORT_WINDOW_MAX_Y_PX, BLOCK}, false, Set.of());
            List<Box> frame = new ArrayList<>(shell);
            for (String upright : RACK_BAY_UPRIGHTS) {
                List<Box> boxes = bayBoxes(tier, upright);
                frame.addAll(boxes);
                frame.addAll(turnedHalfTurn(boxes));
            }
            int backs = 0;
            for (Box box : frame) {
                if (box.name().equals(RACK_BAY_BACK)) {
                    assertEquals(RACK_BAY_BACK_DEPTH_PX, box.max(Z), EPSILON, box + " is the skin it claims to be");
                    backs++;
                    continue;
                }
                assertFalse(box.overlaps(port), box + " stands in the arm's way through the bay");
            }
            assertEquals(1, backs, tier + " closes its rack with exactly one back skin");

            assertEquals(extents(whole), extents(bayBoxes(tier, "item")),
                    tier + "/item.json must be the shell between both of its uprights");
        }
    }

    /** {@code boxes} turned half a turn around the block's vertical axis, the way the right-hand upright is drawn. */
    private static List<Box> turnedHalfTurn(List<Box> boxes) {
        List<Box> turned = new ArrayList<>(boxes.size());
        boxes.forEach(box -> turned.add(turnedAroundTheBlock(box, HALF_TURN_QUARTERS)));
        return turned;
    }

    /** {@code boxes} mirrored across the block's x centre: {@code x -> 16 - x}, with y and z left alone. */
    private static List<Box> mirroredInX(List<Box> boxes) {
        List<Box> mirrored = new ArrayList<>(boxes.size());
        for (Box box : boxes) {
            mirrored.add(new Box(box.model(), box.name(), new float[] {BLOCK - box.max(X), box.min(Y), box.min(Z)},
                    new float[] {BLOCK - box.min(X), box.max(Y), box.max(Z)}, box.rotated(), box.faces()));
        }
        return mirrored;
    }

    /**
     * <b>{@code OVERLOADED} must not change how a bay looks</b> (ADR-044): it is a warning for the goggles and the
     * job planner, and a bay that carries something stronger above it has to look exactly like one that does not, or
     * a player reads a block state a command can set as damage they have to repair.
     * <p>
     * Checked on the generated blockstate rather than on the generator, because that file is what ships: a condition
     * added anywhere in the multipart, by any route, fails here.
     */
    @Test
    void anOverloadedRackBayLooksLikeAnyOther() throws IOException {
        for (String tier : RACK_BAY_TIERS) {
            String json = Files.readString(GENERATED_BLOCKSTATES.resolve(tier + ".json"));
            assertFalse(json.contains("overloaded"),
                    tier + " must not draw its overload warning: it is a goggle line, not a look");
        }
    }

    /**
     * <b>A fluid bay is a tank standing in the rack bay's own frame</b> ({@code docs/warehouse-system.md} §3.9, M30
     * step 5, issue #21), and that is a requirement rather than a resemblance.
     * <p>
     * Joining is <b>across</b> families (ADR-050): a tank placed at the end of a rack wall does not bring its own post
     * to stand beside the rack's, it takes over half of it, and the two {@code upright_half} models that meet on that
     * seam have to make exactly one post. So a fluid bay's uprights are checked against a rack bay's <b>element for
     * element</b>, and so are the two parts of its shell that carry a wall's horizontal lines — the load beam of its
     * own level and the rack's back skin. Anything else about the two blocks may differ; these may not.
     * <p>
     * On top of that, the five things {@link #aRackBayIsAShellBetweenTwoUprights} asks of a rack bay are asked here
     * too, because they are about a blockstate drawing a shell and two uprights at once and that is what this
     * blockstate does as well: everything inside the block, nothing sharing a volume, an upright symmetric about the
     * block's depth, half an upright exactly half as wide and otherwise identical, the arm port clear of everything
     * but the back skin, and the item model the shell between <b>both</b> uprights.
     */
    @Test
    void aFluidBayIsATankInTheRackBaysOwnFrame() throws IOException {
        Map<String, String> referenceFrame = extentsByName(memberBoxes(FLUID_BAY_FRAME_REFERENCE));
        for (String tier : FLUID_BAY_TIERS) {
            List<Box> shell = memberBoxes(tier);
            Map<String, String> frame = extentsByName(shell);
            for (String part : FLUID_BAY_RACK) {
                assertEquals(referenceFrame.get(part), frame.get(part), tier + "/block's " + part
                        + " must be the rack bay's own, or a wall that mixes tanks and racks breaks its lines");
            }
            for (String upright : RACK_BAY_UPRIGHTS) {
                assertEquals(extentsByName(bayBoxes(FLUID_BAY_FRAME_REFERENCE, upright)),
                        extentsByName(bayBoxes(tier, upright)), tier + "/" + upright
                                + " must be the rack bay's own: a seam between a tank and a rack is one post, and "
                                + "each of the two bays draws half of it");
            }

            List<Box> left = bayBoxes(tier, "upright");
            List<Box> right = turnedHalfTurn(left);
            assertEquals(extents(mirroredInX(left)), extents(right),
                    tier + "/upright must be symmetric about the block's depth: the blockstate turns it, not you");

            List<Box> whole = new ArrayList<>(shell);
            whole.addAll(left);
            whole.addAll(right);
            for (int i = 0; i < whole.size(); i++) {
                Box box = whole.get(i);
                for (int axis = X; axis <= Z; axis++)
                    assertTrue(box.min(axis) >= -EPSILON && box.max(axis) <= BLOCK + EPSILON,
                            box + " leaves its own block on axis " + axis);
                for (int j = i + 1; j < whole.size(); j++)
                    assertFalse(box.overlaps(whole.get(j)), box + " shares a volume with " + whole.get(j));
            }

            List<Box> half = bayBoxes(tier, "upright_half");
            assertEquals(left.size(), half.size(), tier + " draws the same frame at a seam, only half of it");
            for (int i = 0; i < left.size(); i++) {
                Box full = left.get(i);
                Box part = half.get(i);
                assertEquals(full.name(), part.name(), tier + " half upright element " + i);
                assertEquals(RACK_BAY_UPRIGHT_PX, full.max(X), EPSILON, full + " is a whole upright");
                assertEquals(RACK_BAY_UPRIGHT_HALF_PX, part.max(X), EPSILON, part + " is half of one");
            }

            Box port = new Box(tier, "arm port",
                    new float[] {PORT_WINDOW_MIN_X_PX, PORT_WINDOW_MIN_Y_PX, RACK_BAY_BACK_DEPTH_PX},
                    new float[] {PORT_WINDOW_MAX_X_PX, PORT_WINDOW_MAX_Y_PX, BLOCK}, false, Set.of());
            List<Box> all = new ArrayList<>(shell);
            for (String upright : RACK_BAY_UPRIGHTS) {
                List<Box> boxes = bayBoxes(tier, upright);
                all.addAll(boxes);
                all.addAll(turnedHalfTurn(boxes));
            }
            int backs = 0;
            for (Box box : all) {
                if (box.name().equals(RACK_BAY_BACK)) {
                    assertEquals(RACK_BAY_BACK_DEPTH_PX, box.max(Z), EPSILON, box + " is the skin it claims to be");
                    backs++;
                    continue;
                }
                assertFalse(box.overlaps(port), box + " stands in the arm's way through the bay");
            }
            assertEquals(1, backs, tier + " closes its rack with exactly one back skin");

            assertEquals(extents(whole), extents(bayBoxes(tier, "item")),
                    tier + "/item.json must be the tank between both of its uprights");
        }
    }

    /**
     * <b>A fluid bay's vessel, and the fluid {@code FluidBayRenderer} draws in it, stay out of the crane's way and
     * inside the window a player reads them through</b> (M30 step 5, issue #21).
     * <p>
     * This is the fluid bay's twin of {@link #aRackBaysLoadStaysUnderTheArmAndInsideItsWindow}, and it has to cover
     * one thing more: half of a fluid bay's look is a model file and half of it is a box computed every frame from
     * {@link FluidBayLayout}, which no model file can express because the fluid's height <b>is</b> the readout. So
     * both halves are held to the same bounds here, read off the crane's own models rather than written down:
     * <ul>
     * <li>the vessel stands in the rack bay's load window in x and z, on the plane a pallet stands on, so a wall that
     * mixes tanks and racks keeps one rhythm;</li>
     * <li>its rim is the <b>sill of the arm port</b> and therefore below the arm floor — a tank that reached into the
     * port would be a grabber travelling through lava on its way to a container;</li>
     * <li>the fluid never touches a wall of the vessel, at any fill, so no quad of it ever z-fights with one of the
     * model's; it is recessed behind the opening rather than flush with it;</li>
     * <li>and the opening really is open: nothing of the shell stands between a full tank and the aisle, which is the
     * only reason the level can be read at all.</li>
     * </ul>
     * The fill function itself is pinned with them: empty draws nothing, anything at all draws at least the minimum
     * film, a full bay reaches the rim exactly, a bay over a lowered configured capacity is clamped to the rim rather
     * than overflowing it, and the surface never falls as the bay fills.
     */
    @Test
    void aFluidBaysVesselStaysUnderTheArmAndInsideItsWindow() throws IOException {
        float armFloor = armFloorPx();
        assertTrue(FluidBayLayout.VESSEL_RIM_PX <= PORT_WINDOW_MIN_Y_PX + EPSILON,
                "the vessel's rim must be the arm port's sill, and is " + FluidBayLayout.VESSEL_RIM_PX);
        assertTrue(FluidBayLayout.VESSEL_RIM_PX <= armFloor,
                "the vessel reaches into the arm's path, which starts at y " + armFloor);
        assertTrue(FluidBayLayout.FLUID_MAX_Z_PX < FluidBayLayout.VESSEL_MAX_Z_PX,
                "the fluid must stand behind the vessel's opening, not in its plane");

        for (String tier : FLUID_BAY_TIERS) {
            List<Box> shell = memberBoxes(tier);
            List<Box> vessel = new ArrayList<>();
            Set<String> names = new TreeSet<>();
            for (Box box : shell) {
                if (FLUID_BAY_VESSEL.contains(box.name())) {
                    vessel.add(box);
                    names.add(box.name());
                }
            }
            assertEquals(new TreeSet<>(FLUID_BAY_VESSEL), names,
                    tier + " must carry a floor and three walls: a tank is a vessel, not a trough with a hole in it");
            for (Box box : vessel) {
                assertTrue(box.min(Y) >= FluidBayLayout.VESSEL_FLOOR_PX - EPSILON,
                        box + " floats below the load beam it should stand on");
                assertTrue(box.max(Y) <= FluidBayLayout.VESSEL_RIM_PX + EPSILON,
                        box + " reaches above the vessel's rim at y " + FluidBayLayout.VESSEL_RIM_PX);
                for (int axis : new int[] {X, Z}) {
                    assertTrue(box.min(axis) >= RACK_BAY_WINDOW_MIN_PX - EPSILON
                            && box.max(axis) <= RACK_BAY_WINDOW_MAX_PX + EPSILON,
                            box + " leaves the bay's window on axis " + axis);
                }
            }

            // The drawn fluid, at every fill: inside the window, below the rim, and touching nothing of the model.
            for (double share : FLUID_FILL_SHARES) {
                long held = (long) (share * FLUID_BAY_CAPACITY_MB);
                if (held <= 0)
                    continue;
                Box fluid = drawnFluid(held, FLUID_BAY_CAPACITY_MB);
                assertTrue(fluid.max(Y) <= FluidBayLayout.VESSEL_RIM_PX + EPSILON,
                        fluid + " rises over the vessel's rim");
                for (int axis : new int[] {X, Z}) {
                    assertTrue(fluid.min(axis) >= RACK_BAY_WINDOW_MIN_PX - EPSILON
                            && fluid.max(axis) <= RACK_BAY_WINDOW_MAX_PX + EPSILON,
                            fluid + " leaves the bay's window on axis " + axis);
                }
                for (Box box : shell)
                    assertFalse(fluid.overlaps(box), fluid + " is drawn inside " + box);
            }

            // The opening: the strip between a full tank and the aisle face has to be empty, over the whole width and
            // height the fluid can reach, or there is nothing to read the level through.
            Box window = new Box(tier, "aisle window",
                    new float[] {FluidBayLayout.FLUID_MIN_X_PX, FluidBayLayout.FLUID_FLOOR_PX,
                            FluidBayLayout.FLUID_MAX_Z_PX},
                    new float[] {FluidBayLayout.FLUID_MAX_X_PX, FluidBayLayout.VESSEL_RIM_PX, BLOCK}, false,
                    Set.of());
            for (Box box : shell)
                assertFalse(window.overlaps(box), box + " stands between the fluid and the aisle that reads it");
        }

        assertEquals(FluidBayLayout.FLUID_FLOOR_PX, FluidBayLayout.surfacePx(0, FLUID_BAY_CAPACITY_MB), EPSILON,
                "an empty bay draws no fluid at all");
        assertEquals(FluidBayLayout.FLUID_FLOOR_PX + FluidBayLayout.MIN_FILM_PX,
                FluidBayLayout.surfacePx(1, FLUID_BAY_CAPACITY_MB), EPSILON,
                "a bay holding one millibucket still says so");
        assertEquals(FluidBayLayout.VESSEL_RIM_PX,
                FluidBayLayout.surfacePx(FLUID_BAY_CAPACITY_MB, FLUID_BAY_CAPACITY_MB), EPSILON,
                "a full bay reaches the rim exactly");
        assertEquals(FluidBayLayout.VESSEL_RIM_PX,
                FluidBayLayout.surfacePx(FLUID_BAY_CAPACITY_MB, FLUID_BAY_CAPACITY_MB / 2), EPSILON,
                "a bay over a lowered configured capacity is clamped to the rim, not drawn through it");
        float previous = -1.0F;
        for (double share : FLUID_FILL_SHARES) {
            float surface = FluidBayLayout.surfacePx((long) (share * FLUID_BAY_CAPACITY_MB), FLUID_BAY_CAPACITY_MB);
            assertTrue(surface >= previous, "the surface fell between " + share + " and the share before it");
            previous = surface;
        }
    }

    /**
     * <b>The two fluid bays are one tank in two materials.</b> What the tier decides is how much a bay holds, never
     * how it is built, so the two model files must differ in exactly one texture entry and in nothing else — the same
     * whole-model comparison {@link #theThreeRackBaysAreTheSameRackInThreeMaterials} makes, for the same reason.
     * <p>
     * The two materials themselves are pinned with it. <b>Copper is Create's own fluid tank plate</b>, because Create
     * has no copper block texture of its own and that plate is the copper a Create player reads as a tank; brass is
     * the brass block, which is <b>exactly</b> the brass rack bay's frame, so a brass tank and a brass rack share a
     * seam post that is one post in one material.
     */
    @Test
    void theTwoFluidBaysAreTheSameTankInTwoMaterials() throws IOException {
        Map<String, Object> reference = null;
        for (int tier = 0; tier < FLUID_BAY_TIERS.size(); tier++) {
            String name = FLUID_BAY_TIERS.get(tier);
            Map<String, Object> model = read(BLOCK_MODELS.resolve(name).resolve("block.json"));
            Map<String, Object> textures = object(model.get("textures"));
            assertEquals(FLUID_BAY_FRAMES.get(tier), textures.get("frame"), name + " is built of its own material");
            assertEquals(FLUID_BAY_FRAMES.get(tier), textures.get("particle"), name + " breaks in its own material");
            Map<String, Object> bare = withoutTextures(model, RACK_BAY_TIER_TEXTURES);
            if (reference == null)
                reference = bare;
            else
                assertEquals(reference, bare, name + " is the same tank as " + FLUID_BAY_TIERS.getFirst());
        }
        for (String part : List.of("item", "upright", "upright_half")) {
            Map<String, Object> first = null;
            for (String name : FLUID_BAY_TIERS) {
                Map<String, Object> bare = withoutTextures(read(BLOCK_MODELS.resolve(name)
                        .resolve(part + ".json")), RACK_BAY_TIER_TEXTURES);
                if (first == null)
                    first = bare;
                else
                    assertEquals(first, bare,
                            name + "/" + part + " is the same tank as " + FLUID_BAY_TIERS.getFirst());
            }
        }
        assertEquals(FLUID_BAY_FRAMES.get(1), RACK_BAY_FRAMES.get(RACK_BAY_TIERS.indexOf(FLUID_BAY_FRAME_REFERENCE)),
                "a brass tank and a brass rack have to be the same brass, or their shared seam post is two posts");
    }

    /**
     * <b>Neither a fluid bay's fill level nor its overload warning is drawn by its blockstate</b> (M30 step 5).
     * <p>
     * {@code OVERLOADED} is left out for the rack bay's own reason (ADR-044) and {@code FILL} for one that is this
     * block's alone: a fluid's look is its own still sprite with its own tint and the set of fluids is open, so no
     * baked variant can draw one and the level is the renderer's whole job. A {@code fill} condition appearing in
     * this file would mean two answers to "how full is it" drawn on top of each other, the coarse one wrong by up to
     * a quarter of the tank.
     * <p>
     * Checked on the generated blockstate rather than on the generator, because that file is what ships.
     */
    @Test
    void aFluidBayDrawsNeitherItsFillNorItsOverloadInItsBlockstate() throws IOException {
        for (String tier : FLUID_BAY_TIERS) {
            String json = Files.readString(GENERATED_BLOCKSTATES.resolve(tier + ".json"));
            assertFalse(json.contains("overloaded"),
                    tier + " must not draw its overload warning: it is a goggle line, not a look");
            assertFalse(json.contains("fill"),
                    tier + " must not draw a coarse fill level: the renderer draws the real one");
        }
    }

    /** The boxes by element name, as "from -> to", so two models compare part by part rather than as a set. */
    private static Map<String, String> extentsByName(List<Box> boxes) {
        Map<String, String> result = new TreeMap<>();
        for (Box box : boxes)
            result.put(box.name(), Arrays.toString(box.from()) + " -> " + Arrays.toString(box.to()));
        return result;
    }

    /**
     * The box {@code FluidBayRenderer} draws for a bay holding {@code millibuckets} of {@code capacity}, read off
     * {@link FluidBayLayout} exactly as the renderer reads it — the one part of a fluid bay's look that lives in no
     * model file.
     */
    private static Box drawnFluid(long millibuckets, long capacity) {
        return new Box("fluid bay renderer", "fluid " + millibuckets + " of " + capacity,
                new float[] {FluidBayLayout.FLUID_MIN_X_PX, FluidBayLayout.FLUID_FLOOR_PX,
                        FluidBayLayout.FLUID_MIN_Z_PX},
                new float[] {FluidBayLayout.FLUID_MAX_X_PX, FluidBayLayout.surfacePx(millibuckets, capacity),
                        FluidBayLayout.FLUID_MAX_Z_PX}, false, Set.of());
    }

    /**
     * <b>A rack is cut from the part of its texture that is actually there</b> (M29 step 12, issue #20).
     * <p>
     * {@code create:block/bracket_plate_wooden} — the wooden tier's frame and every tier's pallet deck — is a
     * 14 x 14 plate in the corner of a 16 x 16 file: everything above {@code v = 2} and right of {@code u = 14} is
     * fully transparent, and every one of those texels is RGB 0,0,0. A rack bay is drawn in the <b>solid</b> layer,
     * where alpha is ignored, so a face whose UV reaches into them is painted pure black. That is exactly what the
     * first M29 wall did: a black cap on every post against the sky, a black bar beside every opening, a black line
     * across the back at every level, and seam uprights darker than the ones at a wall's ends, because half an
     * upright's narrow UV landed wholly inside the transparent column.
     * <p>
     * None of it was light or ambient occlusion, and the proof is in the same screenshot: the andesite and brass
     * bays stacked under the wooden ones, same geometry, same UVs, same light, showed not one black pixel — their
     * frame texture has no transparent texel to sample. So this test reads the texture files themselves and fails on
     * any face of the rack, of its loads or of the pallet that samples a texel the texture does not fill.
     * <p>
     * The premise is pinned with it: none of these models declares a {@code render_type}, so they really are drawn
     * in the solid layer. Switching one to {@code cutout} would turn the same texels into holes rather than into
     * black — a different decision, and one somebody then has to take on purpose rather than by accident.
     */
    @Test
    void theRackIsCutFromTexelsThatAreReallyThere() throws IOException {
        for (String tier : RACK_BAY_TIERS) {
            for (String part : List.of("block", "item", "upright", "upright_half"))
                assertEveryTexelOpaque(BLOCK_MODELS.resolve(tier).resolve(part + ".json"), tier + "/" + part);
        }
        for (String load : RACK_BAY_LOADS)
            assertEveryTexelOpaque(RACK_BAY_LOAD_MODELS.resolve(load + ".json"), "rack_bay/" + load);
        // The pallet a broken bay drops is the same plate texture and was black along the same two edges.
        assertEveryTexelOpaque(BLOCK_MODELS.resolve("pallet").resolve("deck.json"), "pallet/deck");
    }

    /**
     * <b>Every quad a seam buries carries the cullface that lets the block drop it</b> (M29 review, ADR-050).
     * <p>
     * Where two bays join, twelve quads meet at the block boundary and are buried in each other: the shell's
     * {@code deck} and {@code back} on both sides, and the four elements of the {@code upright_half} each of the two
     * bays draws there. A rack bay is {@code noOcclusion()}, so {@code Block#shouldRenderFace} would keep every one of
     * them — about a sixth of a wall's quads baked into the chunk mesh with no camera that could ever see them.
     * {@code RackBayBlock#skipRendering} drops them, and <b>it is consulted only for quads that carry a
     * {@code cullface} in that direction</b>: a face on the seam plane without one is invisible to the fix and comes
     * back as waste, silently, because nothing looks any different. So the cullfaces are pinned here, by name and by
     * count, next to the geometry they belong to.
     */
    @Test
    void everyFaceASeamBuriesIsCullfaced() throws IOException {
        for (String tier : RACK_BAY_TIERS) {
            assertEquals(Map.of("west", 2, "east", 2), seamCullfaces(BLOCK_MODELS.resolve(tier)
                    .resolve("block.json")), tier + "/block buries its deck and its back on both seams");
            assertEquals(Map.of("west", 4), seamCullfaces(BLOCK_MODELS.resolve(tier)
                    .resolve("upright_half.json")), tier + "/upright_half buries all four of its elements");
        }
        // A fluid bay joins a wall across families, so its seams are buried in exactly the same places (M30 step 5).
        // Its vessel stands inside the block and never reaches a seam plane, which is what keeps these two counts the
        // rack bay's own.
        for (String tier : FLUID_BAY_TIERS) {
            assertEquals(Map.of("west", 2, "east", 2), seamCullfaces(BLOCK_MODELS.resolve(tier)
                    .resolve("block.json")), tier + "/block buries its deck and its back on both seams");
            assertEquals(Map.of("west", 4), seamCullfaces(BLOCK_MODELS.resolve(tier)
                    .resolve("upright_half.json")), tier + "/upright_half buries all four of its elements");
        }
    }

    /**
     * How many faces of {@code path} lie <b>on</b> one of the two seam planes ({@code x = 0} for a west face,
     * {@code x = 16} for an east one), with each of them asserted to carry the {@code cullface} of its own name. A
     * west or east face set back from the plane — the pallet's — is none of a seam's business and is passed over.
     */
    private static Map<String, Integer> seamCullfaces(Path path) throws IOException {
        Map<String, Integer> counted = new TreeMap<>();
        for (Object element : array(read(path).get("elements"))) {
            Map<String, Object> fields = object(element);
            List<Object> from = array(fields.get("from"));
            List<Object> to = array(fields.get("to"));
            for (Map.Entry<String, Object> face : object(fields.get("faces")).entrySet()) {
                String side = face.getKey();
                boolean onSeam = side.equals("west") ? number(from.get(X)) <= EPSILON
                        : side.equals("east") && number(to.get(X)) >= BLOCK - EPSILON;
                if (!onSeam)
                    continue;
                assertEquals(side, object(face.getValue()).get("cullface"),
                        path.getFileName() + "/" + fields.get("name") + "/" + side
                                + " sits on a seam plane and must carry its own cullface, or RackBayBlock"
                                + "#skipRendering can never drop it");
                counted.merge(side, 1, Integer::sum);
            }
        }
        return counted;
    }

    /**
     * <b>Every other block model of this mod, too</b> (M29 review): the rack was the case that was found by looking
     * at a screenshot four times, and the rule it taught — a face in the solid layer that samples an empty texel is
     * painted pure black — is not about the rack. So this walks <b>every</b> model under
     * {@code assets/wareworks/models/block}, parent chains flattened, and holds each of them to the same bound.
     * <p>
     * A model that declares a {@code render_type} is a model whose empty texels are <b>holes</b> and not black, so it
     * is not swept — but the set of them is pinned to {@link #CUTOUT_BLOCK_MODELS}, so the way out of this test is a
     * decision somebody takes and writes down rather than a line that quietly appears in a model file.
     */
    @Test
    void everyBlockModelIsCutFromTexelsThatAreReallyThere() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(BLOCK_MODELS)) {
            files = walk.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        assertTrue(files.size() > 40, "the block model folder is suspiciously small: " + files.size() + " files");
        Map<String, String> cutouts = new TreeMap<>();
        for (Path file : files) {
            String name = BLOCK_MODELS.relativize(file).toString().replace(FILE_SEPARATOR, '/');
            name = name.substring(0, name.length() - JSON_SUFFIX.length());
            Map<String, Object> model = flattened(file, name);
            Object renderType = model.get("render_type");
            if (renderType != null) {
                cutouts.put(name, String.valueOf(renderType));
                continue;
            }
            assertEveryTexelOpaque(model, name);
        }
        assertEquals(new TreeMap<>(CUTOUT_BLOCK_MODELS), cutouts,
                "the models that may sample empty texels are exactly the ones drawn outside the solid layer");
    }

    /**
     * {@code path} with its {@code wareworks:block/} parent chain folded in: the elements of the nearest ancestor
     * that has any, the textures of the whole chain with the child's winning, and the {@code render_type} of the
     * nearest ancestor that declares one. A state model like {@code warehouse_stock_keeper/block_lit} is nothing but
     * a parent and one replaced texture, and that replaced texture is exactly what could reach an empty texel.
     */
    private static Map<String, Object> flattened(Path path, String name) throws IOException {
        Map<String, Object> model = read(path);
        Map<String, Object> textures = new LinkedHashMap<>(object(model.getOrDefault("textures", Map.of())));
        Object elements = model.get("elements");
        Object renderType = model.get("render_type");
        Object parent = model.get("parent");
        for (int step = 0; parent instanceof String reference && reference.startsWith(OWN_MODELS); step++) {
            assertTrue(step < MAX_PARENT_DEPTH, name + " has a parent chain that does not end: " + reference);
            Map<String, Object> ancestor = read(BLOCK_MODELS.resolve(reference.substring(OWN_MODELS.length())
                    + ".json"));
            object(ancestor.getOrDefault("textures", Map.of())).forEach(textures::putIfAbsent);
            if (elements == null)
                elements = ancestor.get("elements");
            if (renderType == null)
                renderType = ancestor.get("render_type");
            parent = ancestor.get("parent");
        }
        assertNotNull(elements, name + " has no elements, and no ancestor of it has any either");
        Map<String, Object> flat = new LinkedHashMap<>(model);
        flat.put("textures", textures);
        flat.put("elements", elements);
        if (renderType != null)
            flat.put("render_type", renderType);
        return flat;
    }

    /** Fails on any face of {@code path} whose UV rectangle reaches a texel its texture leaves empty. */
    private static void assertEveryTexelOpaque(Path path, String name) throws IOException {
        Map<String, Object> model = read(path);
        assertFalse(model.containsKey("render_type"), name + " is drawn in the solid layer, where alpha is black");
        assertEveryTexelOpaque(model, name);
    }

    /** {@link #assertEveryTexelOpaque(Path, String)} on a model that is already read and flattened. */
    private static void assertEveryTexelOpaque(Map<String, Object> model, String name) throws IOException {
        Map<String, Object> textures = object(model.get("textures"));
        for (Object element : array(model.get("elements"))) {
            Map<String, Object> fields = object(element);
            for (Map.Entry<String, Object> face : object(fields.get("faces")).entrySet()) {
                String where = name + "/" + fields.get("name") + "/" + face.getKey();
                Map<String, Object> entry = object(face.getValue());
                List<Object> uv = array(entry.get("uv"));
                assertEquals(4, uv.size(), where + " needs an explicit uv rectangle");
                String reference = resolveTexture(textures, String.valueOf(entry.get("texture")), where);
                BufferedImage texture = texture(reference, where);
                double scaleX = texture.getWidth() / BLOCK;
                double scaleY = texture.getHeight() / BLOCK;
                int minX = texel(Math.min(number(uv.get(0)), number(uv.get(2))) * scaleX, texture.getWidth(), false);
                int maxX = texel(Math.max(number(uv.get(0)), number(uv.get(2))) * scaleX, texture.getWidth(), true);
                int minY = texel(Math.min(number(uv.get(1)), number(uv.get(3))) * scaleY, texture.getHeight(), false);
                int maxY = texel(Math.max(number(uv.get(1)), number(uv.get(3))) * scaleY, texture.getHeight(), true);
                for (int x = minX; x <= Math.max(minX, maxX); x++) {
                    for (int y = minY; y <= Math.max(minY, maxY); y++)
                        assertEquals(0xFF, texture.getRGB(x, y) >>> 24, where + " samples texel " + x + "," + y
                                + " of " + reference + ", which the texture leaves empty: the solid layer paints it"
                                + " pure black");
                }
            }
        }
    }

    /** The texel a UV coordinate falls on, clamped into the image. */
    private static int texel(double coordinate, int size, boolean upper) {
        int index = upper ? (int) Math.ceil(coordinate - EPSILON) - 1 : (int) Math.floor(coordinate + EPSILON);
        return Math.min(size - 1, Math.max(0, index));
    }

    /** A face's texture reference with the model's own {@code #key} indirections followed. */
    private static String resolveTexture(Map<String, Object> textures, String reference, String where) {
        String resolved = reference;
        for (int step = 0; resolved.startsWith("#"); step++) {
            assertTrue(step < textures.size() + 1, where + " has a circular texture reference: " + reference);
            Object next = textures.get(resolved.substring(1));
            assertNotNull(next, where + " names the texture " + resolved + ", which the model does not define");
            resolved = String.valueOf(next);
        }
        return resolved;
    }

    /** The texture file behind a resolved reference: ours from the repository, Create's from the test classpath. */
    private static BufferedImage texture(String reference, String where) throws IOException {
        BufferedImage cached = TEXTURE_FILES.get(reference);
        if (cached != null)
            return cached;
        BufferedImage image;
        if (reference.startsWith(OWN_TEXTURES)) {
            image = ImageIO.read(OWN_TEXTURE_FILES.resolve(reference.substring(OWN_TEXTURES.length()) + ".png")
                    .toFile());
        } else {
            int colon = reference.indexOf(':');
            assertTrue(colon > 0, where + " names a texture without a namespace: " + reference);
            String resource = "/assets/" + reference.substring(0, colon) + "/textures/"
                    + reference.substring(colon + 1) + ".png";
            try (InputStream stream = CraneModelLayoutTest.class.getResourceAsStream(resource)) {
                assertNotNull(stream, where + " references " + reference + ", and " + resource
                        + " is not on the test classpath");
                image = ImageIO.read(stream);
            }
        }
        assertNotNull(image, where + " references a texture that cannot be read: " + reference);
        TEXTURE_FILES.put(reference, image);
        return image;
    }

    /** {@code model} without the named texture entries. */
    private static Map<String, Object> withoutTextures(Map<String, Object> model, Set<String> keys) {
        Map<String, Object> copy = new LinkedHashMap<>(model);
        Map<String, Object> textures = new LinkedHashMap<>(object(copy.get("textures")));
        keys.forEach(textures::remove);
        copy.put("textures", textures);
        return copy;
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

    /**
     * The warehouse port has <b>two</b> models, one per direction (M17, issue #12): {@code block} for a requesting port
     * and {@code block_accept} for an accepting one. They must be the very same block — the arm still enters through the
     * same opening, the same faces are covered, nothing moves — and differ in one thing only: the {@code accent} texture
     * of the ring around the aisle opening and of the spout on the back, which turns from brass to andesite
     * (ADR-017's material language: andesite is the dumb intake, brass the smart filtered output).
     * <p>
     * A whole-model comparison with the accent entry removed covers geometry, UVs, faces and cullfaces in one go, so a
     * later edit to one of the files can never leave the other behind. The accent's <b>use</b> is pinned separately, so
     * the cue cannot silently move off the two surfaces a player reads it from.
     */
    @Test
    void portAcceptVariantIsTheSameBlockWithAnAndesiteAccent() throws IOException {
        Path folder = BLOCK_MODELS.resolve("warehouse_output");
        Map<String, Object> request = read(folder.resolve("block.json"));
        Map<String, Object> accept = read(folder.resolve("block_accept.json"));
        assertEquals("create:block/brass_casing", object(request.get("textures")).get(PORT_ACCENT),
                "a requesting port is brass all over");
        assertEquals("create:block/andesite_casing", object(accept.get("textures")).get(PORT_ACCENT),
                "an accepting port shows andesite");
        assertEquals(withoutAccentTexture(request), withoutAccentTexture(accept),
                "the two directions are the same block: only the accent texture may differ");
        assertEquals(PORT_ACCENT_FACES, accentedFaces(request), "which surfaces carry the direction cue");
        assertEquals(PORT_ACCENT_FACES, accentedFaces(accept), "the accepting model accents the same surfaces");
    }

    /** {@code model} without the one texture entry the two port directions differ in. */
    private static Map<String, Object> withoutAccentTexture(Map<String, Object> model) {
        Map<String, Object> copy = new LinkedHashMap<>(model);
        Map<String, Object> textures = new LinkedHashMap<>(object(copy.get("textures")));
        textures.remove(PORT_ACCENT);
        copy.put("textures", textures);
        return copy;
    }

    /** Every {@code element/face} of {@code model} whose texture is {@code #accent}. */
    private static Set<String> accentedFaces(Map<String, Object> model) {
        Set<String> accented = new java.util.TreeSet<>();
        for (Object element : array(model.get("elements"))) {
            Map<String, Object> fields = object(element);
            Map<String, Object> faces = object(fields.get("faces"));
            for (Map.Entry<String, Object> face : faces.entrySet()) {
                if (("#" + PORT_ACCENT).equals(object(face.getValue()).get("texture")))
                    accented.add(fields.get("name") + "/" + face.getKey());
            }
        }
        return accented;
    }

    /** The elements of another block's model, {@code models/block/<block>/block.json}. */
    private static List<Box> memberBoxes(String block) throws IOException {
        return boxes(BLOCK_MODELS.resolve(block).resolve("block.json"), block);
    }

    /** The elements of one model of one rack bay tier, {@code models/block/rack_bay_<tier>/<file>.json}. */
    private static List<Box> bayBoxes(String tier, String file) throws IOException {
        return boxes(BLOCK_MODELS.resolve(tier).resolve(file + ".json"), tier + "/" + file);
    }

    /** The elements of one shared rack bay load model, {@code models/block/rack_bay/<file>.json}. */
    private static List<Box> bayLoadBoxes(String file) throws IOException {
        return boxes(RACK_BAY_LOAD_MODELS.resolve(file + ".json"), "rack_bay/" + file);
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

}
