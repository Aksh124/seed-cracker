# Seed Cracker

Client-side Fabric mod for Minecraft 26.2 that recovers the world seed from structures you visit.
Requires Fabric API.

## Usage

Explore the Overworld. When a desert pyramid, jungle temple or swamp hut loads, the mod notes its
chunk in chat. Once it has 5, it starts cracking in the background. The seed appears in chat
(click it to copy) and is also saved to `config/seedcracker-seeds.txt`.

5 structures usually works. 6 or more is faster and more reliable.

If Structure Map is installed, the seed is handed to it automatically. Any mod can receive it by
declaring a `seedcracker:seed_found` entrypoint that implements `java.util.function.LongConsumer`.

| Command | Effect |
| --- | --- |
| `/seedcrack` | Show the seed and the structures found so far |
| `/seedcrack crack` | Crack again with what has been found |
| `/seedcrack remove <number>` | Remove a structure from the list |
| `/seedcrack clear` | Start over |

## How it works

1. **Lower 48 bits from structures.** Each structure type picks one chunk per 32×32-chunk region,
   using an RNG seeded from the world seed. Only the lower 48 bits of the seed affect it. The
   offset is `nextInt(24)`, whose lowest 3 bits depend only on the seed's lowest 20 bits. That
   allows a 2^20 search for those, then a 2^28 search for the rest.
2. **Upper 16 bits from the hashed seed.** The server sends every client a SHA-256 hash of the
   seed for biome blending. Each 48-bit candidate has 65,536 possible full seeds, and checking
   them against the hash leaves one.

Java's LCG leaves thousands of 48-bit candidates even with several structures, so the hash step
does most of the narrowing. Measured median candidates: about 28,000 with 5 structures, 6,600
with 6, and 600 with 8.

## When it can't work

- **The server hides or changes the hashed seed.** Some anti-seed-cracking plugins do this. You
  still get the 48-bit structure seed, which is enough to locate structures but not biomes.
- **Custom structure seeds.** Spigot and Paper can change structure salts, so positions no longer
  follow vanilla.
- **Player-built copies.** Each structure needs several of its blocks together to count. If one
  wrong structure gets in anyway, the mod tries leaving each one out, and you can remove one with
  `/seedcrack remove`.

## Building

Requires JDK 25.

```bash
./gradlew build
```

`./gradlew test` checks the solver against vanilla's placement code. `./gradlew runClientGameTest`
creates a world with a known seed, visits its structures and checks the mod cracks it.
