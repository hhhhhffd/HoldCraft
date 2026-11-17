# Hold Craft 1.0.0

A client-side mod for Minecraft Java **1.21.11**, **Fabric Loader 0.18.4+** and
Java 21. Put the release JAR in your client's `mods` folder. Fabric API and
server-side mods are not required.

## Crafting

1. Lay out the recipe normally in the 2×2 inventory grid or a 3×3 crafting table.
2. Take a result manually. If it consumed ingredients, the mod displays faint
   ingredient hints in the newly empty slots. These are only drawn on screen.
3. Empty the cursor, and hold the small **Hold** button directly above the
   result. The mod tops up the same occupied positions and crafts one result
   at a time, putting the output into your main inventory or hotbar.
4. Release the button, drag off it, close the screen, or click another slot to
   stop. Auto-crafting also stops on insufficient ingredients or output space,
   invalid results, and unexpected changes to the grid or inventory.

The mod matches the exact items and components you placed; it does not
substitute another wood type or rearrange real items you put in the grid.
It uses normal inventory actions, placing only one ingredient per missing slot
and finishing each small transfer before starting the next. Recipes with
ordinary container remainders (for example buckets) are left to manual crafting.

## Villager trades

Select the offer manually in the vanilla merchant screen, then click **Auto Trade**
below the result. It repeats that selected deal at a paced interval using the
normal vanilla payment slots and items from your inventory. Click **Stop** to
cancel; changing the offer, using another control, closing the screen, a changed
price, exhausted stock, unaffordable payment, and insufficient result room
also stop it. The mod does not bypass trade limits or send custom packets.

## Build

Run `./gradlew build` (`gradlew.bat build` on Windows) under Java 21.
