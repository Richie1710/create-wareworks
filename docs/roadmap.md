# Roadmap

## Milestones

| # | Milestone | Scope | Status |
|---|---|---|---|
| **M0** | Project setup | NeoForge 1.21.1 + Create 6.0.10 build, client and server launch, documentation skeleton | Done |
| **M1** | Foundation + warehouse interface | Registries, server config, creative tab, datagen, inventory snapshot and capacity math, warehouse interface block, GameTest setup | Done |
| **M2** | Aisle | Crane dock, rails, controller, input and output stations, aisle geometry, stock index, membership and persistence | Done |
| **M3** | Crane logic | Jobs, reservations, job planner, crane state machine and motion, controller dispatch, requests and rerouting | Done |
| **M4** | Rendering, sounds, recipes | Animated crane renderer with partial models, held items, block and item models, sounds, item descriptions, recipes, translations | Done |
| **M5** | Robustness, Ponder, polish | Edge cases (blocks broken mid-job, full or removed targets, save and reload, no power), Ponder scenes, visual polish | Done |
| **M6** | Warehouse terminal + showcase world | Terminal block with a searchable stock screen and requests, playable showcase world (`runShowcase`) | Done |
| **M7** | Request batching | Repeated requests for one item at one station merge into a single crane trip | Done |
| **M8** | Storage location filters | Create filter slot on the warehouse interface that partitions the warehouse by item | Done |
| **M9** | Recipe rebalance | Revised recipes for the terminal, rails, crane and output station, with no two recipes sharing an ingredient set | Done |
| **M10** | Terminal redesign | Separate display side (chosen at placement, turned with the wrench) and crane intake side (derived from the aisle) | Done |
| **M11** | Production patterns, stage 1 | Production station with 3 x 3 patterns: the warehouse delivers ingredients to the player's machines and stores the result | Done |
| **M12** | Mechanical Arm interaction points | Create Mechanical Arms put items into the warehouse input and take them out of the output, terminal and production station | Done |
| **M13** | Ponder scenes for terminal, production station and storage filters | Four new scenes: placing a terminal, requesting at a terminal, feeding machines from a warehouse, and dedicating storage locations with filters; terminal and production station appear in the Ponder index | Done |
| **M14** | Display Link sources for stock displays | Four sources a Create Display Link can read: aisle summary and stock list on the controller and the terminal, the stock of a filter slot's item on the output and the interface, and the crane's status on the dock | Done |
| **M15** | Stock rules | Warehouse stock keeper with a minimum, a maximum and a reserve per item; the warehouse restocks its own minimums through a production station and stops ordering when a machine loses a batch; the terminal asks before one of your own clicks crosses a reserve or a maximum; two Ponder scenes teach the three numbers and the restocking | Done |
| **M16** | Storage location priorities | A priority 0-9 on each storage location, set by holding the click on its filter slot and drawn on the block: among the equally suitable locations the crane fills the highest first. Storing only — retrieval always takes the nearest source, and nothing already stored is ever moved | Done |
| **M17** | Warehouse output as a configurable port | The warehouse output becomes the warehouse's general **port**: it either **requests** what its filter names, or it **accepts** what the warehouse cannot keep. A redstone behaviour says when it acts — on a pulse, while powered, or unless powered, so an accepting port works with no wiring at all and a lever switches it off, and a requesting one keeps a machine supplied without a clock, with one trip at a time. A rank orders an accepting port against the storage locations: negative is the overflow behind all of them, positive diverts items before they are stored. An accepting port turns andesite where the crane reaches in and shows its rank on the back; nothing is ever destroyed, and a full port lets the input back up | Done |
| **M18** | Collecting: the crane picks items up from a machine | A third direction on the warehouse port: instead of waiting for a belt or a funnel, the warehouse **fetches**. Point a port at the inventory a machine drops its result into, set the Collect row with a wrench, and the crane takes the items and stores them — so a machine needs no return path back to the aisle and the production loop closes by itself. The same filter says what is fetched, the same redstone rows say when, and a collecting port turns **copper** where the crane reaches in. It stops by itself at a stock rule's maximum, it never hands items back out through a port, and it refuses an inventory the aisle already counts, so no collect-and-overflow loop can churn. A player's request always comes first, several collecting ports take turns with the warehouse inputs, and nothing is ever pushed into your machine | Done |

## Planned

Planned features and ideas are tracked as [GitHub issues](https://github.com/Richie1710/create-wareworks/issues?q=is%3Aissue+label%3Aenhancement):

* [`planned`](https://github.com/Richie1710/create-wareworks/labels/planned): intended for a future version
* [`idea`](https://github.com/Richie1710/create-wareworks/labels/idea): under consideration, not committed

Feature requests are welcome there. Please add a reaction to an existing issue instead of opening a duplicate.
