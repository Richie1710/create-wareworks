# Create: Wareworks

> Mechanical Storage & Intralogistics for Create

Create: Wareworks is an addon for [Create](https://github.com/Creators-of-Create/Create) that adds physical, automated
high-bay warehouses. Racks of chests, barrels and vaults line an aisle, and a kinetic **stacker crane** travels down it
to store and retrieve items. Nothing is teleported: every item you put in or take out is carried by the crane, where you
can watch it happen.

**The machine is the feature.**

## Screenshots

| | |
|---|---|
| ![A stacker crane on its rail, arm extended into a chest](docs/screenshots/stacker-crane.png) | ![A warehouse aisle with dock, rails, controller, stations and 42 storage locations](docs/screenshots/warehouse-aisle.png) |
| The crane: chassis on bogies, a braced mast with brass flanges, lift carriage, telescopic arm and grabber reaching into a chest. | A whole aisle: crane dock, rail line, controller, input and output station, and three levels of storage locations on both sides. |
| ![The crane travelling down the aisle with items on its arm](docs/screenshots/crane-carrying-items.png) | ![The arm extended into a rack at the second level](docs/screenshots/crane-arm-in-rack.png) |
| Travelling to a storage location with items on the arm; the drive cog turns with the kinetic network. | Looking down the aisle: the arm reaches through the port of a storage location. |

![Eight blocks: stacker crane, warehouse rail, controller, interface, input, output, terminal and production station](docs/screenshots/blocks.png)

| | |
|---|---|
| ![The warehouse terminal from the screen side](docs/screenshots/warehouse-terminal.png) | ![The same terminal from the aisle side, showing the crane's arm port](docs/screenshots/warehouse-terminal-aisle.png) |
| The warehouse terminal from the player's side: a recessed display and a take-out tray where requested items come out. | The same block from the aisle, where the crane reaches in through the arm port. |

![The showcase warehouse seen from the world spawn](docs/screenshots/showcase-world.png)

| | |
|---|---|
| ![Looking back down the aisle between two rack walls towards the crane](docs/screenshots/showcase-aisle.png) | ![The warehouse output with its lever, the hopper below it and the chest it fills](docs/screenshots/showcase-output.png) |
| Storage locations on both sides across three levels, the rail line down the middle and the crane at the dock. | The output side: the brass output with its filter item, the lever that requests it, and a hopper draining into a chest. |

## Features

* **Stacker Crane** (dock block): a kinetic machine on a rail aisle. Its crane (base with bogies, braced mast, lift
  carriage, telescopic arm, grabber, drive cog) is fully animated; travel, lift and arm speed follow the RPM. Create-like
  sounds for travel, rail joints, lift, arm, picking and dropping.
* **Warehouse Rail**: lays out the aisle; its length is the straight rail line in front of the dock.
* **Warehouse Controller**: sits behind the dock, gives the aisle its letter, finds storage locations and stations, keeps
  a stock index and plans store and retrieve jobs. It never moves items itself.
* **Warehouse Interface**: turns any inventory with an item capability (chests, barrels, Create vaults, modded storage)
  into an addressable storage location such as `A-03-07R`. Its filter slot decides what may be stored there.
* **Warehouse Input**: the andesite hand-over point for belts, funnels, chutes and hoppers feeding the warehouse.
* **Warehouse Output**: the brass hand-over point that requests the item in its filter slot on a redstone pulse.
* **Warehouse Terminal**: a searchable screen showing the aisle's whole stock; click an item and the crane delivers it
  into the terminal.
* **Warehouse Production Station**: holds production patterns; the crane delivers ingredients to it for your own Create
  machines and the product comes back into storage. Wareworks never crafts anything itself.

Also included: goggle information on every block, Create-style item descriptions, Ponder scenes, English and German
translations, and recipes at mid-game Create tier.

Items only ever move in the crane's grabber, and nothing is lost or duplicated when blocks break, chunks unload or the
server restarts.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.250 or newer |
| Create | 6.0.10 |

## Installation

Download from CurseForge (coming soon) and put the jar into your `mods` folder next to Create.

## Quick start: build your first warehouse

The build *is* the configuration; there is no setup screen.

1. **Place the Stacker Crane dock** looking in the direction the aisle should run.
2. **Power it from below.** The dock takes rotational force only through a shaft underneath (4 SU per RPM by default).
   Higher RPM means a faster crane.
3. **Lay Warehouse Rails** in a straight line in front of the dock. Their number is the aisle length (up to 32 by
   default). Scroll the **Mast Height** value box on the dock for the number of levels.
4. **Put the Warehouse Controller directly behind the dock**, facing it. Its **Aisle** value box sets the letter (A–Z)
   that every address in this aisle starts with.
5. **Build the racks.** Put chests, barrels or vaults beside the aisle and a **Warehouse Interface** in front of each,
   brass port towards the inventory, plate towards the aisle. Clicking the side of an interface you already placed
   copies its facing, so a rack row goes up quickly.
6. **Place a Warehouse Input** at a rack position with its opening towards the aisle and feed it with a belt, funnel,
   chute or hopper. The crane stores whatever arrives.
7. **Place a Warehouse Output** the same way. Put the item you want into its filter slot, hold right-click to set the
   amount, and give it a **redstone pulse**: the crane fetches the items and drops them into the output, where a funnel
   or chute can pull them out.
8. **Put on Engineer's Goggles** and look at any block: addresses, stock, reservations, the crane's job and the
   controller's planning result are all shown.

Hold **W** over the crane, rail, controller, interface, input or output item for a Ponder scene that shows the same
steps.

## Requesting items

**Warehouse Terminal.** Place it at a rack position while standing where you want to read it: the screen faces you, and
the controller turns the crane's intake port towards the aisle by itself. The wrench turns the screen to another side,
never onto the port. Right-click with an empty hand to open it.

* Type to search; `@create` matches a mod id.
* **Click** to request the amount in the scroll input, **shift-click** for a stack, **ctrl-click** for everything
  available.
* Repeated clicks on the same item are merged into one request and one crane trip.
* Delivered items land in the terminal's own slots. Take them by hand, or let a funnel or chute pull them onward.

**Warehouse Output.** No screen: set the filter slot and amount, then send a redstone pulse. This is the automatable
path for your factory.

## Storage filters

Right-click the filter slot on the lower half of a Warehouse Interface's aisle face with an item, and that storage
location only accepts that item. List, Attribute and Package Filters work exactly as elsewhere in Create.

* **An empty slot accepts everything.**
* **Dedicated locations fill first**, before unfiltered ones. A deny-list filter only excludes items, so such a location
  is treated like an unfiltered one.
* **Filters restrict storing only.** Retrieval is never blocked, and changing a filter never moves or deletes stored
  items.
* **Items that match no filter need an unfiltered location.** Without one, the input keeps its items and the
  controller's goggles say that no filter accepts them.

![A rack row of four storage locations, three of them showing their filter item](docs/screenshots/storage-filters.png)

## Production

A **Warehouse Production Station** holds patterns: a 3 × 3 grid of ingredients plus one result. Once a pattern is set,
the result can be ordered at the terminal even at zero stock. The crane delivers the ingredients to the station, your
machine does the work, and the product comes back through an ordinary warehouse input. The terminal shows each order's
state ("waiting for ingredients", "at the machine", "waiting for the result", "complete") and lets you cancel it.
Ingredients that already went into a machine are not recovered.

| | |
|---|---|
| ![The sawmill loop](docs/screenshots/showcase-sawmill.png) | ![The mechanical crafter loop](docs/screenshots/showcase-crafters.png) |
| **Sawmill:** station → hopper → belt → Mechanical Saw → warehouse input. Pattern: 1 andesite alloy → 6 shafts. | **Mechanical crafters:** station → belt → three filtered brass funnels → hoppers → Mechanical Crafters → warehouse input. |

Practical notes:

* **Mechanical crafters need filtered feeds and hopper buffers.** A crafter slot holds exactly one item, so each funnel
  must be filtered to one ingredient. A crafter also accepts nothing while its group is working, so put a hopper
  between each funnel and its crafter to hold the next ingredient.
* **Give a Mechanical Saw a recipe filter.** Saws also run stonecutting recipes and cycle through every matching
  recipe, so an unfiltered saw makes the wrong item most of the time.
* **Wareworks does not check patterns against recipes.** A pattern whose machine cannot make the result never produces
  anything, and the order times out.

## Building from source

Requires a JDK 21 (`JAVA_HOME` must point to it).

```bash
./gradlew build              # compile, run unit tests, build the jar into build/libs
./gradlew runClient          # development client
./gradlew runServer          # development dedicated server (run/server)
./gradlew runGameTestServer  # in-world GameTests; exit code = number of failures
./gradlew runData            # data generation into src/generated/resources
./gradlew runVisualTest      # client screenshots into run/visual/screenshots
./gradlew runRobustnessTest  # chunk unload, save and rejoin, blocks broken mid-job
./gradlew runShowcase        # builds a playable showcase world into run/showcase/saves
```

On Windows use `gradlew.bat`. To play the showcase world, copy `run/showcase/saves/wareworks_showcase` into `run/saves`,
start `./gradlew runClient` and pick **"Wareworks Showcase"**.

## Documentation

* [Architecture](docs/architecture.md): layers, package tree, design decisions
* [Warehouse system](docs/warehouse-system.md): interface, controller, addressing, stock index, jobs, reservations, config
* [Stacker crane](docs/stacker-crane.md): block, state machine, kinetics, rendering, sounds
* [Dependencies](docs/dependencies.md): versions, Create APIs in use, known harmless log warnings
* [Roadmap](docs/roadmap.md): milestones and planned features
* [Manual test checklist](docs/manual-test-checklist.md): play-test checks

## License

All rights reserved.

Create, Ponder, Flywheel and Registrate belong to their respective authors; this addon depends on them and ships none of
their code.
