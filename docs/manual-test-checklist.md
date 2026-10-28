# Manual test checklist

Checks for a manual play test, ordered the way a player builds a warehouse.

Setup: a creative world, Create 6.0.10 installed, **Engineer's Goggles** in a slot, a **Wrench** in hand.
Where a German client is mentioned, restart with `de_de` to check the translation.

---

## A. First launch

1. The creative tab **"Create: Wareworks"** appears after Create's palettes tab, its icon is the stacker crane, and it
   lists exactly **seventeen** items in building order: stacker crane, warehouse rail, warehouse controller, warehouse
   interface, **wooden rack bay, andesite rack bay, brass rack bay**, **copper fluid bay, brass fluid bay**, warehouse
   input, warehouse output, warehouse terminal, warehouse production station, **warehouse crafter feeder**, warehouse
   stock keeper, warehouse home point, and the **hand truck**, the one item that is no block, last — the three rack bays stand directly after the interface, weakest first, the two
   fluid bays directly after them, weakest first again, and the crafter feeder directly after the station it serves.
   (`WareworksItemGameTests#creativeTabOrderAndIcon` pins that list; read it from there if the two ever disagree.)
2. Every item icon shows its own model, no missing-texture checkerboard: the crane icon shows the rail bed with a
   miniature crane, the controller icon its display, the rail icon is readable (not a thin line at the slot's bottom).
3. Holding Shift on each of the **seventeen** items shows a short Create-style description (issue #22): one sentence what
   the block is, at most one note the Ponder scenes do not show; the stacker crane and the crafter feeder additionally
   show their stress impact. At **2560x1440 or 5120x1440 with the automatic GUI scale**, in English and in German, hover
   each bay, the terminal, the output, the crane and the crafter feeder (the tallest tooltip of all) in the **bottom row
   of your inventory**: the item name at the top of the Shift tooltip is on screen. **Does each one tell you what the
   block is for without reading like a telegram?**
4. JEI/EMI shows **seventeen** recipes; the stacker crane needs a 3 × 4 mechanical crafter grid (either mirror image) and
   shows a **brass hand** beside its precision mechanism, rails come out **4** at a time and include a **shaft**, the
   terminal takes a **precision mechanism** (not a funnel) like the controller, the output takes two **brass nuggets**
   the input does not, and the home point stays at the andesite tier (a rose quartz lamp over an andesite casing between
   two andesite alloy, with no electron tube and no precision mechanism), and the crafter feeder is one column: a
   precision mechanism over a brass casing over a vanilla Crafter, and the hand truck is a shaft over andesite alloy over
   a cogwheel, an iron sheet and a cogwheel. In the **vanilla recipe book** the other **sixteen**
   appear once you hold one of their ingredients — the terminal shows up with a precision mechanism and the output with
   a brass nugget — while the stacker crane never does, because mechanical crafting has no recipe-book category
   (Create's own mechanical crafting recipes behave the same).
4a. Lay out the **input** recipe (funnel over casing) and then the **output** recipe (casing over funnel with a brass
    nugget on each side of the funnel) in a crafting table: each gives exactly its own block, and neither turns into
    the warehouse interface (casing + funnel + brass nugget, shapeless).

## B. Build an aisle

5. Place the **stacker crane** dock looking along the intended aisle: it is a low rail bed (gearbox port underneath,
   brass end stop at the controller side) with the parked crane standing on it — chassis on the rail between two bogie
   side frames, wide mast with brass bands every block, brass cap on top, lift carriage at the bottom, drive cog in front.
6. Walk over the dock and the rails: you can step over both, the outline you see is only the bed (never a full block),
   and nothing invisible blocks the aisle while the crane is out at a rack.
7. Lay a line of **warehouse rails** in front of the dock: the line looks continuous, joins the dock's rail start without
   a gap or flickering seam, and each rail shows two sleepers with an iron rail profile. The axis follows your look
   direction, the wrench on the top face toggles it, and a rail placed in water is waterlogged.
8. Put a shaft (or creative motor) **below** the dock: goggles show the kinetic stats (4 SU per RPM). A shaft or cog at a
   side or on top does **not** connect.
9. Look down at the dock bed: the **"Mast Height"** value box sits on the bed top, scrolls between 1 and 16, and the mast
   grows or shrinks immediately. With the crane parked, its chassis stands over the box.
10. Place the **warehouse controller** directly behind the dock, facing it: brass casing with a dark display and an orange
    lamp strip that is flush with the display face (no part sticking out, no flicker against a block placed against it).
11. The controller's **"Aisle"** value box (letters A–Z) appears on the display, the sides and the top — never on the face
    touching the dock or on the bottom. Hold right-click to open the letter board.
12. Goggles on the dock read "Stacker Crane:", "Aisle: L long, mast H high" and "Controller linked". L follows laying or
    breaking rails within about 2 s; H follows the value box immediately.
13. Goggles on the controller read "Ready", the aisle size, storage locations, inputs/outputs, item types, items stored
    and open requests. Wrench the **idle** dock once so it faces another way: the controller now reads "The stacker
    crane in front faces another way" (not "No stacker crane in front"). Break the dock instead and it reads "No stacker
    crane in front". Wrench it back and the aisle returns.

## C. Storage locations

14. Place **warehouse interfaces** against chests, barrels or Create item vaults: the brass port with the dark opening
    faces the attached inventory, and the aisle side shows a brass-framed andesite plate with a dark arm slot — clearly
    different from plain andesite casing when you look down a rack wall.
15. Extend a row by clicking the **side of the previous interface**: the new one keeps the same facing. Clicking the side
    of a chest attaches to that chest; clicking stone or the floor uses your look direction.
16. The wrench on the top face rotates the interface clockwise; the goggles then show the new attached inventory.
17. Goggles on an interface inside the aisle show "Address: A-02-03R" (letter, level, position, side), the inventory name,
    "Slots: used / total" and up to three item types. Turned towards the aisle they show "Misaligned" plus the hint;
    outside the aisle "Not part of an aisle".
18. Add items to a chest by hand or by hopper: the goggle lines update within about a quarter second of looking. Items
    that differ only in damage or enchantment share one line (e.g. "Iron Pickaxe x3").
19. Change the controller's aisle letter: every address in the aisle follows within a second of looking.
19a. **Filter slot (M8).** Look at an interface **from the aisle**: a filter slot sits on the lower half of the framed
    plate, below the dark arm slot. Right-click it with an item — the item is drawn there, and goggles read
    "Filter: <item>" instead of "Accepts everything". Right-click with an **empty hand** clears it again. Holding the
    click with an empty hand opens a board for the location's **storage priority**, not for an amount (a storage filter
    has no amount); that board is check 111. Holding the click with an item sets the filter and opens no board
    (issue #30, section BA).
19b. The slot must not swallow the other interactions: the **wrench** on the top face still rotates the block, clicking
    the **side** of an interface with another interface still copies its facing (row building), and clicking the parts
    of the aisle face away from the slot still places a block normally.
19c. **List filter:** put a *List Filter* configured with one item into the slot, feed a mixed stream into the input, and
    watch that chest receive only the listed item while everything else goes to an unfiltered chest. Flip the list
    filter to **deny list** in its own UI: the same chest now takes everything *except* that item. It must behave like
    an ordinary unfiltered chest while doing so: with a second chest that already holds the incoming item, that second
    chest is still used (a deny list says what may not go there, it does not dedicate the chest to everything else).
19d. **Attribute filter:** put an *Attribute Filter* with a rule (e.g. "is damaged", or a tag such as logs) into the
    slot. Only items matching the rule are stored there.
19e. **Package filter:** put a *Package Filter* with an address into the slot and feed Create **packages** through the
    input: a package whose address matches is stored there, one with another address is not.
19f. Give three chests three different filters and feed all three item types at once: each type lands in its own chest,
    and a **dedicated** chest is used even when an unfiltered chest already holds that item. An item matching no filter
    goes to an unfiltered chest; if there is none, the controller goggles read "Last planning: no storage location
    accepts these items" and the **input keeps** its items.
19g. Fill a filtered chest, then **change its filter to something else**: nothing moves, nothing is dropped, the chest
    keeps its contents — and a request for what is inside is still delivered. The controller goggles show "Filtered
    locations: N" under the storage count.
19h. Save and reload the world: the filters are still set. A world created **before** this version loads with every
    chest accepting everything, exactly as before.
19i. **Right after a reload (M8).** Leave a belt or hopper feeding the input, then save, quit and rejoin, or walk
    far enough away that the chunks unload and come back. Items arriving in the first seconds after the aisle loads
    must still respect every filter — nothing may land in a dedicated chest it does not belong in. Break a filtered
    interface as well: the **filter item drops** on the ground and is still configured when you pick it up.
19j. **Two interfaces on one double chest (documented limitation).** Put an interface in front of each half. Only one of
    them counts the chest, and only its filter applies. The other one's goggles say "Without effect: another interface
    counts this inventory" in gold, and the controller's "Filtered locations" does **not** count it — so what the
    goggles claim and what the crane does always agree.
19k. **A filter item you have not configured** (fresh from the crafting table) reads "Empty filter: this location
    accepts nothing" in gold, not a normal-looking "Filter: List Filter" — and the location really accepts nothing.
    Two chests with *different* List Filters must be tellable apart by their goggles (each names its own contents).
19l. Point a **deployer** at the aisle face of a filtered interface and let it right-click with something in hand: the
    filter must **not** change and the filter item that is in the slot must not disappear.

The same slot carries a second setting since M16 — the location's **storage priority**, set by *holding* the click
with an empty hand or a wrench (since issue #30 a click with an item only sets the filter, section BA). It has its own
checks in **section S**, because it needs a rack wall and a longer look at the crane; do them together with these
if you are already standing at the wall.

## D. Stations

20. Place a **warehouse input** and a **warehouse output** at rack positions: standing in the aisle and placing into the
    rack makes the opening face the aisle. The wrench on the top face rotates clockwise.
21. The two stations are **distinguishable at a glance**: the input is andesite grey with a stepped feed throat in its
    aisle opening and a big intake opening on top; the output is brass all over with an open aisle port and a brass spout
    in its pull port at the back.
22. A belt, funnel, chute or hopper feeds the input; funnels, chutes and hoppers pull from the output. Nothing can be
    pulled out of the input, and nothing can be pushed into the output.
23. Right-click the output's **filter slot** with an item: the item is drawn on the top, back and side faces, never in the
    aisle opening. Hold right-click for the "Requested Amount" board — it offers only the "Up to" row.
24. A list, attribute or package filter is refused; pasting a Create clipboard that carries one does nothing and keeps the
    filter item in your inventory.
25. Goggles on both stations show the address (or "Misaligned" with "Turn the opening towards the aisle") and the buffer.

## E. Storing (the first job)

26. Feed a stack into the input: the crane **visibly** travels down the aisle, lifts the carriage, extends its arm through
    the dark port slot of an interface (not through a solid plate) and retracts. The items ride on the arm and end up in
    the chest.
27. Dock goggles during the job: "Status: …", "Storing Iron Ingot x32", "From A-01-00R to A-01-05L", and "Holding:" while
    carrying.
28. Goggles on the **target** interface show "Incoming: Iron Ingot x32" while the job runs, and nothing afterwards.
29. Feed several item types into empty chests: each type goes to a chest of its own; tools with different damage share one
    chest.

## F. Retrieving

30. Set the output filter and an amount, then give it a **redstone pulse** (lever, button, Create pulse): the controller
    goggles show "Open requests: 1", the output goggles "Items requested: N (requests: 1)" and "Delivered so far: …".
31. The crane fetches the items visibly and they land **physically** in the output buffer, where a funnel or hopper can
    pull them out.
32. Goggles on the **source** interface show "Reserved for pickup: Diamond x10" until the pick.
33. Pulse again for more; without a filter, without stock or without a controller the output shows "Last request refused:
    …" with the reason.
33a. Pulse the **same** output several times for the same filter item before the crane has finished: the controller
    goggles still read "Open requests: 1" and the output "Items requested: N (requests: 1)" with N grown by every pulse
    — the pulses are batched into one request and one trip (M7). A pulse for a *different* filter item is a request of
    its own, and the older one is still served first.
33b. Wire a **pulse clock** (Create pulse repeater) to that output, filter a well-stocked item, and let it run while a
    second output or a terminal asks for the *same* item: the clocked request stops growing at
    `maxOpenRequestsPerOutput × the filter amount` (4 stacks by default) with "Last request refused: the open request
    here already asks for the largest allowed amount", the other station is still served, and the crane alternates
    between them instead of serving the clock for ever. Stop the clock and its request drains away normally.

## G. Look and feel

34. While the crane travels, the **wheels turn**, the drive cog spins with the kinetic network (and turns red when the
    network is overstressed), the carriage rides up the mast and the hoist belt above it shortens. Nothing jitters while
    it stands still.
35. Look at the parked crane from above: no flickering speckle on the chassis top, no iron wedge sticking out of it while
    the carriage is lifted, and no flicker where the hoist belt meets the carriage clamp.
36. From a distance, look along a rack wall of interfaces, stations and a controller: no surface flickers (z-fighting),
    no face is missing, and no neighbouring block shows through.
37. Wrench the **idle** dock through all four directions: the crane turns with the aisle and still reaches the correct
    racks on both sides.
38. `/flywheel backend off` looks identical to the default backend.
39. From the far end of a long aisle the crane is still drawn (see the known limitation about very long aisles at low
    render distance in ADR-007 of `architecture.md`).
40. In a dark aisle with a single torch, the crane is lit where it stands and the arm is not black inside a rack.
41. The crane is audible but quiet: a creak when it sets off, soft metal clacks at the rail joints (faster at higher RPM),
    chain steps while lifting, a soft piston when the arm moves, an item pickup at the rack, a depot plop at the target
    and an iron trapdoor when it arrives. An idle or unpowered crane is silent apart from Create's kinetic hum.

## H. Ponder

42. Hover any of the **nine** pondered items and hold **W**: a scene opens, and the arrow keys or the scroll wheel switch
    between the scenes of one item. The counts are the ones `PonderVisualScenario.SUBJECTS` pins, so read them from there
    rather than from this list if the two ever disagree: **stacker crane 2** and **warehouse rail 2** — both show
    "Moving Items with a Stacker Crane" first and "Rails Around a Corner" second — **controller 2**, **interface 3**,
    **input 1**, **output 4**, **warehouse terminal 3**, **production station 3** and **stock keeper 3**. The warehouse
    home point has no scene yet and is not in the tag either.
43. `/ponder index` and the Ponder tag screen list **"Automated Warehouses"** with the stacker crane as its icon and all
    **nine** blocks inside — terminal and production station since M13, the stock keeper since M15; the dock also
    appears under Create's "Kinetic Appliances", and interface, input, output, terminal, production station and stock
    keeper under "Item Transportation".
44. In the *overview*, *storing*, *retrieving*, *requesting*, *production* and *filters* scenes the crane travels, lifts
    and reaches into a rack or station, and the items it carries ride on the arm. The *interface* and *terminal* scenes
    teach addressing and placement and deliberately show the crane parked, with no rotation — nothing moves there. No
    line shows a raw key such as `wareworks.ponder.…`.

The six checks below are the M13 scenes. The screenshot run (`runVisualTest -Pwareworks.visualTest=ponder`) only proves
that each scene compiles, is registered for the right item and looks right in three frozen frames; whether a scene
*reads* while it plays is exactly what it cannot judge.

44a. **"Placing a Warehouse Terminal".** The screen (brass frame, dark display, tray below) is readable in every beat,
    not edge-on. When the controller corrects the port, the change is visible: the arm port appears on the face
    towards the aisle. The wrench beat clicks three times and the screen ends on the face it started on — it must read
    as "the screen walks around the block", not as flicker.
44b. **"Requesting Items at a Terminal".** The order of the beats matches how you really use the screen (open, search,
    click, click again, crane, delivery, pull out). The funnel above the terminal flaps when the items arrive, and the
    text about the terminal's own slots is on screen while the crane actually drops them. **Since M23** the scene has an
    eighth beat: a clipboard floats at the screen and the text says that a whole list can be handed over and is
    ticked off as it is delivered. Judge whether that beat reads as an *offer* at the end of the scene rather than as
    a second lesson crammed on, and whether the clipboard icon is recognisable at Ponder's distance; the German line
    is the longest of the scene, so check it fits.
44c. **"Feeding Machines from a Warehouse".** The three-block tower reads as production station → funnel → Mechanical
    Crafter, and the crafter is recognisable as *your* machine standing next to the station, not as part of Wareworks.
    The sentence "Wareworks delivers and collects; it never crafts anything itself" arrives while the machine is in
    view. It speaks about the station and stays as it is; the rule in full is that Wareworks never crafts on its own,
    and that at a terminal you can craft by hand from what the crane delivered (section AR). The crane's mast crossing
    the tower while it delivers is expected; it must not hide the crafter.
44d. **"Dedicating Storage Locations".** The filter item is visible **on the block**, in the slot on the aisle face, and
    visibly changes from the iron ingot to the gold ingot when the location is re-dedicated. The crane must be seen
    driving **past** the nearer unfiltered location to reach the dedicated one — that is the beat's whole point.
44e. **Pacing.** In every new scene each line can be read to the end before it disappears, at normal speed and without
    the arrow keys. The four scenes run about 26 s, 37 s, 51 s and 60 s; if one feels rushed, note which text.
44f. **German client (`de_de`).** The lines of the four new scenes are translated and **fit their boxes** — German is
    the longer language and the screenshot run only ever renders English, so overflowing or clipped text can only show
    up here. Check the wording in context as well: the storing and retrieving scenes must name "Mechanische Arme"
    alongside Trichter, Rutschen and Hopper.

The two checks below are the M15 scenes of the stock keeper (both open from its item; arrow keys switch them).

44g. **"Stock Rules of a Warehouse"** (968 ticks, about 48 s). Each of the three numbers gets its own beat and each beat points at
    a different block. For the **minimum**, the comparator behind the keeper and the redstone lamp behind it visibly
    turn on — that pair is the whole point of the beat, so check it reads as "the keeper is calling for the item" and
    not as scenery. For the **maximum**, only the outline and the text say that the input is backing up (a station's
    buffer has no renderer): judge whether that lands, and note it if it does not. For the **reserve**, the crane must
    be seen fetching *less* than was asked for and leaving the rack with items in it. The terminal at the end of the
    row must show its **screen** (it stands one position clear of the output so the camera can see that face).
44h. **"A Warehouse that Restocks Itself"** (1051 ticks, about 53 s). It must read as *the warehouse deciding*, not as somebody
    ordering: nothing is clicked before the crane sets off. The arm and the Mechanical Crafter are recognisable as
    **your** machine, as in "Feeding Machines from a Warehouse". At the end the keeper's lamp goes **out** when the
    product is stored, and then the paused beat shows the **other** lamp — look at whether the two lamp states can be
    told apart at Ponder's distance, which is the same question as check 106 in a smaller picture.

## I. Robustness (worth one pass before shipping)

45. Remove the rotation (break the shaft) or overstress the network mid-job: goggles read "Paused: no rotation" /
    "Paused: overstressed" and nothing moves; restoring it continues the job.
46. Wrench the dock **during** a job: nothing rotates and the action bar says "The stacker crane is busy: …".
47. Break the dock while the crane carries items: exactly those items drop at the dock.
48. Break an output while the crane carries items for it: the items go back into a storage chest, never into another
    output.
49. Fill the only chest while the crane carries items to it and break the input: the crane holds the items ("Holding
    items, no target found"); take items out of that chest and it stores them within a few seconds.
50. Save and reload the world (or walk far away and come back) during a job: the job continues, nothing is lost or
    duplicated.
51. Break the controller while the crane carries items: it finishes that job and then stops taking new ones. Place a
    controller again and the aisle picks up where it left off.
52. Break rails so the aisle ends in front of a carrying crane: the items go to a location still inside the aisle (or back
    into the input), the crane drives back into the aisle, and the chest left outside keeps its contents but disappears
    from the controller's counts.
53. Build two aisles next to each other: each controller counts only its own chests, and a request at one output is never
    served from the other aisle's stock.

## J. Configuration

54. Set `crane.travelBlocksPerTickPerRpm = 0` in `config/wareworks-server.toml`: a powered crane stays still, goggles read
    "Paused: a crane speed factor is 0 in the server config", and the log warns once naming the key. Restoring the value
    resumes the job.
55. Lower `aisle.maxMastHeight` below a crane's mast height: the mast shrinks to the limit. Raising the limit again
    **restores** the height you had scrolled (the stored value is never overwritten), and the value box accepts higher
    numbers again about two seconds later, at the next geometry refresh.

## K. Language

56. A German client (`de_de`) shows every goggle line, every item description, the action-bar message and all Ponder texts
    translated — no raw keys, no English leftovers.

## L. Warehouse terminal (M6)

57. The **Warehouse Terminal** comes after the output in the creative tab (it was the last item until M11); its item icon shows the **screen side** — a
    brass frame around a dark teal display with a search row and a grid of item cells, and the take-out tray below it —
    with no missing-texture checkerboard.
58. **Stand on the far side of the rack, not in the aisle**, and place the terminal into a rack position: the **screen
    faces you** and the crane's **arm port** ends up on the aisle side, whichever side that is. The screen is a
    recessed dark display in a brass frame, readable as a search row over a grid of item cells, with a recessed
    take-out tray under it that reads as the opening items come out of; the two remaining sides show brass bands over a
    recessed ribbed steel panel. It is distinguishable at a glance from the output (fully open brass port, no screen) and
    the controller (small display plus orange lamp). Redstone next to it does nothing.
58a. **Wrench any face** (top, bottom or a side): the screen jumps to the next face clockwise and comes back after three
    clicks, and the arm port **never moves** and is never covered by the screen. Do this while the crane is bringing
    something: it is not refused (unlike the wrench on the dock), the delivery arrives anyway, and nothing is lost.
58b. Place a terminal the wrong way round, with its **screen towards the aisle**: goggles say "Misaligned" with "Turn
    the screen away from the aisle with the wrench", the controller counts it under "Misaligned blocks", and its screen
    refuses requests. One wrench click fixes both — the screen moves off the aisle side and the arm port moves onto it
    within a second, and the goggles then show an address.
58c. Build a terminal in an **aisle that runs to its left or right** rather than behind it: the arm port ends up on that
    side by itself while the screen stays facing you, and the crane delivers normally.
58d. **Old world (only if you have one built before this update):** open a save whose terminal was built the old way.
    The block is still there with its buffer and its open request, the arm port still points into the aisle, and the
    screen is now on the opposite face — where you used to stand to take items out. Nothing has to be rebuilt.
58e. Walk **around** the terminal and look at it from above, in daylight and in a dark room, and put a block against
    each of its faces in turn: no missing-texture checkerboard, no flickering surfaces where two shells meet
    (z-fighting), no see-through gap at the corners, inside the screen recess or inside the tray, and the top and the
    bottom stay flush brass. Blocks placed against it are still occluded normally, and it is still a solid full block
    to walk into and to target.
59. **Right-click it**: the screen opens, with an empty hand and with a block or food in hand alike (check 257). With
    a wrench in hand it rotates instead. Check the window at GUI scale 2, 3 and 4 in a 1280 × 720 window — it must
    never be cut off.
60. Type in the search box (it has the focus at once): the list narrows as you type, `@create` shows only Create's items,
    the sort button cycles "most available first" / "by name", and the second button hides what is already promised.
61. Hover an item: the tooltip shows "In stock", "Available" and "Promised to other requests". Click it: the status line
    turns green, the crane starts, and the line then reads "Waiting for …, delivered …" until the items arrive under
    **"Delivered here"**. Shift-click asks for a stack, Ctrl-click for everything available.
62. Take delivered items out by hand or shift-click; nothing can be put **into** the terminal's slots, and a funnel,
    chute or hopper can pull them out.
63. Walk away from the terminal, or have someone break it, while the screen is open: it closes by itself.
64. A German client shows the whole screen in German (search box, buttons, status line, refusals).
64a. Hover a delivered stack in "Delivered here" (or one in your own inventory) and press **1–9** or **Q**: the stack is
    swapped to the hotbar or dropped, as in any other container, and no digit lands in the search box. Typing still
    works as soon as the mouse is off the slots.
64b. Set `terminalBufferSlots = 27` in `wareworks-server.toml`, restart, and open a terminal at GUI scale 4 in a
    1280 × 720 window: the window is still complete (title, status line, all buffer slots, whole player inventory); the
    item grid shows fewer rows and scrolls instead.
64c. With `maxTerminalStockEntries` set low (e.g. 16) in a warehouse holding more item types than that: the screen shows
    "+N not shown" beside "Delivered here", and requesting items never makes a listed item disappear from the grid —
    rows stay put while the crane works.
64d. **Click the same item ten times in a row** before the crane brings anything (M7): the status line counts the
    request up ("Requested Andesite x1, waiting for 10") on a row of its own, readable end to end and never overlapping
    the "Open requests" number, which sits beside "Inventory" and stays **1**, and
    the crane makes **one** trip that brings all ten. Clicking more while the crane is already on its way adds to the
    same request and costs at most one further trip; nothing is lost and nothing arrives twice.
64e. Scroll the amount to the maximum (`maxTerminalRequestAmount`, 1024 by default) and click the same item twice: the
    second click is refused in red with "the open request here already asks for the largest allowed amount" instead of
    promising more than one request may ask for. It is accepted again once part of the request has been delivered.
64f. Request an item with a **long name** (e.g. Polished Blackstone Bricks) twice, and check it in German too: the
    status line stays inside the window — the item's name is cut with "…" while the amounts stay readable — and it
    never runs into the frame or into another text.

## M. Showcase world (M6)

65. Copy `run/showcase/saves/wareworks_showcase` into `run/saves`, start `./gradlew runClient` and open **"Wareworks
    Showcase"** from the world list. You spawn **in creative, standing on the ground in front of the warehouse**, and can
    immediately mine, place and right-click — the earlier demo world could not, which is what this world fixes.
66. The starter chest beside the spawn point holds Engineer's Goggles, a Wrench and a stack of every Wareworks block.
    Signs label the dock and motor, the controller, the terminal, the input, the output and a storage location, and every
    sign reads the right way round.
67. The crane is powered and idle (creative motor at 96 RPM below the dock). Drop items into the chest on top of the
    input tower: the hopper feeds the input and the crane visibly stores them in a rack.
68. Right-click the terminal and request something: the crane fetches it into the terminal's slots.
69. Flip the lever beside the warehouse output: it requests the preset filter item, the crane delivers it and the hopper
    below pushes it into the pull chest.
70. Put the goggles on and look at the controller, the dock, an interface and the terminal: addresses, stock, the crane's
    job and the open requests all read sensibly.
70a. **The sawmill loop** (right-hand rack wall, labelled by signs): a production station one level up, a hopper below
    it, a short **feed belt**, a **Mechanical Saw** lying face up with its own creative motor, and a warehouse input
    beside it. Open the terminal,
    search for **Shaft**: none are in stock, but the cell is tinted with a `+`. Order 6. The crane carries **andesite
    alloy** to the production station, the hoppers walk it into the saw, the saw cuts and pushes the shafts into the
    warehouse input, the crane stores them and then delivers 6 of them into the terminal. The order line goes from
    "waiting for ingredients" through "at the machine" and "waiting for the result" to "complete". Nothing is ever
    dropped on the floor.
70b. **The mechanical crafter loop** (left-hand rack wall): a production station two levels up, a hopper dropping onto a
    **belt**, three **brass belt funnels** over the belt and three **Mechanical Crafters** behind them. Order 6 **Fire
    Charge** at the terminal (two runs of the pattern). Watch the three ingredients ride the belt and each one get
    lifted by *its own* funnel into *its own* crafter — gunpowder, blaze powder and coal must never end up in the wrong
    one — and the fire charges appear in the warehouse input the last crafter points at. Order 6 again and check the
    second pass is routed just as cleanly; a wrong routing shows as items piling up on the belt or dropping in front of
    the crafters.
70c. Break one of the two production stations with a pickaxe while an order of that loop is running: the order is
    cancelled, the terminal stops waiting, and the ingredients already delivered drop as items rather than vanishing.
    Put the station back and write the pattern again (the block does not remember it).

## N. Warehouse production station (M11)

> The feature in one sentence: **Wareworks never crafts on its own; at a terminal you can craft by hand from what the
> crane delivered** (section AR). At a production station it carries the ingredients to your machine and collects the
> product again. Everything below assumes a working aisle with a terminal, an input and an output.

71. The production station is the **eighth** item of the creative tab, after the terminal. Its icon shows the block, and
    Shift shows a Create-style description (check the German client too). JEI/EMI shows its recipe (three brass funnels,
    a brass casing and an andesite alloy), and it appears in the vanilla recipe book once you hold a brass funnel.
72. Place it in a rack beside the aisle, standing in the aisle: the **opening faces the aisle**, like an input or an
    output, and the goggles show an address. From the aisle it is clearly a different block from the input (andesite),
    the output (brass) and the terminal (screen) — it is the **copper-bodied** one with brass frames.
73. **Right-click it**: the pattern screen opens, whatever you hold (check 257). With a wrench in hand it rotates
    instead. Check the window at GUI scale 2, 3 and 4 in a 1280 × 720 window — it must never be cut off.
74. The screen shows a **3 × 3 grid**, an arrow, a result cell and a strip of **pattern tabs** on the right. Click a
    grid cell while holding an item: the item appears as a ghost. **Your item is not consumed** — check the count in
    your inventory. Click a filled cell with an empty hand to clear it, and **scroll** on a cell to change its amount
    (Shift scrolls faster).
74a. Click a tab to switch patterns; the selected tab is highlighted and each tab shows that pattern's result.
    **Right-click a tab** to empty that pattern slot. Shift-clicking an item in your own inventory drops it into the
    first free grid cell.
74b. Try to put the pattern's **result** into a grid cell, and an item that is already in the grid into the **result**
    cell: both are refused (nothing happens), because a pattern may not produce one of its own ingredients.
75. Write "1 log → 4 planks": one log in a cell, four planks in the result. The goggles on the station now read
    "Patterns: 1". Now put the **same item into several cells** (for example three separate plank cells) and check that
    the crane later makes **one** trip for all three, not three trips.
76. Open a **terminal** in the same aisle with no planks in stock: the planks are listed anyway, their cell shows a
    **`+`** instead of a number, and the tooltip says "Can be produced here".
77. Order 8 planks at the terminal. The status line reads "Requested Oak Planks x8, producing 8". The crane fetches the
    **logs** and delivers them into the **production station** (not into the terminal). The station's goggles count the
    order and show its state.
77a. Let your own funnel, chute or belt carry the logs out of the station into your sawmill, and feed the sawmill's
    output into a **warehouse input**. The planks are stored normally, the order goes to "complete", and the crane then
    delivers the 8 planks to the terminal you ordered from. Nothing appears out of thin air at any point.
78. Order something whose ingredient is **not in stock**: the request is refused with "a requested item is not in
    stock", and no order is created. (Stage 1 does not make the ingredient first — that is deliberate.)
79. **Cancel**: start an order, let the crane deliver the ingredients, then click the order's line in the station's
    screen. The line turns gold and reads "…, ingredients not recovered", and the terminal stops waiting. The
    ingredients already delivered are **still lying in the station** (or in your machine) — they are not returned, by
    design.
80. **Timeout**: start an order and never run the machine. After about five minutes (`productionOrderTimeoutTicks`) the
    order gives up by itself, the reserved ingredients are free again, and nothing was invented. While the machine is
    slowly working, the order must **not** time out.
81. Save and quit during a running order, then load the world again: the order, its patterns and the waiting request are
    all still there and the loop finishes normally.
82. A **German client** shows the whole screen, the goggle lines and the order states in German.

## O. Production at the terminal (M11, terminal production UI)

> Everything below happens at a **warehouse terminal** in an aisle that has a production station with the "1 log → 4
> planks" pattern of section N, and **no planks in stock**.

83. Open the terminal. The planks are in the grid although none are stored: the cell shows a blue **`+`** where other
    cells show a number, and the tooltip reads "Can be produced here" plus "Can be made now: N" (N = four times the
    logs in stock). Sort by **name** as well: the planks stay at the **end** of the list, behind everything that is
    really in stock, in both sort orders.
84. Take every log out of the warehouse, look again: the planks are still listed with the `+` and "Can be produced
    here", but **without** the "Can be made now" line — the pattern exists, the ingredients do not. Ordering then is
    refused in red with "a requested item is not in stock".
84a. Put the logs back and **Ctrl-click** the planks: the request is accepted for **everything the logs allow** (four
    per log), not for one, and the status line reads "Requested Oak Planks x N, producing N". Shift-click and a plain
    click work on the planks too, although none are in stock.
85. Under the status line the **Production** section now lists the order: "Oak Planks xN" on the left, its state on the
    right, and a red **`x`** at the end. Watch the state change while the crane works: "waiting for ingredients", then
    "at the machine" once the logs are in the station, "waiting for the result" once your machine has taken them, and
    "complete" when the planks arrive — all without closing the screen. The state must always be readable in full: try
    it with a long item name too (the **item** is what gets shortened, never the state). With no order at all the
    section reads "No production order".
86. **Cancel from the terminal**: hover the order line (the tooltip says what it still waits for and "Click to give up
    on this order"), then click it. The order ends, the line stays for a while so it can be read, and if ingredients
    had already been delivered it turns gold with "ingredients not recovered". Cancelling again does nothing, and a
    second player standing at another terminal of a **different** aisle can never cancel it.
86a. Set `terminalBufferSlots = 27`, restart, and open the terminal at GUI scale 4 in a 1280 × 720 window: the window
    is still complete. The production section is what gives way for such a buffer — the stock grid and the buffer keep
    their rows, and the order lines disappear rather than the window growing past the screen.
86b. A **German client** shows the section label, the order lines, the states and the tooltips in German.

## P. Mechanical Arms at the stations (M12)

> Take a **Mechanical Arm** item from Create's tab. Selecting targets with it happens on the client, so the GameTests
> only prove what an arm *does* at a station, not what the player sees while selecting.
>
> **Automated in the real game (2026-09-17).** Two scenarios of the visual harness play these checks:
>
> * `./gradlew runVisualTest -Pwareworks.visualTest=arm` plays checks 87–91 and the single-player half of 92 in a real
>   client (about 3 minutes). Every assertion writes a `CHECK <n> PASS` line to `run/visual/logs/latest.log`; a good
>   run ends with `ALL CHECKS PASSED` and `PASSED`, and it fails as soon as one check does not hold.
> * `arm-dedicated` plays the dedicated-server half of 92 against a running `runServer` (see 92 for how to start it).
>
> The clicks are real. In the world, the use, attack and hotbar keys are clicked the way the mouse handler clicks them,
> and the crosshair is checked on the intended face before every click. In a screen, mouse clicks, the mouse wheel and
> Escape go through the same handler methods the GLFW callbacks call (`MouseHandler#onPress`, `#onScroll`,
> `KeyboardHandler#keyPress`), at positions checked against the screen's own hit test. What a person does differently:
> no GLFW device event starts the input, every click is a short click (no key is ever held, so nothing that needs
> "click and hold" is played), and no modifier key (Shift, Ctrl) is ever down. Under each check, **Automated** says what
> the runs prove and where they take a different path than a player, and **By eye** what is left for a person.

87. Hold the arm item and right-click a **warehouse input**: it gets a **yellow** outline and the action bar reads
    "Deposit items to Warehouse Input". Right-click it a few more times: it **stays yellow** with the same message — the
    input can never be selected as a source. (On a depot, for comparison, each click switches between yellow and blue.)

    **Automated** (`arm`, and again in `arm-dedicated`): three right-clicks on the input, each asserted as "deposit"
    (`WarehouseInputArmPoint`) with the outline colour `#DDC166` and the action bar "Deposit items to Warehouse Input";
    three clicks on a depot switch take, deposit, take. **By eye:** nothing left (shots `select-input-click1`,
    `select-input-click3`).
88. Right-click a **warehouse output**, a **warehouse terminal** and a **warehouse production station**: each gets a
    **light blue** outline with "Take items from …", and further clicks keep it blue. Left-click removes a selection as
    usual. Then right-click a warehouse **interface**, the **controller**, the **crane dock** and a **rail** with the arm
    item: none of them is selected; the arm is simply placed against the block like against any other. While the arm
    item points at the output's request filter slot (the middle of its top face), no "Click with item to set" hint
    appears.

    **Automated** (`arm`; selection of all three also in `arm-dedicated`): output, terminal and production station are
    each clicked three times and stay "take" (`DeliveryStationArmPoint`, outline `#7FCDE0`, "Take items from Warehouse
    Output", "… Warehouse Terminal", "… Warehouse Production"). The first click on the output lands on its request
    filter slot: it must select the output and leave the filter empty. A left-click removes the terminal from the
    selection and the terminal stays; the left-click is only tried on the terminal. After the clicks on the terminal and
    on the production station, neither the server nor the client has a screen or menu open (the stations now open with
    anything in hand, issue #26, ADR-060; the arm item keeps its own click). Right-clicks on the interface, controller,
    dock and rail select nothing, show no message and place an arm against the block. With the arm item on the filter
    slot for 25 ticks, Create's outliner holds no value box for the slot and its hint overlay shows nothing; with an
    empty hand on the same spot both appear, so the check can see them (shots `select-output-filter-slot`,
    `output-filter-slot-empty-hand`). **By eye:** nothing left.
89. Place the arm with an input selected as its target and a depot with a stack of items as its source, and power it.
    The claw visibly reaches for the **middle of the input's top face** (not into the opening on the aisle side), the
    items end up in the input's buffer (goggles) and the crane stores them. Put a block on top of the input: the arm
    still delivers, and only the claw dips into that block.

    **Automated** (`arm`): arm A, placed by a click, moves 32 iron from a depot into the input and the crane stores all
    of it; with a stone block on top of the input it moves 32 gold the same way. Arm B empties the output after a
    request and arm C the terminal after 12 diamonds were requested in the terminal screen (right-click to open it, the
    mouse wheel on the amount field, a left click on the diamonds). An item census of the whole scene holds around every
    item move. Where the claw reaches is measured: the game is frozen one tick before the claw arrives, and the claw is
    placed with the arm renderer's own transforms. For the angles the movement ends in, the claw's axis passes through
    the centre of the input's top face (0.0001 blocks off) and the claw tip lies 0.015 blocks from it; in the frozen frame
    the tip is nearest to the top face centre of all six face centres (0.315 blocks, the aisle-side face 0.977). The same
    holds for the output, and with the stone block on top the claw's grip ends inside that block. Differences: the items
    are put on the depot through its item handler instead of by hand, the output's request is the controller call its
    redstone input makes, and the stored items are checked instead of the goggles (`arm-dedicated` feeds by hand and
    triggers the output with its filter slot and a redstone block). **By eye:** nothing required. The motion itself was
    only looked at in frozen frames (shots `claw-input-west`, `claw-input-southwest`, `claw-covered-input-west`,
    `claw-output-*`); watch it once in game if the animation between those frames matters.
90. Build the "1 oak log → 4 oak planks" loop of section N with a single **Mechanical Crafter** as the machine: the
    production station, an arm that takes from the station and deposits into the crafter, and the crafter pointing
    into a warehouse input. Order oak planks at the terminal. The arm carries **one log at a time** into the crafter
    and waits while the crafter is busy, the order moves on to "waiting for the result" once the arm has taken every
    log, the planks go through the input into storage and the order reaches "complete". Nothing is dropped on the
    floor at any point.

    **Automated** (`arm`, and with its own pattern in `arm-dedicated`): this loop, with arm D set up by clicks. The
    planks are ordered in the terminal screen the way a player orders them (an empty hand opens it with a right-click,
    the mouse wheel sets the amount to 12, a left click on the planks sends the request; Escape closes it), and the
    server must then hold a new production order for exactly 12 planks. The order must pass `WAITING_FOR_INGREDIENTS`,
    `DELIVERED`, `WAITING_FOR_RESULT` and `COMPLETE` in this order (recorded every server tick); arm D never holds more
    than one log; 3 logs become 12 planks that reach the terminal; no item entity lies in the scene and the census
    holds. The reopened terminal screen shows "waiting for the result" and then "complete" (shots
    `terminal-order-waiting-for-result`, `terminal-order-complete`), and the claw aim at the production station is
    measured as in 89. After the rejoin a second order of 4 planks is placed the same way. Differences: in `arm` the
    pattern is written by the test (`arm-dedicated` writes it by clicks in the production station's screen), and "waits
    while the crafter is busy" is covered only by the one-log limit. **By eye:** nothing left.
91. Shift on the input, output, terminal and production station items now names **Mechanical Arms** beside funnels and
    chutes. On a **German client** the output and terminal read "Mechanische Arme", the input reads "Mechanischen
    Armen" and the production station reads "Mechanischer Arm", and the arm's selection message names the German block
    ("Lege Gegenstände in Lagereingang").

    **Automated** (`arm`): all four items in English and German with the phrases above, and the German selection
    message on the input. The tooltip without Shift is the real one (`Screen#getTooltipFromItem`, which every inventory
    screen calls); the Shift view is rebuilt from Create's item description (`ItemDescription#linesOnShift`), because
    the test cannot hold the physical Shift key. The run switches the language at runtime the way the language screen
    does (select, reload resources) instead of starting a German client. **By eye:** only the physical Shift key: hold
    it once over one of the four items (shots `tooltip-de-*`, `select-input-german` show the texts).
92. On a **dedicated server** (`runServer`, then join with `runClient`): select a station with the arm item and place the
    arm. The arm works the station exactly as in single player, it still does after the server restarts, and neither
    log shows an error.

    **Automated, single player** (`arm`): after saving, quitting to the title screen and rejoining, all four arms have
    their points with the same type ids and modes and keep working (arm A feeds the input, B and C empty output and
    terminal, D runs a second crafter order); the census holds throughout.

    **Automated, dedicated server** (`arm-dedicated`, passed on 2026-09-17 with 45 `CHECK 92 PASS (dedicated)` lines):
    a client joins a running `runServer` over TCP and builds the aisle of the `arm` scenario with commands. With real
    clicks it selects all four stations (input, output first on its filter slot, terminal, production station), two
    depots, a basin and a Mechanical Crafter, and places four arms, so the synced arm point type registry,
    `ArmPlacementPacket`, Create's value settings packet and the Wareworks screen payloads really cross the network. It
    writes the pattern "1 oak log → 4 oak planks" in the production station's screen (picking the items up from the
    inventory, clicking the cells, scrolling the result to 4), feeds 32 iron and 12 logs onto a depot by hand, sets the
    output's filter by clicking its slot with an ingot and requests with a redstone block, and orders 8 planks in the
    terminal screen, keeping the screen open until it shows the order complete. It reads the server's block entity data
    back with `/data get block`: the saved points of all four arms, the pattern, and the items in depots, claws, station
    buffers, crafter, basin and chests after each step (arm A fed the input, arm B emptied the output, arm D fed the
    crafter, arm C moved the planks out of the terminal into the basin). It then stops the server with `/stop`, joins
    again once the server is back, checks the saved points and the point classes the client arms resolved, and moves
    16 gold and 4 more planks through all four arms. Arm C delivers into a basin because a depot holds one stack and an
    arm puts nothing onto a depot that holds one, while planks can reach the terminal in two deliveries. To run it again
    (the client cannot start the server itself):

    1. Back up `run/server/server.properties` and `run/server/ops.json`. Set `level-name` to a throw-away world (never
       `world`), `level-type=minecraft\:flat` with the classic flat layers, `gamemode=creative`, `allow-flight=true`
       and `spawn-protection=0`, and add the offline dev player `Dev` (UUID `380df991-f603-344c-a090-369bad2a924a`) to
       `ops.json` with level 4.
    2. Start `./gradlew runServer`. The client stops the server once in the middle of the run: start it again right
       away (the client checks the server's port every second and joins once it accepts connections, for up to 10
       minutes). At the end the client stops it again.
    3. Start a client with the system property `wareworks.visualTest=arm-dedicated` (another address:
       `wareworks.visualTest.server=<host:port>`, default `localhost:25565`). `build.gradle` has no run for this; the
       2026-09-17 runs used `runClient` with a local Gradle init script that sets this property, a 1600 × 900 window and
       the game directory `run/dedicated-client`.
    4. The client's `logs/latest.log` (in `run/dedicated-client` for that run) must show the `CHECK 92 PASS (dedicated)`
       lines, `ALL CHECKS PASSED` and `PASSED`. Then restore both files and delete the throw-away world.

    In the 2026-09-17 run neither server log nor the client log had an `ERROR` line. **By eye:** nothing left for one
    machine. Not covered: a client on another machine with real network latency, and a player who is not an operator
    (the scene is built with commands).

## Q. Stock displays (M14)

> Take a **Display Link** and a display target from Create's tab. Build the aisle of sections B–F, stock it, and hang
> the links the way Create intends: right-click the **target** (nixie tubes, display board, sign, lectern) with the
> link item to select it, then place the link **on the source block**. Right-clicking the placed link opens its screen,
> where the source is chosen; shift-right-clicking with the item in hand clears a selection.
>
> **Partly automated in the real game (2026-09-25).** `./gradlew runVisualTest -Pwareworks.visualTest=display` builds a
> powered aisle with a wall of four display boards and a nixie row, hangs five real links and lets them pull on their
> own passive schedule. Before each of its 20 shots it compares every line against the controller's own numbers read
> in the same server tick and checks that each flap section fits its flap count, so a wrong, stale or clipped line
> fails the run. What it cannot judge: whether a board is **readable** from where a player stands, how the update
> *feels*, and anything in German — the run is English only. Under each check, **Automated** says what the run proves,
> **By eye** what is left for a person.

93. Open the Display Link screen on each Wareworks block. The **controller** and the **terminal** offer **"Warehouse
    Summary"** and **"Stock List"**, in that order and with "Warehouse Summary" preselected; the **output** and the
    **interface** offer **"Stock of the Filtered Item"**; the **crane dock** offers **"Crane Status"**. On the
    **rail**, the **input** and the **production station** the link offers nothing at all. Restart the game once and
    open the controller's screen again: the two entries are still in the same order. On a **German client** the four
    read "Lagerübersicht", "Bestandsliste", "Bestand des gefilterten Gegenstands" and "Status des Regalbediengeräts",
    and a board on the controller reads "Lager A: Bereit", "Plätze: …", "Gegenstandsarten: …", "Gegenstände: …" — with
    the crane broken, "Lager A: Kein Regalbediengerät" (the mod uses no short form for that block).

    **Automated** (GameTest `displaysourcesregistered`): the four ids resolve in Create's registry, each name uses the
    generated lang key, and `DisplaySource.getAll` returns exactly the expected list, in the expected order, for all
    nine blocks it lists (every Wareworks block except the home point, which has no display source and no ticker). The screen itself is never opened by a test. **By eye:** that the screen really lists them that way
    and that nothing is cut off in German, plus the preselection after a restart.
94. Point a link on the **controller** at a display board (or a row of nixie tubes) with "Warehouse Summary". Within about
    five seconds it reads `Warehouse A: Ready`, `Locations: 3 / 30`, `Item types: 3`, `Items: 176`. Put on goggles and look
    at the controller: the same numbers. Store something and watch the board pick the change up on its own. Break the
    crane dock: the board falls back to the single line `Warehouse A: No crane`.

    **Automated** (`display`): all four lines compared string by string against the controller's letter, status,
    `occupiedLocations`/`countedStorageLocationCount`, `distinctKeys` and `totalItems`, at rest and again after the
    aisle grew (176 → 312 → 400 items). The `No crane` and `No warehouse` cases are GameTests (`aislesummarydegraded`,
    `sourcesoutsideaisle`), and `aislesummarysharedinventory` pins that two interfaces on one double chest count as
    one inventory in the second number. **By eye:** whether four lines on a 6 × 2 board are legible from the aisle,
    and whether a five-second refresh feels right.
95. Point a link on the **terminal** at a display board with "Stock List". The most stocked item types appear largest
    first, amount then name, and the list is cut to the board's rows. Scroll the link's own **"Display"** option between
    *Shortened* and *Full Number*: the amounts switch between `1K` and `1024`. Stock two item types to exactly the
    same amount and watch the
    board over several refreshes: the two lines keep their order and never swap.

    **Automated** (`display` and GameTests `stocklistoncontroller`, `stocklistonflapdisplay`,
    `stocklistdeterministicties`, `stocklistemptywarehouse`): largest-first order, the row limit, the flap columns, a
    fixed order over five consecutive pulls for two equal amounts, and an empty aisle listing nothing. **By eye:** the
    shortened-number option, which is Create's own widget, and readability.
96. Put an iron ingot in a **warehouse output's** request filter and point a link on it at a row of nixie tubes with
    "Stock of the Filtered Item": the row shows the aisle's whole iron stock, not what the output holds. Type a text
    into the link's **"Label"** field and it appears in front of the number. Take the filter item out: the row reads
    `0`. Repeat on a **warehouse interface** with its store filter; a **List Filter** in that slot also reads `0`.

    **Automated** (`display` and GameTests `filteredstockonoutput`, `filteredstockoninterface`): the stored total for
    both slots, `0` for a cleared slot and `0` for a Create list filter. **By eye:** the label field (Create's own
    widget) and that a nixie row is legible.
97. Point a link on the **crane dock** at a display board with "Crane Status" and request something. The board follows
    the crane within about a second: `Storing` / `Retrieving` / `Supplying`, the item with its amount, `To A-01-04R`,
    and `Empty` flipping to `Holding Iron Ingot x64` when the grabber picks up. Turn the power off: it reads `Paused`.
    With no job it reads `Idle` and `Empty`.

    **Automated** (`display` and GameTests `cranestatusidle`, `cranestatusonjob`): activity, item with amount and
    target address compared against the crane's own `CraneGoggleInfo` pulled in the same server tick, plus the idle,
    holding and paused states. **By eye:** whether a one-second refresh keeps up with the crane well enough to be worth
    watching, and that the board does not look like it is stuttering.
98. **Targets and their limits.** Write the aisle summary onto a **sign** and onto a **lectern** on a *dedicated*
    server, and read both with a **German client**: the lectern is in German, the sign is in English (`Warehouse A: Ready`
    …), because a sign stores plain text and the server flattens the line in its own language — Create's own sources
    behave the same, and a board, a nixie row or a lectern is the target to use. Then point a link on a terminal that
    belongs to **no warehouse** at a board: it reads `No warehouse` (it said `No aisle` until the M22 review fix — it
    is drawn in the slot that otherwise names the warehouse).

    **Automated** (GameTest `displaylinkonsign`, and the small board of the `display` scenario): the sign's four lines
    are the flattened components cut to the sign's line width, line 0 being the English `Warehouse A: Ready`, and the
    stray terminal's board reads exactly the `No warehouse` line — and that the longer wording still fits the board.
    **By eye:** that a German client really sees the sign in
    English and the lectern in German, so the note in `warehouse-system.md` §10.2 is worded honestly.

## R. Stock rules (M15)

Build one aisle with a **warehouse input**, a **warehouse output**, a **warehouse terminal**, a **warehouse production
station** with a machine of your own beside it, and a **warehouse stock keeper**. Everything below is about the three
numbers of one rule, so keep the keeper's screen open on a second monitor if you can.

99. **The block and its screen.** Stand in the aisle and place the keeper into a rack: its panel faces you. Right-click
    it — the rule screen opens, with an empty hand and with an item in hand alike (check 257) — and the wrench still
    rotates it. Click a row's cell with an item on the cursor: the row takes the item and **nothing is
    consumed**. Scroll on each of the three numbers (Shift for whole stacks), right-click one to switch it off, and
    left-click an off number to switch it on at 0. Shift-click an item in your own inventory: it fills the first free
    row.

    **Automated** (`StockKeeperGameTests`, and the `keeper` visual scenario, which shows five rules in five different
    states in one screenshot and edits them through the real payload path): the rows, the clamping, the payload path and
    the persistence; the `restock` scenario clicks a row's **status mark** with a real mouse button, so the mark is at
    least big enough for the screen's own hit test. **By eye:** whether the three columns read as three different
    things without the tooltip, whether the numbers are legible at every GUI scale, and whether the mark is big enough
    for a *hand* to aim at.
100. **Minimum.** Set "keep 64" on an item the aisle holds none of. The keeper's **lamp** lights and a comparator next
    to it reads **1**. Put 64 of the item in through the input: the lamp goes out and the comparator drops to 0 within
    a second. Add a second rule for another missing item: the comparator reads **2**.

    **Automated** (`StockKeeperGameTests`, `StockRuleEnforcementGameTests`): the lamp state, the comparator value and
    that it is not saved across a restart. **By eye:** that the lamp is readable from the aisle and that a comparator
    on the back of the keeper is where a player would look for it.
101. **Maximum.** Set "store at most 32" on an item and drop 64 of it into the input on a belt. Exactly 32 are stored,
    the rest **stay in the input on purpose** and the belt backs up. Look at the **controller** through goggles: "At
    maximum: 1". Raise the maximum: the rest is stored within a second.

    **Automated** (`stockrulemaximumreachedinflight`, `stockrulechangedmidjob`): the in-flight trip finishing, the next
    plan refused with `AT_MAXIMUM`, and storing resuming when the rule goes. **By eye:** whether a backed-up belt reads
    as "the rule is working" rather than as a jam, which is exactly what the goggle line and the display line exist to
    answer.
102. **Reserve, and who it protects.** Set "reserve 16" on an item the aisle holds 32 of. Pulse a **warehouse output**
    with that item in its request filter: it delivers 16 and then refuses, and the controller's goggles say the rule
    keeps the rest in reserve — never "not in stock". Now ask for the same item at the **terminal**: you are served
    down to the last one, and the row's tooltip says your request goes below the reserve.

    **Automated** (`stockrulereserveraisedkeepspromises`, `stockruleterminalreportsrules`): the clamp, the
    `RESERVED` refusal, the badge and the tooltip numbers. **By eye:** that the terminal row makes it obvious which of
    the two cases you are in, and that the badge colour is distinguishable from the producible tint.
103. **The confirmation.** With that reserve still set, click the item in the terminal so that the request would reach
    into it: a short panel asks, naming the number ("This takes 10 of the 64 items held in reserve."), and **nothing is
    requested yet** — the status line stays as it was and the crane does not move. **Escape** and **Cancel** drop it,
    **Enter** and **Confirm** carry it out, and while the panel is up the grid behind it does nothing. Hold **Alt** and
    click: no question at all. Then two more cases:
    * put the reserve on the **ingredient** instead (the logs) and order **planks** at the terminal: the panel names the
      log by name — "Making it takes 1 of the 8 reserved Oak Log." — although no rule governs planks at all;
    * set a **maximum of 2** on the planks and order **one** plank: a run makes four, three of them stay in the racks,
      and the panel names the one that has nowhere to go ("1 of the 4 items this makes cannot be stored: the maximum is
      2."). Now order **four** planks with the same cap: **no question at all**, because every plank the run makes goes
      to you and the cap is never really exceeded. That distinction is the point — the question is about what stays.

    **Automated** (`TerminalConfirmationGameTests`; the `rules` scenario shoots the reserve panel, and the `restock`
    scenario shoots **all three** questions and answers them through the screen's own input path — Escape drops two of
    them and the server is checked to be untouched afterwards, the third is confirmed by a real click on the panel's
    **Confirm** button, and a fourth click takes the skip's branch): that the server asks, that asking queues nothing,
    that a confirmed request is measured **again** before it is carried out, and that a skipped one is not asked about.
    **By eye:** whether the sentence reads without the tooltip, whether "Hold Alt while clicking to skip this question"
    is discoverable, and whether the dim behind the panel is strong enough that nobody clicks through it by accident. The
    **physical Alt and Ctrl keys** are yours to test: no harness can hold a modifier, because `Screen#hasAltDown` polls
    the real keyboard. Check in particular that **Alt alone** asks for the selected amount and skips the question, that
    **Ctrl alone** asks for everything and still asks, and that **Ctrl+Alt** does both — the two used to be one key, and
    a skip that silently asked for everything available was the worst of both. Also worth trying once with the game
    language set to **German**, where the ingredient sentence is the longest line the panel can get: it must wrap inside
    the window rather than run past it.
104. **The warehouse restocks itself.** Give the production station a pattern (1 log → 4 planks), put logs in the
    warehouse, and set "keep 64 planks" on the keeper. Within a second the crane fetches logs to the station **without
    anyone asking for planks**; your machine makes them, they come back through the input and are stored, and the rule
    settles a little **above** 64, because a pattern makes whole runs. Look at the keeper through goggles while it
    runs: "Being made now: 1". Take the logs away and the line reads "Waiting for ingredients: 1" instead.

    **Automated** (`restockfullloop`, `restockreserveblocksanorder`): one order with no backing request, the whole
    loop, no second order while one is open, and a reserve on the ingredient stopping it. The `restock` visual
    scenario runs the same loop through a **real Create Mechanical Arm and Mechanical Crafter** in a live world and
    counts every item of the scene around it. **By eye:** whether the warehouse feels like it is *helping* rather than
    running away with your logs, and whether one number ("keep 64") really is enough to explain what happened.
105. **The reserve binds the restock.** Put a reserve on the **ingredient** (the logs) equal to what you have. The rule
    for planks does **not** order and reads "Waiting for ingredients"; the logs are untouched. Lower the reserve by one
    run's worth and it orders on the next pass.

    **Automated** (`restockreserveblocksanorder`, `stockrulereserveboundsproduction`, and the `restock` scenario,
    which shoots the goggle tooltip and the keeper row of exactly this state before it lowers the reserve). **By eye:**
    that the goggle line and the keeper row make it clear the warehouse is *choosing* not to spend the logs, rather
    than failing.
105a. **The last few items a run cannot make.** Set the **same** number as minimum and maximum on the planks, e.g.
    "keep 10, store at most 10", with a pattern that makes four. The warehouse orders the two whole runs that fit (8
    planks) and then **stops**: the keeper's lamp keeps saying "below the minimum", and the row's tooltip says why
    nothing is being made — "A whole run would go past the maximum". Nothing is ever stored above 10.

    **Automated** (`restockneverovershootsamaximum`, `RestockPlannerTest`): the run count, the stock never passing the
    cap and the outcome the row shows. **By eye:** whether that pair of numbers reads as *your* configuration to fix
    rather than as the warehouse being broken — raising the maximum by three, or choosing a minimum a run divides, is
    the cure, and the row's line has to make that guessable.
106. **The safety stop.** Let a restock order run, then **break or unpower your machine** so the ingredients sit in the
    station and nothing comes back. After `productionOrderTimeoutTicks` (5 minutes by default) the keeper's lamp turns
    to its **paused** colour, its goggles read "Paused after a lost batch: 1" with the hint underneath, the
    controller's goggles count it, the terminal row's badge turns, and an aisle summary display gains a "Rules paused"
    line. **The warehouse orders nothing more for that rule**, however long you wait. Open the keeper: the row says
    what it cost in ingredient items, and the status line at the bottom says how to resume. Fix the machine and click
    the row's **mark**: it orders again. (Clearing or re-editing the rule resumes it too.)

    **Automated** (`restockpausesafteralostbatch`, `restockpausesalthoughtheresultturnsup`,
    `restockpausesurvivesareload`, `restockruleeditedwhileordering`): the pause, what it names, the block state, that
    nothing is ordered while paused, that a **second source of the same product** cannot mask the loss, that a shadowed
    duplicate row neither wears the pause nor lifts it, that it survives a save, and both ways back. The `restock` visual scenario does it with a **real machine that swallows the batch** — a Mechanical
    Crafter with no recipe for what it is given — and shoots the paused keeper block, both goggle tooltips, the
    terminal row and the keeper's row, then clicks the mark and watches it order again. **By eye:** whether the paused
    lamp is unmistakably *different* from the ordinary one at a glance and **in the dark** (the shots are taken at
    noon), and whether a player who was away for an hour can tell what happened from the block alone.
106a. **The one case the warehouse cannot see.** With a rule paused (check 106), fix nothing — instead let a **second
    source of the same product** feed the same aisle: a farm of yours, or simply drop stacks of it into the warehouse
    input by hand. Resume the rule and watch it order once more. The order will now **complete** on those items, because
    they really did arrive through the warehouse's own door and nothing inside it can say which machine made them. This
    is the documented residual of the safety stop (§3.6.4), and it is worth seeing once so it is not mistaken for a bug
    later: everything that does *not* come in through an input — a barrel emptied straight into a rack, the product taken
    out and put back — is excluded, and the rule pauses as it should.

    **Automated** (`restockpausesalthoughtheresultturnsup`) for the excluded half. **By eye only** for this one, and
    only to confirm the boundary is where the docs say it is.
107. **It really is stopped after a restart.** With a rule paused, **quit to the title screen and rejoin**. The lamp is
    still paused, the goggles still count it, and no new order is started. This is the one that matters: a restart must
    not quietly feed the broken machine again.

    **Automated** (`restockpausesurvivesareload`) at the block-entity level. **By eye:** the same thing through a real
    save and rejoin, which is the path a player takes.
108. **Switching it off.** Set `maxRestockOrders = 0` in the server config and reload the world. Rules still cap
    storing and still hold their reserve, the keeper's lamp and comparator still call for items, and the warehouse
    never orders anything by itself.

    **By eye only** (the config path is a player's, not a test's).
108a. **Two rules keeping each other busy.** Set "keep 64" on **iron ingots** and "keep 64" on **iron blocks** in the
    same aisle, give a production station both patterns (9 ingots → 1 block and 1 block → 9 ingots), and put about 200
    ingots' worth in. Nothing may happen: both rules report **"The ingredients are not available"** and name the item the
    other one is asking for, because a minimum holds items back from other rules' automation too. Before that fix the
    aisle converted the same iron back and forth for ever — the crane never idle, both machines running, neither minimum
    ever met.

    **Automated** (`RestockPlannerTest.aRuleNeverSpendsWhatAnotherRuleIsItselfShortOf`). **By eye:** that the two rows
    read as "waiting for each other" rather than as a fault, and that the named ingredient tells you which number to
    lower.
109. **A ruled item the warehouse holds none of.** Set "keep 64" on an item the aisle has **zero** of and open the
    terminal. The cell shows a dimmed plain **`0`**, not an empty corner: read it cold and say whether it lands as
    "none, and the warehouse wants some" rather than as a glitch. (An item a production station can make keeps its blue
    **`+`**, which says more; both must not appear at once.) Then hover the row: the tooltip names the rule's state, its
    maximum and the reserve. Also check the badge in the corner opposite the number — gold below the minimum, red at the
    maximum, blue down to the reserve — and, with a rule **paused** (check 106), that the badge's **tooltip** says so
    even though its colour stays neutral. That is deliberate (§3.4.2), but you are the one who can say whether a paused
    rule is then too quiet in the grid.

    **Automated** (`stockruleterminalreportsrules`, `StockListModelTest`, `StockCountTest`): that the `0`, the badge and
    the tooltip numbers are the server's. **By eye only:** whether they *read*.
110. **German (`de_de`), the whole feature in one pass.** Switch the language and walk the surfaces: the keeper's screen
    (the three column headers, the number and item hints, every status sentence, the correction line after an
    out-of-range edit), its Shift tooltip and its goggle lines, the controller's rule lines, an aisle summary display's
    "Rules: … " and "Rules paused: …", the terminal's badge tooltip and its **confirmation panel**, and both Ponder
    scenes. Nothing may show a raw key, run out of its box or be cut off — German is the longer language and the
    screenshot runs only ever render English, so this is the only place clipping shows up. The longest lines to watch are
    the panel's reserved-ingredient sentence and, in the scenes, `warehouse_stock_rules.text_7` and `text_5` (119 and
    115 characters in German, the two longest the milestone added).

    The plural wart this check used to excuse is **fixed** (M15 Definition of Done): `gui.keeper.paused_lost` now reads
    "Ingredient items not recovered: %1$s" / "Nicht zurückgeholte Zutaten: %1$s", a label-and-number line that is right
    at every count and needs no singular key. Both languages should read correctly at a count of **1**. Note anything
    else.

    The lines M15 part 2's review added and German therefore has to fit: the keeper row's **restock outcome** (ten
    sentences, `gui.keeper.restock.*`, the longest being "A whole run would go past the maximum"), the status line after
    an edit lifted a safety stop ("The warehouse orders this item again"), the panel's reworded maximum sentence and its
    Alt hint, and the amount hint under the scroll input, which now names four things and is the widest single line of
    the terminal window.

    **Automated:** `LangConsistencyTest` proves German has exactly the generated keys with the same placeholders —
    nothing about how they look.


## S. Storage location priorities (M16)

Build one aisle with a **warehouse input** fed by a belt or a funnel, a **warehouse output**, and a rack **wall** of at
least four storage locations — some of them above your head, which is the case this feature was designed against. The
automated side of M16 (`StoragePriorityGameTests`, `JobPlannerTest`, the `priorities` visual scenario) already proves
*what the crane does*; everything below is about whether a person can reach the number, read it, and see why the crane
went where it went.

111. **Can you set it, on the wall, at any height?** Stand in the aisle and look at the filter slot on the lower half of
    an interface's aisle face. A right-click **with an item** sets the filter, and a short right-click with an **empty
    hand** clears it. **Hold** the click **with an empty hand** for about a quarter second: Create's value settings
    board opens with one row, "Priority", and a scale from 0 to 9. Set 5, release, and check that the number stuck. Do
    this for a location **above head height** and one at your feet — the judgement is whether you can aim at a 6 px
    plate on a rack wall without crouching around, and whether the board opens reliably for an empty hand.

    Until issue #30 every filter click fired on **release**, which made it unreliable: held a moment too long, the board
    opened instead, and a mouse moved before the release lost the click. A click with an item now sets the filter the
    instant you press, and only an empty hand or a wrench opens the board; section BA checks that in detail.

    The other interactions must still work over it: the **wrench** on the top face rotates the block, clicking the
    **side** of an interface with another interface still copies its facing (row building), and the parts of the aisle
    face away from the slot still place blocks normally.

112. **Can you read it without goggles?** A location at priority 0 draws **nothing at all**. Any other number is drawn
    as a digit on the andesite plate. Walk the aisle and judge: is it legible at 2, 5 and about 8 blocks, at a grazing
    angle down the wall, and **at night** or in an unlit warehouse? Does it stay inside the plate and clear of the dark
    arm slot above it? On a location that also has a **filter item**, the digit moves to the plate's upper right —
    check that the two do not overlap and that the digit is still the thing you notice first. (Known and expected: the
    *filter item* on a full-cube block is lit by the block's own interior and comes out nearly black, while the digit is
    drawn at full brightness. So on a filtered, prioritised location the number reads and the filter item barely does.)

    Aim at the block and check that the digit stays **one clean glyph**: the number must not grow a second, smaller copy
    of itself and must not turn into a smear (that was an M16 defect — Create's corner label on the value box was drawn
    on top of it for every targeted block, and it is switched off now). Then step close enough to hit the slot itself:
    Create's box highlight and the checkered face appear and cover the digit, which is expected, and the hover tip's
    third line must read "Hold with an empty hand to set the priority; higher fills first". Goggles on the interface
    read "Priority: 5"; goggles on the **controller** read "Prioritised locations: N" under the storage count, and
    **nothing** while no location is prioritised.

113. **Is the effect obvious?** Leave two nearer locations empty and unprioritised, give a **far** one priority 5, and
    feed a stack into the input. The crane must drive **past** the near ones to the far one — watch a whole trip and
    judge whether it reads as intent rather than as a bug. Then fill that location and keep feeding: the items must go
    somewhere else without any stall, and the controller goggles must **not** say the warehouse is full.

    Then the two rules that matter more than the feature: give a location a **filter** for something else and a priority
    of 9 — it must still refuse the incoming item, and a *dedicated* location must still win over a merely prioritised
    one. And **request** the stored item at the output or the terminal: the delivery must come from the **nearest**
    source that holds it, even when a farther one is prioritised higher. A priority that changed retrieval would be a
    defect, not a preference.

    Finally, raise a priority while the crane is **already on a trip**: the running job must finish where it was going,
    the next trip goes to the new preferred rack, and **nothing already stored may move**.

114. **Copying, saving, and an old world.** Set a filter and a priority on one interface, then use Create's
    **clipboard**: copying it onto the other interfaces of the rack wall must carry **both** settings — this is the
    reason the number sits on the filter slot at all, so judge whether dedicating and prioritising a whole wall is
    actually quick. Copy a **funnel's** filter onto an interface as well: the funnel's *amount* must never turn into a
    priority, and copying an interface onto a funnel must not change the funnel's amount. Then the **reset** direction:
    copy an interface that has *neither* a filter nor a priority and paste it onto a prioritised, filtered one — both must
    end up cleared, and the number must not survive the paste that visibly removed the filter.

    Save and reload: the numbers are still set. Open a world created **before** this version: every location reads
    priority 0 and the warehouse behaves exactly as it did. Break a prioritised interface and place it again — it comes
    back at 0, like any freshly placed block (the number is a setting, not an item property).

    Two interfaces on **one double chest**: only one of them counts the chest, so only its priority applies. The other
    one's goggles must say "Without effect: another storage location counts this inventory" in gold **even when it carries only
    a priority and no filter**, and the controller's "Prioritised locations" must not count it.

115. **German (`de_de`).** Switch the language and walk the same surfaces: the board's title ("Lagerpriorität") and its
    row ("Priorität"), the hover tip ("Mit leerer Hand halten: Priorität; höhere füllt sich zuerst"), the interface's
    goggle line ("Priorität: 5"), the controller's "Priorisierte Lagerplätze: N", and the interface's Shift tooltip,
    which names the priority in its one sentence (since issue #22 the descriptions are short and the Ponder scenes carry
    the detail). Nothing may show a raw key, run out of its box or be cut off.

    **Automated:** `LangConsistencyTest` proves German has exactly the generated keys with the same placeholders, and
    `HoverTipWidthTest` (issue #30) that every hover-tip line fits a 320 GUI px wide screen in both languages — nothing
    else about how they look.
## T. The warehouse port (M17)

Build one aisle with a **warehouse input** fed by a belt or a funnel, a **warehouse stock keeper**, a rack wall of a few
storage locations, and **three warehouse outputs** at rack positions — some of them above your head. Put a funnel, a
hopper or a belt behind each output, so items that leave really go somewhere. Keep a **wrench** and **Engineer's
Goggles** on you.

The automated side of M17 (`WarehousePortGameTests`, `JobPlannerTest`, `PortSettingsTest` and the `ports` visual
scenario) already proves *what the warehouse does*: every combination of the issue's table was played in a real world and
asserted on the server, and the screenshots were inspected. Everything below is about whether a person can **reach** the
settings, **read** the result and **believe** what the warehouse did.

116. **Can you set all three settings, on the wall, at any height?** Stand in the aisle and look at the filter slot on an
    output's aisle-side plate.

    * With an **empty hand or an item**: a short right-click still sets or clears the filter, exactly as before. **Hold**
      the click: the board opens with the **three redstone rows** ("On a pulse", "While powered", "Unless powered") and
      the amount on the horizontal axis. Set a row, release, and check that it stuck (the goggles say "Redstone: ...").
      The hover tip under the slot follows the direction: "Hold to set the amount and when the port acts" while the port
      requests, "Hold to set when the port acts" while it accepts, where the amount is no setting at all.
    * Now hold a **wrench** and look at the same face. Exactly **one** value box must be drawn, the port's own — the
      filter slot's outline and its hover tip must disappear, while the filter **item** stays painted on the plate. Hold
      the click: the board opens with the three rows "Request", "Overflow — after storage", "Diversion — before storage"
      and a magnitude 0–9, and the request row shows a dash instead of a number.
    * Judge the aim: the two boxes sit on the same faces, 3.5 px apart, and the port's own is smaller. Does a wrench
      click ever set the filter by accident, or an item click ever open the port board? Try it above head height and at
      your feet, and on a port with a **non-stackable** item in its filter slot (the board must still open).
    * A **wrench on the top or bottom face** must still rotate the station, and sneak-wrench must still dismantle it.
    * If you have another mod's **wrench** installed (anything in `c:tools/wrench` that is not Create's): holding it must
      leave the **filter slot's** box, label and hover tip drawn, exactly as any other item does — Create only draws the
      port's own box for its own wrench, so refusing every wrench here would leave that face with no box at all. Only
      Create's wrench hands the face to the port box.

117. **The overflow, which is what this was built for.** Give the stock keeper a rule with a **maximum** of 8 for an item,
    and feed 24 of it into the input. The warehouse stores 8; the rest stays in the input and the controller's goggles
    say `at maximum`. Now turn one output into an **overflow**: wrench board, row "Overflow", magnitude 0, no filter, and
    the redstone row "Unless powered" — and wire **nothing at all**.

    The surplus must leave through that port, trip by trip, with the crane carrying it. Watch a whole trip: does it read
    as the warehouse *giving items up* rather than as a bug? Then put a **lever** on the port and flip it on: the port
    stops dead, the input backs up again, and the reason goes back to `at maximum`. Flip it off: it starts again.

    Then the part that matters more than the feature: **fill the chest behind the port** and keep feeding. Nothing may be
    destroyed and nothing may drop on the floor — the input must back up exactly as it does for a full warehouse, and the
    controller must say `an accepting port was the only place left ... and it is full`. Take one stack out of the chest
    and it must resume by itself.

118. **Filtered overflow, and several ports at once.** Put **cobblestone** in the overflow port's filter slot and feed
    cobblestone *and* iron into the input past their maximums: only the cobblestone may leave, and the iron must back up.
    Then add a second accepting port with a different rank and no filter. Judge by eye which one the crane goes to and
    whether that matches what the numbers say: a **filtered** port wins over an unfiltered one for its own item, and
    among ports of the same kind the **bigger magnitude** wins. Finally set one port to **Diversion** with a positive
    rank and power it: everything arriving at the input must now leave immediately, **even while the racks are empty** —
    that is the point of a diversion, and it should feel deliberate rather than broken. Unpower it and the warehouse must
    store again on the next trip.

119. **Feed a machine without a clock.** Set another output to **Request**, put an ingredient in its filter slot, set the
    amount, and choose the row **"While powered"**. Put a funnel behind it onto a belt into a machine, then hold a signal
    with a **lever** — no clock, no pulse extender.

    The port must keep itself supplied: one trip arrives, the port asks again by itself, and at no moment may the
    goggles show more than **one** open request ("Items requested: N (requests: 1)"). Judge the rhythm — does the pause
    between trips feel like the warehouse working or like it being stuck? Then drop the lever **while a trip is on its
    way**: that load must still arrive, and no further trip may start. Let the warehouse run out of the item: the port
    must say "not in stock" and must not hammer the controller (watch the goggle line change at most a few times a
    second, not every tick).

120. **Can you read a port without goggles?** Stand **in the aisle** and look down the rack wall. An accepting port's
    ring around the opening is **andesite**, a requesting port's is **brass** (the accent covers the back spout too, but
    the ring inside the aisle is the cue that is always readable). Is that difference
    obvious at a glance, from the far end of the aisle, and at night? Then stand **behind** the ports, where the wiring
    is: each accepting port shows its **signed rank** (`-1`, `+4`) on the back plate, and a requesting port shows no
    number at all. Read it from a step away, from ~7 blocks and at a grazing angle — beyond about 10 blocks it is not
    drawn at all, which is Create's own filter render distance and is expected.

    With goggles: the port's own lines ("Port: overflow (-1)", "Accepts: Any item", "Handed over: 24", "Redstone: Unless
    powered", "Active" / "Waiting for a signal") and the controller's "Accepting ports: 2". Put a **Display Link** on the
    controller with the **Warehouse Summary** source: the row "Ports: 2 accepting" must appear, and must be *absent* on an
    aisle whose outputs all request. And while the crane carries items into a port, its goggles and a **Crane Status**
    display must say **"Handing over"**, never "Storing".

    Judge one thing on purpose: "Active" and "Waiting for a signal" are drawn in the same dim grey as "Empty". Is the
    line that tells you whether a port is doing anything right now readable enough, or should it be brighter?

    Then check the one case that line exists for: set an accepting port to **"On a pulse"**, give it a single rising edge
    with a button and look at it **before** the crane arrives. It must read "Active" — the unspent edge is the only thing
    that says the next trip may export — and "Waiting for a signal" again once the trip is done. The token reaches the
    client only in the goggle summary, which the server sends when it notices you looking, so allow a moment for the line
    to settle; it must not stay wrong. For "While powered" and "Unless powered" the line is read from the block state.

121. **Clipboard, saving, and an old world.** Configure one port fully — filter, amount, redstone row, direction and
    rank — then use Create's **clipboard**: copying it onto the other ports of the wall must carry **all four**, so judge
    whether setting up a row of ports is actually quick. Copy a **funnel's** filter onto a port as well: the funnel's
    *amount* may set the port's amount, but the funnel's "exactly" row must **never** become a redstone behaviour, and
    copying a port onto a funnel must not make that funnel "exactly". Then the **reset** direction: copy an unconfigured
    output onto a configured port — everything must end up cleared, including the rank and the redstone row.

    If you use **schematics**: print a configured port over a plain one (or run `/data merge block <pos> {PortRank:4}`).
    The block must switch to the andesite model at once and the warehouse must plan by the **new** policy on the next trip,
    without a reload — and the same in reverse, a plain output printed over an accepting one must stop exporting.

    Save and reload: every setting is still there, and a port that was armed by a pulse before the save still holds that
    one edge. Open a world created **before** this version: every output requests on a pulse, its model is the brass one,
    no rank is drawn, and the warehouse behaves exactly as it did. Break a configured port and place it again — it comes
    back as a plain requesting output, like any freshly placed block, and its buffer drops.

122. **German (`de_de`).** Switch the language and walk the same surfaces: the port board's title
    ("Anschluss-Richtung") and its rows ("Anfordern", "Überlauf — nach dem Lager", "Umleitung — vor dem Lager"), the
    filter board's title in both directions ("Angeforderte Menge" / "Redstone-Verhalten") and its rows ("Bei Impuls",
    "Bei Signal", "Ohne Signal"), both hover tips ("Halten, um Menge und Zeitpunkt zu setzen" while the port requests,
    "Halten, um den Zeitpunkt zu setzen" while it accepts), the filter slot's label, which
    changes with the direction ("Angeforderter Gegenstand" / "Aufgenommener Gegenstand"), the port's goggle lines
    ("Anschluss: Überlauf (-1)", "Nimmt auf: Jeden Gegenstand", "Abgegeben: 24", "Aktiv", "Wartet auf ein Signal"), the
    controller's "Aufnehmende Anschlüsse: 2", the aisle display's "Anschlüsse: 2 aufnehmend", the crane's "Gibt ab", the
    planning reason "ein aufnehmender Anschluss war der letzte mögliche Platz ..." and the output's Shift tooltip, which
    names the overflow in its one sentence (since issue #22; the direction box is the Ponder scene's job). Nothing may show a raw key, run out of its box
    or be cut off — German is the longer language and the screenshot runs only ever render English, so this is the only
    place clipping shows up. The longest lines to watch are the new tooltip paragraphs and the planning reason.

    **Automated:** `LangConsistencyTest` proves German has exactly the generated keys with the same placeholders —
    nothing about how they look.

123. **Read both Ponder scenes at normal speed.** Hold **W** over a Warehouse Output: after "Retrieving from a
    Warehouse" it now has "Supplying a Machine from a Warehouse" and "An Overflow for a Warehouse", and the **Warehouse
    Stock Keeper** shows the overflow scene as its third one. Judge the pacing and whether the beat lands where the text
    says it does — in particular whether the pulse beat's trip arrives while its text is still up, and whether the
    control icon in the accepting scene's closing beat obscures the `+4` on the back plate for longer than it should.
    Then read them again in German.

## U. Collecting from a machine (M18)

Build one aisle with a rack wall of storage locations, a **warehouse input**, a **warehouse stock keeper** and at least
two **warehouse outputs** at rack positions. Behind one of them put a **chest a machine drops into** — a Create
Mechanical Crafter's output chest, a Mechanical Press dropping onto a depot, or simply a chest you fill by hand — and
behind another a **vanilla furnace**. Keep a **wrench** and **Engineer's Goggles** on you.

The automated side of M18 (`WarehouseCollectGameTests`, `JobPlannerTest`, `PortSettingsTest`, the `collect` and
`collect-loop` visual scenarios) already proves *what the warehouse does*, including a whole production loop closing
through a real Mechanical Arm and Mechanical Crafter, with every claim asserted on the server. Everything below is about
whether a person can **find** the direction, **read** what the port is doing and **trust** it near their own machines.

124. **Turning a port around, and the fourth row.** Hold the **wrench** and hold right-click on a port's own box: the
    board must now have a **fourth** row, "Collect — from a machine", below "Diversion — before storage". Its cells must
    show a **dash**, not a number — a collecting port has no rank — and picking the row must take effect at once. Check
    it above head height and at your feet, and check that the port box is still the only box drawn while you hold the
    wrench. Then set the row back to "Request": the port must stop collecting immediately and behave as it always did.

125. **It fetches.** Put items into the chest behind the collecting port (or let your machine fill it) and watch a whole
    trip: the crane travels to the **port**, reaches **in**, and the items end up in the racks like anything that
    arrived at an input. Nothing may ever be pushed *into* the chest by the warehouse. Judge whether the trip reads as
    the warehouse fetching rather than as a delivery going the wrong way, and whether "the port reaches one block
    further" is obvious enough from the aisle. Then break the chest while the crane is on its way to it: the job must
    end cleanly, no items may drop, and the port's goggles must say "No inventory behind the port" in gold.

126. **A real machine, and its own rules.** Point one port at the **furnace** with no filter. A furnace offers its
    **fuel** slot to a horizontal face and its result only downwards, so what the port takes is the fuel — surprising,
    but it is the machine's rule, not the warehouse's. Put coal in the fuel slot and watch it be collected. Then set the
    port's **filter** to what your machine really produces and point a port at that machine's own output chest instead:
    with a filter, only that item may be fetched, and everything else must stay in the chest — and the port's goggles
    must then count **only** what it may fetch under "Ready", not everything in the chest, so the "Collects:" line and
    the "Ready:" line can never contradict each other. This is the check that
    decides whether the mod's promise "the filter says what is fetched" is understandable without reading the docs.

127. **It stops where it should.** Give the stock keeper a rule with a **maximum** of 16 for the collected item and fill
    the chest behind the port with far more than that. The warehouse must stop at the maximum and simply **leave the
    rest in your machine** — the controller says `at maximum`, and the port's goggles keep counting "Ready: N" and add
    the gold line **"Not fetched: a stock rule for these items is at its maximum"**. Standing at the machine, is that
    line enough to understand why the chest stops emptying, without walking to the controller? That is the whole point
    of it. Now add
    a second port as an **overflow** (negative rank, "Unless powered") whose chest is the **same** chest the collecting
    port reads, wired with a hopper if you like. Nothing may churn: watch for a minute and the three numbers (collected,
    handed over, in the chest) must go constant and stay constant. Then fill every rack chest so the warehouse is
    genuinely full: the items must stay in the machine, nothing may drop, and the moment you empty one rack chest the
    next trip must use the room.

128. **It never shuffles its own aisle.** Point a collecting port at an inventory that is **already a storage location**
    of the same aisle — the far half of a double chest a warehouse interface reads is the honest version of this
    mistake. The port must collect **nothing** and its goggles must say "This inventory is already a storage location"
    in gold. Without that line a player would be left with a port that silently does nothing.

129. **Fairness, and your own requests first.** Run two collecting ports, both with a full machine behind them, while a
    belt keeps feeding the **warehouse input**. Over a few minutes all three must make progress — neither port may
    starve the other, and neither may starve the input. Then request something at a **terminal** while both machines are
    full: your request must be served on the **next** trip, not after the machines are emptied. This is the one
    behaviour a player will complain about if it is wrong, and it is only judgeable by watching.

130. **Reading it, saving it, and German.** Stand **in the aisle** and look down the rack wall: a collecting port's ring
    around the opening is **copper**, next to the **andesite** of an accepting one and the **brass** of a requesting
    one. Is the third material obvious at a glance, from the far end of the aisle, and at night? The ring is the cue
    that has to carry this: the casing accent covers the back spout too, but a collecting port's spout stands against
    the machine, so from behind there is usually nothing to see. Nothing may be drawn on its back plate, because it has
    no rank. With goggles the port must show "Port: collects into the warehouse", "Collects: <item>" or "Any item",
    "Ready: N", "Collected: N" and its redstone line — plus, while the warehouse refuses the items, the gold "Not
    fetched: <reason>" line and no such line once it is being served again — the controller
    "Collecting ports: N", and a **Display Link** with the **Warehouse Summary** source the row "Ports: 1 accepting, 1
    collecting" (each half only while it is above 0). While the crane carries a collected load, its goggles and a
    **Crane Status** display must say **"Collecting"**, never "Storing". Copy the port with a Create **clipboard** onto
    the next one — the direction must travel with the rest of the policy — then save, reload and check that everything
    survived, and that a port that was armed by a pulse still holds its edge.

    Then switch the client to **German** and walk the same surfaces: the board row ("Abholen — aus einer Maschine"), the
    filter slot's label ("Abgeholter Gegenstand"), the goggle lines ("Anschluss: holt ins Lager", "Holt ab: ...",
    "Bereit: 24", "Abgeholt: 152", "Kein Inventar hinter dem Anschluss", "Dieses Inventar ist bereits ein Lagerplatz",
    "Nicht abgeholt: kein Lagerplatz nimmt diese Gegenstände an"),
    the controller's "Abholende Anschlüsse: 1", the display's "Anschlüsse: 1 aufnehmend, 1 abholend" and the crane's
    "Holt ab". Nothing may show a raw key, run out of its box or be cut off — German is the longer language and the
    screenshot runs only ever render English, so this is the only place clipping shows up.

    **Automated:** `LangConsistencyTest` proves German has exactly the generated keys with the same placeholders —
    nothing about how they look.

131. **Read the new Ponder scene at normal speed.** Hold **W** over a Warehouse Output: after the two port scenes it now
    has "Collecting from a Machine", and the **Warehouse Production Station** shows the same scene as its second one.
    Judge the pacing and whether each beat lands where its text says it does — in particular whether the wrench beat's
    control icon leaves the port's copper back readable, and whether the closing beats (the maximum, and the lever) are
    understood as *reasons the port stops* rather than as faults. Then read it again in German.

132. **A machine in a chunk of its own.** Build a collecting port so that its machine sits in a **different chunk** than
    the rack wall (F3+G draws the borders), let a collect job start, and then get that chunk unloaded while the aisle
    stays loaded — a spare chunk loader switched off, or simply walking away far enough on a server with a small view
    distance. The crane must **pause** with "Paused: area not loaded" in its goggles and on a Crane Status display, and
    carry on the moment the chunk is back: it must never sit at the port with no reason given while the whole aisle
    waits behind it. This is the one M18 review fix nothing automated can reach — a GameTest force-loads its own area,
    and the robustness run can only unload a whole scene.

## V. Optional chunk loading (M19)

Setup for this whole section: a world (a **dedicated** server is the honest setting, but a single-player world works if
you keep the view distance small), one aisle built **far away from the world spawn** — at least 300 blocks, because the
spawn chunks stay loaded whatever you do — and something the warehouse does by itself: a stock rule with a minimum plus a
production station, or a **collecting** port pointed at a machine that keeps producing. `F3+G` draws chunk borders and the
`C:` line of `F3` counts loaded chunks; both are worth having on screen. The setting lives in
`config/wareworks-server.toml` (per world: `<world>/serverconfig/wareworks-server.toml`), section `chunkLoading`, and it
needs a server restart or a `/reload` to be re-read. Since M21 a warehouse may bend, so the unit of every number here
is one **warehouse** — one controller and every aisle it owns — and `maxChunksPerAisle` defaults to **10** chunks
rather than 8, which is what a warehouse of two or three aisles needs.

133. **The shipped default, first.** Do not touch the config yet. Start a job — or let the warehouse start one for itself
    — and walk away until the aisle unloads (watch the `C:` count drop, or simply go 500 blocks and wait). Come back. The
    job must be **unfinished and then resume**: the crane picks up where it stopped, nothing is duplicated and nothing is
    missing. With goggles on the crane while you are still in range of the far end, the pause line reads "Paused: area not
    loaded". The controller's goggles must show **no chunk line at all** — not "Chunk loading: none", nothing. This is the
    behaviour every server gets unless somebody changes the config, and it is the thing M19 must not have broken.

134. **Turn it on, then walk away and come back.** Set `chunkLoading.maxTicketedAislesPerLevel = 4`, restart, and check
    the controller's goggles: while the aisle is working they must read **"Chunk loading: N chunks (crane job)"** — or
    "(open requests)", "(production orders)". Note N; on a normal aisle it is 2 to 8.

    Now walk 500 blocks away with a job running and **wait longer than the job needs**. Come back: the job must be
    **finished**, the items where they belong. That is the whole feature in one observation, and the difference from check
    133 is the only proof a player can see.

    While you are away, `F3`'s `C:` count is not the check — you are far away and your own chunks are loaded around you.
    Use the command instead (check 136).

135. **It lets go, and it says so.** Stand at the controller with goggles and watch the line after the last job finishes:
    it changes to **"Chunk loading: N chunks (idle, letting go)"** for about 5 seconds (`releaseDelayTicks`) and then
    disappears entirely. Feed the aisle again within those 5 seconds — the line must go straight back to "(crane job)"
    without ever vanishing. This is the anti-thrash linger; if the line flickers off and on between two jobs of a busy
    warehouse, that is a defect.

136. **`/wareworks chunks`, the operator's view.** With the aisle holding, run `/wareworks chunks`. You must get: a header
    naming the dimension, one row per holding aisle with its position, aisle letter, chunk count, reason and how many
    seconds it has held, a line with **two** numbers for that dimension — how many chunks are force-loaded there by
    **block tickets** (any mod) and how many in total (entity tickets and `/forceload` included) — and a totals line. On a
    server with no other chunk loader the block-ticket number must equal the totals line above it; that is the leak check
    in your own hands. The total may legitimately be larger if you have used `/forceload` yourself, so try that too:
    `/forceload add ~ ~` somewhere and watch only the second number grow.

    Then `/wareworks chunks release <the controller's x y z>`: it answers, the goggle line changes to **"Chunk loading:
    none (let go; holds again when its work changes)"**, and a second `/wareworks chunks` lists nothing. The aisle must
    **not** take the chunks straight back although its job is unchanged. Give it a genuinely new request and it holds
    again. Finally check `/wareworks chunks` with no warehouse holding at all: "No Wareworks warehouse is holding any
    chunks".

    **Scope**, worth checking once: `release <x y z>` acts on the dimension you run it in, exactly as `/forceload` does.
    From the server console (always the overworld) a Nether aisle needs
    `/execute in the_nether run wareworks chunks release <x y z>`, and running it without that must answer "No warehouse at
    ... is holding chunks in minecraft:overworld" — naming the dimension it looked in. `release all`, by contrast, covers
    **every** dimension and answers with how many warehouses in how many dimensions.

    Check the permission too: a non-op player must not see the command at all.

137. **Hunt for leftovers — the check that matters most.** Do all five of these and run `/wareworks chunks` after each
    one. Every single time the answer must be that nothing is held any more.
    * **Break the controller** while the aisle is holding (it is easiest to see with a job running). The chunks must be
      gone in the same tick, and the crane must fall back to the pre-M19 behaviour.
    * **Save, quit and rejoin** with a job running and the aisle holding. In the server log look for the line about a
      **reinstated seed chunk**; the interrupted job must finish, and the hold must be back at its full count within a
      second or two. Then let the aisle go idle and confirm nothing is left.
    * **Restart with the controller gone**: quit while the aisle holds, then remove the controller before the world comes
      back (a `/setblock ... air` right after joining is close enough, but the real case is a world edit or a rollback).
      Within about 5 seconds the log must carry a `WARN` about *releasing a reinstated chunk ticket nobody claimed*, and
      `/wareworks chunks` must be empty.
    * **Set `maxTicketedAislesPerLevel` back to 0** while an aisle is holding and `/reload`. Everything must be released
      within a second although the work is still there, and nothing may come back.
    * **Give up, then restart.** Set `maxHoldTicks` short (say 200 ticks) and give an aisle work it can never finish — a
      request for an item with the crane's motor removed. Wait for it to let go ("let go; holds again when its work
      changes") and check `/wareworks chunks` is empty. Now quit and come back. It must **still** hold nothing and still
      say it let go, although the request is still there: the bound survives the restart. Then cancel the request — the
      goggle line must disappear entirely — and give the aisle a new one, which must hold again.

    A ticket that survives one of these is the defect that makes a server operator remove a mod. `/forceload query`
    cannot help you here — it does not see mod tickets, which is why `/wareworks chunks` exists.

138. **The caps, and that a refusal costs nothing.** Set `maxChunksPerAisle` **below** what your aisle needs (check 134
    told you the number) and restart. The goggles must read **"Chunk loading: none (this warehouse needs 6 of 3
    chunks)"**, naming both numbers — and the warehouse must go on working exactly as in check 133, pause and all.
    Nothing may be held partially: `/wareworks chunks` lists nothing.

    Then the level cap: set `maxTicketedAislesPerLevel = 1`, give two aisles work at the same time, and check that the
    second one reads **"Chunk loading: none (server limit: 1 warehouses)"** while still serving its request. Let the
    first aisle finish — the second must take the freed slot by itself, without you doing anything.

    Then lower a cap **under** a holding aisle: with the aisle holding N chunks, set `maxChunksPerAisle` to N − 1 and
    `/reload`. It must let go within a second and read "Chunk loading: none (this warehouse needs N of N−1 chunks)";
    a hold that stays above the number you just set is a defect. The same with `maxTicketedAislesPerLevel` lowered below the
    number of aisles currently holding: enough of them must let go to respect it.

    And the emergency valve against a cap: with `maxTicketedAislesPerLevel = 1`, one aisle holding and a second waiting
    with "server limit", run `/wareworks chunks release all`. Afterwards **nothing** may be held — the waiting aisle must
    not take the freed slot — and both must read "let go; holds again when its work changes".

139. **No crops, no mobs, and German.** Put wheat on farmland and a dark spawning space **inside** a held chunk (F3+G to
    be sure it really is one), raise `randomTickSpeed`, and leave with the aisle holding. Come back: the crane's work must
    have progressed, and the **wheat must not have grown** and **no mobs must have spawned** in that chunk. Do the same
    with yourself standing nearby to see the difference. This is the promise "this loads a warehouse, not a farm", and it
    is the one part of the feature only a real world can show.

    Note also that the loaded area is **larger** than the number the goggles name: a forced chunk lets its eight
    neighbours tick their blocks too, exactly as vanilla `/forceload` does. A furnace one chunk beyond the aisle keeps
    burning. That is intended and documented, not a leak — `/wareworks chunks` counts the chunks the mod *holds*, not
    every chunk that ticks because of them.

    Then switch the client to **German** and read every line again: "Chunks geladen: 4 (Auftrag des
    Regalbediengeräts)", "(offene Anforderungen)", "(Produktionsaufträge)", "(holt aus einer Maschine ab)", "(untätig,
    gibt frei)", "Chunks geladen: keine (Serverlimit: 1 Lager)", "Chunks geladen: keine (Abhol-Limit: 1 Lager)",
    "Chunks geladen: keine (dieses Lager braucht 6 von 3 Chunks)", "Chunks geladen: keine (hat losgelassen; hält erst
    wieder, wenn sich die Arbeit ändert)", the display row "Chunks: 4 geladen" and the command's own answers ("Kein
    Wareworks-Lager hält Chunks geladen", "Die Chunks des Lagers bei ... wurden freigegeben", "Kein Lager bei ... in ...
    hält Chunks geladen"). Nothing may show a raw key, run out of its box or be cut off.

    **Automated:** `LangConsistencyTest` proves German has exactly the generated keys with the same placeholders —
    nothing about how they look. The screenshot runs render English only.

140. **The collecting limitation, stated so you can check it.** With `maxCollectHoldAislesPerLevel` still at 0 (the
    default), point a collecting port at a machine that produces slowly, leave while the port has items **waiting**, and
    come back: the aisle unloaded and fetched nothing in between. Now set the opt-in to 1 and repeat — the collection
    that was **already running** keeps going while you are away, and the goggles read "(collecting from a machine)".

    With the opt-in still at 1, give a **second** aisle a collecting port with items waiting as well. One of the two holds
    and the other must read **"Chunk loading: none (collecting limit: 1 warehouses)"** — a warehouse queued behind this
    cap has to say so rather than look idle. Raise the opt-in to 2 and both must hold.
    Finally let the machine run empty, leave, and start it again from a distance (a redstone clock, a timer): the aisle
    must **not** notice. It cannot, and the docs say so. If you ever see it fetch from a machine it could not have read,
    something polls an unloaded chunk and that is a defect.

## W. Recursive production (M20)

Setup for this whole section: one aisle with a **terminal**, two **warehouse production stations** each with a machine of
the player's behind it — a **Mechanical Saw** with its recipe filter set to Oak Planks, and a **Mechanical Crafter** group
— both feeding a **warehouse input**, and a Mechanical Arm or a funnel carrying items from each station into its machine.
Pattern at the saw's station: `1 Oak Log → 4 Oak Planks`. Pattern at the crafter's station: `4 Oak Planks → 1 Oak Button`
(any two-step chain of your own does as well). Put **only logs** into the warehouse. Goggles in a slot for every check
below.

141. **A chain is followable, which is the whole question.** Order the **button** at the terminal. It must be accepted
    ("Requested Oak Button x4, producing 4"), and the order section must then show **one line**, not two: the ordered item
    on the left, a gold **"2 steps"** badge, and on the right "now: Oak Planks" in blue while the saw's step is the one
    working. Watch it through: when the planks have been stored and the crafter's step starts, the right half must change
    to the item the chain is now waiting for.

    Judge it as a player, not as a tester: **can you tell what the warehouse is doing from that one line, at a glance?**
    Then check it at **GUI scale 1 and 4** and with a long item name (a named shulker box, an enchanted book). The item
    name is trimmed first and keeps a floor; the state gives way. Nothing may be cut so far that you cannot tell which
    order the line is about, and nothing may run into the red `x`.

142. **The step panel, and the machine it sends you to.** Click the line (anywhere but the `x`). A panel must open over the
    grid with one row per step, indented by depth, each naming a **rack address** and the step's own state — e.g.
    `A-01-08R: Oak Button x4 - waiting for an earlier step` above `  A-01-05R: Oak Planks x4 - waiting for ingredients`.
    The row that is actually working is aqua.

    Now **walk to the address the panel names** and check that the station standing there really is the one holding that
    step's pattern. That is what the whole panel exists for; if the address sends you to the wrong block, nothing else in
    this section matters. Then press **Escape**: the panel must close and the terminal must stay open. Open it again and
    press **Close**: the same. Click the grid behind the panel while it is up: nothing may happen.

143. **Giving up on a chain says what it costs — and the numbers have to be the truth.** Order the button again and let
    the crane deliver the logs to the saw, but **before** the planks come back open the step panel. The saw's step now has
    its batch while the order above it has nothing, and that is the state to judge: the panel must say "Steps that would
    end: **1**", not 2, and it must name **no** loss — because giving up leaves that step running and its planks still
    come back. Click **"Give up on the chain"**, then watch: exactly one order may end, the planks must arrive in a rack a
    moment later, and the ingredients at the saw must never have been touched. If the panel said 2, or named the saw's
    logs as lost, that is the defect this check exists for.

    Now do it one level further on, with the planks already **at the crafter**: the panel must name those planks as
    "Ingredients already delivered" and add a **third** line, that the warehouse will stop making Oak Button until you
    resume it at the machine. Give up, and check that sentence was true — ordering a button again must be refused, and
    only a resume at the station may bring it back. A cost line that promises a stop and then does not arm one, or arms
    one it never mentioned, is worse than no line at all.

    Then repeat it *before* the crane has picked anything up: the panel must say every step ends, name no loss and no
    stop, and the crane must turn around rather than finish the trip.

    While a panel is open, look at the **grid behind it**: no item and no amount from a cell may be readable through the
    panel's text, at any GUI scale. Resize the window with the panel up (or change the GUI scale): the panel must move
    with the window and stay clickable where it is drawn.

144. **The refusal names the item, and you can read it.** Empty the warehouse completely and order the **button**. The
    status line must name the item that is really missing — "Oak Log is missing" and **not** "not in stock" about the
    button. Read it at **GUI scale 1 and 4** and with the longest item name you can find: the item's name is the part that
    gets trimmed, so judge whether the sentence is still useful when it is. Then try the other refusals a player can
    reach by hand: a pattern pair that is the inverse of another ("The chain loops at …"), a stock keeper maximum of 64 on
    the planks ("No room for Oak Planks"), and `maxProductionPlanSteps = 1` in the server config, which must bring back
    exactly the pre-M20 answer.

145. **A pause is noticeable at the machine, which is the point.** Order the button, let the crane deliver the logs to the
    saw, then **break the saw's shaft** so nothing comes back, and wait out `productionOrderTimeoutTicks` (5 minutes by
    default; lower it in the config if you prefer). Then:
    * the saw's **production station** must show a lit **rose-quartz ring** around its openings. Judge this the way check
      106 asks about the keeper's lamp: **in the dark**, from the far end of the aisle, and — if you can — with a
      colour-blind filter. Put it next to a station that is *not* stopped and see whether you would notice the difference
      without being told;
    * its **goggles** must read "Stopped products: 1", "Ingredient items not recovered: N" and "Sneak-click the station to
      make them again";
    * the **controller's** goggles and an **aisle display** must both say "Stopped products: 1" — including on an aisle with
      **no stock keeper at all**, which is the normal case for an intermediate;
    * ordering the button again must be refused with "Making Oak Button is stopped" rather than "not in stock".

146. **Both ways back, and what they tell you.** Repair the machine. Then lift the stop twice, once each way:
    * **Sneak-right-click with an empty hand** on the station. The chat must say "The warehouse makes Oak Button again"
      and, when a batch was really lost, "… %N ingredient items were delivered and never came back" — check that
      wording against the station's own buffer: items still lying in the buffer are part of that number and you can take
      them out by hand, which is why the line does not claim they are inside the machine. Sneak-click a station that has
      nothing stopped: it must say so instead of staying silent. Do the sneak-click **once with a shield (or a torch) in
      your offhand** as well: it must work exactly the same, which is the case vanilla drops before the block reaches it;
    * with `productionBufferSlots` set to **27** (server config, a fresh world or a restart), open that station's screen
      again: the window has no order line at all in that configuration, so the red row must take the **buffer's label
      row** instead — it must still be there, still red and still clickable, and the tooltip of the stopped product's
      **pattern tab** must name the sneak-click. A screen that states the problem and offers no way out of it is the
      defect this check exists for;
    * open the station's **screen**. Its first order line must be a **red** "Stopped: …. Click to make it again", the
      **pattern tab** of the stopped product must be tinted red, and the tooltip must name the item, why it stopped and
      what it cost. Click the red row with a real mouse: the same chat line, the row gone, the tab back to normal, and the
      lamp on the block out.

    Then check the lamp cannot outlive its warehouse: with a station lit, **break the controller**. The ring must go dark
    in the same tick. Break the station instead and place it again: it must come back dark until the controller lights it.
    Finally the case a save can hide: with a station lit, **quit to the title screen, rejoin**, and lift the stop at the
    **stock keeper** (or delete the rule) rather than at the station — within one rule pass the ring must go out. Then
    `/setblock` a station's `stopped` to `true` by hand: the next pass after a rejoin must put it out again.

147. **A redstone port may start a chain, but only one.** Set a warehouse output's filter to the button, wire it to a
    **redstone clock** and let it pulse for a minute with only logs in the racks. Exactly **one** chain may be open at a
    time: the controller's goggles must never show more production orders than one chain's worth plus what you started by
    hand, and the port's goggles must read "too many production orders are running; wait for one or give one up" for the
    pulses it refuses. If you ever see a second chain start while the first one is still working, that is the defect this
    guard exists for.

148. **German, everywhere this feature speaks.** Restart with a German client and read every surface of this section
    again: the chain line and its badge, the step panel with its two buttons and its cost lines, all eight refusal
    sentences, "wartet auf einen früheren Schritt", the station's stopped row and its tooltip, the resume message with and
    without a loss, the goggle lines on station and controller, the display board line, the Shift tooltips of the
    production station (its one note is the lost batch, since issue #22) and of the terminal, and the new Ponder scene
    (`/ponder wareworks:warehouse_production`, paged to its third scene, then the same on the terminal). Nothing may show
    a raw key, run out of its box or be cut off, at **GUI scale 1 and 4**.

    **Automated:** `LangConsistencyTest` proves German has exactly the generated keys with the same placeholders, and that
    every refusal sentence names its item and fits the status row — nothing about how any of it looks. The screenshot runs
    render English only, and the harness drives every click through a screen's own API rather than with a real mouse.

## X. Rails around corners (M21, issue #1)

Setup for this whole section: a **creative** flat world, a Stacker Crane dock powered from below (a creative motor at about
64 RPM reads best for judging the turn), a Warehouse Controller behind it, and rails you are willing to rebuild several
times. Goggles in a slot for every check. Have a **Wrench** and a few spare Warehouse Rails on the hotbar. Where a check
says "an L", build a run of six rails out of the dock and a second run of six at right angles out of its last rail.

149. **Does the turn read as a machine turning?** This is the question the whole milestone is judged on, and no test can
    answer it. Build an L, put a chest and a Warehouse Interface at a rack position on the far aisle, feed the warehouse
    through an input on the near aisle, and **watch a whole trip from the side** — not from above. The machine must arrive
    at the corner, *stop*, swing, and set off again: judge whether the stop reads as a deliberate manoeuvre or as a stutter,
    whether the swing is too fast to see or slow enough to be annoying, and whether the chassis looks like it is turning on
    its own centre rather than sliding. Then watch the same trip again **from the end of the far aisle**, head on. Then at a
    low RPM (16) and a high one (192): at 16 the turn takes about twelve ticks and at 192 about one, so if either reads
    badly, `crane.turnPenaltyBlocks` is the knob and this check is where that gets decided.

150. **The wheels, and nothing else, betray a fake.** Watch the wheels through a corner and then on the way **back**. They
    must roll forwards on the way out, backwards on the way home, and **not move at all** in the tick the machine changes
    aisle (it is renamed there, it does not travel). A wheel spinning the wrong way on a return trip is the exact regression
    the odometer exists for, and only an eye catches it.

151. **Does the corner rack look obvious?** On the corner block, place one Warehouse Interface facing **away** from the near
    aisle and, on the opposite side of the same block, one facing away from the far aisle. Stand in each aisle in turn and
    ask, without goggles: **can you tell which rack belongs to the aisle you are standing in?** Then put the goggles on and
    check the two addresses agree with what you guessed. Now turn one of them the wrong way (towards the rails) and confirm
    it is reported as misaligned rather than silently ignored. This rule is the one thing a player must understand about
    corners, and if the block does not show it, the Ponder scene has to carry more of the weight.

152. **The rail models, across the room and in the dark.** Lay a straight run, an L, a T and a cross, plus one lone rail.
    From about twenty blocks away, at day and at night, can you read the shape of the network off the floor — especially,
    can you tell the **corner** from the straight rails beside it? Then close one rail with the **Wrench**: the brass end
    stop must be unmistakable from the same distance and in the dark, because that block is the difference between one
    warehouse and two.

153. **The wrench message, and that the axis really is gone.** Right-click a rail with the Wrench: the action bar must say
    that the rail was closed, and a second click must open it again. Then confirm the wrench does **not** rotate the rail
    any more — this changed, so a player who used the old behaviour has to be told by the block rather than by the
    changelog.

154. **A T joins the warehouse — rewritten for M22, because this is the one behaviour change of that milestone.** With a
    working L, lay one extra rail so that the corner becomes a T-junction: on the **far side of the corner block**, in
    line with the second run. Until M22 the warehouse stopped **before** that rail and the goggles said a split needed a
    later version; now the rails join, the stop line disappears, and the second aisle simply becomes **longer** — it grew
    past its origin, so its positions really are renumbered while it keeps its letter. Check all of that as a player:
    every chest keeps its contents, its filter and its priority (walk to one and read its goggles — the address is new,
    the chest is the same), the size line and the aisle list grow with it, and breaking the extra rail again puts every
    address back exactly as it was. Then lay a rail **sideways out of the middle** of a run instead: that one starts the
    **next** aisle with the next free letter, counted outwards from its junction. Is it obvious which of the two things
    you just did, before you read the letters?

    **Automated** (`WarehouseNetworkGameTests#networkbranchgrowsatbothends`, `RailNetworkGameTests#networkfollowsatee`,
    `WarehouseCombGameTests#anaisleaddedandremovedunderarunningjob`): both growth directions, the remap of every record
    through its world position, the letters, and a rail laid and taken away **under a running job** with an item census
    on every tick. **By eye:** whether the renumbering surprises a player in a bad way — check 165 is the same experiment
    on a warehouse really in use.

155. **The network lines on the goggles, and whether they fit.** On a warehouse of **three** aisles, read the controller's
    goggles: the size line, the aisle list with a letter and a length each, and — if anything is cut short — the stop line.
    Check that nothing overflows its tooltip or wraps badly at **GUI scale 1 and 4**, and that a warehouse of **one** aisle
    still reads exactly as it always did (no network line at all). Then build seven aisles and confirm the list says "and N
    more" rather than running off the screen. Same on the dock: "On aisle B at position 7" under the size line.
    Finally run a **single straight** aisle into a lowered `aisle.maxNetworkRails`: the stop line must appear on the
    goggles, and a **Warehouse Summary** display on that controller must carry its "(cut short)" mark — since M22 that
    mark reaches a one-aisle warehouse too, because every stop that is left can cut a straight aisle as easily as a comb.
    Read that mark on a target with **five or more rows** (a lectern, or two nixie rows): the aisle line sits **below**
    the four numbers, so a four-tube board shows the numbers and drops the mark rather than the other way round. Check
    that too — on a four-row target the last line must still be "Items: N", never the aisle list (M22 review fix).

156. **The Ponder scene, watched once through as a new player.** `/ponder wareworks:warehouse_rail`, paged to the **corner**
    scene (the second one). Watch it start to finish without skipping. Does it teach, in order, that rails connect wherever
    they touch, that a corner is the block two runs share, that the machine turns there, and that a corner rack belongs to
    the aisle it faces away from? Is the turn actually **visible** from the camera angle, or is a rack row in front of it?
    Is any caption on screen too long to read before it moves on, and is the whole scene (about 65 seconds) too long to sit
    through? Then check the two addresses in caption nine really match what the goggles say on a warehouse you build the
    same way.

157. **German, everywhere this feature speaks.** Restart with a German client and read every surface of this section: the
    item descriptions of rail, dock and controller (Shift tooltips), the wrench message, the goggle lines on controller and
    dock, the aisle list, the stop sentence for each reason you can provoke (a second dock, a rail cap or — since M22 — a
    junction cap you lower in the config; a split is no longer one of them, and a rail you closed with the wrench is a
    deliberate end and deliberately gets no sentence), the aisle
    display line, the `/wareworks chunks` over-cap row, and the whole Ponder scene. Nothing may show a raw key, run out of
    its box or be cut off, at **GUI scale 1 and 4**. The machine is **"Regalbediengerät"** in every one of them — the
    unreachable-aisle line and the collecting-port Ponder captions said "Kran" until the M22 review fix, and that word is
    now nowhere in `de_de.json`, so the longer name has to fit every box it reaches (the Ponder captions are where to look
    first).

    **Automated for this section:** `RailNetworkGameTests`, `RackBranchGameTests`, `WarehouseNetworkGameTests` and
    `CraneCornerGameTests` prove the world behaviour (connections and the wrench, discovery around a bend — and since M22 a
    tee, a cross and a ring followed as ordinary networks, with `WarehouseCombGameTests` for the comb — the corner block's
    two racks, pinned letters and origins, the remap, the goggle lines through the synced tag, a
    real job round a corner, a rail broken behind the machine, a save in mid-turn); `CraneModelLayoutTest` and
    `WarehouseRailModelTest` pin the chassis sweep and the rail models to the pixel; the `corner` visual scenario
    photographs the bend and every stage of a quarter turn and counts the turn sound over a real cogwheel drivetrain; the
    `ponder` scenario compiles the scene against a real `PonderLevel`. None of that can judge whether the turn **reads**
    well, whether the corner rack is obvious, or whether the German lines fit — which is what 149 to 157 are for.

## Y. The warehouse home point (M21, issue #1)

Setup: the same creative flat world and powered dock as section X, an **L** of rails (six out of the dock, six at right
angles out of its last rail), a Warehouse Controller, one input and one rack on the near aisle so the warehouse has real
work, and a Warehouse Terminal on the **far** aisle — a home point exists because you walk somewhere. Goggles in a slot.
The default idle delay is 10 seconds (`crane.returnHomeIdleTicks`); lower it to about 40 ticks in the server config if
you get tired of waiting, and put it back before judging check 160.

158. **Does the block say "wait here" without a manual?** Place the home point at a rack position on the far aisle,
    standing in the aisle so its plate faces you. Without goggles: can you tell it is a marker and not a station — that
    there is no opening for items anywhere on it? Then watch the lamp come on (it takes up to one geometry refresh, ~2 s)
    and confirm that a block whose lamp is **on** reads as "this one is in use" rather than as a warning.

159. **Does a refusal read across a room, and in the dark?** Place a **second** home point on the same warehouse. It must
    turn red **and** grow the crossed brass stop over its plate. Now walk back about ten blocks and ask, without goggles,
    which of the two the crane uses. Then do it again **at night with no light source**: the two lamp textures are only a
    shade apart, so the stop is what has to carry it. If the stop cannot be read at distance or in the dark, the model is
    wrong, not the tester. Then place a third one on the **near** aisle: which home point serves is decided by address
    order — aisle A before aisle B, then the lower position number — and not by the order you placed them, so the new one
    must take over and both on the far aisle must turn red.

160. **Does a machine coming home feel right, or does it feel like it is in your way?** This is the question the feature
    is judged on and no test can answer it. With the **default** 10-second delay, work the warehouse for a few minutes as
    a player: feed the input, request something at the terminal, walk away, come back. Judge three things. Does the
    machine turn up where you are about to need it, or does it look like it is fussing? Does a request you make while it
    is rolling home ever feel **delayed** (it must not — it takes the job in the same tick, including mid-corner, so if it
    ever feels slow that is a real defect)? And is 10 seconds right — too twitchy between two jobs, or too long to be
    useful? `crane.returnHomeIdleTicks` is the knob and this check is where it gets decided.

161. **Every "without effect" sentence, provoked for real.** Read the home point's goggles in each of these states and
    judge the sentence as a player — does it tell you what to do?
    * **serving:** "Warehouse Home Point: / Address: B-01-03R / The stacker crane waits here" — and the machine really is
      parked there while you read it.
    * **a second one:** "Without effect: this warehouse already has a home point".
    * **unreachable:** this one is deliberately hard to provoke, because closing or breaking a rail normally takes the
      far aisle out of the warehouse altogether and you get "Not part of a warehouse" instead. The state you are after
      is a machine standing further out on its aisle than the aisle now reaches: drive the crane out to the far end of
      the **near** aisle, take its power away, break the rails behind it, and read the home point on the other aisle.
      Red lamp, "Without effect: the crane cannot drive here", and the crane waits where it is instead of pushing at the
      gap. If you cannot get there in a few minutes, leave it: `HomePointGameTests` builds exactly this and the state is
      defensive until junctions land (issue #2).
    * **one straight aisle:** on a warehouse with **no** corner at all, both lamps stay dark and it reads "Without
      effect: on one aisle the crane waits where it is" — and the machine must stand exactly where its last job left it,
      which is the behaviour this whole feature must not change.
    * **switched off:** set `crane.returnHomeIdleTicks = 0` and it reads "Without effect: this server switched returning
      home off"; no machine anywhere drives home.
    * **not part of a warehouse:** place one out in a field, or turn one away from the rails — "Not part of a warehouse",
      or "Misaligned" with the station hint.

162. **Break it and watch the machine go back to its dock.** With a serving home point on the far aisle, break the block
    while the machine is parked at it. Within a refresh it must set off, turn the corner and park at position 0 of the
    aisle at the dock, facing the way that aisle runs — with no message, no lamp left burning anywhere, and nothing in
    the log.

163. **German, everywhere this block speaks.** Restart with a German client: the block's name (**"Warteplatz"** — is that
    the right word for a German player, or does it read like a bus stop?), all five Shift tooltip rows with their
    conditions, the goggle header and each of the six status sentences from check 161, at **GUI scale 1 and 4**. Nothing
    may show a raw key, run out of its box or be cut off. "Ohne Wirkung: In einem einzelnen Gang wartet das Gerät, wo es
    ist" is the longest of them and the one to measure.

    **Automated for this section:** `HomePointGameTests` proves the world behaviour (a home point served round an L with
    the parked pose measured to the block, breaking it falling back to the dock, a second one refused while the first
    keeps serving, an unreachable one reported and handed to nobody, a return interrupted **mid-turn** by real work with
    the stack still stored, a whole trip home asserting zero held chunks on every tick, and a single straight aisle whose
    pose is compared against a recorded one on every tick for three idle delays); `HomeReturnTest` (12 JUnit) pins the
    rule itself; `WareworksItemGameTests` pins the recipe and the tenth creative slot; the `home` visual scenario
    (`./gradlew runVisualTest -Pwareworks.visualTest=home`) photographs the block, both lamp states, the trip and the
    English goggle tooltip. None of that can judge whether the crossed stop reads at distance, whether "Warteplatz" is
    the right word, or whether a machine that comes to meet you feels right — which is what 158 to 163 are for.

## Z. One warehouse, many aisles (M22, issue #2)

164. **Build a comb and watch it work for ten minutes.** One main run out of the dock, three or four side aisles hanging
    off it with a rack wall down each, one input and one output beside the dock. Everything below is automated; what is
    not is whether this is a **warehouse you want to use**. Does the machine look like it knows where it is going, or
    does it look like it is dithering at the junctions? Does it feel unbearably slow once you have four teeth — and if
    so, is that the honest "one crane cannot be in two places" or does it look like a bug? Would you build the warehouse
    this way again, or does the shape fight you?

    The answer decides whether several cranes on one warehouse is the next milestone or merely the next feature, so
    note the throughput that made it feel wrong.

165. **Lay a junction into a warehouse you already use, and read what it tells you.** On a warehouse with stock and open
    requests, run rails across an aisle so the two meet. Then: do the new addresses on the controller's goggles make
    sense at a glance? Can you tell which letter a rack at the junction belongs to **without reading the wiki** — the
    rule is still "point the interface away from the aisle it belongs to", but at a T there are two aisles beside the
    same block. Is a ring legible at all, or does the aisle list read as nonsense? Then lower `aisle.maxAisleLength`
    below the main run you built — that is the one maximum that can leave an aisle behind, because `maxBranches` and
    `maxJunctions` drop whole aisles out of the warehouse instead — and read the gold **"The crane cannot reach this
    aisle"** on a rack of the cut-off aisle and the stop line on the controller: do the two together tell you which
    number to raise, at **GUI scale 1 and 4** and in **German** ("Der Kran kann diesen Gang nicht erreichen")?

    One wording in particular, found while shooting the `comb` scenario and **changed** in the sweep that followed it:
    the controller's goggles and the terminal's header used to say **"Aisle A"** over a stock list covering A, B, C and
    D, which was the name of the whole warehouse until M21 and is the name of its **first** aisle now. They say
    **"Warehouse A"** since M22, and so does the first line of the warehouse summary display. Read all three on your
    comb and say whether that is the right word: does "Warehouse A" read as the warehouse's name, or does the letter
    now look like it belongs to nothing in particular? The German is "Lager A". The lines that really do name one
    aisle were left alone on purpose — an address, the dock's "On aisle B at position 7" and the controller's aisle
    list — so check that the two kinds of line are still easy to tell apart.

    **Automated for this section:** `RailGraphTest` and `RouteCostsTest` prove the decomposition and the route search
    without a world (a tee, a cross, both ways round a ring at four turn prices, a comb, the caps, two identical builds
    answering identically, and M21's own chain walk as an oracle over every chain shape);
    `RailNetworkGameTests#networkfollowsatee` and `#networkfollowsacrossandaring` prove discovery and ownership in a
    real world; `WarehouseCombGameTests` proves a comb stored down and emptied out of every aisle into one block, a rack
    beside a tee joining the aisle it faces, a ring where the crane drives the way the planner costed, an aisle a cap
    cut loose and reported, and an aisle appearing and disappearing under a running job — each with an item census on
    every tick. And `./gradlew runVisualTest -Pwareworks.visualTest=comb` photographs the whole shape in a running
    game — a main run with three side aisles, the machine turning off at a junction and driving straight over one, the
    rack at a junction being served, both halves of the mirror pair with the address each really has, the controller's
    aisle list, one terminal for the whole comb, a retrieval across two turns, and both failure states with what the
    player is told — so **look at those shots before building anything by hand**; they are what 164 and 165 are a
    second opinion on. None of it can judge whether a comb is a warehouse a player enjoys, or whether an address at a
    junction is guessable, which is what 164 and 165 are for.

166. **Watch "Rails That Split" the way a player meets it, and say whether it teaches.** Hold **W** over a Warehouse
    Rail (or a Stacker Crane) and scroll to the **third** scene. Watch it once at normal speed without scrubbing, in
    **English and in German**, and answer three questions. Did you understand, without being told anywhere else, that
    a straight run keeps **one** letter through a junction while the side run gets the next one? Is every caption gone
    before the next one appears, and is each one on screen long enough to read in German, where the sentences are
    longer? And at the very end: can you see **which way each of the two outlined racks faces** well enough for "a
    rack belongs to the aisle it faces away from" to land — the blue one shows its framed plate towards the run, the
    red one its brass port away from aisle C, and that difference is the whole beat.

    Two things the scene deliberately does not show, so do not look for them: a **ring** (nine blocks of base plate
    have no room for one, and "the machine takes the cheaper way round" is a claim about numbers rather than a
    picture), and an aisle the crane cannot reach. Both are proved in `WarehouseCombGameTests` instead.

    **Automated for this check:** `./gradlew runVisualTest -Pwareworks.visualTest=ponder` compiles every storyboard
    against a real `PonderLevel` and shoots each scene at 20, 60 and 90 % of its length, so a missing schematic, an
    out-of-bounds `setBlock`, a raw lang key or a lost registration fails the run; `PonderNetwork#requireTwinOf` makes
    the storyboard refuse to compile if either outlined rack ever stopped being a rack of both aisles. What no run can
    judge is pacing and whether the lesson lands, which is what this check is for.

## AA. Order a whole list from a clipboard (M23, issue #19)

167. **Build something from a Schematicannon's checklist, end to end, and say whether the loop is worth it.** Build a
    small house, save it as a schematic, deploy it somewhere else and put the schematic plus a blank clipboard into a
    Schematicannon: it prints the **material checklist**. Carry that clipboard to a warehouse terminal, drop it into
    the slot at the right-hand end of the buffer row, press the **list button** and walk away. Come back and empty the
    terminal while the warehouse keeps filling it.

    Everything about the mechanism below is automated (see the note at the end of this section); what is not is whether
    this is a **way you want to build**. Does "hand the warehouse the shopping list and carry the crate to the site"
    feel better than clicking twenty items, or does the trip back and forth to empty the terminal eat the gain? Is one
    terminal's buffer the right size for it — nine slots is the default — or did you find yourself wanting a port with
    a chest behind it instead? And when the list is long: does the crane look like it is working the list off, or like
    it is shuffling? Note the list length at which it stopped feeling good, because that is the number a later
    milestone would have to answer.

168. **Read the Fetch dialog with your own eyes, in both languages.** Make a list the warehouse cannot cover — two
    entries it has plenty of, one it has part of, one it has none of — and press the list button. Since issue #36 the
    panel is a table (section BG): the question names the result, **"Fetch what there is (x of y)?"**, with the split
    "from stock, produced, missing" under it, then one row per entry with its status and reason, problems first, and a
    Missing section. (A clipboard longer than `maxTerminalListEntries` adds "Entries not taken from the clipboard: N" in
    gold, above the table.) Then:
    can you tell from it what you will get and what you will not, without counting? Restart with a German client
    (`de_de`) and read it again — German is the longer language and the automated run only ever renders English, so a
    line that overflows its box or clips can only show up here. The same for the **production** case: put a pattern in
    a production station for an item the racks do not hold, and check that the dialog says items would be **produced**
    and that you understand you are about to start machines by pressing Confirm.

169. **The list button's four faces and the status row.** The one button beside sort and filter changes with the state:
    **play** before you start, **stop** (green while it runs) while the list is being worked off, **confirm** while a
    portion is waiting for an answer, **refresh** when the order has given up for now. Hover each one and read the
    tooltip. Then: can you tell at a glance which of the four you are looking at, at **GUI scale 1 and 4**, and in
    German? Is "stop" unmistakably "give the list up" rather than "pause"? The status row below the buffer reads
    `List 1/2, 5 left (fetching)` while it runs and `List done: 4/4` for a few seconds when it is finished, after which
    the row goes back to the crane and the finished receipt stays on the clipboard slot's own tooltip — read both at
    scale 1 and in German and say whether they fit the row and whether the numbers mean what you expect (entries, then
    items). Then say whether the done line disappearing again is right: the clipboard is the lasting receipt, the row
    is what the terminal is doing now, and if you missed the done line because you were away, say so.

    Also read the panel a **single portion** raises, which is the other half of check 168. Put a pattern in a
    production station that makes four of something at a time and list **one** of it: the whole-list dialog asks about
    one produced item, and the portion that follows asks again, because a whole run is four. That panel must name
    "From the clipboard list: 1 <item>" and "Making it starts a production order; 4 would be made in total." and must
    **not** offer the Alt skip (there is no click to hold Alt on). Press **Cancel** on it: the order must park, the
    button must become *refresh*, and pressing the button again must bring the same question back. Read it in German
    too.

170. **The receipt.** Take the clipboard out while the list is half done and open it: the entries that really arrived
    carry a **tick**, the others do not, and the one that arrived only partly must be **unticked** — the warehouse
    never ticks a line it did not finish. Put it back in and press the button again: it picks up where it left off.
    Then let a list finish and open the clipboard one last time. Does it read as a **receipt** — "this is what I
    ordered, this is what came" — or does a half-ticked checklist look like something went wrong? If it reads wrong,
    say what you expected instead, because this is the part of the feature the issue called "the point".

171. **A list that cannot be served, and a terminal that is full.** Put an entry on the list the warehouse can neither
    stock nor produce (a nether star in a cobblestone warehouse) beside one it can: the entry it can serve must be
    served and ticked while the impossible one stays unticked. Then let an order run out of things to do altogether
    and wait a minute of game time: it **parks**, the button becomes *refresh*, and the status row and the terminal's
    goggle lines are the only things that say so (saying **no** to a portion's question parks it the same way). Walk up
    to that terminal as a player who did not start the order and
    ask whether you would ever find out why nothing is happening — if the answer is no, a chat message or a coloured
    status row is worth considering, and this check is where that decision belongs. Finally fill the terminal's buffer
    to the brim with the list still running: it must **wait**, not refuse, and continue by itself the moment you pull
    the items out.

    **Automated for this section:** `TerminalListGameTests` (10 tests, item census on every tick) proves a list worked
    off and ticked completely, the partial question answered No and then Yes, a producible entry asked about **for a
    list and not for a click**, a full destination waited for and then continued, a reload, the clipboard taken out and
    swapped mid-list, an unobtainable entry stepped over, a tick mark that does not leak onto a clipboard sharing the
    same content, one portion's question shown again and refused and then answered, and a clipboard the entry cap cut
    short being asked about first; `ListOrderTest`, `ListOrderConfirmationTest` and
    `RequestScopeTest` (63 tests) pin the order's own arithmetic, including the parking the GameTests do not wait out
    and that a Yes about one item never pays for another's reserve.
    And `./gradlew runVisualTest -Pwareworks.visualTest=checklist` plays check 167's whole story in a running game with
    a **real Schematicannon** — the checklist printed onto the clipboard, the partial dialog with its drawn lines
    asserted word for word, 80 ticks of a full terminal delivering nothing and refusing nothing, the ticks appearing
    one after another, the missing material fed in and the last entry following, and the finished clipboard opened in
    **Create's own clipboard screen** — with every claim a shot makes asserted on the server first, including that the
    number of tick marks equals the number of lines the order reports as complete, plus one shot of the panel a
    **single portion** raises, whose lines are read back from the screen itself. So **look at those shots before
    building anything by hand**: the mechanism is covered, and 167–171 are only about whether it is a good way to
    play, whether the four button faces and the two status sentences are legible, and whether the German reads.

## AB. Sort the terminal's stock list (M24, issue #17)

172. **Does "most used" put the right things first after a few hours of play?** This is the one thing no harness can
    answer. Play normally for an evening with a warehouse that holds a hundred item types or more, requesting from the
    terminal as you would, then open it on **most used** (press the sort button until the tooltip says so). Are the
    first twenty cells the twenty you keep fetching? If something you use constantly is not near the front, say what
    it is and roughly how often you asked for it, because that is the counting rule being wrong and not a bug.
    Then try the other side of it: build something completely different for an evening and check whether the order
    **follows you** — the fade is supposed to let an old habit shrink as a new one grows, so a list still frozen on
    last week's build is the thing to report.

173. **The three icons, at every GUI scale.** The sort button has one icon per order: a double chevron up for *most
    available first*, a **target** for *most used*, a stack of lines for *by name*. Cycle through all three at GUI
    scale 1, 2, 3 and *auto*, and at the smallest window vanilla allows: can you tell at a glance **which** order is
    active without hovering, and without reading the list? The chevrons and the lines are the pair to look at hardest
    at scale 1, since both are a few horizontal strokes. The shots `sort-amount`, `sort-used` and `sort-name` of
    `./gradlew runVisualTest -Pwareworks.visualTest=terminal` are the same three buttons at the scale the harness
    uses, if you want something to compare against.

174. **It really is remembered, and it is really yours.** Choose *most used* at one terminal, walk to a **second**
    terminal of the same warehouse and open it: the same order, with no flicker of the old one first. Then quit to the
    title screen, rejoin, and open a terminal again: still *most used*, still with your favourites first. Then, on a
    **LAN world or a server with a second player**, have both of you pick different orders and request different
    items: each of you must keep your own order and your own favourites at the same terminal, and nothing either of
    you does may reorder the other's list.

175. **Only what you ask for counts.** Set up a **Warehouse Output** (the warehouse's port) that requests an item on
    redstone and let it pull a few hundred of that item through the warehouse while you request something else by
    hand. Open the terminal on *most
    used*: the item the **port** fetched must not have moved up at all. Then ctrl-click a thousand of one item once
    and deliberately click another item ten times: the one you clicked ten times must come first. Finally hand the
    terminal a clipboard list and press Fetch — every item **on the list** counts once, so a list you fetch regularly
    must pull its items forward over a few runs. Try that with a **long** list (a Schematicannon checklist of more
    than sixty item types is ideal): fetch it three or four times and check that the items near the **top** of the
    clipboard really do climb. Before M24's review they could not: the list spent its own memory on itself and never
    raised a single count, which is the kind of thing only repeated play shows.

    **Automated for this section:** `TerminalUsageGameTests` (8 tests) proves that an accepted click counts once
    whatever amount it asked for, that a refusal and a redstone request at a port count nothing, that the cap evicts
    the weakest entry and never a favourite, that the chosen order and every count survive the exact save-and-load
    path a world load takes (including items with data components and a lowered cap), that two players keep separate
    counts and separate orders, that the payload reads back exactly what it wrote, that a **clipboard order** counts
    once per item type and that **ordering the same list again raises those counts**, and that an item carrying a
    whole inventory with it is not remembered at all while the request itself still works; `TerminalUsageTest`,
    `TerminalSortTest` and `StockListModelUsageTest` (44 tests) pin the counting, the eviction, the fade, what one
    action naming many item types does and all three comparators without a game; and `./gradlew runRobustnessTest`
    proves the order and the counts coming back after a **real** save, quit to the title screen and rejoin.

    The **button** is covered too since M24's client half: `-Pwareworks.visualTest=terminal` presses it with real
    clicks through the mouse handler, shoots each order twice (the grid, and the button being hovered) and asserts
    before every shot that the order is the one claimed, that the three icons differ, that the three lists differ
    while holding exactly the same items, that the longest name in the aisle reads the same in all three, that "most
    used" without a history *is* the amount order, and that no tooltip line is wider than the window's row — in
    English and, after a real language switch, in German. It also scrolls the grid down and makes the server push new
    counts at it, to prove that a push moves neither the scroll position nor the order. Since the review pass it also
    clicks **one cell four times in a row** in the used order and fails if that cell ever stops holding the item that
    was clicked, or if the row moves forward in the list.

    Since M24's evidence pass the same run makes "most used" a **ranking** rather than one favourite — it asks for
    four item types at four different counts, three of them the aisle's smallest stacks, so the used order is the
    amount order's tail turned into its head — lets the **server** name the expected first rows before every shot from
    its own stock snapshot and its own request counts, checks the counts and the size of the store against the clicks
    it made, and then saves the world, quits to the **title screen** (shot `reload-title`) and opens it again: the
    reopened terminal has to come up in the chosen order with nobody pressing anything, and the order and every count
    have to be read back from the player's own save data (`reload-chosen` before, `reload-kept` and
    `sort-used-reloaded-tip` after). It also walks every text the status row can hold, in both languages, and fails on
    anything wider than the row except the one sentence about a crane speed factor of 0 in the server config.

    What is left for a human is whether the icons tell themselves apart at **your** GUI scale (173), whether "most
    used" is *right* after an evening of play (172), and the second-terminal and two-player halves of 174 and 175 —
    the restart half of 174 is now automated, but doing it once by hand is still worth the minute.

176. **The crane line without its label.** The terminal's status row now drops the "Crane:" label whenever keeping it
    would cut the text, which in German is most of the time ("Regalbediengerät: " alone is 93 of the row's 216 pixels).
    Watch a crane work with the terminal open, in **German** and in English: does `Fährt zur Quelle` / `Travelling to
    the source` standing on its own still read as *the crane*, or does the row look like it is talking about the
    terminal? There is nothing else in that window it could be about, which is why the label is the thing that gets
    dropped — but it is a judgement a human has to make. The row's whole vocabulary is already measured automatically
    in both languages, so this is about wording and not about width.

177. **Does the sort tooltip say the right thing — in German?** Only the **width** of these lines is checked
    automatically; whether they *mean* the right thing is a human's. Restart with a German client, hover the sort
    button in each of its three states and read every line it shows (up to four):
    * the label, `Sortierung: am meisten Verfügbares` / `am häufigsten angefordert` / `nach Name` — does each one name
      the order the grid is actually in?
    * the sentence under it, e.g. `was du am häufigsten anforderst, zuerst` — does it say what the order does, or does
      it only repeat the label?
    * `Klick: <order>` — is it clear that this is the order the **next** press gives, and not the one you are in? This
      is the line to be hardest on: it is two words carrying the whole cycle, and if it reads as "click for this
      order" instead, the button becomes guesswork again.
    * `noch nichts angefordert, Menge zählt`, which appears on *most used* only while you have requested nothing
      — does it explain why that order looks exactly like the amount order, or does it read like a fault? (It used to
      read `… daher nach Menge`, which was 7 px too wide for the window and named an order that does not exist: the
      amount order is called `am meisten Verfügbares`, so "nach Menge" looked like a fourth one.)

    Then do the same in English and compare the two: both languages should make the same promise. The nit this check
    used to hand over is **fixed**: the English amount sentence said "what the *aisle* holds most of comes first" and
    now reads "what the warehouse has most of first", so the two languages agree about whose stock the list is. Say
    whether the shorter English wording still reads well — it is short because the obvious version does not fit the
    window.

178. **Does the list hold still while you work in it?** Open a terminal on *most used* with a warehouse holding a few
    dozen item types and click one cell in the middle of the grid several times without moving the mouse: every click
    must ask for the **same** item (watch the line under the grid, which names what was requested and grows one
    request rather than making several). Then press the sort button twice to come back to *most used*: now the item
    you clicked should have moved towards the front, because that is where the new counts take effect. Does that feel
    right, or does it read as the button being slow to notice what you did? The alternative was a list that reorders
    the moment the server answers, which moved the row out from under the cursor that had just clicked it — say which
    of the two you would rather have.

## AC. German lines that had to get shorter (the release audit)

> The widths are all measured now — the `terminal` visual run walks the whole vocabulary of seven hard-clipped rows in
> both languages on every pass, so no line here can grow past its row again without a gate failing. What a gate cannot
> judge is whether the **shorter wording still says the right thing**, and that is what these four checks are for. Run
> them on a German client (`de_de`) and then again in English.

179. **The clipboard order's progress line.** Start a clipboard order at a terminal and read the line under the grid:
    `Liste 1/4, 192 offen (holt)`. The word in brackets used to be a sentence (`wartet auf deine Antwort`,
    `vorerst aufgegeben`) and was cut off in both languages — three numbers leave the brackets 48 px in German. It is
    now one verb per state: **`holt`** while it is fetching, **`fragt`** while a portion raised a question,
    **`pausiert`** after it stopped itself, `fertig` in the receipt. Provoke `fragt` (put an entry on the list that
    costs more than the Fetch dialog's consent covered) and `pausiert` (let an entry the warehouse cannot serve stall
    the order for a minute), and for each one ask: **does the one word, plus the button beside it, tell you what to
    do?** The button is the other half of the answer on purpose — it turns into a green check mark with *Die Rückfrage
    zu dieser Liste beantworten* for `fragt` and into the arrows with *Den Rest der Liste noch einmal versuchen* for
    `pausiert`. If you would rather lose a number than that sentence, say so: the alternative is a second line shape
    for the two states that are not running.
180. **The three clipboard answers that got shorter.** Press the list button in the states that produce them and read
    the answer in the status row: *Hier ist die Rückfrage noch einmal* (was `… die Rückfrage zu dieser Liste noch
    einmal`, 251 px in a 216 px row), *Bitte beantworte die Rückfrage zur Liste* (was `… zu dieser Liste`) and *Der
    Rest der Liste wird neu versucht* (was `… wird noch einmal versucht`). Each one lost a few words to fit. Do they
    still name the same thing — in particular, is it still obvious **which** list "die Liste" is when you are standing
    at one terminal of a warehouse that has several?
181. **The production station's order line and its stopped row.** Open a production station with an order running and
    one that ended badly. The line now puts the item and the amount on the left and the state on the **right**, so a
    long item name can no longer push the state off the row — check it with something like *Poliertes
    Schwarzsteinziegel*. An order that handed ingredients to your machine and then ended carries the short marker
    **`, Zutaten weg`** instead of `, Zutaten nicht zurückgeholt`; the full sentence (*Zutaten, die deine Maschine
    bereits genommen hat, kommen nicht zurück*) is in the line's tooltip. Is the short marker enough on the row, or
    does it read as the *order* being gone rather than the ingredients? Then stop a product and read the red row:
    **`Angehalten: Diamant - weitermachen`** (English: `Stopped: Diamond - click to resume`). German had no room for
    the verb *anklicken* beside it. Does the row still read as something you can click?
182. **A refused request without its label.** Click an item the warehouse cannot serve and watch the status row. A
    refusal now drops its own `Anforderung abgelehnt: ` frame whenever keeping it would cut the reason — the row is
    red already, and the frame costs 124 px in German. Check both halves: a **short** reason still comes framed
    (`Anforderung abgelehnt: nicht auf Lager`), a **long** one comes bare (`du bist zu weit vom Terminal entfernt`).
    Does the bare red sentence still read as a refusal, or does it look like the crane saying something?
    * And the open decision behind it: **seven German reasons and four English ones still do not fit this row at all**
      and end in an ellipsis — among them *zu viele laufende Herstellungsaufträge; warte auf einen oder gib einen auf*
      (382 px) and *das Lager stellt es nicht mehr her; prüfe die Maschine und setze es fort* (372 px). They are the
      **goggles'** sentences, shared with the port and terminal tooltips, and they all name a remedy after the cause;
      the cause survives on the row and the remedy is what is cut. Put the goggles on after such a refusal and read
      the full sentence there, then decide: is one goggle-glance an acceptable price, or should the terminal get a
      short vocabulary of its own (eleven sentences, two languages)? Until you decide, every visual run prints all
      eleven with their overflow.

## AD. A world from the last release (the release audit)

> The automated gate for this is `gametest.MigrationGameTests` (6 tests, `run/migration/README.txt`): the real
> terminal bytes and the real player file of a pre-0.7.0 world, replayed against this build, plus a delivery caught in
> flight. What those tests cannot do is open a **whole** world that the released build wrote — no such world exists on
> the dev machine any more, and making one means running the released code (the README ends with the three commands).
> So the last word is a human opening an old world and using it. Both checks below are about an existing save, so take
> a copy of it first.

183. **Open your oldest real world.** `run/saves/wareworks_showcase_gespielt` is the world you played, last written on
    2026-09-16 — older than the release this build follows, which makes it the better test. Copy it, open the copy and
    walk the warehouse: is it the warehouse you left? Check the things a save carries and a test cannot judge by
    looking — the **addresses on your own signs** still name the same racks, the stock counts on the controller's
    display match what is in the chests, your production patterns are still in the machines, the crane parks where it
    parked. Then let it work: feed the input, pull from the output, and watch one crane trip end to end.
184. **The terminal of a world that never had the new per-player data.** In that same old world, open a warehouse
    terminal. It must come up with the list sorted **"most available first"** (`Meiste zuerst`), with no counts behind
    it, because the order you may have had on screen in 0.6.0 lived in a static field of the screen and was never
    saved — there is nothing to migrate, and the build before this one showed exactly this order after every restart.
    The question for you: **does that feel like a loss?** If you habitually left the screen on "by name", you will now
    have to press the button once more per world, after which the choice really is remembered (it is yours, per player,
    at every terminal of that world). Then use the terminal for a while and watch "most used" fill up from zero: the
    items you fetch climb, and nothing that is out of stock is ever sorted above something you can actually have. Last,
    put a clipboard into the terminal's **new list slot** — it is empty in every old world, as it must be — and fetch a
    list, to see the feature arrive in a warehouse that was built before it existed.

## AE. Name your aisles (M25, issue #15)

> Everything about the mechanism is automated: the gesture and what survives it (`aislenaming`,
> `aislenamingwithoutawarehouse`, `aislenamepersistence`, `aislenamefollowsitsletter`, `interfacenamesitsownaisle`),
> the five surfaces (`aislenamesurfaces`, `aislesummarywithnames`) and the real goggle tooltips in both languages
> (`./gradlew runVisualTest -Pwareworks.visualTest=comb`, shots `goggles-named-controller`, `goggles-named-rack` and
> their `-de` twins). What follows is only what a person can judge.

185. **Is the off-centre right-click findable, and is the hint what finds it?** Build a warehouse, put the goggles on
    and look at the controller **before naming anything**: the last line is a quiet grey "Right-click with a renamed
    item to name an aisle". Now do it — name an item in an anvil, right-click the **face** of the controller a little
    away from the little box in the middle — and watch the hint go, because the tooltip now has a name of its own on
    it. (On a warehouse of **more** than six aisles whose only named aisle is the seventh or later, the hint stays:
    neither of the two values this tooltip is sent carries that name, while the aisle's own blocks and the display
    board show it. Known, and documented in ADR-038 — tell us if it bothers you, because the fix is a new synced value
    whose only job is to hide a hint.) The questions: did the hint tell
    you enough, or did you first click the box in the middle and get the value panel instead? Would you have found the
    gesture without it? And is one grey line the right price, or should the box have given up its clicks (it will not:
    that would cost the aisle letter and, on an interface, its filter and priority slot for every renamed item in
    hand)?
186. **Does a named aisle list read at a glance — and is dropping the length right?** Name four or five aisles of a
    comb and read the controller's aisle line: a named aisle shows its **name** where an unnamed one shows its rail
    count, so the line reads "Aisles: A Ores · B Metals · C 14 · D Tools". This is the one open design decision of
    the feature. With six aisles named and six names of ten to sixteen characters, does that line still read at a
    glance, or does it run off the tooltip? **It runs off it**, and the number is measured rather than guessed: the
    `comb` visual run prints a `MEASURED` line for the warehouse it builds (four aisles, three of them named with
    short names: 199 px of the 510 px a goggle tooltip has at this window size, 39 %) and for the widest list the
    design allows (six aisles of sixteen characters: **721 px, 141 %** of the same room). A goggle line is **never
    wrapped** — Create's `GoggleOverlayRenderer` passes `maxTextWidth = -1`, so an over-long line is drawn past the
    edge of its own box — so the question is not whether it wraps but how long a name you can give six aisles before
    it does. About ten characters each is the crossing point. And do you **miss the length** once the name is there —
    the warehouse's total rails are on the line above, but per aisle the number is gone. If you want both, say so: it
    is one method body (`WarehouseControllerBlockEntity#aisleEntries`) plus one lang value in each language, and the
    honest version is to drop the line's budget from six aisles to four in the same change.
187. **Do the names fit, in German, on the two surfaces that have a width?** Switch the game to German, name an aisle
    with a sixteen-character name and look at: the controller's own line (`Lager A — Erze`), a rack's address
    (`Adresse: B-03-07R (Erze)`) and a **Warehouse Summary display board** (`Namen: A Erze · B Metalle`). A goggle
    tooltip has no fixed width, but it is not wrapped either (see 186), so the first two look wrong rather than break
    — do they? The board is the one that
    really cuts — but since the M25 review it **sizes the row to the board**: on a narrow board it names as many
    aisles as fit and counts the rest ("Namen: A Erze (+2)"). Build it four flaps wide, then wider, and check that
    what you read is always a whole name and never half a word; the one case nothing can save is a **single** name
    longer than the row, because the alternative is cutting text you typed. Note the width at which one sixteen-
    character name stops fitting, because a board row that is cut shows text no source ever produced.
188. **Does a name make the warehouse easier to use, or only prettier?** Live with it for a session in a warehouse of
    at least three aisles. Walk to a block and read its address with the name behind it; order something at the
    terminal and notice that the terminal says nothing about names at all (deliberately — its title row is the one
    text row in the screen nothing measures, and a name there would render through "Warehouse Terminal"). Then decide
    the two things only use can answer: is the **address plus name** enough, or did you want the name in the terminal
    and on the request answers too? And did you ever want to name a **single rack** rather than a whole aisle — if so,
    what would you have called it, and where would you have expected to read it?

## AF. What the crane got done (M25, issue #16)

> The mechanism is automated: the six buckets and the ring in JUnit (`ThroughputWindowTest`, `CraneActivityTest`,
> `CraneTickMotionTest`, 53 cases), and the world behaviour in GameTests (`cranethroughputcountsarealjob`,
> `cranethroughputbooksaturnatacorner`, `cranethroughputofaparkedcrane`, `dockthroughputsyncsonlywhileobserved`,
> `cranethroughputcreditsnotripforaresumedjob`). The four lines themselves are built by the real renderer and the
> real lang files, in both languages, by `./gradlew runVisualTest -Pwareworks.visualTest=corner`, which asserts
> that each is translated text carrying the right numbers and that the corner count, the blocked line and the
> whole block are left out exactly where they should be. The controller's two lines come out of the same builder, and
> the display board is covered by `displaysourcesregistered`, `cranethroughputwhilemeasuring` and
> `cranethroughputnumbersonboard` plus `LangConsistencyTest#theThroughputBoardRowsFitADisplayBoard`, which holds every
> board row inside a row's flap count in both languages. Since the M25 evidence pass both tooltips are also
> **photographed**: `corner` shoots the dock's four lines and the controller's one, in English and in German, at the
> end of a minute of real work that really holds corners — the dock through a camera that looks down on the north
> strip of its top face, because its outline is a three-pixel rail bed and its one large face carries the mast-height
> value box Create's overlay refuses to draw over. What follows is only what a person can judge: a photograph shows
> that a line fits one frame, not whether the number reads as a diagnosis, whether four lines earn their place, or
> whether the figures stay believable while they move — which is what questions 189 to 191 ask you to look at with
> your own eyes.

189. **Does "Busy" read as a diagnosis rather than a score, and are four lines right?** Build a warehouse that bends,
    feed it steadily and read the dock through goggles while it works: "Busy: 78% of the last minute", "Blocked: 4%",
    "Travel 54% · turning 6% (9 corners) · at the rack 18%", "Trips: 9 · items: 412". The questions only playing can
    answer: does **"Busy"** read as *is this machine my bottleneck* — or does it read like a mark out of ten, so that
    78 % feels like a failing grade on a warehouse that is simply not busy? Is **four lines** what the dock can
    afford, on a tooltip that already runs to a dozen, or is the breakdown one line too many? (If it is, the retreat
    is already built in: `CraneGoggleInfo#addGoggleLines` takes a `detailed` flag, so dropping the dock to the
    headline and "Blocked" is one argument at `StackerCraneBlockEntity#addToGoggleTooltip` and nothing else.) And is
    **"Blocked"** the word for "it has a job and is getting nowhere" — remembering that "Waiting" was not available,
    because German already spends *Wartet* on *Idle*.
190. **Do the lines fit, in German, and do they tell the truth while you watch them?** Switch the game to German and
    read the same four lines: "Auslastung: 78 % der letzten Minute", "Blockiert: 4 %", "Fahren 54 % · Drehen 6 %
    (9 Ecken) · Am Regal 18 %", "Fahrten: 9 · Gegenstände: 412". The third is the long one — does it still fit a
    goggle tooltip beside the job lines above it, or does it wrap? Then watch the numbers move for a few minutes and
    judge whether they stay believable: stand in front of the dock as a job starts and finishes and see the trips
    climb; walk away for two minutes and come back, and check that the label says "der letzten 23 s" while the window
    refills rather than claiming a minute; park the crane with nothing to do and watch the whole block **disappear**
    within a minute rather than freezing on its last reading; and cut the rotation and confirm that the pause line
    says why while the shares simply stop growing. Anything that reads as a stale number is a bug worth reporting,
    because every one of those cases is supposed to be visible.
191. **Are two lines the right amount on the controller, and does a board of four numbers tell you anything?** Walk
    to the **warehouse controller** of a working warehouse and read it through goggles: at the end of its crane block
    it now says "Busy: 78% of the last minute" and, whenever something is holding the machine up, "Blocked: 4%". The
    tooltip is long — is that pair worth its place there, or did you scroll past it looking for the stock numbers? Did
    you want the breakdown here too, or were you glad it stayed on the dock? Then put a **Display Link** on the dock,
    pick **"Crane Throughput"** in its screen (it is the second entry; "Crane Status" is still the first, so an older
    link of yours shows what it always did) and point it at a display board of four rows: "Trips: 9", "Items: 412",
    "Busy: 78%", "Turning: 6%". Watch it for a few minutes and judge whether a board of those four is something you
    would really build into a warehouse, or whether a different four would have been more use. Then switch the game
    to **German** — "Fahrten", "Gegenstände", "Auslastung", "Drehen", and "Kein Regalbediengerät" on a board whose
    dock you have just broken — and check with your own eyes that no row is cut off by the flaps on the board size you
    actually built, not just on the six-wide one the test assumes. Finally, break the warehouse's crane rotation and
    confirm that a freshly placed board says **"Messung läuft"** rather than a number, and keeps saying it until the
    machine has really run a minute.

## AG. Packages at the door (M26, issue #18)

> Almost everything about the mechanism is automated, and unusually so for a feature this new — because almost nothing
> about the mechanism is new. Both directions worked before M26 with no Wareworks code in the item path, so what the
> tests hold is that they keep working: the direction lock on both halves (`packagerbehindaportcanneverunpack`,
> `packagerbehindaninputcanneverpack`), the addressing compared character for character against Create's own rule over
> six sign shapes and then against a real box (`packagesignaddressmatchescreate`, plus 8 JUnit cases), the Packager
> found only behind the station (`packagerfoundonlybehindthestation`), a **real** Stock Link stopping a door and the
> door opening again when it is removed (`packagerlinkedtoanetworkignoresredstone`), both refusals with their exact
> numbers (`packagearrivalrefusedwhenfull`, `inputrefusesapackageitcannotholdwhole`), the overflow path unchanged
> (`packagearrivaltakestheoverflow`), a box stored as an ordinary item (`packagestoredasanitem`), persistence
> (`packagepersistencemidhandover`) and Create's real drop path (`packageentitydroppedcountsasitscontents`) — 19
> GameTests in all, with an item census that now counts a package as its contents. On screen,
> `./gradlew runVisualTest -Pwareworks.visualTest=packages` carries 16 iron out of one warehouse as an addressed box,
> along a belt, into a second warehouse's racks, and reads all seven new goggle rows back off the client block entity
> line by line, in the five tooltips that hold them — the address, the missing address, the gold `LINKED` dead end, the
> in door's counter and the refusal — and then switches the client to **German** and re-shoots every one of those
> seven rows, which is the only place in the
> build that renders the hand-written `de_de.json`; `-Pwareworks.visualTest=ponder` compiles the new scene against a
> real `PonderLevel`. What follows is
> only what a person can judge, and most of it is about **discovery**: the whole milestone exists because a capability
> was invisible, and whether it is visible now is not a thing a test can answer.
>
> One automated gap to know about while you check: **nothing automated reaches the far end of either door.** A
> Frogport, a Postbox, a chain conveyor, a train and a Create Package Port are all outside the GameTest harness, so
> checks 193 and 196 are the only evidence that a Wareworks box really travels Create's own logistics the way any
> other box does.

192. **Would you ever have found the out door — and does the port tell you enough once you have?** Build an ordinary
    warehouse, put a **warehouse port** on a rack wall with iron in its filter, and then build the out door: a **Create
    Packager** one block behind the port (place it against the port and it faces itself correctly — check that it
    does), a plain **sign** on the Packager reading `Base North`, and a **Create Smart Observer** looking at the port
    with one block of redstone dust onto the Packager. Pulse or lever it if you prefer; the observer is what the Ponder
    scene teaches, because it keeps the signal up while anything is still in the port and so empties the door by
    itself. Now put the goggles on and read the port: under its own three settings it should say **"Hands over as a
    package"** and **"Addressed to: Base North"**. The questions only you can answer: without reading this document,
    would the **Ponder scene** on the port (hold **W**) have got you to this build — in particular, would you have
    known that it is the Packager's **back** that has to touch the port, and that this is the only thing deciding
    whether the door sends or receives? Does the port's own tooltip, with the package lines at the end of six or seven
    other lines, read as *this block is a package door*, or did they get lost in the list? And does "Hands over as a
    package" on an **overflow** port (a negative rank, no filter, unwired) read as useful rather than odd — that build
    is "whatever my warehouse cannot keep leaves my base in boxes", and the lines are shown there on purpose.
193. **Does a Wareworks box really behave like any other box, out there in Create's logistics?** Nothing automated
    leaves the Packager, so this is the one check that the feature is actually usable. Take the door from 192 and send
    its boxes somewhere: a **chute** or a **funnel** into a **Frogport**, a **Postbox**, a **chain conveyor** to a
    **Package Port** named `Base North` at the other end of your base, and — if you have the rails — a **train** with
    a delivery schedule. Then do the same in reverse into a second warehouse's **in door** (a Packager against a
    **warehouse input**). Three things to watch for specifically. **A Frogport will not pull a box out of the port
    itself** but will out of the Packager — Create exempts its own Packager from the address filter it applies to
    every other inventory, and a Frogport only ever looks at its own inventory and the block **directly below** it, so
    sitting it on the Packager is the build that works. Does that read as reasonable when you hit it, or as a bug?
    **Rename the sign** while boxes are in flight and check that the next box carries the new address and the ones
    already travelling keep the old one. And **take the sign off entirely**: the port's line turns gold, and the box
    that then goes out is unaddressed — which, in Create's own matching, can only ever be delivered to a Package Port
    that has **no name of its own**, or to one named `*`, which matches everything; a chain conveyor carries it past
    every other named one. If you have a `*` catch-all port anywhere, that is where the box will land. Build exactly
    that and
    confirm you can see where the box ends up, because an unaddressed box going round for ever is the most confusing
    thing this feature can do to you.
194. **Is the gold `LINKED` line the rescue it is meant to be?** This is the failure the milestone exists for. With the
    door from 192 working, put a **Stock Link** on the Packager — for any reason, which is the point: a player does it
    to put the Packager on a logistics network, not to break the door. Everything stops: no pulse, no lever and no
    observer will send another box, and Create itself says nothing anywhere. Now look at the port through goggles:
    **"The Packager is linked to a logistics network and ignores redstone"**, in gold. The questions: standing there
    confused, would you have looked at the **port** rather than at the Packager, the observer or the wiring? (The line
    is on the port because that is the Wareworks block, and nothing can be added to Create's Packager tooltip from
    here.) Is one gold line enough, or did you want it louder? And note the timing: the line can appear up to **half a
    second** after you place the link, because Create only rechecks the link in its lazy tick — which also means it
    can lag the same amount after you take it off again. Is that noticeable enough to annoy you? Then break the link
    and confirm the same door opens on the next signal with nothing else touched.
195. **Do the seven lines fit and read, at the GUI scale and window you actually play at?** All seven rows of both
    doors are measured against a budget of 81 characters, which is the room a goggle tooltip has at the visual run's
    1600x900 window at GUI scale 3 (534 scaled px less the 24 px Create's placement keeps, the 510 px the `comb` run
    prints for itself); a goggle line is **never
    wrapped**, so an over-long one is drawn past the edge of its own box. The German `LINKED` row is the long one, 73
    characters or roughly 438 px, so it needs about 460 scaled px. **1920x1080 at GUI scale 4 gives 480 − 24 = 456 px,
    so it only just fits there** — while a smaller window at the same scale (1600 wide gives 376 px) clips it plainly.
    Please look at both, because a number this close is a person's judgement: set the GUI scale to 4, read the rows
    full screen, then again in a window about two thirds as wide. The German text itself is now shot by the `packages`
    visual scenario, so this check is about the **fit**, not about the words. Switch the game to **German**, set the
    GUI scale to 4, and read the port (`Übergibt als Paket`, `Adressiert an: Base North`, `Keine
    Adresse — hänge ein Schild an den Verpacker`, `Der Verpacker ist mit einem Logistiknetz verbunden und ignoriert
    Redstone`) and the input (`Nimmt Pakete auseinander`, `Geöffnete Pakete: 12`, `Letztes Paket abgelehnt: Stapel
    darin 3, freie Plätze 1`). Note that the port shows `Adressiert an: …` **or** the `LINKED` row, never both: take
    the Stock Link off to see the address again. Is the long German `LINKED` row cut off, and if it is, is it still readable enough to
    do its job? Then write a sign with a **very long** address — more than 25 characters — and check that the port
    shows it cut with an **ellipsis** rather than silently shortened: a shortened address you cannot tell from a whole
    one is a thing you would compare against a Package Port filter and get wrong. And read the **Ponder scene** in
    German from beginning to end: twelve beats, and the only check on them is that they compile.
196. **Does the in door's pair of numbers tell you what to do?** Build an in door (a Packager against a **warehouse
    input**), feed it boxes with a belt funnel, and read the input through goggles while it works: **"Takes packages
    apart"** and **"Packages opened: 12"**. Then make it fail the way it really fails: fill the input's buffer by
    hand, or stop the crane, so that only one or two slots are free, and send in a box holding three or four different
    items. Create opens a package **whole or not at all**, so the box is refused and simply waits in the funnel, and
    the input now says **"Last package refused: stacks in it 3, free slots 1"** in gold. The questions: does that
    sentence tell you to **wait for the crane** rather than to rebuild the door? Do the two numbers read the way they
    are meant to — the first is what the **box** brought (three kinds of item, so three stacks) and the second what the
    buffer had free, so the line says "it needs three at once and there is one". Both counts come after their words on
    purpose, because `1 Plätze frei` would be wrong German; does `stacks in it 3` read as stilted to you? And note the
    one case the pair does not explain on its own: a stack also merges into a slot that already holds that very item,
    so a package can be accepted when the free-slot count alone looks too small — put iron in the buffer by hand, leave
    one slot free, and send in a box of iron and gold. Then notice two deliberate things and say whether either bothers you. **"Packages
    opened" counts since the chunk was loaded**, not since you built the door, so it resets when you come back to your
    base — it is a diagnosis, not a statistic, and making it permanent would mean writing to the save. And if your
    server has `inputBufferSlots` set **below** 9, say 1 or 2, every multi-type package is refused **for ever** and
    that gold line will never go away; nothing is lost, the boxes queue up outside, but is "correct and permanently
    stuck" something the line should say differently?

## AH. A drawer behind a warehouse interface (the drawer fix, before M28)

> Everything that can be checked without a third-party mod is automated: the estimate's new "I do not know" answer and
> the whole truth table around it in JUnit (`CapacityMathTest.Unknown`, `InventorySnapshotTest`), the planner still
> offering such a location and the ender-pearl case costing no live call at all (`JobPlannerTest`), and in a running
> server the two world facts the fix rests on (`stacksizeceiling`: the mirrored vanilla constant, a real chest
> reporting exactly 99 per slot, and a chest full of ender pearls still answering an exact 0). What **cannot** be
> automated is the thing the fix is for: no block in this mod, in Create or in vanilla reports a slot limit above 99,
> so a real drawer is the only way to see it work. One check, and it needs a storage mod.

197. **Does a drawer behind a warehouse interface fill past one stack now?** Install any mod with a drawer, barrel or
    crate that holds **more than one stack per slot** (a Storage Drawers-style drawer is the clearest case). Build an
    ordinary working warehouse, put that drawer where a chest would go — behind a **warehouse interface**, nothing
    else different — and feed a few thousand cobblestone into the warehouse input. Watch the drawer. Before this
    fix it took exactly **one stack** and was never touched again, however much the input held and however long you
    waited; the crane simply drove past it for ever, with no message anywhere saying why. It should now keep filling,
    stack after stack, up to whatever that drawer's real capacity is, and the terminal's stock list should keep
    counting it up past 64. Then check the two things around it. **Nothing slowed down:** a warehouse of ordinary
    chests, and especially a **full** one, should feel exactly as it did — the drawer rule deliberately cannot
    apply to any vanilla container, so a full chest is still passed over for nothing. And **nothing is overfilled:**
    put a full stack of **ender pearls** (16) into an ordinary chest behind an interface, leave no other free slot, and
    ask the warehouse to store more pearls — it must simply report the warehouse full, never plan a trip that then
    cannot be unloaded. If your drawer mod uses a slot limit **at or below 99** the drawer stays conservative by design
    and will still stop at one stack; that is the one case the fix knowingly leaves alone (at most 35 items of a
    99-limit slot go unused), so note which mod and which limit if you hit it.

## AI. A rack bay you fill by hand (M28, issue #20)

> Take a bay out of the creative tab — the three stand directly after the warehouse interface — and place it with
> its **open front** towards you; `facing` points **into the rack**, away from where you stand. Everything
> below is state and wording, which is exactly the half no gate can judge: the gestures are asserted by
> `RackBayGameTests#bayhandgestures`, the client packet by `baysyncisbounded`, the address by
> `baygogglestatefollowsthewarehouse`, and the goggle rows' **widths** in both languages by
> `LangConsistencyTest#theRackBayRowsFitAGoggleTooltip`, whose list of rows is itself kept complete by
> `everyRackBayGoggleRowIsMeasured` — but the goggle **lines** cannot be built on a server at all
> (`LangBuilder#forGoggles` measures the client font), so how they read has never been seen by anything but a person.

198. **Can you find the gesture at all, and does the filter box still get its clicks?** Stand in front of a bay with a
    stack of cobblestone. Right-click the **face**, a little away from the little box in the middle: **one** item goes
    in. Hold **Shift** and right-click: a **whole stack** goes in. Empty your hand: a plain right-click gives **one**
    back, Shift gives a **stack**. Now aim deliberately at the box in the middle and click with a filter item: it must
    go into the **filter slot**, not into the bay, the moment you press, and holding that click opens no board (issue
    #30, section BA); holding a click with an **empty hand** (or a wrench) on the box opens the **priority** board. The
    sphere is 4 px across and nothing automated can feel where its edge is — so: **is the box findable, and is it easy
    to miss when you mean to store something?** The honest failure mode here is a player who tried to store an item
    and set a filter instead, or the other way round.
199. **The two things the gesture takes away, and whether they are acceptable.** While a bay would accept what you are
    holding, **Shift-clicking it puts the item in instead of placing a block against it** — try to Shift-place
    cobblestone against a bay that holds cobblestone and the cobblestone goes into the bay. Aiming at a neighbouring
    block still places it, and a bay that **refuses** the item (a filter, a different type inside, no room) places it
    as before. Second: right-click a bay with an **item you named in an anvil** and the action bar says
    *"Name an aisle at a warehouse controller or interface"* — the item stays in your hand, it is not stored, and
    **Shift**-clicking with it still places it. Ask both questions: does the Shift-insert ever get in your way while
    building a wall, and does the naming answer send you to the right block?
200. **Does the goggle text read like a rack bay?** With Engineer's Goggles on, look at a bay in each of these states
    and read the whole tooltip aloud. **Empty, no filter:** it names itself (*"Brass Rack Bay"*), says
    *"Takes the first item that arrives"*, *"Empty"* and *"Capacity: 1,024 stacks"*. **Holding something, no filter:**
    *"Holds Cobblestone until it is empty"* and *"Cobblestone 4,096 / 65,536"*. **With a filter:** *"Filter:
    Cobblestone"* instead, and the line stays after you empty the bay — that difference is the whole point, so: **can
    you tell the item the bay decided on from the one you set?** Then put an **ender pearl** in a bay and check that
    the capacity line still says the same stack count while the item total is sixteen times smaller, which is the
    deliberate price of counting in stacks.
201. **Does a bay no warehouse serves read as fine rather than broken?** Put a single bay down in the middle of
    nowhere, fill it by hand, and look at it through goggles: *"Not part of an aisle"* and, under it,
    *"A rack bay works by hand with no warehouse"*. This is the one line written for the player who meets this block
    before they meet the rest of the mod, so the question is blunt: **would you go looking for a fault after reading
    it?** Then build an aisle around it — dock, rails, controller — and watch the same block show an **address**
    instead. Break the controller again and it must go back to the two quiet lines with its load untouched.
202. **The gold line of an overloaded bay, and whether it blames the right block.** Put a wooden bay down, then
    `/setblock` a **brass** one directly on top of it. The **wooden** bay now reads, in gold,
    *"The rack above this bay is overloaded"*. Read it standing at the wooden bay: **does it send you to the block
    above?** It is worded for the rule and not for the neighbour on purpose — in a column only a command can build
    (brass under wood under andesite) the brass bay carries the same line although nothing stronger stands directly
    above it. Check that the bay still **hands out** what is inside it by hand and through the crane while it says
    this, and that taking the brass bay away makes the line go.
203. **The same five checks on a German client.** Restart with `de_de` and read 198 to 202 again:
    *"Nimmt den ersten Gegenstand, der ankommt"*, *"Enthält Kopfsteinpflaster, bis es leer ist"*,
    *"Kapazität: 1.024 Stapel"*, *"Ein Regalfach arbeitet von Hand auch ohne Lager"*,
    *"Das Regal über diesem Fach ist überladen"*, and in the action bar
    *"Benenne einen Gang an einer Lagersteuerung oder Lagerschnittstelle"*. The widths are measured in both languages,
    so what is left is the wording: **does the German say the same thing, and does "Regalfach" read as a rack bay
    rather than as a shelf?**
204. **The front stays right while you watch it.** Stand at a bay with **no goggles on**, have a hopper or a funnel
    feed it, and keep watching — this is the one thing a server test cannot see at all, because the contents are
    pushed to the client on **every** change and nothing else in this mod does that. Then walk twenty blocks away and
    come back, and leave and rejoin the world. **Is what the block says about itself ever stale?** (Until the model
    lands, "what the block says" means its goggle tooltip; from the step that draws the fill level on it, it means the
    block itself.)

## AJ. Breaking a full rack bay: the pallet (M28, issue #20)

> Place a brass bay from the creative tab, its open front towards you, and fill it by hand or with a hopper.
> Everything about the pallet's **state** is asserted — `RackBayGameTests#baybreakresets`, `baybreakincreative`,
> `palletspawnrefusedfallsbacktoitementities`, `bayrefilledfromapalletbyhand` and the whole of `PalletGameTests`, plus
> the dev harness breaking a full bay while the crane carries items. What no gate can judge is whether the thing you
> are left standing in front of **reads** as the load of the bay you just broke, and whether refilling by hand feels
> like work or like a chore.

205. **Break a full bay and look at what is left.** Fill a brass bay with a few stacks of cobblestone, then break it.
    You should get the (eventual) bay block back and **one pallet** on the floor in front of you, with the item and
    the count written above it. **Is it obvious at a glance that nothing was lost?** Count the number against what
    you put in. Then break a bay standing **high up in a wall** and check that the pallet comes down to the aisle
    floor rather than hanging in the air where the bay was.
206. **Refill a bay from a pallet, and judge the tedium honestly.** Place a fresh bay, then take stacks off the pallet
    with **Shift** + right-click (empty hand) and Shift-click them into the bay. This is the path that replaced
    "put the filled bay back", and the owner settled it deliberately — so the question is the one only playing can
    answer: **at two stacks it is fine; at sixty-four, is it still?** Note how it felt, because the answer decides
    whether the "retire this location" mechanic needs to come sooner.
207. **Shove it around.** Walk into the pallet: you should pass through it and push it along the floor. **Does that
    read as moving a pallet, or as a bug?** Try to pick it up: middle-click it, right-click it with something in your
    hand, try to put it in a minecart or a boat. None of that may work, and that is the point — a pallet in your
    pocket would be a thousand stacks in one slot.
208. **Set fire to it.** Push a pallet into **lava**, blow it up with **TNT**, set it alight, and drop it in water.
    It must come out of all four with its load intact and no flame overlay stuck on it. Then save and quit, reload,
    walk far enough away that its chunk unloads and come back. **Is the count still the same every time?** The one
    thing that *does* destroy it is the void — and when it happens the server log says what fell and where, so check
    that line exists.
209. **A hopper drains it and a funnel does not.** Put a vanilla **hopper** under a pallet: it empties it item by
    item and the pallet vanishes when it is done. Now aim a Create **funnel**, **chute** or **belt** at one: nothing
    happens, for ever. That is a real limitation of how Create recognises items on the ground, and it is in the
    Ponder scene and the docs for that reason — so the check is whether a player who tries the funnel first would
    work out what is going on, or file a bug.
210. **The German name.** On a `de_de` client the pallet is **"Palette"**. Read it in the one place a player sees it
    (the entity name, e.g. after a `/kill @e[type=wareworks:pallet]` message or in F3 + B's hitbox label): **does it
    read as a warehouse pallet rather than as a colour palette?**

## AK. What a rack wall looks like (M28 step 9, issue #20)

> The one half of this block no automated gate can judge. The geometry is pinned
> (`CraneModelLayoutTest#aRackBaysLoadStaysUnderTheArmAndInsideItsWindow` and
> `#theThreeRackBaysAreTheSameRackInThreeMaterials`), the fill step is pinned
> (`RackBayGameTests#bayfilllevelfollowsitscontents`) and the creative tab order is asserted twice — but whether a
> wall of them **reads** as racking, and whether the load **reads** as a level, is a person's call. Build in survival
> if you can: the recipes are the other half of this step.

211. **The first bay, before anything else exists.** In a fresh survival world, with no machine built and no brass in
    sight, craft a **Wooden Rack Bay**: six planks and two andesite alloy. **Was anything in that chain a surprise —
    did you need a machine, a casing you did not have, or a trip anywhere?** It should be craftable the same evening
    you make your first andesite alloy. Then fill it by hand and use it as a barrel for a while. **Is it worth the
    two andesite alloy over a barrel, for one item type and 64 stacks?**
212. **Read a wall by walking past it.** Build a wall of at least nine bays, two or three high, and put different
    amounts into them — a handful in one, a few stacks in another, one filled to the brim. Now walk past it at
    normal walking speed, five or six blocks out, without goggles. **Can you tell the full ones from the nearly empty
    ones?** Then walk away until the wall is at the far edge of your render distance and look back: the fill level is
    block geometry, so it must still be there at any distance — unlike an interface's filter item, which disappears
    at ten blocks.
213. **Does it read as racking?** Stand in the aisle in front of that wall. **Does it look like a high-bay rack —
    uprights, shelves, pallets with goods — or like a grid of decorated cubes?** This is the question the whole
    block exists for, and M29 is the milestone that answers it properly; what is wanted here is a note of what is
    already right and what still reads as boxes.
214. **The three materials, side by side.** Put a wooden, an andesite and a brass bay next to each other, all empty,
    and step back ten blocks. **Can you tell which is which without looking at a tooltip?** Then fill all three with
    the same number of cobblestone — say a wooden bay's worth. The wooden one must look **full** and the brass one
    nearly **empty**, because the level is a fraction of the bay's own capacity. **Does that read as "a brass bay
    holds far more", or as a bug?**
215. **The icons in the creative tab.** Open the Wareworks tab. The three bays stand directly after the warehouse
    interface, weakest first. **Does each icon read as a rack bay rather than as a coloured cube — can you see the
    pallet and the arm slot?** They are turned to their front for that reason, unlike every other block of this mod,
    so also check the opposite: **does that look out of place in the row?**
216. **The item descriptions.** Hover each bay and read its Create-style description, then do it again on a `de_de`
    client. Two things have to be right in both languages: the **capacity** (64 / 256 / 1024 stacks) and the sentence
    about what each tier may carry **above** it. Since issue #22 the pallet on breaking is the Ponder scene's to show:
    **would a player who read the tooltip and watched the scene know not to break a full bay expecting to carry it
    away?**
217. **The arm goes in through the slot.** Build a bay into a real aisle and watch the crane store something in it.
    **Does the arm go in through the dark slot above the load, or does it appear to pass through the woodwork?** And
    when it reaches all the way in, **does anything of the grabber stick out of the back of the bay?**
218. **The filter slot is still where your click lands.** The little value box sits in the middle of the bay's front,
    in the window, with no plate behind it any more. Right-click it with an item: it must set the **filter** and not
    store the item. Then right-click two pixels away from it: that must **store** the item. **Is the boundary where
    you expect it, or does the open window make you miss?**
219. **The pallet got a pallet.** Break a full bay and look at what lands on the floor: it should be a wooden pallet
    — boards on three runners — with the goods standing on it and the count above. **Does it read as a pallet now,
    rather than as a slab of planks?**

## AL. What the game teaches about a rack bay (M28 step 11, issue #20)

> The three Ponder scenes — **"Storing Bulk Goods in a Rack Bay"**, **"Building a Rack Wall"** and **"Rack Bays in a
> Warehouse Aisle"**, in that order on all three bay items — are compiled, run against a real `PonderLevel` and
> screenshotted at three moments each by `runVisualTest -Pwareworks.visualTest=ponder`, which is what catches a
> missing schematic, a crashing instruction or a raw lang key. What it cannot judge is the only thing that matters
> here: **whether a player who has never seen this mod understands the block afterwards.** This is the block most
> players will meet first — a wooden bay needs no machine to craft — so these checks are worth more than most.
>
> The last two are not about Ponder at all. They are the two questions the milestone itself rests on, and no
> automated run can answer either.

220. **Watch all three scenes through, once, at speed, as a stranger would.** Hold **W** over a Wooden Rack Bay in the
    creative tab and watch scene one without pausing, then press the forward arrow for two and three. **Afterwards,
    could you build and fill a bay without reading anything else?** Specifically: do you know that it holds one item
    type, that a plain click moves one item and Shift a stack, that the goods you can see through the front are the
    fill level, and that breaking it gives you the block back plus a pallet?
221. **The order has to feel right.** Scene one shows the block **alone**, with no crane, no controller and no rails —
    deliberately the opposite order to every other block of this mod. **Does meeting it that way make the bay feel
    like something you could build tonight, or does it feel like a piece of a machine whose other half is missing?**
    If the second, the registration order in `WareworksPonderScenes` is what to change.
222. **The pallet's Create limitation has to land.** The last caption of scene one says that a plain Hopper drains a
    pallet and a Deployer can, while Create's Belts, Chutes and Funnels cannot see one at all. **Having watched only
    the scene, would you try a funnel on a pallet?** Then try one: put an andesite funnel against a pallet and
    confirm it does nothing, and a vanilla hopper under one and confirm it drains it. This is the sentence that stops
    the first bug report, and it is the one caption whose German is not a translation — Minecraft's Hopper and
    Create's Funnel are both *Trichter* — so read it on a `de_de` client too and check it still tells them apart.
223. **No two captions at once.** Watch each scene once more with your eye on the text box alone. **Is there ever a
    moment where two captions are drawn on top of each other?** This is the defect M22 found and M26 shipped again in
    one beat; the rule (a caption never outlives the idle after it) is now written into `RackBayScenes`, but only a
    person watching can confirm it holds at every beat and at every window size.
224. **The German pass over all three scenes.** Switch the client to Deutsch and watch all three again. Ponder wraps
    its captions, so the risk is not clipping but a line that wraps to four rows and outruns its beat. **Does every
    caption finish being readable before it fades?** And does the wall scene's German still name the three materials
    the way the blocks themselves are named — *Holz-*, *Andesit-* and *Messing-Regalfach*?
225. **Does 64 stacks feel right?** Play an evening with a few wooden bays as your only bulk storage — cobblestone,
    deepslate, dirt from a tunnel. A wooden bay is 64 stacks for six planks and two andesite alloy, against a vanilla
    barrel's 27 stacks of anything for six planks and two slabs. **Is that a trade you would make, and does the bay
    fill up fast enough that andesite feels like something to want, without wood feeling pointless?** If a wooden bay
    never fills, the ladder starts too high; if it fills in an hour, it starts too low. The three numbers are server
    config (`storage.woodBayStacks` and its two siblings), so this check is a recommendation and not a bug report.
226. **Does the wall read as racking?** This repeats check 213 on purpose, because it is the question the whole
    feature exists for and the one the milestone has to be judged on. Build a wall at least five bays wide and three
    high, in one material, fill it unevenly, and stand in the aisle in front of it. **Does it look like a high-bay
    rack — uprights, shelves, pallets with goods — or like a grid of decorated cubes?** Write down which parts
    already read as racking and which still read as boxes: that list is M29's step 12.

## AM. What the M28 review's fixes leave to a person

227. **A bay against a bay really places.** This is the half of the gesture no test can reach: a GameTest calls
    `useItemOn` directly, while a real click goes through `ServerPlayerGameMode`. Place one wooden bay, then click the
    **side** of it holding another wooden bay — plain, and again with **Shift**. **Does a second bay appear beside the
    first, facing the same way, both times?** It must never go *into* the bay, which is what happened before the fix
    and which made a free-standing wall's second row impossible to start. Then click the **top** of a wooden bay with
    an **andesite** one: refused, with *"A rack bay may carry nothing stronger above it"* in the action bar and the
    item still in your hand. Everything else the bay would accept still goes **in** rather than being placed against
    it, by design — try a cobblestone block on an empty unfiltered bay and aim at a neighbouring block instead.
228. **An observer beside a rack wall stays quiet.** Put an **observer** facing a bay, and a **comparator** and a
    **piston** beside two more, then fill the bay through all four fill steps from a hopper or by hand. **Does any of
    the three fire?** None may: a fill level is written with `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE`, so neither a
    neighbour update nor a shape update goes out. The observer is the one this check exists for — it hooks
    `updateShape`, not `neighborChanged`, so dropping the neighbour update alone never silenced it. Then break the
    bay: an observer in front of a block that disappears **does** fire, which is ordinary and right.
229. **A schematic print over a full bay keeps its goods.** Build a small rack wall, fill one bay, and capture it with
    a **Schematic and Quill**. Print that schematic back over the standing wall — in creative with the instant print,
    and again with a **Schematicannon** whose *Replace Block Entities* is on. **Is the full bay still full afterwards,
    with the right fill level on its front?** The filter and the priority *are* overwritten, which is what a schematic
    of a rack wall is for; the goods are not. A vanilla chest beside it keeps its contents through the same print,
    which is the comparison this check is measured against.
230. **A floor of pallets costs nothing.** Break eight or ten full bays so their pallets lie about, then compare
    **F3**'s tick time with the pallets loaded and with them gone. **Is there a difference you can see?** A resting
    pallet runs its collision step only every fourth tick, which is vanilla's own rule for a dropped item. Then mine
    the floor out from under one: **does it fall at once**, within a tick or two, rather than hanging in the air? And
    shove one across the floor: **does it still slide and stop as it did?**


## AN. The load a rack bay shows (M29 step 13, issue #20)

> The silhouette of cartons on the pallet is block state geometry and costs nothing at any distance; the **stored item
> itself** is drawn by a block entity renderer whose view distance is cut to Create's `filterItemRenderDistance`
> (10 blocks by default), because a warehouse places bays by the hundred. The `bays` visual scenario shoots and
> measures all of that, but three things about it are feel rather than numbers.

231. **Does the item fade out at a sensible distance?** Build a rack wall of one item, stand in the aisle, and walk
    backwards. **At what distance do the goods disappear, and does losing them feel like "too far to read" or like a
    glitch?** The fill silhouette must still be there after they go — that is the whole split, the shape says how full
    and the item says what. If the cut feels too close, it is Create's own `filterItemRenderDistance` in the client
    config and not a Wareworks setting; raising it costs frames on a big wall, which is the trade this check is for.
232. **Does the value box still win where you aim?** The drawn item stands at the mouth of the bay, in front of the
    store filter's value box at (8, 5) — the two overlap on screen while the crosshair rests on the block, which is
    Create's own idiom (a funnel's filter box sits over its own model). **With a bay that already holds something,
    does a plain right-click near the middle of the front still set the filter rather than putting the held item in?**
    (Since issue #30 a click with an item sets the filter on the press.) And the other way round: **can you still get
    an item *into* a full-looking bay without fighting the box?** The box's hit sphere is 4 px across and no automated
    test can feel it.
233. **Does a bay read right in a dark aisle?** Light one aisle well and leave the next one unlit, and put the same
    item in a bay of each. The drawn item takes the brighter of the bay's own light and the aisle block in front of
    it. **Is the item in the dark aisle readable but clearly dark, rather than either pitch black or oddly glowing?**
    Check it again with a bay in the middle of a wall three deep, which is the darkest a bay's own position gets.


## AO. The verdict on issue #20's fourth reason (M29 step 14)

> This is the milestone's own judgement, and the only place it can be made. Everything else about M29 is measured:
> the joins are asserted on the block states of every wall the `bays` and `blocks` scenarios build, the drawn item's
> anchor is read off the model files by `CraneModelLayoutTest`, and the per-frame budget is counted on the client's
> own render list. What no gate can answer is the sentence the issue opens with — *a warehouse does not look like
> one*. Checks 213 and 226 asked that question of M28 and were told to write down what still read as boxes; these
> three ask it of the finished thing.
>
> **Do check 232 in the same pass.** It is the third of the three the milestone owes a person — the store filter's
> 4 px value box sharing its place with the drawn item — and it was written with step 13 rather than here.

234. **Does a wall read as racking *as it grows*?** This is the one the issue is really about: "you extend a wall by
    placing a bay beside an existing one, and the wall joins visually as it grows". Stand still and build, one bay at
    a time, a run six or seven long and two or three high, in survival, without moving the camera. **At the second
    bay, does the wall become one structure — one upright between them rather than two — or does it still read as two
    boxes pushed together?** Then look along the finished run from one end. **Do the uprights stand at an even pitch
    and the beam lines run unbroken from end to end?** Then break one bay out of the middle: **do the two ends grow
    their own uprights back, so the gap reads as the end of two racks rather than as a hole?** The automated side of
    this is in `runVisualTest -Pwareworks.visualTest=bays` (`grow-1`, `grow-2`, `grow-3`, `grow-6` — the same camera
    after each bay), but a screenshot cannot tell you whether *building* it feels like putting up racking.
235. **Is the fill level readable while walking past, beyond ten blocks?** The split M29 rests on is that the shape
    says *how full* at any distance and the item says *what* only from the aisle. Build a wall of at least a dozen
    bays filled to different levels, walk past it at normal speed about five blocks out, then again at fifteen, then
    look back at it from the far edge of your render distance. **Beyond ten blocks the items are gone — can you still
    read which bays are full and which are nearly empty, or does the wall go blank?** If the silhouette alone does
    not carry it, the four fill steps are the thing to argue about and not the renderer. Check 212 asked this of M28,
    before anything was drawn at all; the question now is whether *losing* the items changes the answer.
236. **Does the load look like a load, rather than like a floating item?** Stand in the aisle, close, in front of
    bays at every fill step. **Does the item read as a sample set down at the front of the location — something a
    worker put there — or as an item hanging in the air in front of a rack?** Try it with a flat item (paper, an
    ingot) and with a block item (a log, a furnace) in neighbouring bays, and once with a bay whose cartons are at
    the top step, where the item stands closest to them. The drawn item reaches about 1.7 px past the bay's front
    posts on purpose, which is what stops it reading as buried; the question is whether that is too far.

## AP. The stock keeper's panel after the black-texel sweep (M29 review)

> The test the rack bays brought — no face of a model drawn in the solid layer may sample a texel its texture leaves
> empty, because alpha is painted pure black there — was widened in the review to **every** block model of the mod.
> It found exactly one other case, shipped since the stock rules in M15: the warehouse stock keeper's front panel
> stretched the whole 16 x 16 sheet of `create:block/factory_panel` over its 10 x 8 px face, and 125 of that sheet's
> 256 texels are empty. The face now maps the display plate Create's own factory gauge uses for the same surface.
> The test can only say that nothing samples an empty texel any more; whether the block looks better is a person's
> call.

237. **Does the warehouse stock keeper's front panel read as a display?** Put one down and look at it from the front,
    in daylight and at night, with its lamp lit and unlit, and beside a Create **factory gauge** if you have one.
    **Does the panel read as one display plate inset in the brass frame, or as a flat grey patch?** Compare it with
    `run/visual/m29-evidence/joining/visual-blocks-41-stock_keeper-front.png`, which is the block before the fix:
    black rectangles inside the frame and a black band down its right-hand side and along its bottom edge. The shot
    of the fixed block is `runVisualTest -Pwareworks.visualTest=blocks`, `stock_keeper-front`. If the plate reads as
    too plain, the argument is about which part of Create's sheet to use and not about whether the black was wrong.

## AQ. A warehouse that holds fluid (M30, [issue #21](https://github.com/Richie1710/create-wareworks/issues/21))

> Everything measurable about M30 is measured: the bay's persistence, its capability on every face, the break that
> loses exactly the fluid its own log line names, the exchange against a scripted lying location, the whole loop as
> one crane job with items **and** fluid conserved jointly on every tick, the store gate in all three dedication
> states, the readouts, and the renderer's own box corners against the arm's path. What no gate can answer is whether
> **one trip is one bucket** feels like the reason pipes exist or like a bug, whether a refusal reads as a refusal,
> and whether the scene teaches the loop. The last check is the one that decides how M31 is built.
>
> Setup: a creative world with a **copper fluid bay**, a **brass** one, a few rack bays, a working one-aisle
> warehouse with an input, a stack of **lava buckets**, a stack of empty ones, a **Mechanical Pump** with a source of
> lava or water, and goggles.

238. **Does one trip per bucket feel right?** Fill a copper bay through the warehouse: drop lava buckets into the
    warehouse input and watch. A filled bucket stacks to **one**, so that is **64 crane trips** for one copper bay and
    256 for a brass one, while the empty ones come back sixteen at a time. **Watch a few minutes of it. Does it read
    as "this is what the pipe at the back is for", or as the warehouse being broken?** If it reads as broken, the
    thing to argue about is the item description and the Ponder scene, not the crane: the mod deliberately has no
    bulk container. Then do the same with a **pump** at the back and compare the two in your own head.
239. **Does a bay that cannot take a bucket read as a refusal rather than as a lost bucket?** Fill a copper bay to
    63 001 mB (pump it, or fill it by hand and then draw a little off), then put a lava bucket into the input. The
    bucket must be **shelved as an item** and the bay must take nothing. Look at the bay through goggles: it says
    `Lava 63.00 / 64 buckets` **and**, in gold, *"Less than a bucket of room: a whole bucket does not fit"*. **Without
    the gold line, would you have thought the warehouse ate your bucket?** And with it: **is it the sentence you
    needed?** Do it in German too.
240. **Does a bucket in your hand feel like a bucket?** Right-click a bay with a filled bucket, with an empty one,
    with a **stack of sixteen** empty ones, with a full inventory, with a bay that holds another fluid, with the
    bucket in your **off hand** (main hand empty, and again with a pickaxe in it), and with **Shift held**. The whole
    container moves or nothing does; a stack of sixteen fills **one** and tucks it away; either hand works the same
    way; Shift still places a block against the face. **Does any of it surprise you in a way the game did not warn you
    about?** Then the refusal, which has one deliberate hole in it: aim a bucket of lava at a bay that cannot take it
    and confirm that a **plain** click — in either hand — does **nothing at all** and pours no lava against the front
    of your warehouse. Now do the same click **sneaking**: the lava *is* poured, because the game never offers a block
    a sneaking click at all and Shift has to keep its own meaning so you can place a block against a tank's face.
    **Is that trade the right way round, or would you rather lose sneak-placing at a bay?**
241. **Is a tank wall readable from across the room, and does it cost you frames?** Build a wall of at least a dozen
    fluid bays at different levels and with two different fluids. Walk past it at five blocks, at fifteen, and look
    back from sixty. Every bay keeps its level the whole way: a fluid bay draws as far as any other block entity in
    the game, where the item drawn in a rack bay and the filter on an interface stop after ten blocks. That is
    deliberate — the level *is* the readout, and a gauge you have to walk up to is not one — and the cost is the other
    half of the check. **With F3 open, does standing in front of that wall cost you anything you can see?** Then build
    the unreasonable case, forty or fifty bays in sight at once, and look again. If it ever bites, the fix is a cap in
    this renderer, which is exactly what the two other renderers already do; say so here and it gets one.
242. **Does a tank beside a rack read as one wall?** Put a copper fluid bay directly beside a rack bay of the same
    facing, then a brass one on the other side. They must **share the upright** between them, exactly as two rack bays
    do. **Does the row read as one rack holding both kinds of goods, or as a tank parked next to a shelf?** Then put a
    rack bay **on top** of a copper fluid bay and a copper one on top of a brass one: the first is refused (the
    families do not carry each other), the second is allowed. **Does the refusal message say something you can act
    on?**
243. **Were you warned before you broke a full one?** Fill a bay, then break it — in survival. The fluid is **gone**,
    the empty bay comes back, and three things should have told you so first: the **item description** (Shift in the
    inventory), the **goggle line** in gold while there is anything in it, and the **first hit** on the block, which
    names the fluid in your action bar. **Did any of the three reach you before the loss?** Note that a **creative**
    break shows no action-bar line at all — the server never calls that hook for a creative player — so judge this in
    survival. Then check the server log for the one `WARN` naming the fluid, the amount and the position.
    Now the other way to take a bay away: fill one again and **sneak-click it with a wrench**. The bay comes into your
    inventory, the fluid is gone, and the same line names it — but it arrives **with** the loss rather than before it,
    because a wrench is one click where mining is a warning hit and then a break. **Is a notice enough there, or did
    you want the wrench to refuse the first click?** (Refusing it at all was rejected: a block you cannot move is
    worse than one that says what moving it costs.)
244. **Does the scene teach the loop?** Hold **W** over a fluid bay and watch *"Fluids Travel in Containers"* once,
    at speed, as a stranger would — then answer without scrolling back: **what happened to the bucket?** The scene's
    whole reason to exist is that nothing else in the game says the fluid stays in the bay and the **empty container
    comes back as stock**. If you cannot answer it after one watch, say which beat you lost it at. Then watch it again
    from the **warehouse terminal**, where it is also registered, and judge whether it belongs there.
245. **No two captions at once.** Watch the new scene once more with your eye on the text box alone. **Is there ever a
    moment where two captions are drawn on top of each other?** This is the defect M22 found and M26 shipped again
    (check 223); the schedule is written so it cannot happen, and the three beats that ride the crane's motion are the
    ones to watch.
246. **The German pass.** Restart with `de_de` and look at everything this milestone says: the two bays' names and
    their Shift descriptions, every goggle row (including the gold ones), the controller's `Flüssigkeitsarten` and
    `Flüssigkeit gelagert` lines, the Fluid Stock board and the `Flüssigkeiten:` row on a warehouse summary, and all
    twelve captions of the Ponder scene. **Does every line fit its surface at your own GUI scale** (1 and 4 for the
    goggles, which is the scale no measured budget covers) **and does it say the same thing as the English?** The
    units are Create's own (`B`, `mB`), so they are the one thing that should *not* be translated. One thing that
    looks like a bug and is not: the **decimal separator** of a bucket amount follows the machine's locale and not the
    game's language, so a Fluid Stock board reads `52,0B Lava` on a German system even while the game is in English.
    That is Create's own `FluidFormatter` (`String.format("%.1f", …)` with no locale), which this mod reuses verbatim
    so that a Wareworks board and a Create fluid board never disagree on the same machine. **Would you rather have the
    separator follow the game's language, and accept that the two boards then differ?**
247. **Does a dedicated bay outranking every shelf surprise you?** This is the decision most likely to be argued
    about. Set up a lava bay at the far end of an aisle and a chest behind an interface right beside the input, both
    able to take a lava bucket. Drop lava buckets in: while the bay has room, **every one of them goes to the bay**,
    past the nearer chest, and none can be kept as item stock. **Is that what you want?** If you wanted a crate of
    lava buckets for building, you now have to keep them out of the warehouse or fill the bay first. The opposite
    surprise is on the same wall: an **unfiltered** bay takes a container of whichever fluid reaches it first, milk
    or water included.
248. **Pipes at the back: does the config read the way you expect?** With `storage.fluidBayPipeExtraction` at its
    default, put a pump on the back face of a full bay and draw lava out of it, then set the key to `false`, reload
    and try again. The goggles state which way it stands **either way**, in both settings. **Is the line the one you
    would have looked for?** And the question behind it: a pipe drawing fluid off means fluid leaves a warehouse with
    no crane carrying it — the same thing a vanilla hopper under a rack bay has always done for items. **Does that
    feel consistent, or does it want to be off by default?**
249. **The question that decides M31.** Hand a player a warehouse holding 60 buckets of lava and **no empty buckets
    in stock at all**, and let them try to get lava out of it at a terminal. Today there is no fluid row, so watch
    what they reach for and how long it takes them to find the pipe at the back of a bay. **Do they reach for buckets
    within ten seconds, or do they conclude the warehouse cannot give lava back?** M31 adds the request, the
    empty-container economy and the refusal that names it; what this answer decides is how loudly that refusal has to
    speak.

## AR. Craft at the terminal (M32, [issue #24](https://github.com/Richie1710/create-wareworks/issues/24), first part)

> Everything measurable about this part is measured: every craft against vanilla's own delta with an item census on
> every tick, the refusals that move nothing, two players in one tick, limited crafting, 400 idle ticks in which
> nothing is crafted, and the `craft` scenario's real clicks with the window measured at a 320 × 240 GUI. What no gate
> can answer is how the ghost **feels** in the hand, the two Shift gestures with a real Shift key (the harness cannot
> hold one), the German at the one screen where the window is tightest, and what a real server with two real players
> does. Fetching the ingredients for a recipe has its own section, AU.
>
> Setup: a working one-aisle warehouse with a terminal (nine delivery slots, the default) and about a thousand oak
> planks, some sticks, milk buckets, sugar, eggs and wheat in its racks. Open the terminal and fold the crafting grid
> out with the grid button right of your inventory.

250. **Does the ghost feel like a tool rather than a trick?** Right-click Oak Planks in the stock list and paint the
    ring of a chest with left clicks, then press **Esc**: the ghost goes, the screen stays open. Pick a ghost again and
    left-click **another** item in the stock list: it becomes the ghost and **nothing is requested** (watch the crane
    and the status row). With an empty hand, hold a real **Shift** key and right-click Stick twice: the sticks land in
    the next free cells. Right-click a filled cell to clear it, press **Clear**, and put a real item on the cursor and
    click a cell with it: the cell takes the item's key and the item stays on your cursor. **Is there a moment where
    you did not know what a click would do, or where a click you meant as a request picked a ghost?** Hover a stock
    cell, a grid cell and the result while a ghost is held: no tooltip at all, which is deliberate. **Does that read as
    calm, or as broken?** Finally, with planks delivered and the result showing, move one plank of the grid and click
    the result **straight away**: nothing is crafted, a low note sounds, and a moment later the new result shows and
    the next click crafts it. **Does that read as the panel catching up, or as a click that went missing?**
251. **Shift-click on the result, with a real Shift key.** Request 64 planks and wait for them, lay the chest, then
    **Shift-click** the result: eight chests, the planks gone from "Delivered here", the status row green "Crafted Chest
    x8". Now make it stop half way: request planks until every slot of "Delivered here" is full, put **62 chests** in
    one slot of your inventory and cobblestone in every other main slot, hotbar included, and Shift-click again. Two
    chests are made (the stack is then full), the third has nowhere to go, and the row turns gold: "Crafted Chest x2.
    No room for Chest". **Is it clear that the first part happened and the rest did not?** Exactly 16 planks are gone.
252. **German at the tightest screen, and the window that must not jump.** On the **5120x1440** monitor in fullscreen
    with the automatic GUI scale (6, so 854 × 240 GUI px) and the game in German: fold the panel out and in. The window
    must not move, the title reads **"Werkbank"** inside its frame, and every tooltip of the toggle, the cells, the
    result and a stock cell fits the screen. Craft once ("Truhe x1 gefertigt") and set up the stop of check 251 ("Kein
    Platz für Truhe"). **Does "fertigen" read right beside the production station's "herstellen"?** Then on a
    1920 × 1080 screen at GUI scale 4, and on any screen at the largest GUI scale it offers: the folded-out window
    stays on screen with a little margin on both sides. If you play with **JEI**, its item list must keep clear of
    the panel at every one of these sizes.
253. **A dedicated server round trip.** Start `runServer`, join with a client, and at a terminal craft once,
    Shift-craft, make a cake (three buckets stay in the terminal) and provoke one refusal. Close the screen and open
    it again: the panel and the grid you laid are still there and the result shows without a click. Now put an
    **enchanted book** into a cell (it need not make anything), quit to the title screen and join again without
    closing the game: the panel is still folded out, the grid is **empty**, and opening the terminal does **not**
    disconnect you — which is intended: a grid is remembered only while you stay connected, because the enchantment
    in its key belongs to that connection. Restart the **server**: the
    delivered items and the buckets are still in the terminal, and nothing was crafted while nobody clicked. Read the
    server and the client log for errors mentioning `wareworks`.
254. **Two real players at one terminal.** Two clients open the same terminal. Each lays a **different** recipe: neither
    sees the other's grid. One player requests planks; both screens show them arrive and both "xN" counts move. Lay the
    chest on both screens and Shift-click on both at the same moment: between them they make exactly as many chests as
    the planks allow, and the player who came second reads "Not enough Oak Planks here". **Count the chests and the
    planks afterwards: nothing duplicated, nothing lost.**
255. **Survival with limited crafting.** In a survival world, `/gamerule doLimitedCrafting true`, and lay a recipe you
    have not unlocked (a fresh player has almost none). The result is drawn with a red overlay and its tooltip says
    "You have not unlocked this recipe"; a click answers the same and moves nothing. Unlock it with
    `/recipe give @s <recipe id>` and click again: it crafts, and the red overlay is gone. Open **Statistics →
    Items** and check that the crafted count went up by exactly what you made, and that a refused click added
    nothing. Then set the rule back.

## AS. Schematics on a server with Create: Schematic Checker ([issue #25](https://github.com/Richie1710/create-wareworks/issues/25))

> What Create prints is automated: `SchematicSafetyGameTests` saves every Wareworks block, configured and stocked, the
> way Schematic and Quill's instant upload does, prints it with Create's own printer into a fresh copy and over the
> standing original, and asserts the exact settings that travel, an empty copy, an original that keeps its goods and
> an unchanged item census. What no test can run is the other mod: *Create: Schematic Checker* is not a dependency, and
> what it removes was read from its bytecode, not watched (ADR-061). One check, and it needs a dedicated server.
>
> Setup: a dedicated server with Create, Wareworks and **Create: Schematic Checker** (2.27.47 is the reported
> version) with `WhiteListModEnable = true` under `[core]` in `config/CSC/config.toml` (the checker's default), and a
> client with a **Schematic and Quill**, a **Schematic Table**, a **Schematicannon** and a blank **clipboard**.

256. **Do filtered rack bays survive the checker, without and with the whitelist line?** Build a small warehouse with a
    few **rack bays** (some with a filter and a priority, one of them stocked, one left unconfigured), a plain
    **warehouse interface**, a **terminal** and a few **rails**, and save it with the Schematic and Quill. First with
    the checker's shipped `WhiteListModList`, unchanged and without `wareworks`: upload the schematic at a Schematic
    Table, deploy it, look at the hologram, then load it into a Schematicannon and write its material checklist onto
    the clipboard. **Which Wareworks blocks are missing from the checklist and from the print, and which survived?**
    The hologram should still show every block, because it is drawn from your own file. The report says the bays
    vanished while interfaces and terminals survived; the checker's bytecode says every block with block data should
    vanish, which includes those, so note exactly which survived — the README's wording depends on it. Then append
    `"wareworks"` to the list, keeping every entry already in it, restart the server, upload the same file again and
    print it: **is every block on the checklist and in the print now, each filtered bay with its filter and priority,
    and is every printed bay empty — the stocked one included?** Note any line the checker writes into the server log
    in either run.

## AT. A screen opens with anything in your hand ([issue #26](https://github.com/Richie1710/create-wareworks/issues/26))

> The warehouse terminal, the production station and the stock keeper now open like a chest: a right-click opens the
> screen whatever you hold, and sneak + right-click places the block or uses the item. `StationClickGameTests` drives
> every case below through the server's real click handling, also the wrench in adventure mode. The `arm` visual
> scenario clicks the terminal and the production station with the arm item on a real client and checks that no screen
> opens (CHECK 88). It also covers the **client** half of check 257 for the terminal (CHECK 257): with cobblestone and
> then with bread in hand, a real right-click opens the screen, and on every client tick from the click until the
> screen is open there is no block above the terminal, no stone placement sound and no item in use; the server has
> the menu open and placed and used nothing. That run is creative and on the integrated server, and it only clicks the
> terminal. What is left for a person is the production station and the keeper on a client, survival stack counts, a
> dedicated server with real lag, and how it feels.

257. **Do the three stations open like a chest, and does nothing flicker?** Build one of each in survival, then
     right-click each one holding, in turn, a stack of cobblestone, a pickaxe, bread (while hungry) and a chute. The
     screen opens every time; **no block appears against the station even for a frame, no placement sound plays, the
     stack keeps its count and you do not start eating.** Now **sneak** + right-click with the same items: the
     cobblestone and the chute are placed against the station, the bread is eaten, and no screen opens. Then hold each
     of these and right-click without sneaking: the **wrench** turns the block (the terminal's screen face), the
     **Mechanical Arm** highlights the terminal and the production station as targets and is placed against the keeper,
     the **clipboard** opens its own editor, a **Display Link** says it selected the block, and **goggles** go on your
     head — and none of them opens a station's screen. With an empty hand, sneak + right-click on the production station
     still answers in chat ("Nothing this station makes is stopped", or the items it makes again). In **adventure
     mode**, right-click a station with the wrench and bread in the offhand: the screen opens (the wrench cannot turn
     anything there) and the bread is not eaten. Do it once on a **dedicated server** as well: a laggy connection is
     where a disagreement between client and server would show as a block that appears and vanishes again.

## AU. Fetch the ingredients at the terminal (M32, [issue #24](https://github.com/Richie1710/create-wareworks/issues/24), second part)

> **Fetch** is measured too: exact amounts, xN, the top-up against the buffer, the room and stock questions, every
> refusal, a fetch beside a clipboard order, a cancel that keeps a merged click (before and after the pick, and with a
> share that was being made), a stock rule's question answered, refused, resumed and reloaded, a stale Yes from a
> second screen, a reload and a broken terminal in GameTests, a chunk unload and a rejoin in `runRobustnessTest`, and a
> Fetch, a cancel and the room question clicked through the real screen in the `craft` scenario. What no gate can
> answer for it is a real dedicated server, two real clients at one terminal, the panel a stock rule's question puts
> up, and the German at real scale 6.
>
> Setup: the warehouse of section AR, with the terminal's crafting grid folded out.

258. **A dedicated server round trip with Fetch.** Start `runServer` and join with a client. Lay a chest and set the
    window's amount field to **3**. Hover **Fetch** (the play button under the grid): "Fetch the ingredients for Chest
    x3", "Oak Planks: 24 to fetch". Press it: the status row says "Fetching for Chest x3", the bar under the buttons
    counts up, a right-click on a grid cell answers "Cancel the fetch to change the recipe", and Engineer's Goggles on
    the terminal show "Crafting fetch: 0 of 24 (fetching)". While the crane carries the first planks, stop the server
    with `/stop`, start it again, join and open the terminal: the panel folds out by itself, the grid shows the chest,
    still locked, and the fetch finishes with exactly 24 planks in "Delivered here" ("Delivered: click the result to
    craft"). Press Fetch once more: "Already here: click the result to craft", and nothing is requested. Shift-click
    the result: three chests. Then lay a crafting table, set x4, press Fetch and give it up with the red **x** at the
    end of the bar while the crane carries planks: "The fetch was given up", what had arrived stays in the terminal,
    the planks on the crane go back into the racks, and the grid unlocks with the table still laid. Read the server and
    the client log for errors mentioning `wareworks`. **The amount field is the one a click in the list requests with:
    once the panel is out, does it read naturally as "how many crafts", or do you trip over it after a request?**
    (Since issue #33 the field counts result items in the panel too; section BD asks the same question again.)
259. **Two real players at one terminal while a fetch runs.** Slow the crane down (a low-speed motor), so a click is
    still on its way a moment later. Two clients open the same terminal. Player B lays a crafting table and folds the
    panel **in**; player A lays a chest and clicks Oak Planks in the stock list for 8 with the amount field at 8. Right
    after, A sets x3 and presses Fetch: B's panel folds out by itself (B's screen at least 374 GUI px wide, e.g.
    1920x1080 at scale 4) and B's grid now shows A's chest, locked — B's crafting table is gone, which is intended
    while a fetch runs. B presses Fetch: "Already
    fetching ingredients here". B gives the fetch up with the red x: both screens drop the bar, both grids unlock and
    keep the chest. Count the planks in the racks and in the terminal before the click and after everything has
    settled: none appeared or vanished, and A's 8 planks arrived although the fetch they shared a request with was
    given up. Close B's screen and open it again: the panel is folded **in**, as B left it. Do the Fetch once more with
    B on a **narrow** screen (1280x960 at GUI scale 4, i.e. 320 GUI px): B's panel stays folded **in**, the stock list
    does not move under B's cursor, and B's status row shows "Fetching for Chest x3". **Is it clear to B why their own
    recipe disappeared, and is laying it again an acceptable price?**
260. **German at the real scale 6.** On the **5120x1440** monitor in fullscreen with the automatic GUI scale (6, so
    854 × 240 GUI px) and the game in German, lay a chest and set x3. Fetch's tooltip reads "Zutaten für Truhe x3
    holen", "Eichenholzbretter: 24 zu holen" and "Das Mengenfeld gibt die Stückzahl an", every line on screen. Press
    it: the status row "Zutaten für Truhe x3 werden geholt", the bar's tooltip "Geliefert: 0 von 24" and "Unterwegs:
    24", over the x "Klick: aufgeben; Geliefertes bleibt hier", a right-click on a cell "Zum Ändern erst das Holen
    abbrechen", at the end "Geliefert: aufs Ergebnis klicken", and on the goggles "Werkbank-Zutaten: 0 von 24 (holt)".
    Then lay a cake and set x4: the question reads "Zutaten für Kuchen x2 holen?" and "Nur x2 von x4 passen hinein.",
    inside the panel. **Does "holen" read right beside the player's "fertigen" and the production station's
    "herstellen"? Does "Werkbank-Zutaten" on the goggles say what is being fetched?**
261. **A stock rule's question during a fetch: Yes, No, Resume.** Give Oak Planks a stock keeper row with a
    **reserve** of 32 while the racks hold 40, lay a chest and press Fetch for x3 (24 planks). It **starts without a
    question** — a player is never held back by a reserve — but the first portion that would go below the reserve asks
    with the ordinary panel: it names the item ("For the crafting grid: 24 Oak Planks"), shows no Alt hint, the order
    button turns to **Answer** and the bar turns gold. Press
    **No** (or Escape): "The rest of the fetch is left for now", the row says "Fetch for Chest x3 is paused" and the
    button turns to **Resume**. Press Resume: the same question comes back. Answer **Yes**: the 24 planks arrive and 16
    stay in the racks, below the reserve you agreed to cross. (What the server does with the question — Yes, No,
    Resume, "show it again" and a reload while it is up — is the GameTest
    `terminalcraftorderportionquestionisshownagainandcanberefused`; this check is about the panel.) **Is it clear that
    this question belongs to the fetch and not to a click of yours?**
262. **A fetch beside a clipboard list, and the terminal's request limit.** Slow the crane down (a low-speed motor),
    empty the terminal's buffer, and make sure the racks hold at least 6 oak planks, 8 cobblestone, 2 iron ingots and 2
    redstone. Put a **hand-written** clipboard list of four item types, at most one stack of each (16 each of four items
    that stack to 64; none of them planks, cobblestone, iron ingots or redstone), into the list slot — not a
    Schematicannon checklist: the Fetch is measured with the list's open portions put down first, and the nine buffer
    slots have to hold the list's four stacks and the piston's four ingredients together, so a list of several stacks
    per entry makes Fetch answer that there is no room for one craft, which is then expected and no bug. Press the list
    button; while it runs, lay a **piston** and press Fetch for x2. Both run at once: with
    the panel folded out the status row shows the fetch's line, folded in the list's. While both hold two requests
    each, click a fifth item type in the stock list: it is refused because the terminal already waits for four item
    types (`maxOpenRequestsPerOutput`), while a click for an item one of the orders is fetching merges. **Does that
    refusal read as a limit you understand, or as a broken terminal?** Both orders finish: the clipboard is ticked
    off, and the terminal holds the list's 4 × 16 items plus exactly 6 planks, 8 cobblestone, 2 iron ingots and 2
    redstone (eight of the nine slots).
263. **A stuck list says why, and stops saying it.** Take every glass block out of the racks, write a clipboard for
    3 glass and 16 oak planks, press the list button and say Yes to fetching what there is. Once the planks have
    arrived the status row says in **gold** "Glass is missing" instead of the list's progress, and the clipboard slot's
    tooltip names it under the progress.
    Give Oak Planks a stock keeper **reserve** that the list's planks would go below and start the list once more with
    a fresh clipboard: while its question is up the row says the list waits for an answer, not "Glass is missing"; a
    **No** turns it into "paused". Put glass into the racks and resume: the row goes back to the list's progress and the
    list finishes. **Does the gold line read as a reason you can act on, and does it ever stay after it stopped being
    true?**

## AV. Missing ingredients made first (M32, [issue #24](https://github.com/Richie1710/create-wareworks/issues/24), third part)

> The chain offer is measured as well: the offer and its Yes played through to the craft, a No that orders nothing and a
> portion's question declined, a refusal that names the raw material, a stopped ingredient and the click that lifts the
> stop, previews that change nothing, two chains sharing two logs, and a fence gate whose chains follow its lines in
> `TerminalCraftChainGameTests` (the tests play the machines themselves), and a Chain x4 whose nuggets a real
> Mechanical Arm and Mechanical Crafter make in the `craft` scenario, in English and in German, with the makeable cells
> and the question shot. What no gate can answer is a chain at a machine a player built, on a dedicated server; how the
> offer reads in German at the real scale; whether a declined offer feels as safe as it is; and how the stopped cell
> looks, which no shot shows.
>
> Setup: the warehouse of section AR with the crafting grid folded out, plus a warehouse production station on its rack
> wall with the pattern **1 Oak Log → 4 Oak Planks**, feeding a real **Mechanical Saw** whose planks go back into a
> warehouse input (the saw of section W). Take every oak plank out of the racks and leave about 16 oak logs in them.

264. **The chain offer at a real machine, on a dedicated server.** Start `runServer` and join with a client. Lay
    **sticks** (two planks, one above the other) and set x1. Both plank cells show the blue **+** on a faint blue, and
    their tooltip reads "Can be made here: Fetch asks first"; the Fetch tooltip's plank line is blue too. Press Fetch:
    the question reads "Fetch the ingredients for Stick x1?" and "Oak Planks x4 will be made first.", and **nothing has
    moved** — the crane stands still and the terminal's production section is empty. Press **Yes**: one order appears in
    the production section, the crane takes a log to the station, the saw cuts it, the crane stores the planks and
    brings exactly two into "Delivered here"; the rest of the log stays in the racks as planks. Click the result: four
    sticks. Now take the planks out of the racks, put exactly **3** back, set x4 and press Fetch: "Oak Planks x8 will be
    made first.", "Without making: x1" and "Set x1 to fetch only that.". Yes: exactly 8 planks arrive, and what the saw
    cut beyond them stays in the racks. Read the server and the client log for errors mentioning `wareworks`. **Does
    "will be made first" make it clear that your saw does the work and the warehouse only carries? Does the made number
    — x8 where only 5 were missing, because the pattern makes 4 per log — read as a surprise?**
265. **German at the real scale 6.** On the **5120x1440** monitor in fullscreen with the automatic GUI scale (6, so
    854 × 240 GUI px) and the game in German, with no planks in the racks: lay sticks and set x1. A plank cell's tooltip
    reads "Hier herstellbar: Holen fragt erst nach", and Fetch asks "Zutaten für Stock x1 holen?" and "Wird zuerst
    hergestellt: Eichenholzbretter x4". Answer No, put 3 planks back, set x4: "Wird zuerst hergestellt:
    Eichenholzbretter x8", "Ohne Herstellung: x1" and, in dark grey italics, "x1 einstellen, um nur das zu holen.",
    every line inside the
    dialog without a wrap. Answer No again, take every log out of the racks and press Fetch for x4 once more: "Nur x1
    von x4 verfügbar:", "Eichenholzbretter: 3 verfügbar, 8 nötig" and, in grey, "Eichenstamm fehlt". Answer No, take the
    3 planks out as well and press Fetch: nothing is ordered and the status row says "Eichenstamm fehlt". Repeat the
    dialog at GUI scale 4. **Does "Wird zuerst hergestellt: Eichenholzbretter x8" read right — the colon form needs no
    agreement with a plural item name — and does "hergestellt" — your machines — stay clearly apart from "gefertigt",
    your own craft? Is the dark grey italic hint readable on the dialog's background at both scales?**
266. **A declined offer orders nothing, and the hint leads to what the racks hold.** With 3 planks and the logs in the
    racks, lay sticks, set x4 and press Fetch. Look at the stock list's log count, then answer **No** (or press Escape):
    the dialog goes, the crane does not move, the production section stays empty, no log is taken or held back, and
    nothing is requested for the terminal. Press Fetch again: exactly the same question. Answer No once more and do what
    the hint says — set x1 and press Fetch: it starts **without a question** and brings 2 planks; nothing is made. Craft
    the sticks, set x4 again, say **Yes**, and give the fetch up with the red **x** while the saw is cutting: "The fetch
    was given up", whatever planks arrived stay in "Delivered here", and the saw's order is not cancelled with it: it
    runs on, and its planks go into the racks, promised to nobody. **Is it clear after a No that nothing at all was
    ordered? Is "Set x1 to fetch only that." an instruction you would follow, or does it read like a second question?**
267. **A stopped ingredient looks stopped.** With no planks in the racks, arm the safety stop for Oak Planks the way
    check 145 does, or quicker: break the saw's shaft so it cuts nothing, click Oak Planks in the stock list for 4 and,
    once the crane has handed the station its log, cancel that order in the terminal's production section; the station's
    ring lights up. Lay sticks: the plank cells are a lighter red than a missing cell and carry a red **!** in the count
    corner, their tooltip reads "Making Oak Planks is stopped" in red, and the Fetch tooltip's plank line is red. To
    compare the two side by side, put a slime ball (none in the racks, nothing makes it) into a free cell, look at both
    at GUI scale 4 and 6, and clear that cell again. Press Fetch: nothing is ordered and the status row says "Making Oak
    Planks is stopped".
    Sneak-right-click the station with an empty hand: the ring goes out, the plank cells turn to the blue **+** within a
    moment, and Fetch offers the chain again (answer No, then repair the shaft; the log the saw was handed comes back as
    planks). **Can you tell a stopped cell from a missing one at a glance — is the red "!" visible at both scales — and
    the "+" on its faint blue from an available cell?**

## AW. JEI's "+" at the terminal (M32, [issue #24](https://github.com/Richie1710/create-wareworks/issues/24), fourth part)

> What the server does with JEI's "+" is measured: the pick — the buffer first, then the stock, then what a station
> makes, plain items before worn ones, one item per candidate list, missing ingredients that still lay — every refusal,
> the lock of a running fetch and the laid grid in the view, in `TerminalCraftLayGameTests` and `IngredientPickerTest`,
> and in the `jei` scenario a real click on JEI's real "+" with JEI 19.8.4.110, 19.19.6.236, 19.21.0.247, 19.57.0.451
> and 19.64.0.454, in English and German,
> with the greyed-out "+" while a fetch runs and an item census around every lay. The scenario also checks that JEI's
> every-tick check lays nothing, that a click lays exactly once and rings one bell, that the terminal comes back from
> JEI already folded out, and that a fetch finishing behind JEI's open screen reaches the terminal: its stock list
> updates and the "+" turns usable without JEI closing. What no gate can answer is a dedicated server in survival,
> Shift on the "+" with a real Shift key, the German at the real scale 6, EMI, and
> whether a laid recipe and a greyed-out "+" read right to a player.
>
> Setup: the warehouse of section AR, with these racks: about 200 spruce planks, 64 oak planks and 16 birch planks,
> cobblestone, redstone, gunpowder, blaze powder, 32 charcoal and 4 coal, and **no iron ingots**. The client runs with
> JEI (`./gradlew runClient -Pwareworks.jei`, or the JEI of your pack); the terminal's crafting grid is folded **in**.

268. **The "+" in survival on a dedicated server.** Start `runServer` **without** `-Pwareworks.jei` (a server needs no
    JEI), join with the JEI client and switch to survival. Open the terminal, hover the Chest in JEI's item list and
    press **R**: the recipe shows a "+". Click it: JEI's screen closes, the panel folds out, the ring is laid in
    **spruce planks** — the warehouse holds most of them, whichever planks JEI happened to show — the result shows a
    Chest, and the status row says "Laid the recipe for Chest" in green with a bell. **Nothing moved**: the crane stands
    still, "Delivered here" is unchanged and nothing is requested. Open JEI on the Crafting Table, hold a real **Shift**
    key and click its "+": exactly the same — the table laid, nothing fetched, the amount field as it was. Set x1 and
    press Fetch: four spruce planks arrive. Then the stock list behind JEI: note the spruce count, drop 64 spruce planks
    into the warehouse input and at once open JEI on any item, so the crane stores them while JEI's screen is open;
    close JEI after the crane is done: the stock list shows the new count without a click. If your JEI offers a recipe's
    "+" from a bookmark beside the terminal (newer JEI versions do), use it once: it lays the same way. Read the server
    and the client log for errors mentioning `wareworks`, and the client log for JEI's `Recipe transfer handler ...
    threw an error`. **Does the spruce read as the warehouse being sensible, or as JEI's choice being ignored? Does
    Shift on the "+" doing nothing more feel right?**
269. **German at the real scale 6.** On the **5120x1440** monitor in fullscreen with the automatic GUI scale (6, so 854
    × 240 GUI px) and the game in German, with the panel folded in: open JEI on **Truhe**. The recipe screen fits and
    the "+" can be clicked. Click it: the panel folds out without the window moving, its title reads **"Werkbank"**, the
    status row says "Rezept für Truhe gelegt" inside the row, and JEI's item list stays right of the panel. Lay the
    **Feuerkugel** the same way ("Rezept für Feuerkugel gelegt", with Holzkohle in its coal cell), slow the crane down
    (a low-speed motor), set x1 and press Fetch; while it runs, open JEI on Truhe: the "+" is greyed out, and hovering
    it shows JEI's "Bewege Items" and, in red, "Zum Ändern erst das Holen abbrechen", every line on screen. Then on a
    1280 × 960 window at GUI scale 4 (320 × 240 GUI px), with the panel folded in, use the "+" once more: the terminal
    comes back from JEI already folded out, 27 px left of where it was before JEI opened, and **nothing moves after
    JEI closes** — no window that pops open or slides under the cursor a moment later. **Does "gelegt" read right
    beside "fertigen" (your craft) and "herstellen" (your machines)? Does the terminal coming back wider and 27 px to
    the left at 320 px feel like the "+" did it, or like a jump?**
270. **EMI without JEI.** Put EMI for NeoForge 1.21.1 into `run/mods` and start `./gradlew runClient` **without**
    `-Pwareworks.jei` (take EMI out again afterwards). The game starts with no errors mentioning `wareworks`. Open the
    terminal and fold the panel out: **does EMI's item list keep clear of the panel** at 1920 × 1080 at GUI scale 4 and
    at the largest GUI scale? Look the Chest up in EMI: there is no working "+" for the terminal — EMI's fill button is
    missing or greyed out, and pressing it changes nothing in the grid. Lay the chest by hand, press Fetch and craft:
    everything works as without a recipe viewer. Only if you want to know more: add JEI next to EMI (`-Pwareworks.jei`)
    and note whether EMI then offers a "+" for the terminal and what it does; nothing is promised there.
271. **A recipe with a missing ingredient.** Look up the **Piston** in JEI and press its "+": it is usable — not greyed
    out, no red slots — and the grid is laid with spruce planks, cobblestone, redstone and an iron ingot. The iron cell
    is drawn on the recipe book's red with "None available, and nothing here makes it", the result shows a Piston, the
    Fetch tooltip's iron line is red, and with x1 Fetch answers "Can't fetch: not enough Iron Ingot" and orders nothing.
    Then empty "Delivered here", take every plank out of the racks and leave about 16 **birch** logs in them (no oak
    logs), put the production station of section AV on the rack wall and set its pattern to **1 Birch Log → 4 Birch
    Planks** instead of oak (its saw cuts any log), so that nothing holds or makes oak. Lay the Chest with "+": all
    eight cells are **birch** planks with the blue **+**, because your saw makes birch — not oak, the tag's first
    entry, which a "+" without this rule would lay — and Fetch for x1 offers "Birch Planks x8 will be made first."
    (answer No). Last, a recipe JEI made up: look up a **Tipped Arrow** and press "+" on one of its recipes: eight
    arrows around a lingering potion are laid, every cell the racks cannot cover is red, and the result shows the
    tipped arrow, although the recipe JEI showed has an id the server does not know. Put the planks back and the
    station's pattern back to 1 Oak Log → 4 Oak Planks afterwards. **Is it clear that the "+" laid a plan and fetched
    nothing, and that the red cell is what you still need?**
272. **The locked grid, and a "+" that comes back by itself.** Slow the crane down (a low-speed motor), with planks in
    the racks. Lay a chest by hand, set x3 and press Fetch. While it runs, open JEI on the Chest: the "+" is shown but
    greyed out, and hovering it reads "Move Items" and, in red, "Cancel the fetch to change the recipe". Click it, with
    and without Shift: nothing happens and JEI's screen stays open. Keep JEI open until the fetch has finished: the "+"
    turns usable **without closing JEI** (the `jei` scenario checks this too, with a fetch of one fire charge). Press
    Escape: the progress row says "Delivered: click the result to craft" and the plank count in the stock list is the
    current one. Press Fetch for a crafting table x4, give it up at once with
    the red **x**, and open JEI on the Chest again: the "+" is usable, and a click lays the chest over the table. **Does
    "Move Items" above the red reason read as if the "+" would move items, and is the red line enough to tell you what
    to do?**

## AX. Quark's seed pouches in the warehouse ([issue #27](https://github.com/Richie1710/create-wareworks/issues/27))

> An item key now hashes only what its equality is known to compare (ADR-064), so a value that compares by value but
> hashes by identity — Quark 4.1's seed pouch contents — no longer makes one item many keys. `ComponentHashGameTests`
> reproduces the pouch without Quark, with a dev-only component that has Quark's exact `equals` and `hashCode`: pouches
> built apart, seeded into the racks or stored by the crane through an input, are one stock entry per content; after
> the storage chests and the controller are saved and loaded while one content lies in several locations, the keys
> loaded per location merge into one entry; and a request whose key went through the request payload's codec is
> delivered into the terminal, with the item census on every tick. At the key level only — equal keys built apart,
> saved and sent are equal and hash equally — the same is checked inside a shulker box, a bundle, a crossbow, a name's
> hover item and a Create clipboard's icon, and for fluid keys; none of these goes through the warehouse. What no test
> can run is Quark itself, a nested pouch going through the warehouse (check 275), a restart of the game and a
> dedicated server.
>
> Setup: Quark 4.1 for NeoForge 1.21.1 and the Zeta version it asks for in `run/mods`, then `./gradlew runClient`
> (take both out again afterwards). A powered warehouse with an input, a terminal and at least four free storage
> locations. Fill four **Seed Pouches**: two with exactly **64 wheat seeds** each, one with **32 wheat seeds**, and one
> with another seed, such as chestnuts. Note the "Item types" line on the controller's goggles.

273. **Store, request and retrieve.** Put all four pouches into the input and wait until the crane has stored them.
    "Item types" on the controller has gone up by **three**, not four, and the terminal's stock list shows three pouch
    entries: the two 64-wheat pouches as one with **2**, the 32-wheat pouch and the chestnut pouch with 1 each. Click
    the entry with 2 with the amount field at 2: both pouches arrive in "Delivered here", and the status line shows no
    "Request refused: not in stock". Request the 32-wheat pouch and the chestnut pouch the same way, with the amount
    field at 1: each arrives. Hover every delivered pouch: it holds exactly what you put in. **Does each entry stand for
    one content, and does each request bring exactly that content?**
274. **After a restart.** Put the four pouches back into the input and let the crane store them. Save and quit, close
    the game, start `./gradlew runClient` again and rejoin. The stock list shows the same three entries with the same
    counts, and a request for each entry is delivered.
275. **Inside a shulker box.** Place two empty **shulker boxes** of the same colour, put one 64-wheat pouch into the
    **first slot** of each, break both and put them into the input; do the same with the chestnut pouch in a third
    shulker box of that colour. The stock list shows two shulker box entries, one with 2 and one with 1. Request the one
    with 2: both boxes are delivered, each still holding its pouch. (A shulker box counts as the same item only with
    the same contents in the same slots, so a pouch in another slot makes an entry of its own, as for any shulker box.)
276. **On a dedicated server.** Put Quark and Zeta into `run/server/mods` as well, start `./gradlew runServer` and join
    with `./gradlew runClient` — not with a release build, which a dev server turns away for its dev-only components
    (ADR-064). Run checks 273 and 275, then stop the server with `/stop`, start it again, rejoin and request each entry
    once more: every request is delivered, and neither log has an error mentioning `wareworks`.

## AY. Order to a door (M27, [issue #18](https://github.com/Richie1710/create-wareworks/issues/18), second half)

> A retrieve into a warehouse port now carries at most what the port can take when the trip is planned, and sets off
> only when the port takes the whole trip or a full stack of a larger request (ADR-065), so the crane never waits in
> front of a port. `WarehousePortGameTests#craneNeverWaitsAtAFullPort` proves it with a port that a test empties by
> hand, on every tick; `aPortThatTakesLittleWaitsForAFullTrip` proves the minimum carry. A request to an accepting or
> collecting port is refused, and one to a port that stopped requesting without telling the controller is cancelled
> on the next relink (`controllerRefusesARequestToAPortThatDoesNotRequest`, `aPortTurnedSilentlyLosesItsRequestsOnRelink`).
> A port that fills up under a carrying crane all the same — a trip planned before a restart that lowered
> `outputBufferSlots`, or a crane saved waiting before this version — gets the rest back into storage at the crane's
> next retry (`aPortFilledUnderACarryingCraneGetsTheRestLater`). What no test shows is a real hopper draining a port in
> a world, and how it looks.
>
> Setup: `./gradlew runClient`, a creative world with a powered warehouse, a warehouse terminal and a chest holding at
> least ten stacks of cobblestone behind an interface, plus a Warehouse Output with **nothing** behind or below it.

277. **A port nobody empties.** Set the output's filter to cobblestone with the amount **40**, its redstone behaviour
    to *while powered*, and power it with a lever. The crane brings 40 at a time. After 14 trips the port holds 560
    cobblestone and has room for 16 only: the crane now stays put with an **empty claw**, the port's goggles still list
    the open request for 40, and the controller's goggles read "Last planning: an output is full". Request a stack of
    anything at the terminal: the crane serves it at once. Then place a hopper under the port, leading into a chest: as
    soon as the hopper has taken 24 cobblestone, the crane brings exactly 40 and drives off again. **Does the crane ever
    stand in front of the port with items in its claw?** (Before this, it carried the fifteenth trip, dropped 16 and
    waited in front of the port with 24 in its claw until somebody emptied it.)

> **Ordering to a door from the terminal.** The server half is pinned by `PackageDoorGameTests` against a real Create
> Packager, with the item census on every tick: a door click delivers into the door's port and never into the
> terminal, the box leaves with the sign's address and no order data of Wareworks', what is no door is refused before
> the stock is looked at, and nothing a door click ends in is remembered. The screen is pinned by
> `TerminalDoorTextWidthTest` (every door text in its room, English and German) and by the `terminal` visual scenario,
> which drives the door button with real clicks, a right-click and the wheel, checks the gold cues, a question naming
> the door, the standing line, here on the next screen and the fallback when the Packager is broken. The `door` visual
> scenario follows one order the whole way, in English and German: chosen on the real screen, carried into the port,
> boxed by the Packager when a lever is flipped, and stored by a second warehouse at the end of a belt. What no gate can
> answer is a Smart Observer emptying a door by itself, everything past the belt, two players on a dedicated server, a
> recipe viewer's round trip, the wrench as the way out of an order, and how the button, its tooltip and the gold lines
> read at the GUI scales a player uses.
>
> Setup: the warehouse of check 277, with its terminal and stock: iron ingots, cobblestone and something a stock
> keeper holds in reserve. Turn the port of check 277 back to *Request* without a filter and put a **Create
> Packager** one block behind it, its back to the port, with a sign on a post beside it reading `Base North`. A **Smart
> Observer** looking at the port powers the Packager through a piece of redstone, and a **funnel** under or beside the
> Packager puts its boxes onto a belt.

278. **The button.** Open the terminal: an 18 × 18 button with Create's "send" icon sits left of your inventory, and
    its tooltip reads "Deliver to: Here", "-> Here", "> Base North  A-…" and "Click: next door, right-click: here".
    Click it: the button turns green, the title row's right end reads "→ Base North" in gold. Right-click it: back to
    here. Scroll over it: down chooses the door, up goes back to here. Break the Packager: the button disappears within
    about two seconds, and a terminal of a warehouse without any door looks exactly as before. **Is the button only
    there while there is a door, and does every gesture do what its tooltip says?**
279. **64 iron to Base North.** Choose the door, set the amount to 64 and click the iron ingots. The status line reads
    "Iron Ingot x64 to Base North" (in German "Eisenbarren x64 an Base North"), uncut, the crane drives to the door's
    port and not to the terminal, the
    Smart Observer fires the Packager, and the box on the belt shows "→ Base North" in its tooltip. While the crane is
    still on its way, the status line counts "Base North: waiting for 64, delivered 0" once the answer has gone, and
    the port's goggles list the request. **Does anything arrive in the terminal's own slots?**
280. **Here on every open.** Choose the door, close the screen and open it again: it says "Here". Choose the door
    again and resize the game window, fold the crafting grid out and in, and, with JEI installed, open an item's
    recipes from the terminal and close JEI again: the door stays chosen through all of it. **Does the door ever
    survive closing the screen, or get lost while it stays open?**
281. **Stock Link.** Put a Stock Link on the door's Packager: within about two seconds the door's entry in the tooltip
    turns red, "ignores redstone", and a click on the button skips it. If the door was chosen, the status line says
    "Door unavailable: clicks deliver here" and the next click delivers here. Click an item in the very moment after
    linking, before the screen has caught up: the answer is "that door ignores redstone". Take the link off: the door
    can be chosen again within about two seconds.
282. **Blank sign.** Remove the sign: the door's entry says "no address" in gold and the chosen door's line under it
    "No sign: the box leaves unaddressed". It can still be chosen and ordered to, and the box leaves without an
    address.
283. **Past the Packager, by chain.** Put a Frogport on the door's Packager, run a chain conveyor to a Frogport named
    `Base North` above a Packager behind a Warehouse Input of a second warehouse, and order 64 iron to the door: the
    iron arrives in the second warehouse and is stored there. **Is any step after the Packager done by Wareworks?**
284. **Past the Packager, by train.** Replace the funnel by one leading into a Postbox at a train station, and send a
    train on a "Deliver Packages" schedule to a second station with a Postbox named `Base North`: the box arrives there.
285. **A question.** Choose the door and click the item the keeper holds in reserve. The question shows a gold line
    "Goes to Base North (A-…)" under its title. Scroll over the door button and over the stock list while it is up:
    nothing moves, and the amount field keeps its number. Answer Yes: the item goes into the door's port.
286. **Fit.** At GUI scale 4 on a 1920 × 1080 window and on a 1280 × 960 window (the crafting grid folded in and out),
    and fullscreen on a 5120 × 1440 monitor at the automatic scale 6, in English and in German: the button, its
    tooltip (at most ten lines), the gold title row, the cell's gold line, the answers, the standing line and the
    question's door line are whole and overlap nothing. A sign with a long text is cut with an ellipsis.
287. **Multiplayer.** On a dedicated server, two players at one terminal choose two different doors (or one a door and
    the other here) and click the same item: each order goes where its player chose, and both see the same door list.
    Restart the server while the crane carries to a door: after the restart the delivery is finished into that door.
288. **Lists and Fetch stay here.** Choose the door and hover the list button and the crafting panel's Fetch: they say
    "A list always comes here" and "The ingredients always come here". Order a clipboard list and fetch a recipe's
    ingredients: both arrive in the terminal.

> **A box that has been waiting.** `PackageDoorGameTests#doorRowSaysNothingTakesTheBox` and
> `#doorRowClearsOnceTheBoxIsTaken` pin the decision on the server: the door's row turns to "box waiting" only after
> the box has rested ten seconds (200 ticks), the port's goggle decision equals the truth on every tick, and a hopper
> that takes the box clears both, while a second box it takes is never reported. The `door` visual scenario shoots the
> row and its advice line with the hopper taken away, in English and German, and the `packages` scenario the gold
> goggle row. What no gate shows is a slow but working taker — a belt funnel on a slow belt — next to the report.

289. **A box nobody takes.** Take the funnel away from the door's Packager and order 64 iron to the door, so the
    Packager boxes it and holds the box. After about ten seconds the door's entry in the terminal button's tooltip
    reads "box waiting" in gold, the chosen door's advice line "A box waits: is anything taking it?", and the port's
    goggles show the gold line "A box has been waiting at the Packager: is anything taking it?" under the address. The
    door can still be chosen. Put the funnel back: the goggle line goes at once, the tooltip entry within about two
    seconds. In German the goggle line reads "Am Verpacker wartet schon länger ein Paket: Holt es etwas ab?". Then let
    the Packager feed a **belt funnel** onto a belt at the slowest speed you use (8 RPM, say) and order several stacks:
    **does either ever show for a door whose funnel works, while boxes leave one after the other?**

> **The way out of an order, a question that outlives its door, and the Ponder scene.** There is no cancel button for
> an order to a door: turning the door out of *Request* cancels what it waits for, and what the crane carries goes back
> into storage, which `PackageDoorGameTests#doorTurnedMidOrderSendsTheCarryBack` and
> `#doorBrokenMidOrderSendsTheCarryBack` pin with an item census on every tick. A Yes always sends the door its question
> was measured for (`confirmedDoorRequestGoesToTheEchoedDoor`). The `ponder` visual scenario compiles the new scene and
> shoots it three times from each block. What no gate shows is the wrench in a player's hand, the question and the
> door list changing under two players at once, and whether the scene reads well in both languages.
>
> Setup: the warehouse and door of checks 278 to 289, with the funnel back on the Packager, plenty of cobblestone in
> the racks, and for check 291 a second player (a second `./gradlew runClient` on a server, or a LAN world).

290. **The wrench escape.** Note how much cobblestone the warehouse holds. Choose the door, put 512 in the amount field
    and click the cobblestone. While the crane is on its way to the door with a load, turn the door's port to
    *Accept* with the wrench. The port's goggles drop the request at once, the terminal's standing line for the door
    goes, the crane drives the load it carries back into a storage location — not into the terminal and not into
    another port — and within about two seconds the door is no longer in the button's list, because an accepting port
    is no door. Turn the port back to *Request*: the door is listed again and its old order does not come back. Add up
    the cobblestone in the racks, the port and the boxes that left: nothing is missing. Do the same once more and break
    the port instead of turning it. **Does any item go missing, or end up anywhere you did not order it?**
291. **A question that outlives its door.** Player one chooses the door and clicks the item the keeper holds in
    reserve, so the question with "Goes to Base North (A-…)" is open. Player two breaks the door's Packager. Player
    one answers Yes: the answer is "not a package door of this warehouse", nothing is requested, neither for the door
    nor for the terminal, and the next click delivers here (with no door left, the door button is gone until there is
    one again). Player two puts the Packager back; player one chooses
    the door again, clicks the item and answers Yes: it goes into the door's port. **Does a Yes ever deliver somewhere
    other than the door its question named?**
292. **The Ponder scene.** Hold **W** over a Warehouse Terminal and over a Warehouse Output: the last scene of each is
    **"Delivering to a Package Door"**, and every other scene of both blocks is where it was before. Watch it through:
    the first bubble on the terminal shows the door button's own icon, a click with a Packager in the bubble draws a
    gold line from the terminal to the door, the crane fills the port, a box appears in the Packager when the redstone
    fires, and the Stock Link at the end turns the door red. Switch the game to German and watch it again ("An eine
    Pakettür liefern"). **Do any two captions overlap, or does any text stand as a raw key?**

> **A full port, a door's shared request, and a chain that cannot finish.** `PackageDoorGameTests#doorRowsNameTheirState`
> reads a full port and a missing sign off real blocks, and `#doorWaitsForAtMostFourItemTypes` pins the refusal of a
> fifth item type at a door. What no gate shows is a door filling up in a world, how its gold lines read, and the chain
> refusal behind a door's name on screen.
>
> Setup: the warehouse and door of checks 278 to 289, with the funnel and the Smart Observer on the door's Packager.

293. **Full port.** Take the Smart Observer off the door's Packager so nothing fires it, and order nine stacks of
    cobblestone to the door. Once the port's nine slots are taken, the door's entry in the button's tooltip reads
    "port full" in gold within about two seconds ("Ausgang voll" in German), and the chosen door's advice line reads
    "Full: is the Packager running?" ("Kein Platz mehr: Läuft der Verpacker?"). The door can still be chosen, a click
    to it is accepted and waits, and the crane serves a click to here meanwhile. Put the observer back: the Packager
    empties the port, and the entry turns back to the rack address within about two seconds. **Does the crane ever
    stand in front of the full port with items in its claw?**
294. **A chain that cannot finish, to a door.** Set up production patterns for chests and for planks from logs, put a
    few chests into the racks and no logs at all, choose the door and order more chests than the racks hold: the
    click is accepted for the chests in stock, and the status line reads the chain's reason behind the door's name in
    gold, for example "To Base North: Oak Log is missing" (in German "An Base North: Eichenstamm fehlt"). The chests
    in stock go into the door, and the standing line names the door once the answer has gone. **Does the line ever
    leave out the door, or name the item cut to nothing?**

## AZ. Create Toolboxes in the warehouse ([issue #29](https://github.com/Richie1710/create-wareworks/issues/29))

> An item key now compares Create's toolbox inventory by what it holds — every compartment with its counts, and the
> item each compartment remembers (its filter) — and keeps a copy of its own, because Create's own comparison looks at
> the remembered items by identity and never at the contents (ADR-066). The same goes for a Sand Paper paused while
> polishing, and for a Wand of Symmetry, whose own comparison never looks at the kind of mirror. The warehouse's
> stations and the crane also stack two items into one slot only when they are the same key. `ComponentMeaningGameTests` uses real toolboxes, placed, filled through Create's own item handler and taken the
> way breaking one takes it, or decoded by Create's codec: equal ones built apart, saved and sent are one key, other
> contents or filters are other keys, and filtered and unfiltered toolboxes are stored, reloaded with the chests and the
> controller, requested with a key decoded as a client sends it and delivered whole, with the item census on every
> tick. What no test does is a player's hands: opening the toolbox, picking it up, placing it again, the toolbox's own
> screen, a real restart of the game and a dedicated server.
>
> Setup: `./gradlew runClient`, a creative world with a powered warehouse: an input, a terminal and at least four free
> storage locations. Place three **Create Toolboxes**. Into the first, put **16 iron ingots** into the top-left
> compartment and **8 gold ingots** into the one beside it; into the second, put **16 iron ingots** into the top-left
> compartment; into the third, put a diamond into the top-left compartment and take it out again, so the compartment
> keeps the diamond as its filter. Switch to survival (`/gamemode survival`) and pick up all three by left-clicking
> them — in creative a left-click destroys a toolbox with its contents — and note the "Item types" line on the
> controller's goggles. Pick toolboxes up the same way, in survival, in every check below. Every toolbox that has been
> placed keeps an id of its own, so two placed toolboxes are two entries even when they hold the same; that is not a
> fault.

295. **Store, request and retrieve.** Put the three toolboxes into the input and wait until the crane has stored them.
    "Item types" has gone up by **three**, and the terminal's stock list shows three toolbox entries. Request each one
    with the amount field at 1: each arrives in "Delivered here", and the status line never shows "Request refused: not
    in stock". Place each delivered toolbox and open it: the first holds 16 iron and 8 gold in the same compartments,
    the second 16 iron, the third nothing, with the diamond still shown as the empty compartment's filter. **Does each
    request bring exactly the toolbox it names, with what it held?** (Before this, every one of them answered "not in
    stock".)
296. **After a restart.** Pick the three toolboxes up again, put them into the input and let the crane store them. Save
    and quit, close the game, start `./gradlew runClient` again and rejoin. The stock list shows the same three
    entries, and a request for each is delivered with the same contents. (Before this, none of them could be requested
    after a restart either.)
297. **Two contents without a filter.** A command makes toolboxes without any filter and without an id, which Create
    itself treats as the same whatever they hold: every inventory of the game stacks two of them into one, outside the
    warehouse too, so move them only with plain clicks into empty slots, never with Shift or onto each other. Give
    yourself two of them, one holding **8 diamonds** and one empty, each into its own hotbar slot (`/give` would stack
    the second onto the first):
    `/item replace entity @s hotbar.7 with create:brown_toolbox[create:toolbox_inventory={items:{items:{"0":{id:"minecraft:diamond",count:8}},size:32},filters:[{},{},{},{},{},{},{},{}]}]`
    and
    `/item replace entity @s hotbar.8 with create:brown_toolbox[create:toolbox_inventory={items:{items:{},size:32},filters:[{},{},{},{},{},{},{},{}]}]`.
    Put a hopper on the input and move each toolbox into a hopper slot of its own. Once the crane has stored both, the
    stock list shows **two** new Brown Toolbox entries with 1 each. Request each with the amount field at 1, take each
    out of the terminal into an empty slot and place it: one holds 8 diamonds, the other nothing. **Are there exactly 8
    diamonds between the two?** (Before this, both were one entry with 2, and the requests could bring the diamonds
    back twice or not at all.)
298. **Refilled and stored again.** Place the second toolbox that check 296 delivered (16 iron), add **8 gold ingots**
    to the compartment beside the iron, pick it up and put it into the input. Once the crane has stored it, the stock
    list shows it as one new entry with 1. Request it and place it: 16 iron and 8 gold, no more. Count the iron and
    gold in all toolboxes and the terminal together: **is any ingot there twice?**
299. **A sand paper paused while polishing.** Hold a new **Sand Paper** in your main hand and a **Rose Quartz** in your
    off hand, hold the right mouse button and switch to another hotbar slot before the polish finishes: the off hand
    holds one rose quartz less, and the paper keeps it. Do the same with a second new sand paper, put both papers into
    the input and wait until they are stored: the stock list shows them as **one** entry with 2. Request both: they
    arrive. Take one, hold it in your main hand and hold the right mouse button until the polish finishes: you get a
    **Polished Rose Quartz**. (Before this, a paused paper could be stored but never requested, and each one was an
    entry of its own.)
300. **On a dedicated server, with a restart.** Start `./gradlew runServer`, join with `./gradlew runClient`, fill the
    setup's three toolboxes there and run checks 295 and 299. Then pick the three toolboxes up again and put them and
    the paper that still holds its rose quartz into the input. Once they are stored, stop the server with `/stop`,
    start it again and rejoin: the stock list shows the same four entries, and a request for each is delivered with
    the same contents. Neither log has a warning "Cannot read Create's" or "Cannot copy a", or an error
    mentioning `wareworks`.
301. **Two kinds of Wand of Symmetry on one block.** Take two **Wands of Symmetry** and right-click the top of the same
    block with each, so both mirrors stand on that block. Shift-right-click with the first and set its Mirror to
    **Rectangular** with the Orientation **Orthogonal**; Shift-right-click with the second and set its Mirror to
    **Octagonal**. Put both wands into the input and wait until the crane has stored them: the stock list shows **two**
    Wand of Symmetry entries with 1 each. Save and quit and rejoin: still two entries. Request each with the amount
    field at 1, take it out and Shift-right-click with it: **does each wand show the Mirror it was stored with?**
    (Before this, both wands were one entry with 2, and a request could bring back the other Mirror.)


## BA. Setting a filter with an item in hand ([issue #30](https://github.com/Richie1710/create-wareworks/issues/30))

> A click **with an item** on the filter slot of a Warehouse Interface, a Rack Bay or a Fluid Bay now sets the filter
> the moment the button goes down, instead of Create's hold-to-edit warmup setting it on the release. An **empty hand**
> or a **wrench** keeps the warmup: a short click with an empty hand clears the filter, holding either opens the
> priority board. Only a press sets a filter: a button held down elsewhere that slides onto a slot sets nothing. While
> the button that set a filter stays down, its repeats do nothing on any filter slot, so a Create filter item that has
> left a survival player's hand is not cleared again by the empty hand (ADR-028, amendment).
> `StorageFilterClickGameTests` proves the server half on all three blocks, including a claim listener that refuses
> the click, `HoverTipWidthTest` the width of the hover tip, and the visual scenario `filterclicks` holds real clicks
> past the warmup, drifts the crosshair off the slot before the release, empties the hand while the button is down,
> slides a held button onto the slot from the face above it, and reads the hover tip and the rack bay's Ponder line in
> English and German. What no test does is a player's own hands: a slow click, a shaky mouse, a low frame rate,
> survival, and a server with latency.
>
> Setup: `./gradlew runClient`, a creative world, one Warehouse Interface with a chest behind it, one Rack Bay and one
> Fluid Bay standing in a row with their aisle faces towards you. In the hotbar: cobblestone, an iron ingot, a List
> Filter set to iron, a water bucket, a Wrench, a Create clipboard, the Mechanical Arm item and an empty slot. In the
> inventory: two untouched Attribute Filters (check 304 runs in survival, where the creative tab is out of reach).

302. **Held and shaky clicks.** On each of the three blocks, right-click the filter slot with cobblestone (the water
    bucket on the Fluid Bay) and **keep the button down for a second** before you let go: the item is drawn in the
    slot the moment you press, and **no board opens**. Then, on the Warehouse Interface, click with the iron ingot and
    **move the mouse** off the slot before you let go: the filter is iron. (On a bay, a crosshair that slides off the
    slot onto the bay's face while the button is still down is a held right-click on that face, as anywhere else on it:
    a Rack Bay takes one iron ingot about every fifth of a second, a Fluid Bay fills or empties the bucket. That is
    expected.) Set your frame rate limit to 10 in the video settings and do both again. **Does a click with an item
    ever open the board, or ever do nothing?** (Before this, a held click opened the board and a moved one was lost,
    and both happened more often at a low frame rate.) Then the other way round: clear the Rack Bay's slot with a short
    empty-hand click, hold right-click with cobblestone on the bay's face **above** the slot, so cobblestone goes in
    one at a time, and let the crosshair slide down onto the slot before you let go. **No filter may be set**, no board
    may open, and no more cobblestone may go in while the crosshair is on the slot; a fresh click there sets the filter.
    The same with the water bucket on the Fluid Bay, and with the iron ingot on the interface.
303. **Empty hand and wrench.** With an empty hand, a short click on a filtered slot clears it; holding the click opens
    the priority board, and so does holding the Wrench. Set 7 and release: the digit 7 is drawn on the block. Aim at the
    slot and read the hover tip: its third line says to hold with an **empty hand** and that **higher fills first**.
    **Is it clear from the tip how to reach the board, and which way the number ranks?**
304. **A Create filter item in survival.** Switch to survival (`/gamemode survival`), take the List Filter, right-click
    a slot and **keep the button down for a second**: the filter goes into the slot and leaves your hand, and after the
    release it is still the slot's filter — no board opened, the filter was not cleared and did not come back to you.
    Click the same slot with an Attribute Filter: it replaces the List Filter, which comes back to your inventory. Click
    once more with a second, untouched Attribute Filter: nothing changes, and it stays in your hand.
305. **Everything else still does what it did.** The clipboard still copies a slot with a right-click and pastes it
    with a left-click down a wall; the Mechanical Arm item still selects the interface as a target where it did; a
    sneaking click with cobblestone on a Rack Bay's slot puts a whole stack in, and a plain click beside the slot one
    item; in adventure mode (`/gamemode adventure`) a click on a slot sets no filter and opens no board (a Rack Bay
    still takes or gives one item and a Fluid Bay still fills or empties a bucket, as a click beside the slot does; the
    interface does nothing). A Deployer facing a slot still cannot set a filter.
306. **On a dedicated server.** Start `./gradlew runServer`, join with `./gradlew runClient` and repeat 302 and 304. The
    filter you see is the one the goggles report after a reconnect, the hand swings once per press while the crosshair
    stays on the slot, and holding the button on a slot with an item in hand neither flickers the slot nor puts
    anything into a Rack Bay. Neither log has an error mentioning `wareworks`. If you have a claim mod at hand (FTB
    Chunks, Open Parties and Claims) and a second account: claim the chunk with one player and click a slot with an
    item as the other, who is not allowed there. **Nothing may be set**, and the client must show the old filter.
307. **German (`de_de`).** The hover tip's third line reads "Mit leerer Hand halten: Priorität; höhere füllt sich
    zuerst", in one line that fits the screen also in the default window size, and the sixth text of the Ponder scene
    "Regalfächer in einem Lagergang" says to hold Right-Click with an empty hand to set a priority, and that the higher
    it is, the sooner the bay fills. Nothing shows a raw key, runs out of its box or is cut off.

## BB. Balancing the crane ([issue #31](https://github.com/Richie1710/create-wareworks/issues/31))

> The README's section "Balancing the crane" and the `[crane]` comments in `wareworks-server.toml` describe settings
> that already existed; nothing about the crane changed. `TravelTimeModelTest#documentedBalancingNumbers` pins every
> speed the text gives, and the GameTest `cranestressimpactwaitsforthenetwork` shows that a running kinetic network
> keeps the stress impact it read, and its total, until the crane joins it again, which breaking and placing the shaft
> under the dock does, and so does a speed change at the creative motor. What no test does is edit the file while the
> game runs, read a Stressometer, restart the world, slow a crane with a Rotation Speed Controller, and run all this on
> a dedicated server.
>
> Setup: `./gradlew runClient`, a creative world with a straight aisle of 16 rails and a few items to store, the dock
> driven by a creative motor at 256 RPM through a Stressometer and then one shaft directly under the dock, with nothing
> else on that network, and Engineer's Goggles. The dock's goggles show its own "Stress Impact ... at current speed".
> The Stressometer's goggles show the network's "Remaining Capacity": the creative motor gives 4,194,304 SU at 256 RPM,
> so the load the network counts is that minus the remaining capacity, and the dial barely moves. Put every value you
> change back afterwards.

308. **The comments.** Open `run/config/wareworks-server.toml` after the world has started once (an older file gets the
    new comments then, keeping its values). In `[crane]`, `maxBlocksPerTick` says it caps travel and lift but not the
    arm, that the default factors never reach it up to 256 RPM and that it has to be lowered to act;
    `stressImpact` says a running network counts a change only once the crane joins it again (the shaft under the dock
    broken and placed again, or a speed change at the source) and that after a restart the network total follows only
    at its next change; `armExtendPerTickPerRpm` says the arm never extends faster than fully in one tick. **Does the
    file explain both settings without the README?**
309. **`stressImpact` while the world runs.** The dock's goggles show 1024 SU at current speed (4 SU per RPM), and the
    Stressometer a Remaining Capacity of 4,193,280 / 4,194,304 SU, so the network counts 1024 SU. Set
    `stressImpact = 8.0` and save the file: within a few seconds the dock's goggles show 2048 SU, while the Stressometer
    still reads 4,193,280. Break the shaft under the dock and place it again: 4,192,256 / 4,194,304 SU, so the network
    counts 2048 SU. Set 4.0 again and save, then scroll the creative motor to 128 RPM and back to 256: 4,193,280 again,
    without touching the shaft. Set 8.0 again and save, leave the world and open it again: the dock's goggles show
    2048 SU. Note what the Stressometer reads before you touch anything: 4,193,280 (the saved total) or 4,192,256,
    depending on the order the blocks load in. Break and place the shaft under the dock: 4,192,256.
310. **`maxBlocksPerTick`.** Request a stack from the far end of the aisle and watch the trip. Set
    `maxBlocksPerTick = 0.25`, save and request again: travel and lift are clearly slower (16 rails take about 64 ticks
    instead of 24), the arm reaches in exactly as fast as before, and the turn at a corner, if your aisle has one, is
    slower too. With 1.0 again the crane is as fast as on the first trip.
311. **One crane slowed down.** Drive the dock through Create's **Rotation Speed Controller** set to 32 RPM: the crane
    travels, lifts and turns at an eighth of its speed and extends its arm at a sixth (the arm is capped at one full
    extension per tick from 192 RPM on, so it ran at that cap at 256 RPM). The dock's goggles show 128 SU at current
    speed and the Stressometer reads 4,194,176 / 4,194,304 SU, while a second crane on another motor keeps its speed.
312. **On a dedicated server.** Start `./gradlew runServer`, join with `./gradlew runClient`, build the setup above on
    the server, and while you are connected set `maxBlocksPerTick = 0.25` in `run/server/config/wareworks-server.toml`
    (or in the world's `serverconfig/` copy, if there is one) and request a stack. The crane on the server is capped at
    once; your client still animates it with the old values until you rejoin, then it moves smoothly at the capped
    speed. **How does the crane look before the rejoin: does it jump, and is the README's one sentence about it
    enough?** Then, still connected, set `stressImpact = 8.0` in the same file, save, and break and place the shaft
    under the dock: the Stressometer reads 4,192,256 / 4,194,304 SU, while the dock's goggles still show 1024 SU at
    current speed; after a rejoin they show 2048 SU. Neither log has an error mentioning `wareworks`.

## BC. One warehouse number on a display ([issue #32](https://github.com/Richie1710/create-wareworks/issues/32))

> The controller and the terminal offer a fourth Display Link source, **"Warehouse Number"**, last in the list, which
> writes one number: a selector in the Display Link screen picks which (ADR-026, amendment). The GameTests
> `warehousenumberstock`, `warehousenumberorders`, `warehousenumberrules` and `warehousenumberports` read every one of
> the fourteen numbers through real links onto a nixie row and a lectern, `warehousenumberlabel` the "Label" box on
> both and on a display board, `warehousenumbermodeorder` that a saved link keeps its number,
> `warehousenumberdegraded` a block outside a warehouse and a saved number this build does not know, and
> `warehousenumberdocklost` a controller whose dock was broken. `WarehouseNumberTextTest` measures every name against
> the selector in English and German, and the visual scenario `display` opens the real screen in both languages,
> checks what it preselects and that every name fits, and shoots a board with a labelled share. What no test does:
> pick numbers with your own mouse, judge whether the names say what they count, read a board from where you stand,
> play on a dedicated server, and open a world built with 0.12.1.
>
> Setup: `./gradlew runClient`, the aisle of sections B–F, stocked, with a fluid bay holding some fluid, a warehouse
> output, a production station with a pattern, a stock keeper with a rule and Engineer's Goggles. From Create's tab:
> Display Links, a row of eight Nixie Tubes (each tube shows two characters, so the row holds 16, like the GameTests'
> row), a display board four blocks wide driven by a motor, a lectern with a book and a sign.

313. **The screen.** Select the nixie row with the link item and place the link on the controller. Open it: the source
    still starts on "Warehouse Summary", and "Warehouse Number" is the last entry. Pick it: the first line below is
    Create's "Label" box, the second the selector, which starts on "Items stored". Hover the selector: its tooltip
    "Number shown" lists all fourteen names. Scroll through them: each one stays inside its box. Confirm, and within
    about five seconds the nixie row shows the number. **Is the selector easy to find, and does every name say what it
    counts?**
314. **Every number against the goggles.** Pick each number in turn and compare it with the controller's goggles (and
    the Warehouse Summary for the locations): items stored, item types, fluid stored (`48.0 B`, or `250 mB` below a
    bucket), locations in use and in total (the summary's `Locations: 3 / 30`; the fluid bay counts as in use while it
    holds fluid, so empty it with a bucket and the first number drops by one while the total stays), locations in use
    as a share (`10%` there), open requests (request something while the dock's motor is stopped), production orders
    (order the pattern's result), rules below minimum and at maximum, stopped products, accepting and collecting ports
    (set a port's rank), misaligned blocks (turn an interface along the aisle with the wrench). Each one follows a
    change within about five seconds. **Is any of them a surprise?**
315. **Label and board.** Type `Items` into the "Label" box: the nixie row reads `Items 176` (a row of only four tubes
    would cut it to `Items 17`, which is Create's width and not a bug). Point a second link at the four-block display
    board: the label stands in a column of its own on the left and the number beside it, and with an empty label the
    number has the row to itself. A sign and a lectern show `Items 176` as well. **Is the board
    readable from where you would stand?**
316. **The terminal and a stray block.** A link on the terminal gives the same numbers as on the controller. Place a
    terminal away from every aisle, put a new link on it, point it at the nixie row, pick "Warehouse Number" (a new link
    starts on "Warehouse Summary") and leave the Label box empty: the row shows `No warehouse`. Break the warehouse's
    crane dock: within about five seconds the links on its controller and on its terminal show `No warehouse` for every
    number, stopped products included; place the dock again and the numbers come back.
317. **Old worlds and saves.** Open a world with display links built in 0.12.1-beta: every link shows what it showed,
    and a new link placed on a controller still starts on "Warehouse Summary". Pick "Open requests" with a label, leave
    the world and open it again: the link still shows open requests, with the label.
318. **German (`de_de`).** The source reads "Lagerkennzahl", the selector's title "Angezeigte Zahl", and the names
    "Gelagerte Gegenstände", "Gegenstandsarten", "Gelagerte Flüssigkeit", "Belegte Lagerplätze", "Lagerplätze
    insgesamt", "Belegte Lagerplätze (%)", "Offene Anforderungen", "Produktionsaufträge", "Regeln unter Minimum",
    "Regeln am Maximum", "Angehaltene Produkte", "Aufnehmende Anschlüsse", "Abholende Anschlüsse" and "Verdrehte
    Blöcke". The share reads `10 %`, with a space, and a block outside a warehouse `Kein Lager` (read on the nixie row
    or the lectern, with an empty label). Try GUI scale 2 and the largest your window allows. **Does any name run out
    of its box, and do they read like German?**
319. **On a dedicated server.** Start `./gradlew runServer`, join with `./gradlew runClient`, set up a link with a
    number and a label, leave and join again: the screen opens on the same number and label, and the display shows it.
    Neither log has an error mentioning `wareworks`.

## BD. Amount in items at the crafting panel ([issue #33](https://github.com/Richie1710/create-wareworks/issues/33))

> The crafting panel's amount field counts the result items the player wants, as a click in the list does, and a Fetch
> is for that many rounded up to whole crafts (ADR-062, amendment). `CraftAmountsTest` pins the rounding, that every
> number the panel shows types back into the same crafts, and that it is a production order's rule. The visual
> scenario `craft` lays Oak Planks in English and German: x4 is one craft, x5 shows "Oak Planks x8" and "Crafts: 2 (4
> each)", fetches two logs, the status row and the result read x8, and a Shift-click makes eight planks. The `jei`
> scenario asks for three fire charges on a JEI-laid recipe and checks that they are one craft. What no test does: read
> the lines as a player, see a multi-output question with your own stock, and judge the German.
>
> Setup: the warehouse of section AR with Stone and Oak Logs in the racks, the terminal's crafting grid folded out.

320. **One craft's worth.** Lay Stone Bricks (four Stone in a square) and set the amount field to **4**. Hover
    **Fetch**: "Fetch the ingredients for Stone Bricks x4", "Crafts: 1 (4 each)", "Stone: 4 to fetch" and "The amount
    field sets how many items". Press it: the status row says "Fetching for Stone Bricks x4", four Stone arrive, the
    result reads x4, and a click on it gives four Stone Bricks.
321. **Rounded up.** Set **5**: the tooltip says "Stone Bricks x8", "Crafts: 2 (4 each)" and "Stone: 8 to fetch".
    Press Fetch: eight Stone arrive, the result reads "x8" and its tooltip "Ready to craft: x8" with "Crafts: 2 (4
    each)" under it, and a Shift-click gives eight Stone Bricks. **Does "x8" for a request of 5 surprise you, or does
    the crafts line make it clear?**
322. **A question in items.** Leave only 8 Stone in the racks and set **20**. Fetch asks "Fetch the ingredients for
    Stone Bricks x8?", "Crafts: 2 (4 each)" and "Only x8 of x20 are available:". Say No. With a planks pattern at a
    production station and a recipe that needs planks, check that "Without making: x…" and "Set x… to fetch only that"
    name items; type that number into the amount field and press Fetch: exactly those crafts are fetched and nothing is
    made.
323. **Nothing changed for one-item recipes, and JEI follows the rule.** Lay a Chest and set **3**: "Fetch the
    ingredients for Chest x3", no crafts line, "Oak Planks: 24 to fetch", exactly as before. With JEI installed, lay
    Oak Planks with its **+** and set **5**: the tooltip says "Oak Planks x8" and "Crafts: 2 (4 each)", and Fetch brings
    two logs.
324. **German (`de_de`).** Repeat 321 in German: "Fertigungen: 2 (je 4)" and "Das Mengenfeld gibt die Stückzahl an".
    **Do both read naturally, and does any line run out of the tooltip at GUI scale 6?**

## BE. The Crafter Feeder ([issue #34](https://github.com/Richie1710/create-wareworks/issues/34))

> A **Warehouse Crafter Feeder** on the back of a production station lays the station's patterns into the **vanilla
> Crafter** in front of it, one order at a time, and gives the Crafter one redstone pulse per run; the Crafter crafts
> and its output goes into a warehouse input (ADR-067). While a feeder is attached, every pattern is checked against a
> real crafting recipe, and a refused one is red in the screen and never ordered. The GameTests `crafterfeedertorch`
> (slots, locks, one rising edge per run, the craft ledger on every tick, the stress impact), `crafterfeederchain` (logs
> and coal to torches through one Crafter), `crafterfeederinvalidpattern` (three refusals, the screen state through its
> codec, nothing checked without a feeder), `crafterfeederinvalidafterdelivery`, `crafterfeedertwoorders`,
> `crafterfeedertwosameorders`, `crafterfeederbigorder` (68 runs laid as 64 + 4), `crafterfeederreplacedbetweenbatches`,
> `crafterfeederremainder` and `crafterfeederremainderblocked` (honey bottles), `crafterfeederneedsrotation`,
> `crafterfeederrotationoptional`, `crafterfeederblockedoutput`, `crafterfeederexternalsignal`,
> `crafterfeedertwostations`, `crafterfeedergriddisturbed`, `crafterfeederbrokenmidorder`,
> `crafterfeedercraftermissing`, `crafterfeederstationbrokenmidorder` and `crafterfeederreload` cover the behaviour;
> `runRobustnessTest` carries a laid batch through a real chunk unload and a real save, quit and rejoin; and the visual
> scenario `feeder` writes the pattern with real clicks, orders at the terminal and shoots the goggles, the red slot and
> the lit Crafter in English and German. What no test does: build it with the block's own placement and a wrench, judge
> the model and the Ponder scene, judge the pace of a large order and a cake, play on a dedicated server, and judge the
> German.
>
> Setup: `./gradlew runClient`, the aisle of sections B–F with a production station at a right-hand rack position and a
> warehouse input at the next one, coal and sticks in the racks, a terminal. From the Wareworks tab a Warehouse Crafter
> Feeder, from vanilla a Crafter, from Create a creative motor (or a shaft from your network), Engineer's Goggles and a
> wrench.

325. **The build.** Place the **Crafter first**, before the input: stand in the empty rack position where the input will
    go (or above it) and look out at the Crafter's spot behind it, so the Crafter's face points into the rack (a placed
    Crafter faces the player, and neither the vanilla game nor a wrench turns it later). Then place the Warehouse Input
    in that rack position. Stand outside the rack wall and place the feeder against the station's back so that it faces
    along the rack towards the Crafter (sneak to reverse it, wrench to turn it). Give the feeder rotation through its
    shaft. **Is it clear from the model which face points at the Crafter and where the shaft goes in?** Sneak-place a
    second feeder against another station's back while looking at that station: it faces away from the station, never
    into it.
326. **A torch.** In the station's screen write coal into the top left cell, a stick below it and 4 torches as the
    result. "Crafter Feeder" stands on the Patterns row and nothing is red. Order 16 torches at the terminal: the crane
    brings 4 coal and 4 sticks, they appear in the Crafter's slots (open the Crafter: the other seven slots are switched
    off), the Crafter crafts four times, 16 torches go through the input into the racks and on to the terminal, and
    the Crafter's slots are all switched on again afterwards. Nothing lies on the floor.
327. **The goggles.** While it works, the feeder's goggles say "Crafting Torch · runs left: 3" (counting down to 1)
    above the kinetic stats; idle they say "Waiting for a delivered order". The station's goggles show "Crafter Feeder
    at the back". Stop the rotation: "Needs rotation" in gold, and nothing is laid.
328. **Red patterns.** Write dirt → diamond in a second slot, two coal in one cell of a torch in a third, and a torch
    that claims 8 in a fourth. Each of those tabs gets a red bar, its grid a red frame and its arrow a red cross;
    hovering tab or arrow says "Not a crafting recipe", "Each cell must hold exactly 1 item" or "The recipe makes Torch
    x4", each with "The Crafter Feeder skips this pattern". Diamonds are not offered at the terminal, the station's
    goggles count "Patterns the feeder skips: 3", and breaking the feeder makes all of them plain patterns again. **Is
    it clear why a slot is red, and what to change?**
329. **Blocked runs.** Stop the crane's motor, fill the input by hand and order torches with the motor running again
    only for the delivery: the batch is laid, the feeder says "The Crafter's output is full" and the Crafter does not
    craft until the crane has room in the input again. Take a coal out of the Crafter while a batch lies in it: "The
    Crafter's grid was changed by hand" and no craft; put it back and it goes on. Put a redstone torch next to the
    Crafter: "Something else powers the Crafter". Put a second station with its back against the feeder: "Touches two
    production stations". Break the station while a batch lies in the Crafter: nothing more is crafted, and once you
    have taken the items out of the Crafter all nine of its slots are switched on again.
330. **A large order and a cake.** Order 400 torches: the feeder lays 64 runs, then the other 36 (the GameTest
    `crafterfeederbigorder` checks the split with 68 runs), and at 32 RPM or more the Crafter crafts about 2.5 times a
    second. Break the feeder and place it again while the 36 wait: it lays only those 36, and the order completes. Write
    the cake (each cell one item: three milk buckets, two sugar, an egg, three wheat) and order one: the cake and the
    three empty buckets come back through the input. **Does the pace feel right next to Create's own machines?**
331. **Save and load.** Leave the world while a batch lies in the Crafter and open it again: the run goes on where it
    stopped, every torch arrives once, and the Crafter gets its slots back at the end. Break the feeder mid-run: the
    Crafter's slots are switched on again and what lies in it stays there.
332. **German (`de_de`).** The block is "Lager-Crafter-Zuführung"; everywhere else the vanilla block is called by its
    German name, "Werker" (the tooltip and the Ponder scene say "Werker (Crafter)" once). Its goggles read
    "Crafter-Zuführung:" and "Fertigt Fackel · verbleibende Durchläufe: 3", the blocked statuses "Kein Werker davor",
    "Die Ausgabe des Werkers ist voll" and "Leere den Werker, damit es losgeht"; the reasons are "Kein Werkbankrezept",
    "Jedes Feld braucht genau 1 Gegenstand", "Das Rezept ergibt Fackel x4" and "Die Zuführung überspringt dieses
    Muster". Hold Shift over the item at GUI scale 6. **Do they read like German, and does any line run out of its
    box?**
333. **On a dedicated server.** Start `./gradlew runServer`, join with `./gradlew runClient` and repeat 326 and 328:
    the red slots and the goggles show the same, and neither log has an error mentioning `wareworks`.

## BF. More pattern slots ([issue #35](https://github.com/Richie1710/create-wareworks/issues/35))

> A production station can hold up to 36 patterns (`maxProductionPatterns` 1–36, default 4, ADR-068). Up to 9 the tab
> strip has 3 columns as before, 10 to 18 get up to 6 columns, and above 18 the strip shows 6 x 3 tabs and scrolls by
> rows with the mouse wheel, with a slim scroll bar that marks hidden red rows. The JUnit test `ProductionMenuLayoutTest`
> checks every count, buffer size and scroll position against the 230 x 240 budget, `ProductionScaleTest` measures the
> planners with 8 stations x 36 patterns, the GameTest `productionthirtysixpatternssurviveareload` saves and loads 36
> full patterns and sends them through the screen's codec, and the visual scenario `patterns` scrolls, clicks and
> resizes the real screen (stations at 9, 12, 18 and 36 patterns, English and German). What is left is what only a
> player can judge.

334. **36 patterns.** Set `maxProductionPatterns = 36` in `run/config/wareworks-server.toml` (or the world's
     `serverconfig`), restart and place a new station. The screen shows 6 x 3 tabs and a slim bar on their right. Turn
     the mouse wheel over the tabs: one row per notch, the bar's light part moves, and it stops at the top and the
     bottom. The wheel over the grid still changes a cell's amount. Fill a pattern in the last row and order it at the
     terminal: it is made like any other.
335. **Selection.** Click a tab in the last row, scroll up until it is hidden: the grid still shows that pattern, and the
     bar shows a gold tick at the bottom where its row is. Close the screen and open it again: the first pattern is
     selected and its row is at the top, with no error in the log.
     Resize the window while a tab in the last row is selected: its row is scrolled into view again.
336. **Small screens.** At GUI scale 4 make the window 1280 x 960 (320 x 240, the smallest scaled screen vanilla
     allows) and then 1920 x 1080 (480 x 270): the window fits, all 6 columns and the bar are inside the frame, and
     nothing overlaps the grid, the arrow or the result.
337. **The other counts.** With 9, 12 and 18 patterns the strip has 3, 4 and 6 columns, no bar, and the wheel over it
     does nothing. A station placed with 4 patterns looks exactly as it did in 0.14.0. With a Crafter Feeder behind a
     36-pattern station, write one pattern that is no recipe into the last row and scroll to the top: the bar shows a
     red tick at the bottom. **By eye:** does the bar read as a scroll bar, and is the red tick seen?
338. **Versions.** A dedicated server with this version refuses a 0.14.0 client and the other way round. With matching
     versions on a dedicated server (`./gradlew runServer` and `./gradlew runClient`), 334 and 335 behave the same and
     neither log has an error mentioning `wareworks`.

## BG. The list table ([issue #36](https://github.com/Richie1710/create-wareworks/issues/36))

> Pressing Fetch on a clipboard now asks with a table: one row per entry with its icon, name, wanted amount and status
> (not possible in red, partly in gold, crafted in blue, available in green, problems first), a grey detail line with
> the split or the planner's reason, and a Missing section at the end. Every entry is measured with the click's own
> planner (ADR-069). The JUnit tests `ListOrderConfirmationTest`, `ListTableLayoutTest` and
> `TerminalListTableTextWidthTest`, the GameTests in `TerminalListTableGameTests` (the owner's lantern case among them)
> and the visual scenario `listtable` (real clicks and wheel, 320 x 240, English and German) check the numbers and the
> layout. What is left is what only a player can judge.

339. **Every status by eye.** Build an aisle with a production station holding the lantern chain (1 iron ingot → 9 iron
     nuggets, 1 coal + 1 stick → 4 torches, 8 iron nuggets + 1 torch → 1 lantern) and put 1 iron ingot, 2 coal, 2
     sticks, 20 glass and 64 cobblestone into the racks. Write a clipboard with Cobblestone 64, Glass 64, Lantern 2,
     Torch 4, Diamond 3 and Iron Nugget 9, put it into the terminal's list slot and press Fetch. The question reads
     "Fetch what there is (89 of 146)?"; Diamond and Iron Nugget are red "Not possible" ("No pattern makes Diamond",
     "Iron Ingot is missing"), Glass and Lantern gold "Partly", Torch blue "Crafted" ("0 from stock, 4 produced"),
     Cobblestone green "Available". **Can you tell the four colours apart at a glance, and is each icon the item's
     own (glass and cobblestone drawn as blocks)?**
340. **Scrolling.** Add entries until the table has more rows than it shows (a slim bar appears at its right). Turn the
     mouse wheel anywhere over the dialog: one row per notch, it stops at the first and the last row, and the question,
     the summary and the Confirm/Cancel buttons do not move. The arrow keys, Page Up/Down, Home and End scroll too;
     Enter still confirms and Escape still cancels.
341. **Tooltips.** An entry with a long name (Polished Blackstone Bricks) is shortened with "…"; hover its icon or
     name: the tooltip shows the full name. Hover the amount or the status of a red or gold row: the status, "x from
     stock, y produced, z missing" and the reason. Scroll to the Missing section and hover a material: "Needed by:"
     and the entries that need it with their amounts. **Is anything cut that you would want to read without hovering?**
342. **German at the floor.** With a German client (`de_de`), GUI scale 4 and a 1280 x 960 window (320 x 240), open the
     same dialog: "Holen, was da ist (… von …)?", "Nicht möglich", "Teilweise", "Hergestellt", "Verfügbar", the
     Missing section "Es fehlen (…)". At least three rows are visible, nothing overlaps, and both buttons can be clicked.
     Resize the window while the table is scrolled: it stays scrolled.
343. **The owner's lantern.** With only the ingot, coal and stick in stock (no nuggets, no torches), a clipboard with
     Lantern 1 shows "Crafted" ("0 from stock, 1 produced"), not "cannot be had". Confirm: the warehouse makes the
     nuggets, the torch and the lantern (play the station's machine by hand or with arms) and delivers it, without a
     second question for the list.
344. **Whole runs.** With only 1 coal and 1 stick in stock (no torches) and the torch pattern (1 coal + 1 stick → 4
     torches), a clipboard with Torch 2 shows "Crafted" ("0 from stock, 2 produced") and the question names the whole
     run: "Fetch all of it (2, 4 made)?" (German "Alles holen (2, 4 gefertigt)?"). Confirm: the list starts without a
     second question, and the two extra torches land in a rack.
345. **Versions.** A dedicated server with this version refuses a 0.15.0 client and the other way round. With matching
     versions on a dedicated server (`./gradlew runServer` and `./gradlew runClient`), 339 and 341 behave the same and
     neither log has an error mentioning `wareworks`.

## BH. The hand truck ([issue #37](https://github.com/Richie1710/create-wareworks/issues/37))

> A held item that carries a chest, trapped chest or barrel with everything in it (ADR-070, `docs/hand-truck.md`).
> The JUnit tests `HandTruckPenaltyTest` and `HandTruckLimitsTest`, the GameTests in `HandTruckGameTests` (round trips,
> refusals, menus, container items, a hopper, tossing, death) and the visual scenario `handtruck` (real clicks, first
> and third person, the walk and the glide measured against an empty-truck control, the drop key, a barrel slot and a
> second truck refused, English and German, Flywheel on and off, 18 shots) check the behaviour. What is left is what
> only a player can judge.

346. **Round trips.** In survival, put a few stacks into a chest, a trapped chest and a barrel. Right-click each with an
     empty Hand Truck: the block disappears without dropping anything and without opening, the truck reads "Hand Truck
     with Chest" (or the container's own name) and its tooltip "N stacks inside". Right-click the floor somewhere else:
     the container stands there facing you and holds exactly what it held. Name a chest in an anvil and lock nothing;
     the name comes back too.
347. **Double chest and loot.** Carry one half of a double chest: the other half stays as a single chest with its own
     items, the carried half comes back with its 27 slots. Find an unopened loot chest (a village or a dungeon), carry
     it ("Unopened loot") and set it down: the loot is rolled only when you open it.
348. **How it looks.** Hold the empty and the loaded truck in first person and in both third person views (F5), with
     Flywheel on and with `/flywheel backend off`: the truck stands upright in front of you with its wheels on the
     ground, the chest (or the barrel, standing upright) sits on its nose plate, and the hotbar icon shows the container
     too. **Does it read as a hand truck with a box on it, and does anything clip through the player in a way that
     bothers you?**
349. **The feel of the penalty.** On a dedicated server (`./gradlew runServer` and `./gradlew runClient`) walk with a
     loaded truck: about half speed, Ctrl and double-tapping W do not sprint, an elytra does not open. Drop the truck
     into a chest (it comes back) and walk again at full speed with the empty truck in the inventory. **Is half speed
     the right amount of punishment?** (`handTruck.speedMultiplier` in `wareworks-server.toml`.)
350. **Nothing escapes.** With a loaded truck try: Q (refused, "Set it down with Right-Click"), dragging it into a chest
     slot, the crafting grid and a shulker box slot, a bundle, an item frame and a donkey's chest. Every time the truck
     stays loaded in your inventory and nothing is duplicated. Throw it out of the inventory screen by dragging it
     outside the window: it comes back. Die with it (`/gamerule keepInventory false`): the contents, the chest and the
     empty truck lie on the floor, with keepInventory true the loaded truck is still in your inventory.
351. **Refusals.** A second empty truck on a chest while one is loaded: "You are already carrying a loaded hand truck".
     Warehouse blocks, an ender chest, a shulker box, a Create item vault and a toolbox: nothing happens beyond their
     own click. In adventure mode or inside spawn protection on a server: "You may not take this here". With a claims
     mod (FTB Chunks, Open Parties and Claims) if you have one: a claimed chest of another player is refused.
352. **JEI and German.** JEI shows the recipe (a shaft over andesite alloy over cogwheel, iron sheet, cogwheel). With a
     German client the item is "Sackkarre", the loaded one "Sackkarre mit Truhe", the messages are German, the Shift
     tooltip fits the screen, and the Ponder scene (hold W over the truck) reads in German.

## BI. Overstressed after a restart ([issue #38](https://github.com/Richie1710/create-wareworks/issues/38))

> A crane or Crafter Feeder that is overstressed has Create recompute its network 40 ticks later and then every 100
> ticks (ADR-071). The JUnit test `OverstressRecheckTest`, the GameTests in `StaleOverstressGameTests` and the visual
> scenarios `restart`, `restart-stale` and `restart-late` (three Large Water Wheels, a speed controller at 128 RPM, the
> wheels' chunk really unloaded, a real save and rejoin) check it. What is left is the owner's real server.

353. **The owner's server.** Update the server to this version and restart it without touching anything. Within a few
     seconds of the warehouse's chunks loading, the crane goggles no longer say "Paused: overstressed", the cranes turn
     and take jobs. The large cogwheel at the speed controller does not need to be broken and placed again. Restart the
     server twice more: the cranes resume each time.
354. **Generators far away.** Walk far enough away that the water wheels' chunks unload while the warehouse stays loaded
     (its own chunk loading or `/forceload`), break and place one shaft on the warehouse side, and come back: the
     goggles may say "overstressed" while the wheels are away, and within about five seconds of the wheels' chunk loading
     the cranes run again.
355. **A real overstress stays.** Add more consumers than the wheels can carry (or set the speed controller higher): the
     network is overstressed, the cranes stay "Paused: overstressed" and the Stressometer agrees. Remove the load: the
     cranes run again within about five seconds.

## BJ. The arm stops at half a block at stations ([issue #39](https://github.com/Richie1710/create-wareworks/issues/39))

> The crane's arm reaches half a block into the warehouse terminal, every warehouse output and the production station,
> and a full block into storage (ADR-072, `docs/stacker-crane.md` §7.1). The JUnit tests `LocationKindStrokeTest`,
> `CraneStateMachineTest` and `CraneModelLayoutTest#theArmStopsInsideEverySolidStationAtItsStroke` and the GameTests
> `CraneJobGameTests#craneDeliversIntoATerminalAtTheStationStroke` and `#craneDeliversIntoAnOutputAtTheStationStroke`
> check the stroke, the hand-over and the timing. What is left is what only a player can judge.

356. **Terminal screen.** Build a terminal with its screen on the face opposite the aisle, request items and watch
     the drop from behind the terminal and from the side: no fork tip on the screen. Turn the screen onto a side face
     and repeat: nothing on the screen or on the plain panels. From the aisle the load sits in the intake slot while it
     is handed over.
357. **Output port.** A warehouse output with nothing in front of its port face: request items and watch the port
     mouth from outside. No grabber shows in it. Repeat with an accepting and a collecting port.
358. **Production station.** Order a production run and watch the station's far side the same way: no grabber in it.
359. **Collecting port.** A collecting port with a chest behind it: the arm goes half a block in, and the items are
     still collected into storage.
360. **Storage still full.** Watch a store into an interface with a chest, into a rack bay and into a fluid bay: the arm
     still reaches the whole block, as before.
361. **Block items in the slot.** Request a stack of planks (or another block item) at a terminal and look at the
     intake slot during the drop: for the whole hand-over (about half a second) the item sits 1.2 px into the top
     edge of the slot; with 32 or more items the second copy sits inside the frame top and shows about 1 px above the
     block. **Is that acceptable?** If not, the stroke can be set to 11/16, which puts the first copy fully inside the
     core.
362. **Save while the arm is out.** Save and quit while the arm is out at a terminal (a large request), then rejoin:
     the arm settles at half a block and the delivery completes with nothing lost or duplicated.

## BL. Computers and Electro Energetics ([issue #9](https://github.com/Richie1710/create-wareworks/issues/9))

> With CC: Tweaked installed the warehouse terminal is a
> peripheral of type `wareworks_terminal` (ADR-074, `docs/computercraft.md`); Create: Electro Energetics' Electric Motor
> drives the crane like any Create motor. The GameTests `ComputerAccessGameTests` (every run),
> `ComputerCraftPeripheralGameTests` (`-Pwareworks.cc`) and `CeeMotorGameTests` (`-Pwareworks.cee`) check the logic,
> the real peripheral called from Java and the motor at 230 V and 70 V. What is left is real Lua, real wiring and a
> real server. Setup: CC: Tweaked 1.116.1 or newer and Create: Electro Energetics installed (`./gradlew runClient
> -Pwareworks.cc -Pwareworks.cee` in a dev checkout), a stocked warehouse with a terminal.

363. **Find it.** Place an advanced computer next to the terminal and run `lua`, then
     `t = peripheral.find("wareworks_terminal")`: `t` is a table. `peripheral.getType(t)` is `wareworks_terminal`,
     and `peripheral.getNames()` lists it on the side it touches. `t.getStatus()` shows `connected = true`,
     `status = "ready"`, the aisle letter and the same item types and totals as the terminal's goggles.
364. **Read the stock.** `t.getStock()` returns the rows in the screen's order; the counts match the screen. With
     `t.getStock({ limit = 2 })` there is a second value, the start of the next page; `t.getStock({ start = 3,
     limit = 2 })` continues there. `t.getStock({ name = "minecraft:oak_log", detailed = true })[1].details` holds
     CC's item details. `t.getItem("minecraft:dirt")` is `nil` when the warehouse has none.
365. **Request.** `ok, r = t.request("minecraft:iron_ingot", 16)` gives `ok = true` and a table `r` with an `id`.
     The crane fetches the ingots into the terminal's buffer; `t.getRequests()` lists the request while it is open,
     and `t.getRequest(r.id)` returns `nil` once it is delivered. Nothing appears in the buffer that the crane did not carry.
366. **Refusals and errors.** `t.request("minecraft:dirt", 1)` with no dirt in stock returns `false, "not_in_stock"`.
     `t.request("minecraft:iron_ingot", 0)`, `t.request("minecraft:iron_ingot", 2.5)` and `t.request("nothing:here", 1)`
     raise errors with readable messages.
     Set a stock keeper's reserve on an item and request more than lies above it: the computer gets only what lies
     above the reserve (or `false, "reserved"`), while a click on the screen can still take it. Run
     `for i = 1, 30 do print(t.request("minecraft:iron_ingot", 1024)) end` with several item types: it ends in
     `request_full`, `output_full` or `queue_full`, never in more items than the caps allow.
367. **Merged with a player.** Click an item in the screen, then request the same item from the computer before the
     crane delivers: the computer's answer has `merged = true` and the screen shows one request with both amounts.
368. **Package door.** At a terminal with a package door (warehouse port with a Packager and a sign), `t.getDoors()`
     lists it with its label, rack address and state. `t.request("minecraft:iron_ingot", 8, { x = ..., y = ..., z =
     ... })` with that door's position sends the items through the door as a screen request to the door would; a
     position that is no door returns `false, "not_a_door"`.
369. **Wired modem.** Connect a second terminal far away to the computer with wired modems and cable; it appears as
     `wareworks_terminal_0` (or similar). Requests through it deliver at that terminal.
370. **Config off.** Set `computers.computerAccess = false` in the server config: every method raises
     `computer access is disabled on this server`; the terminal's screen still works. Set it back: the computer
     works again without replacing anything.
371. **Terminal gone.** Break the terminal while a program holds `t`: the next call raises an error (`terminal is gone`
     or CC's own error for a detached peripheral), and nothing else breaks.
372. **Dedicated server.** Run a dedicated server with CC: Tweaked and join it with a player: checks 363 and 365 work
     the same, and the server log shows no error naming wareworks or computercraft.
373. **Without CC: Tweaked.** Remove CC: Tweaked: the game starts, the world loads, the terminal works as before and
     its tooltip and screen are unchanged.
374. **CEE motor drives the crane.** Place a white Electric Motor facing up under the dock, set it to 128 RPM, and wire
     it to a Creative Battery set to 230 V with the Creative Wire Spool. The crane runs, stores items from an input
     and delivers a request. The Stressometer on the network shows a capacity of about V² / 30 SU (about 1763 SU at
     230 V).
375. **Below 80 V it stops.** Lower the battery to 70 V: the motor stops, the crane's goggles say it has no rotation
     and it starts no job; no item is lost. Raise it to 230 V again: the crane carries on by itself.
376. **Voltage sag overstresses.** At 128 RPM lower the battery to 100 V: the motor turns but the network is
     overstressed (about 333 SU for 512 SU of crane), and the crane waits "overstressed". Raise the voltage above
     about 124 V: the crane runs again within about five seconds.

## BK. Asking the warehouse for fluid ([issue #21](https://github.com/Richie1710/create-wareworks/issues/21), second half)

> The terminal's fluid rows, the question before a fluid request, the fill trip (empty containers from stock, filled
> at a fluid bay, carried straight on to the station that asked), the red row with no empty containers, ports with a
> filled container in their filter and fluid stock rules (ADR-073, `docs/warehouse-system.md` §3.9.6). The JUnit tests
> `ContainerPlanTest`, `FillLedgerTest`, `KeyMemoTest`, `JobPlannerFillTest` and `FluidQuestionTextWidthTest`, the
> GameTests in `FluidRequestGameTests`, `TerminalFluidRequestGameTests` and `FluidRuleGameTests`, the visual scenario
> `fluidrequest` (English and German) and the two fill phases of `runRobustnessTest` check the trip, the numbers, the
> texts' width and conservation of items and fluid. What is left is what only a player can judge.

377. **Fluid row look.** A warehouse with a lava bay and a water bay and some items: open the terminal. The two fluid
     cells stand above the items, show the fluid's own texture and the amount in buckets, and read well in the light
     and the dark GUI theme (a resource pack with a dark container texture, if you use one). Search for "lava": only
     the lava cell and lava items stay.
378. **Tooltip.** Hover a fluid cell: stored and available amounts in millibuckets and buckets, the container it fills
     ("Fills: Bucket (1000 mB)") and the amount hint. Clear and in the right order?
379. **The question.** Click the lava cell with 10 in the amount field: the question names 10 × Bucket, 10 crane trips
     (1 at a time) and, since the terminal holds 9, that the crane waits while it is full. Switch the game to German and
     at GUI scale 4 on a 1280 × 960 window (320 × 240) ask again: every line fits and reads naturally.
380. **One bucket per trip.** Ask for 16 buckets and watch the whole order: the crane takes an empty bucket from a
     shelf, fills it at the bay and carries it straight to the terminal, 16 times. Does the pace feel acceptable for 16
     buckets, given that the question said so and a pipe is the bulk path?
381. **A full terminal.** Leave the 16 buckets in the terminal until it is full: the crane rests and does not shuttle
     back and forth. Does that read as waiting rather than as a hang (goggles on the crane, the order line in the
     terminal)? Take buckets out: the rest arrives.
382. **Partial service.** With 3 empty buckets in stock ask for 5: the question offers 3 and says why. With 2 500 mB in
     the bay and plenty of buckets, Ctrl-click: it asks about 2 buckets (2 000 mB), and the 500 mB stay in the bay.
     Is it clear from the question why?
383. **The red row.** Take every empty bucket out of the warehouse: the lava cell turns red, and the tooltip says "No
     empty containers in stock" with the pipe hint. Is the hint understood?
384. **The make offer.** Give a production station a pattern for buckets (3 iron ingots → 1 bucket, your own machine
     behind it) and stock iron: the tooltip says "Click to have them made first (Bucket)", the question offers to make
     them, "Make and fetch" makes them and the lava arrives afterwards. Cancel one fill line in the order panel before
     it is served: nothing is lost. With four orders already waiting at the terminal, "Make and fetch" is refused and
     no iron leaves the racks.
385. **Port with a lava bucket filter.** A requesting warehouse port with a lava bucket in its filter and a redstone
     pulse: with lava buckets on a shelf it takes those; with none it is filled from the bay. Its goggles say "(filled
     from the fluid bays)", and a display board with "Stock of the Filtered Item" on the port shows the lava in buckets.
386. **Fluid rule toggle.** In a stock keeper, set a row to a lava bucket: it counts fluid ("B"). Click the switch: it
     counts items ("x") and the numbers convert. Set a fluid maximum below what the bay holds: the crane stores a
     lava bucket from the input as an item instead. Set a reserve: the port above is refused while the terminal is not.
     A reserve on empty buckets that covers all of them refuses the port too, and its goggles say the stock is
     reserved, not that no empty containers are in stock.
387. **Dedicated server.** Repeat 379, 380 and 385 on a dedicated server with a second player watching: both see the
     same fluid rows and lines, and nothing in either log mentions a wareworks exception.
388. **The Ponder scene.** Hold **W** over a copper fluid bay, a brass fluid bay and a Warehouse Terminal and play
     "Asking the Warehouse for Fluid" in English and in German. Does every caption stay up long enough to read, and
     does each one point at the right block (the terminal, the rack bay with the empty buckets, the fluid bay, the pipe
     at the bay's back)?

## BM. Several cranes on one warehouse ([issue #14](https://github.com/Richie1710/create-wareworks/issues/14))

> A dock without a controller of its own that faces into a warehouse's rails is another crane of it (ADR-075,
> `docs/warehouse-system.md` §1.2, §7.8, §8.2, `docs/stacker-crane.md` §4.8). The JUnit tests of `core.traffic` check the
> rules and the deadlock proof on every reachable state of small warehouses, `MultiCraneGameTests` checks them in a world,
> and the `cranes` visual scenario and the "two cranes in flight" phase of `robustness` check the shots, the goggle lines
> in both languages and a real save and chunk round trip. What is left is what only a player can judge.

389. **Two cranes on one line.** Build a straight aisle with the controller at one end and a second dock at the other,
     facing back, each dock with its own rotation. Goggles on the controller: "Cranes: 2 (2 idle)" and one line per
     crane. On the second dock: "Crane 2 of 2". Order something stored near each end: both cranes move at once and never
     share a rail.
390. **The maximum.** Put a fifth dock on a warehouse that has four cranes (default `aisle.maxCranesPerNetwork = 4`): it
     is refused, the controller's stop line names the block and says the warehouse has as many stacker cranes as the
     server allows, and the refused dock's goggles say why. Put it nearer the controller than crane 4: it is still the
     one refused, and crane 4 keeps working.
391. **The off switch.** Set `aisle.maxCranesPerNetwork = 1` and restart: the second dock is a wall again (the stop line
     says "another stacker crane dock stands here"), the warehouse works exactly as 0.17, and its dock and controller save nothing
     new. Set it back.
392. **A dock with its own controller.** Put a controller behind the second dock: two separate warehouses as before; the
     second dock's goggles say it faces the other warehouse but has a controller of its own.
393. **A comb.** Docks at both ends of a main run with side aisles: order things whose stops cross. One crane waits in a
     side aisle while the other passes. Note how long the slowest order took; nothing waits for ever.
394. **Moving aside.** Place a home point for crane 2 in the middle of the main run, and order something crane 1 must
     fetch beyond it: crane 2 moves aside — to its dock, because its home point is in the way — and drives back to its
     home point about ten seconds after crane 1 is gone.
395. **Why a crane waits.** Look at a waiting crane's dock through goggles: the line names the other crane and the rail it
     holds, "About N s left, not counting the wait" stands under the job, and "Reserved: … to …" names the stretch.
     Read it in German as well.
396. **A crane without rotation.** Stop one crane's motor while the other works: the other keeps working; a job that
     needs the stopped crane's rails waits with "Waiting for crane 2: it is stopped". Motor on: everything finishes.
397. **A rail closed between them.** Close a rail between the two cranes with a wrench while the far one carries: it is
     "Cut off from its dock", finishes on the warehouse's side, and rejoins when the rail is open again. Nothing is lost.
398. **Save while both carry.** Save and quit while both cranes carry; rejoin: both finish, no item is missing or
     duplicated.
399. **Break a working second dock.** Its items drop at the dock; the first crane keeps going; the controller lists one
     crane again.
400. **The terminal and the displays.** The terminal's crane line reads "2 cranes: …". A Display Link on the controller
     offers Crane Status and Crane Throughput after its four sources (Warehouse Summary is still preselected): one row
     per crane, and the whole warehouse's throughput. On a dock, Crane Status reads "Retrieving, waiting for crane 2"
     while it waits.
401. **Ponder.** "Two Cranes, One Warehouse" on the stacker crane and on the warehouse rail, in English and German: the
     second dock joins, both work at once, crane 1 waits for the rails crane 2 stands on, crane 2 moves aside to its
     dock, and crane 2 drives past crane 1 standing in a side aisle. Does the scene read well, and are the captions
     readable at the scene's pace?
402. **Two docks at the same end.** A second dock beside the first position of the aisle, facing into it: it joins as a
     stub aisle, but the two cranes can never work the main run at the same time. Is that understandable from the
     goggles, or does it need the hint the design mentioned ("put docks at opposite ends")?
403. **The tooltips.** Shift on the stacker crane and on the warehouse home point (both reworded) in English and German, as in
     item 3: everything fits.
404. **Breaking the controller of a working warehouse.** Break the controller while crane 2 carries: crane 1, parked on its
     dock, is a crane by itself; crane 2 stops where it stands with its items and its goggles say the controller is
     missing. Place the controller again: crane 2 drives on and nothing is lost.
405. **Lowering the cap.** With three cranes, change `aisle.maxCranesPerNetwork` to 2 in the server config while crane 3
     carries: it delivers, drives home to its dock and only then leaves; until then the controller still
     lists three cranes and crane 3 takes no new job. Set it back.
