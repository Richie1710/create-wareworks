# Manual test checklist

Checks for a manual play test, ordered the way a player builds a warehouse.

Setup: a creative world, Create 6.0.10 installed, **Engineer's Goggles** in a slot, a **Wrench** in hand.
Where a German client is mentioned, restart with `de_de` to check the translation.

---

## A. First launch

1. The creative tab **"Create: Wareworks"** appears after Create's palettes tab, its icon is the stacker crane, and it
   lists exactly **ten** items in building order: stacker crane, warehouse rail, warehouse controller, warehouse
   interface, warehouse input, warehouse output, warehouse terminal, warehouse production station, warehouse stock
   keeper, warehouse home point. (`WareworksItemGameTests#creativeTabOrderAndIcon` pins that list; read it from there
   if the two ever disagree.)
2. Every item icon shows its own model, no missing-texture checkerboard: the crane icon shows the rail bed with a
   miniature crane, the controller icon its display, the rail icon is readable (not a thin line at the slot's bottom).
3. Holding Shift on each of the **ten** items shows a Create-style description; the stacker crane additionally shows its
   stress impact.
4. JEI/EMI shows **ten** recipes; the stacker crane needs a 3 × 4 mechanical crafter grid (either mirror image) and
   shows a **brass hand** beside its precision mechanism, rails come out **4** at a time and include a **shaft**, the
   terminal takes a **precision mechanism** (not a funnel) like the controller, the output takes two **brass
   nuggets** the input does not, and the home point stays at the andesite tier (a rose quartz lamp over an
   andesite casing between two andesite alloy, with no electron tube and no precision mechanism). In the **vanilla recipe book** the other **nine** appear once you hold one of
   their ingredients — the terminal shows up with a precision mechanism and the output with a brass nugget — while the
   stacker crane never does, because mechanical crafting has no recipe-book category (Create's own mechanical crafting
   recipes behave the same).
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
    click opens a board for the location's **storage priority**, not for an amount (a storage filter has no amount);
    that board is check 111.
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

The same slot carries a second setting since M16 — the location's **storage priority**, set by *holding* the click. It has
its own checks in **section S**, because it needs a rack wall and a longer look at the crane; do them together with these
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
    view. The crane's mast crossing the tower while it delivers is expected; it must not hide the crafter.
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
59. **Right-click it with an empty hand**: the screen opens. With a wrench in hand it rotates instead, and other items do
    their own thing. Check the window at GUI scale 2, 3 and 4 in a 1280 × 720 window — it must never be cut off.
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

> The feature in one sentence: **Wareworks never crafts.** It carries the ingredients to your machine and collects the
> product again. Everything below assumes a working aisle with a terminal, an input and an output.

71. The production station is the **eighth** item of the creative tab, after the terminal. Its icon shows the block, and
    Shift shows a Create-style description (check the German client too). JEI/EMI shows its recipe (three brass funnels,
    a brass casing and an andesite alloy), and it appears in the vanilla recipe book once you hold a brass funnel.
72. Place it in a rack beside the aisle, standing in the aisle: the **opening faces the aisle**, like an input or an
    output, and the goggles show an address. From the aisle it is clearly a different block from the input (andesite),
    the output (brass) and the terminal (screen) — it is the **copper-bodied** one with brass frames.
73. **Right-click it with an empty hand**: the pattern screen opens. With a wrench in hand it rotates instead. Check the
    window at GUI scale 2, 3 and 4 in a 1280 × 720 window — it must never be cut off.
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
    selection and the terminal stays; the left-click is only tried on the terminal. Right-clicks on the interface,
    controller, dock and rail select nothing, show no message and place an arm against the block. With the arm item on
    the filter slot for 25 ticks, Create's outliner holds no value box for the slot and its hint overlay shows nothing;
    with an empty hand on the same spot both appear, so the check can see them (shots `select-output-filter-slot`,
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
    it with an **empty hand** — the rule screen opens; right-click it with an item in hand and nothing opens, and the
    wrench still rotates it. Click a row's cell with an item on the cursor: the row takes the item and **nothing is
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
    an interface's aisle face. A **short** right-click still sets or clears the filter, exactly as before. **Hold** the
    click for about a quarter second: Create's value settings board opens with one row, "Priority", and a scale from 0
    to 9. Set 5, release, and check that the number stuck. Do this for a location **above head height** and one at your
    feet — the judgement is whether you can aim at a 6 px plate on a rack wall without crouching around, and whether the
    board opens reliably rather than sometimes only setting the filter.

    Watch for the one change in feel this brought: since the slot accepts value settings, a filter click fires on
    **release** instead of the instant you press. Note it if it feels laggy or if a quick click ever fails to set a
    filter.

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
    third line must read "Hold to set the priority". Goggles on the interface read "Priority: 5"; goggles on the
    **controller** read "Prioritised locations: N" under the storage count, and **nothing** while no location is
    prioritised.

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
    one's goggles must say "Without effect: another interface counts this inventory" in gold **even when it carries only
    a priority and no filter**, and the controller's "Prioritised locations" must not count it.

115. **German (`de_de`).** Switch the language and walk the same surfaces: the board's title ("Lagerpriorität") and its
    row ("Priorität"), the hover tip ("Halten, um die Priorität zu setzen"), the interface's goggle line
    ("Priorität: 5"), the controller's "Priorisierte Lagerplätze: N", and the interface's Shift tooltip, which now has
    a **fourth** section, "Beim Einstellen der Priorität". Nothing may show a raw key, run out of its box or be cut off
    — German is the longer language and the screenshot runs only ever render English, so this is the only place
    clipping shows up. The longest line to watch is the new tooltip paragraph.

    **Automated:** `LangConsistencyTest` proves German has exactly the generated keys with the same placeholders —
    nothing about how they look.
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
    now has a **fifth** section, "Beim Einstellen der Richtung" — which must not read like the terminal's rotation
    section, because it is about holding the click on a box. Nothing may show a raw key, run out of its box
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
    production station (now six sections, the longest in the mod) and of the terminal, and the new Ponder scene
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
    entries it has plenty of, one it has part of, one it has none of — and press the list button. The panel must say,
    in this order: **"Fetch this list?"**, how many of the list's items are **not in stock**, how many would be
    **produced** (only if any would), how many entries cannot be had at all and stay unticked, then up to five named
    entries as "what there is of what it asked for", then "More entries: N" if there are, and finally
    **"Fetch what there is?"**. (A clipboard longer than `maxTerminalListEntries` adds "Entries not taken from the
    clipboard: N" in gold, before the named entries.) Then:
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
