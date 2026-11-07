# Seed Cracker

Client-side Fabric mod for Minecraft 26.2 that recovers the world seed from structures you visit.
Requires Fabric API.

## Usage

Explore the Overworld. When a **desert pyramid, jungle temple, swamp hut, igloo or buried
treasure** loads, the mod notes its chunk in chat along with how many bits it has so far. Once it
has enough, it starts cracking in the background. The seed appears in chat (click it to copy) and
is also saved to `config/seedcracker-seeds.txt`.

Buried treasure chests are read from chunk data, so you don't need to dig them up. Just get close
enough for the beach chunks to load.

If Structure Map is installed, the seed is handed to it automatically. Any mod can receive it by
declaring a `seedcracker:seed_found` entrypoint that implements `java.util.function.LongConsumer`.

Commands work as `/seedcracker` or `/seedcrack`:

| Command | Effect |
| --- | --- |
| `/seedcracker` | Show the seed and the structures found so far |
| `/seedcracker info` | Explain how cracking works, the bits each find is worth, and what's still needed |
| `/seedcracker crack` | Crack again with what has been found |
| `/seedcracker remove <number>` | Remove a structure from the list |
| `/seedcracker clear` | Start over |

## How it works

A world seed is 64 bits. Structures only use the lower 48 (the structure seed). The top 16 come
from the hashed seed the server sends.

| Find | Bits each | Low bits each | Why |
| --- | --- | --- | --- |
| Desert pyramid, jungle temple, swamp hut, igloo | 9.2 | 6 | One per 32×32-chunk region, offset by `nextInt(24)` on each axis: 2 × log2(24) |
| Buried treasure | 6.6 | 0 | Each chunk has a 1% chance (`nextFloat() < 0.01`): log2(100) |

1. **Lowest 20 bits.** Since 24 is a multiple of 8, each structure offset's lowest 3 bits depend
   only on the seed's lowest 20 bits. All 2^20 values are tried, which needs 18 low bits (3 spread
   structures).
2. **The other 28 bits.** For each surviving low half, all 2^28 values are tried against every
   find. This needs about 45 bits in total.
3. **Top 16 bits.** Each 48-bit candidate has 65,536 possible full seeds, checked against the
   server's SHA-256 hashed seed, which leaves one.

Java's LCG makes similar seeds give similar results, so structure bits overlap and the real
information is lower than the sum. Treasure overlaps much less. Measured median 48-bit candidates:

| Finds | Candidates |
| --- | --- |
| 5 structures | ~10,000 |
| 6 structures | ~1,250 |
| 8 structures | ~500 |
| 3 structures + 3 treasures | ~30 |
| 4 structures + 4 treasures | ~17 |

Fewer candidates make step 3 faster, so a few treasures help a lot.

## Detection

- **Jungle temples and swamp huts** fit inside their start chunk, so any marker block gives it.
- **Desert pyramids** use the trap's pressure plate at the centre, which is always in the start chunk.
- **Igloos** can spill into the next chunk. The furnace's facing shows which way the igloo was
  rotated, and undoing that rotation gives the start chunk's corner, which must land on a multiple
  of 16. The crafting table and bed must also be where the template puts them.
- **Buried treasure** is a chest at block 9, 9 of its chunk, covered by sand, sandstone or similar.

## When it can't work

- **The server hides or changes the hashed seed.** Some anti-seed-cracking plugins do this. You
  still get the 48-bit structure seed, which is enough to locate structures but not biomes.
- **Custom structure seeds.** Spigot and Paper can change structure salts, so positions no longer
  follow vanilla.
- **Anti-xray.** Paper's anti-xray can hide buried chests from the client.
- **Player-built copies.** Each structure needs several of its blocks together to count. If one
  wrong structure gets in anyway, the mod tries leaving each one out, and you can remove one with
  `/seedcracker remove`.

## Building

Requires JDK 25.

```bash
./gradlew build
```

`./gradlew test` checks the solver against vanilla's placement code. `./gradlew runClientGameTest`
creates worlds with a known seed, visits pyramids, temples, huts, igloos and buried treasure, and
checks the mod detects each one and cracks the seed.
