package com.holdcraft.trade;

import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

/** Read-only checks over vanilla merchant payment and player inventory slots. */
public final class TradeInventory {
    private static final int INVENTORY_START = 3;
    private static final int INVENTORY_END = 39;

    private TradeInventory() {}

    public static int outputSlot(MerchantMenu menu, ItemStack result) {
        int firstEmpty = -1;
        for (int i = INVENTORY_START; i < Math.min(menu.slots.size(), INVENTORY_END); i++) {
            Slot slot = menu.getSlot(i);
            if (!slot.isActive() || !slot.mayPlace(result)) continue;
            ItemStack current = slot.getItem();
            int max = Math.min(slot.getMaxStackSize(result), result.getMaxStackSize());
            if (current.isEmpty()) {
                if (firstEmpty < 0 && max >= result.getCount()) firstEmpty = i;
            } else if (ItemStack.isSameItemSameComponents(current, result)
                    && max - current.getCount() >= result.getCount()) {
                return i;
            }
        }
        return firstEmpty;
    }

    public static boolean canAfford(MerchantMenu menu, MerchantOffer offer) {
        ItemCost a = offer.getItemCostA();
        ItemCost b = offer.getItemCostB().orElse(null);
        int needA = offer.getCostA().getCount();
        int needB = b == null ? 0 : b.count();
        int onlyA = 0, onlyB = 0, either = 0;
        for (int i = 0; i < Math.min(menu.slots.size(), INVENTORY_END); i++) {
            if (i == 2) continue; // Result slot is not inventory.
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            boolean matchesA = a.test(stack);
            boolean matchesB = b != null && b.test(stack);
            if (matchesA && matchesB) either += stack.getCount();
            else if (matchesA) onlyA += stack.getCount();
            else if (matchesB) onlyB += stack.getCount();
        }
        return Math.max(0, needA - onlyA) + Math.max(0, needB - onlyB) <= either;
    }
}
