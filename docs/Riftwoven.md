# Realm Seeds

Riftwoven grows persistent, finite dimensions from materials. Ingredients determine the realm's
versioned rules; a separate, immutable server seed determines its landscape. Matching offerings
reproduce a profile, not a map or an ore count. Creation prices and generation tendencies are
provisional survival rules, not a perfectly controlled economy.

## Craft and plant

Craft one Realm Seed using this shaped recipe:

```text
Obsidian     Diamond          Obsidian
Eye of Ender Amethyst Shard   Ghast Tear
Obsidian     Diamond          Obsidian
```

Place it on the center of a five-block stone footing. Two blocks to either side, build
three-high crying-obsidian uprights, each capped with an amethyst block. Keep the three-by-three
opening above the seed clear. The seed's facing determines the arch's orientation; rotating a
valid arch does not secretly change the recipe.

Use accepted materials on the core to deposit up to sixteen items at a time. No loose dropped
items are collected. Empty-hand use displays the current profile. Sneak-use with an empty main
hand withdraws the latest contribution; a refund waits if inventory space is insufficient rather
than dropping belongings into the world.

Sneak while holding diamonds and hold use on the core for three seconds to awaken it. Releasing
use, changing the target or leaving range cancels the countdown. Awakening consumes four
additional diamonds and makes the paid seed, profile, size and world identity immutable. Offers
are no longer refundable afterward. No terrain is sampled before payment.

## Materials shape independent traits

- Stone, cobblestone, common terrain blocks, and biome materials grow the horizontal footprint.
  Growth has diminishing returns and is capped: square halfwidth is
  `min(1024, 128 + 8 * floor(sqrt(bulk units)))` blocks.
- Moss and azalea favor lush habitats; sand and terracotta favor arid habitats; snow and ice
  favor cold habitats; prismarine favors wet habitats. The recipe divides sixteen habitat
  sectors between supplied families and a temperate baseline. These are guaranteed palette
  allocations, not exact surface-block percentages. Dry and wet habitats can coexist.
- Deepslate influences cavern terrain; concentrated terracotta favors plateaus; concentrated
  prismarine favors islands and higher water. Individual worlds still combine varied terrain,
  caves, biomes and ruins rather than selecting complete dimension templates.
- At least 32 wet units and one-quarter of bulk gives wet weather; 64 and one-half gives stormy
  weather. Arid offerings without lush/wet offerings give clear weather. Other mixes use
  changeable weather. Individual events vary deterministically within the regime.
- Glowstone extends the cycle and favors daylight. Amethyst and echo shards extend the cycle
  and favor night. Cycles range from 24,000 to 72,000 ticks; daylight remains between 15% and
  85% of the cycle. Sleeping advances the realm's own clock, not the Overworld's.
- At least one echo shard, sixteen solar units and solar concentration of at least 1/32 unlocks
  a second visible sun. One echo shard and nine lunar units unlocks a second visible moon.
  Extra bodies are visual: they do not double sunlight, temperature or mob protection.
- Lapis, gold, emeralds, diamonds and netherite increase mixed mineral richness and danger based
  on concentration. Bulk dilutes exceptional influence. There are no fixed ore quotas, deposits
  to redeem, matching-ore world types, replenished seams or punishments for successful mining.

Ordinary accepted items count as one unit; storage metal/gem blocks count as nine, snow blocks
as four, and amethyst blocks as nine lunar units. Supported ingredients and version-one formulas
are defined in `RealmRecipe.java`; the live preview is authoritative for the current draft.
Unsupported or specially modified ingredient stacks are rejected before transfer.

The initial vocabulary includes several vanilla biome families, a crystalline-grove hybrid,
continental/island/plateau/cavern terrain tendencies, mixed geology and terrain-adapted courtyard,
arcade, path and tower ruins. These building blocks combine and vary with each seed; they are
not a claim of infinitely many distinct art assets. Generation versions are persisted and unknown
versions fail closed instead of silently regenerating an existing realm.

## Enter, revisit and return

Use an awakened core empty-handed and hold use for three seconds to accept its warnings and
open a private seamless rift. Everyone opens their own authorized view. Initial entrances pass
players and their inventories, not vehicles, mobs, projectiles, fluids or reach-through attacks.
Open entrances from a non-Realm dimension; nested realm visits aren't supported.

A reserved arrival shelter has standing room and a permanent return core. Use that core to
return to your recorded departure. For emergency extraction elsewhere in a realm, empty both
hands, look upward, sneak and hold use for thirty seconds. Damage does not restart the timer;
outside the shelter it grants no protection. Ordinary death, keep-inventory and hardcore rules
remain in force. Another mod can refuse travel through its normal admission safeguards.

Your return record is separate from items and entry permission. If the departure is obstructed,
return tries a safe Overworld spawn-area position without replacing terrain. A destroyed exterior
seed does not erase the realm or the return record. Buildings, dropped items and exhausted
deposits retain ordinary saved state.

Breaking and moving a core, copying an authorized reference, or recovering a reference reconnects
to the same realm. Sneak-use a held Realm Seed in the air to cycle through your recoverable drafts
and shared realms; place the chosen reference to reconnect. Recovering a growing draft also
recovers access to its offering receipts. It never generates a replacement map.

Optional `/riftwoven realms`, `/riftwoven invite <realm UUID> <player> [view|visit|build]`,
`/riftwoven revoke <realm UUID> <player>` and `/riftwoven return` commands support management.
The default invite permits visiting without building; `view` permits only a private window;
`build` also permits building. Ownership is checked server-side; revocation never removes an occupant's right to return.
The protected arrival shelter cannot be harvested or used as a building/farming area. Outside
it, authorized builders can prepare, bridge, tunnel, automate and tame the generated hazards.

## Skies and compatibility

Realm clocks, weather and celestial state are dimension-addressed and synchronized to actual
portal views. Additional suns/moons reuse the normal celestial render path. A shader pack that
replaces the sky or suppresses vanilla celestial bodies may not display them. Disable that pack
when exact visible sky traits matter; Riftwoven does not bypass its compatibility safeguards or
reduce portal quality to force the effect.

## Saves and server operation

Back up worlds before installing development builds. Riftwoven retains the existing
`immersive_portals` mod/registry/config identifiers and the bundled DimLib identity for compatibility
with existing portal worlds. New feature content uses the `riftwoven` namespace. The display-name
change is not a world-identifier migration.

Offering/payment recovery uses world-local `data/riftwoven` journals plus receipts in normal player
save data. Keep these together with dimension and player files in backups. Do not manually remove
journals to retry a transaction: a blocked or corrupt identity is retained for investigation, not
replaced with a newly rolled world. Successful retries reuse the same payment and seed.
Journal storage requires same-filesystem atomic rename and directory forcing; unsupported storage
fails closed before payments instead of silently weakening recovery guarantees.

The default cap is 32 paid or reserved realms. Dimensions retain their registrations and data;
idle activity relies on normal chunk loading, not deleting or unregistering worlds. The finite
generation envelope and maximum size bound new terrain. There is no automatic expiry of builds,
items, disconnected players' worlds or exhausted realms, and no post-awakening expansion.
