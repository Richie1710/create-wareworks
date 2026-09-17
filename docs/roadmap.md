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

## Planned

* Multi-aisle warehouses and several cranes per controller
* Stock rules (minimum, maximum, reserve) and restocking of production lines
* Recursive production (stage 2): ordering an item whose ingredients must be produced first
* Ponder scenes for the warehouse terminal and the production station, and a Ponder beat for storage location filters
* Display Link sources for stock displays
* Pallets, Create packages and fluids
* Computer integration (e.g. CC: Tweaked)
* Mechanical arm interaction points for stations and warehouse interfaces
* Optional chunk loading for aisles with active jobs
