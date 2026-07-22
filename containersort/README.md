# ContainerSort

One-click alphabetical sorting for chests, barrels, shulker boxes, and ender chests.
A **client-side** Fabric mod for **Minecraft Java Edition 26.2** — works in singleplayer
and on completely unmodded vanilla servers.

Press the small **A-Z** button (or a configurable keybind) in any supported container
screen and the container's stacks are consolidated and rearranged alphabetically.
Every rearrangement is performed as a sequence of standard slot-click packets — the
same packets your mouse would generate — so from the server's perspective the mod is
indistinguishable from a very tidy human player. Your own inventory and hotbar are
never touched.

---

## Features

- **Sort button** in chest, double chest, trapped chest, barrel, ender chest, and
  shulker box screens (all 17 variants). Appears only when the container holds two or
  more stacks.
- **Stack consolidation** — partial stacks of the same item merge first (32 + 20 → 52;
  40 + 32 → 64 + 8). Items with distinguishing components (enchantments, custom names,
  potion effects, durability, …) are treated as distinct and never merged into plain ones.
- **Three sort modes** — `ALPHABETICAL` (displayed name in your locale, registry-ID
  tiebreak), `ITEM_ID` (locale-independent), `COUNT_DESC` (biggest stacks first).
- **Minimal transfers** — a cycle-based planner computes near-minimal click sequences
  instead of naively rewriting every slot; re-sorting an already-sorted container sends
  zero clicks.
- **Undo** — `/containersort undo` restores the exact pre-sort arrangement, including
  re-splitting stacks the sort merged.
- **Keybind** — an optional key (unbound by default, set it under Options → Controls →
  Inventory → *Sort Open Container*) triggers sorting while a supported container is open.
- **Safety first** — the mod snapshots the container before sorting, reconciles the
  server's response after every click, and aborts cleanly if the screen closes, the
  server disagrees, or anything unexpected happens. Item counts are never changed:
  no duplication and no deletion, under any failure path.
- **Functional containers excluded** — furnaces, smokers, blast furnaces, dispensers,
  droppers, hoppers, brewing stands, crafting tables, looms, etc. never get a sort
  button; slot positions carry meaning there. Detection is by screen/menu class, so an
  anvil-renamed chest is still recognised correctly.
- **Plays nicely with other mods** — if a known inventory-sorting mod (Inventory
  Profiles Next, Mouse Wheelie, …) is installed, ContainerSort hides its own button by
  default to avoid a doubled-up UI (configurable).

## Requirements

| Item          | Version |
|---------------|---------|
| Minecraft     | Java Edition **26.2** |
| Fabric Loader | **0.19.3** or newer |
| Fabric API    | **0.155.2+26.2** or newer (in `.minecraft/mods/`) |
| Java (runtime)| 25 (bundled with the official launcher) |

> **Note on 26.2 tooling:** MC 26.x uses Mojang's official mappings directly — there is
> no Yarn/mappings line in the buildscript anymore — and Loom 1.17 / Loader 0.19.x
> introduce the new enum-extension API. ContainerSort does not extend any vanilla
> enums, so this affects only the toolchain pins, not the mod's code.

## Building from source

Prerequisites: **JDK 25** and (once, to generate the wrapper) Gradle 9.5.1+ on PATH.
This repository already ships the Gradle wrapper, so in the normal case only JDK 25 is
needed.

```bash
# 1. Enter the project folder.
cd containersort

# 2. Confirm JDK 25 is active.
java -version        # must report 25

# 3. Build.
gradlew.bat build    # Windows
./gradlew build      # macOS / Linux

# Output:
#   build/libs/containersort-26.2-1.0.0.jar          <- install this
#   build/libs/containersort-26.2-1.0.0-sources.jar  <- ignore
```

The first build downloads the Minecraft 26.2 client jar and the Fabric toolchain
(Loom 1.17, Loader 0.19.3, Fabric API 0.155.2+26.2), so it needs a network connection
and a few minutes; later builds are quick. `./gradlew runClient` launches a development
client with the mod loaded.

## Installation

1. Install Fabric Loader 0.19.3+ for Minecraft 26.2 via the [Fabric installer](https://fabricmc.net/use/installer/).
2. Place **Fabric API 0.155.2+26.2** in `.minecraft/mods/`.
3. Place `build/libs/containersort-26.2-1.0.0.jar` in `.minecraft/mods/`.
4. *(Optional)* Place Mod Menu in `.minecraft/mods/` to see the mod listed with its metadata.
5. Launch the `fabric-loader-26.2` profile.

Nothing needs to be installed server-side; the mod is safe to use on vanilla servers.

## Usage

- **Button:** open a chest/barrel/shulker/ender chest and click the `A-Z` button in the
  top-right corner (position configurable). Hover it for the tooltip *"Sort Items (A–Z)"*.
- **Keybind:** bind *Sort Open Container* in Options → Controls, then press it while a
  supported container screen is focused.
- **Commands** (client-side):
  - `/containersort sort` — sort the currently open container.
  - `/containersort undo` — restore the most recent pre-sort arrangement. Intended for
    the container you just sorted; the mod verifies the contents are still compatible
    before restoring, and refuses otherwise.
  - `/containersort reload` — reload `config/containersort.toml` without restarting.

> **Undo caveat:** opening chat to type a command closes the container screen, so in
> practice you reopen the container and then run `/containersort undo`. The restore is
> verified against the snapshot's contents; if the container changed too much in
> between, undo refuses rather than guessing.

## Configuration — `config/containersort.toml`

Created with defaults on first launch; reload in-game with `/containersort reload`.

| Option | Type | Default | Description |
|---|---|---|---|
| `enabled` | Boolean | `true` | Master toggle — disables all mod behaviour when false. |
| `sort_mode` | Enum | `ALPHABETICAL` | `ALPHABETICAL` \| `ITEM_ID` \| `COUNT_DESC`. `ITEM_ID` gives a locale-independent order. |
| `merge_partial_stacks` | Boolean | `true` | Consolidate partial stacks of the same item before reordering. |
| `show_button` | Boolean | `true` | Render the sort button. The keybind still works if disabled. |
| `button_position` | Enum | `TOP_RIGHT` | `TOP_RIGHT` \| `TOP_LEFT` — corner of the container GUI the button is anchored to. |
| `click_delay_ms` | Integer | `50` | Delay between simulated slot-click packets. Range 0–250. Raise on high-latency or heavily-moderated servers. |
| `min_slot_threshold` | Integer | `27` | Suppress the button on any supported container smaller than this slot count. |
| `enable_undo` | Boolean | `true` | Keep a pre-sort snapshot and allow `/containersort undo`. |
| `respect_other_sort_mods` | Boolean | `true` | Auto-hide the button when a conflicting sort mod is detected. |
| `debug_logging` | Boolean | `false` | Verbose sort-plan diagnostics in the Fabric log. Development use only. |

## How it works (and why it is server-safe)

A container's contents are authoritative on the **server**; a client-side mod cannot
simply rewrite slots. ContainerSort therefore:

1. Reads the container's own slots (never the player-inventory slots that follow them
   in the vanilla menu layout).
2. Computes a complete click plan offline — consolidation, ordering, then a
   cycle-based permutation — simulating vanilla `PICKUP` semantics for every click.
   The plan is verified before use: it must reach the target arrangement, end with an
   empty cursor, and conserve every item. Planning a full double chest takes well
   under a millisecond.
3. Sends the plan as ordinary slot-click packets through the already-open container
   menu, one click at a time, spaced by `click_delay_ms`.
4. After every click, reconciles the live slot and cursor contents against the plan's
   expectations. If the server rejects or alters anything, or the screen closes
   mid-sequence, the remaining clicks are aborted with a warning — whatever partial
   arrangement exists is left as-is rather than risking item loss.

No custom payload or plugin-channel packets are used anywhere.

### Anti-cheat note (important on public servers)

A full-container sort sends many slot clicks in quick succession — more than a human
usually would. The default `click_delay_ms = 50` paces clicks at roughly humanly-
plausible speed, but on servers with aggressive anti-cheat or strict packet-rate
moderation you should **raise the delay** (e.g. 100–150 ms) — a slower, safer sort
beats a kick. Use the mod on public servers at your own discretion.

## Project layout

```
containersort/
├─ build.gradle / gradle.properties / settings.gradle   Fabric Loom 1.17 build (MC 26.2, Java 25)
├─ src/main/resources/
│  ├─ fabric.mod.json                                   Mod metadata (client-only environment)
│  └─ assets/containersort/lang/en_us.json              UI strings
└─ src/client/                                          All code is client-side
   ├─ java/com/iceyatom/containersort/client/
   │  ├─ ContainerSortClient.java        Entrypoint: config, keybind, commands, tick driver
   │  ├─ ModConflicts.java               Startup detection of other sorting mods
   │  ├─ config/ContainerSortConfig.java TOML config load/save/reload
   │  ├─ mixin/AbstractContainerScreenMixin.java  Adds the button; abort-on-close hook
   │  └─ sort/
   │     ├─ ContainerSortHelper.java     Pure click planner — no Minecraft imports, unit-testable
   │     ├─ ItemKey.java                 "Same item + same components" merge/sort key
   │     ├─ SortManager.java             Screen support checks, snapshots, plan orchestration
   │     └─ SortExecutor.java            Tick-driven click sender with server reconciliation
   └─ resources/containersort.client.mixins.json
```

### Mixin notes

The only mixin targets `net.minecraft.client.gui.screens.inventory.AbstractContainerScreen`
(methods `init`, `containerTick`, `removed` — all declared on that class itself, never
inherited ones). In MC 26.2 the concrete `ContainerScreen`/`ShulkerBoxScreen` subclasses
no longer declare `init()` at all, so the shared superclass is the narrowest possible
target; concrete screen types are filtered with `instanceof` guards. The injection uses
priority 1100 so mods injecting at the default 1000 run first. No access widener is
needed — all fields used (`leftPos`, `topPos`, `imageWidth`) resolve as `protected`.

### Testing the planner

The sort/undo planner is deliberately free of Minecraft imports and can be exercised
standalone. It has been fuzz-tested with 40,000+ randomized container states across
two harnesses, asserting for every state that: the plan reaches the target, item
counts are conserved, re-sorting is a zero-click no-op, and undo restores the exact
original arrangement (including re-splitting merged stacks via right-click placement).

## Known limitations

- A **completely full** container that needs same-item stacks rearranged may
  occasionally be unsortable/unrestorable purely with container-internal clicks (there
  is no free slot to route through, and same-item stacks merge rather than swap). The
  planner detects this and declines — the container is simply left untouched.
- Undo is only meaningful while you are at the container you sorted; it verifies
  content compatibility before restoring and refuses otherwise.
- Nested shulker boxes inside a sorted container are moved as single stacks — their
  own contents are not sorted recursively.
- Mod Menu integration is limited to metadata display; configuration is via
  `config/containersort.toml` + `/containersort reload` (a Mod Menu config screen is
  planned once a 26.2-compatible Mod Menu API is published).

## Out of scope (v1.0)

Sorting the player inventory/hotbar; functional containers; multi-container
auto-balancing; automatic sort-on-close; additional sort criteria (rarity, mod
namespace, enchantment); Forge/NeoForge/Quilt; any server-side component.

## License

[MIT](LICENSE) — © 2026 iceyatom
