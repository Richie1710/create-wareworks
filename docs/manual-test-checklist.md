# Manual test checklist

Checks for a manual play test, ordered the way a player builds a warehouse.

Setup: a creative world, Create 6.0.10 installed, **Engineer's Goggles** in a slot, a **Wrench** in hand.
Where a German client is mentioned, restart with `de_de` to check the translation.

---

## A. First launch

1. The creative tab **"Create: Wareworks"** appears after Create's palettes tab, its icon is the stacker crane, and it
   lists exactly eight items in building order: stacker crane, warehouse rail, warehouse controller, warehouse
   interface, warehouse input, warehouse output, warehouse terminal, warehouse production station.
2. Every item icon shows its own model, no missing-texture checkerboard: the crane icon shows the rail bed with a
   miniature crane, the controller icon its display, the rail icon is readable (not a thin line at the slot's bottom).
3. Holding Shift on each of the eight items shows a Create-style description; the stacker crane additionally shows its
   stress impact.
4. JEI/EMI shows eight recipes; the stacker crane needs a 3 × 4 mechanical crafter grid (either mirror image) and
   shows a **brass hand** beside its precision mechanism, rails come out **4** at a time and include a **shaft**, the
   terminal takes a **precision mechanism** (not a funnel) like the controller, and the output takes two **brass
   nuggets** the input does not. In the **vanilla recipe book** six of them appear (controller, rail, interface, input,
   output, terminal) once you hold one of their ingredients — the terminal shows up with a precision mechanism and the
   output with a brass nugget — while the stacker crane never does, because mechanical crafting has no recipe-book
   category (Create's own mechanical crafting recipes behave the same).
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
    accepts the input items" and the **input keeps** its items.
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

42. Hover any of the **nine** items and hold **W**: a scene opens. Crane and rail show "Moving Items with a Stacker
    Crane"; the controller, the **warehouse terminal** and the **stock keeper** have two scenes each, the **interface
    has three** and the **production station** one (arrow keys or scroll switch them).
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
    text about the terminal's own slots is on screen while the crane actually drops them.
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

57. The creative tab ends with the **Warehouse Terminal** after the output; its item icon shows the **screen side** — a
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

93. Open the Display Link screen on each Wareworks block. The **controller** and the **terminal** offer **"Aisle
    Summary"** and **"Stock List"**, in that order and with "Aisle Summary" preselected; the **output** and the
    **interface** offer **"Stock of the Filtered Item"**; the **crane dock** offers **"Crane Status"**. On the
    **rail**, the **input** and the **production station** the link offers nothing at all. Restart the game once and
    open the controller's screen again: the two entries are still in the same order. On a **German client** the four
    read "Gangübersicht", "Bestandsliste", "Bestand des gefilterten Gegenstands" and "Status des Regalbediengeräts",
    and a board on the controller reads "Gang A: Bereit", "Plätze: …", "Gegenstandsarten: …", "Gegenstände: …" — with
    the crane broken, "Gang A: Kein Regalbediengerät" (the mod uses no short form for that block).

    **Automated** (GameTest `displaysourcesregistered`): the four ids resolve in Create's registry, each name uses the
    generated lang key, and `DisplaySource.getAll` returns exactly the expected list, in the expected order, for all
    eight blocks. The screen itself is never opened by a test. **By eye:** that the screen really lists them that way
    and that nothing is cut off in German, plus the preselection after a restart.
94. Point a link on the **controller** at a display board (or a row of nixie tubes) with "Aisle Summary". Within about
    five seconds it reads `Aisle A: Ready`, `Locations: 3 / 30`, `Item types: 3`, `Items: 176`. Put on goggles and look
    at the controller: the same numbers. Store something and watch the board pick the change up on its own. Break the
    crane dock: the board falls back to the single line `Aisle A: No crane`.

    **Automated** (`display`): all four lines compared string by string against the controller's letter, status,
    `occupiedLocations`/`countedStorageLocationCount`, `distinctKeys` and `totalItems`, at rest and again after the
    aisle grew (176 → 312 → 400 items). The `No crane` and `No aisle` cases are GameTests (`aislesummarydegraded`,
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
    server, and read both with a **German client**: the lectern is in German, the sign is in English (`Aisle A: Ready`
    …), because a sign stores plain text and the server flattens the line in its own language — Create's own sources
    behave the same, and a board, a nixie row or a lectern is the target to use. Then point a link on a terminal that
    belongs to **no aisle** at a board: it reads `No aisle`.

    **Automated** (GameTest `displaylinkonsign`, and the small board of the `display` scenario): the sign's four lines
    are the flattened components cut to the sign's line width, line 0 being the English `Aisle A: Ready`, and the
    stray terminal's board reads exactly the `No aisle` line. **By eye:** that a German client really sees the sign in
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
