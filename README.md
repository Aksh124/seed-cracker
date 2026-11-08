# Seed Cracker

Client-side Fabric mod for Minecraft 26.2 that recovers the world seed from structures you visit.
Requires Fabric API.

## Usage

Explore. When one of these loads, the mod notes its chunk in chat along with how many bits it has
so far:
- **Overworld:** desert pyramids, jungle temples, swamp huts, igloos, buried treasure, villages,
  ruined portals, ancient cities and woodland mansions.
- **Nether:** ruined portals.
- **End:** End cities, and the ten obsidian pillars around the main island.

Once it has enough, it starts cracking in the background. The seed appears in chat (click it to
copy) and is also logged to `config/seedcracker-seeds.txt`.

Buried treasure chests and ancient cities are read from chunk data, so you don't need to dig down
to them. Just get close enough for their chunks to load.

If Structure Map is installed, the seed is handed to it automatically. Any mod can receive it by
declaring a `seedcracker:seed_found` entrypoint that implements `java.util.function.LongConsumer`.

Commands work as `/seedcracker` or `/seedcrack`:

| Command | Effect |
| --- | --- |
| `/seedcracker` | Show the seed and the structures found so far |
| `/seedcracker info` | Explain how cracking works, the bits each find is worth, and what's still needed |
| `/seedcracker crack` | Crack again with what has been found |
| `/seedcracker remove <number>` | Remove a structure from the list |
| `/seedcracker share` | Get a code for your progress, click to copy |
| `/seedcracker share <player>` | Send that code to a player by `/msg` |
| `/seedcracker import <code>` | Add someone's progress to yours |
| `/seedcracker clear` | Start over here, including the saved progress |

## Saved progress

What you find is saved per server (by address) or per single-player world, in
`config/seedcracker/`. Leave, relaunch or come back days later and it picks up where you left off.
A world whose seed was already found shows it as soon as you join.

Each save also holds the world's hashed seed. If a server has reset its world, the old finds are
thrown away so they can't spoil the new crack.

## Sharing

`/seedcracker share` gives a short code (`sc1.…`) for what you've found. If the seed is known, the
code is just the seed. Otherwise it holds your finds, the most useful first, as many as fit in one
chat message.

- **Sending it:**
  - `/seedcracker share <player>` sends it by `/msg`. If they have the mod, the raw code is hidden
    and they see "shared Seed Cracker progress: N finds **[Import]**" instead.
  - Or paste the code anywhere (Discord, a book) for them to run `/seedcracker import <code>`.
- **Importing** adds only finds you don't have, saves them, and starts cracking if that's now
  enough. Several players can each explore part of the map and pool what they find.
- **Wrong-world codes are refused.** Each code carries the world's hashed seed, so a code from
  another server or world can't mix in.

The server sees a shared code like any `/msg`. It only says which chunks hold which structures,
or the seed.

## What's enough

You need about 45 bits in total, plus one of these:
- **Three temples or igloos.** These give the low bits the first search step needs.
- **The End pillars.** They make the search work differently, so any mix of structures will do.

Some examples that work:
- 5 temples or igloos.
- 3 temples, plus 3 buried treasures or 2 villages.
- The End pillars plus 3 ruined portals.
- The End pillars plus a mansion and 2 End cities.

## How it works

A world seed is 64 bits. Structures only use the lower 48 (the structure seed). The top 16 come
from the hashed seed the server sends.

| Find | Bits each | Low bits each | Why |
| --- | --- | --- | --- |
| Desert pyramid, jungle temple, swamp hut, igloo | 9.2 | 6 | One per 32×32-chunk region, offset by `nextInt(24)` on each axis: 2 × log2(24) |
| Village | 9.4 | 2 | 34-chunk regions, `nextInt(26)` |
| Ruined portal | 9.3 | 0 | 40-chunk regions, `nextInt(25)`. Overworld and Nether share them |
| Ancient city | 8.0 | 0 | 24-chunk regions, `nextInt(16)`, which uses the RNG's top bits |
| Woodland mansion | 11.3 | 0 | 80-chunk regions. Each offset is the average of two `nextInt(60)` rolls |
| End city | 5.8 | 0 | 20-chunk regions, the average of two `nextInt(9)` rolls |
| Buried treasure | 6.6 | 0 | Each chunk has a 1% chance (`nextFloat() < 0.01`): log2(100) |
| End pillars | 16 | see below | Their heights are a shuffle picked by `Random(seed).nextLong() & 65535` |

**Without the pillars:**
1. **Lowest 20 bits.** Since 24 is a multiple of 8, each temple offset's lowest 3 bits depend
   only on the seed's lowest 20 bits. All 2^20 values are tried, which needs 18 low bits.
2. **The other 28 bits.** For each surviving low half, all 2^28 values are tried against every
   find.

**With the pillars:**
1. **Lowest 32 bits.** The pillar order comes from bits 16–31 of the RNG's second step, which
   depend only on the seed's lowest 32 bits. All 2^32 values are tried, leaving about 65,536.
2. **The other 16 bits.** Each survivor needs only 65,536 more tries. This takes a few seconds and
   needs no temples at all.

**Then the top 16 bits.** Each 48-bit candidate has 65,536 possible full seeds, checked against
the server's SHA-256 hashed seed, which leaves one.

Java's LCG makes similar seeds give similar results, so structure bits overlap and the real
information is lower than the sum. Treasure overlaps much less. Measured median 48-bit candidates:

| Finds | Candidates |
| --- | --- |
| 5 structures | ~10,000 |
| 6 structures | ~1,250 |
| 8 structures | ~500 |
| 3 structures + 3 treasures | ~30 |
| 4 structures + 4 treasures | ~17 |

Fewer candidates make the last step faster, so a few treasures help a lot.

## Detection

- **Jungle temples and swamp huts** fit inside their start chunk, so any marker block gives it.
- **Desert pyramids** use the trap's pressure plate at the centre, which is always in the start chunk.
- **Igloos** can spill into the next chunk. The furnace's facing shows which way the igloo was
  rotated, and undoing that rotation gives the start chunk's corner, which must land on a multiple
  of 16. The crafting table and bed must also be where the template puts them.
- **Buried treasure** is a chest at block 9, 9 of its chunk, covered by sand, sandstone or similar.
- **Ruined portals, villages, ancient cities, mansions and End cities** are matched against the
  game's own structure templates, read from the Minecraft jar. The mod tries every rotation (and
  mirror, for portals). A template only counts if two things hold:
  - its fixed point lands exactly on the chunk grid where vanilla puts it, and
  - most of its blocks are really there.

  | Structure | Fixed point | Matched on |
  | --- | --- | --- |
  | Ruined portals | the template corner on the start chunk's corner | obsidian, crying obsidian allowed |
  | Villages | the town centre's corner on the start chunk's corner | from the bell |
  | Ancient cities | the centre's `city_anchor` jigsaw on the start chunk's corner | reinforced deepslate |
  | Woodland mansions | 9 blocks east (rotated) of the entrance, at block 7, 7 of the start chunk | the entrance |
  | End cities | the base floor's corner, at block 7, 7 of the start chunk | the base floor |
- **End pillars** are read from their heights (76 + 3 × size), once all ten are loaded.

## When it can't work

- **The server hides or changes the hashed seed.** Some anti-seed-cracking plugins do this. You
  still get the 48-bit structure seed, which is enough to locate structures but not biomes.
- **Custom structure seeds.** Spigot and Paper can change structure salts, so positions no longer
  follow vanilla.
- **Anti-xray.** Paper's anti-xray can hide buried chests from the client.
- **Mined End pillars.** If a pillar's top has been mined away, its height is wrong, and the
  pillars are skipped.
- **Player-built copies.** Each structure needs several of its blocks together to count. If one
  wrong structure gets in anyway, the mod tries leaving each one out, and you can remove one with
  `/seedcracker remove`.

## Building

Requires JDK 25.

```bash
./gradlew build
```

`./gradlew test` checks the solver against vanilla's placement code, including the triangular
spread and the pillar order. It also cracks seeds from only new structures plus the pillars.

`./gradlew runClientGameTest` creates worlds with a known seed and checks the mod detects each
structure at the start chunk the server reports. It visits:
- pyramids, temples, huts, igloos and buried treasure
- ruined portals in the Overworld and Nether
- villages, an ancient city and a woodland mansion
- End cities and the End pillars

It cracks the seed from the temples, and again from only the new structures. Along the way it
checks:
- leaving and rejoining keeps progress, and a reset world's save is dropped
- `/seedcracker clear` deletes the save
- a shared code arrives in a whisper as an [Import] prompt and brings every find back
- a seed code gives the seed straight away
- a code from another world is refused
