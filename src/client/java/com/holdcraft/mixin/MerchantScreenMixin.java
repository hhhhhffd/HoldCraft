package com.holdcraft.mixin;

import com.holdcraft.trade.TradeInventory;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MerchantScreen.class)
abstract class MerchantScreenMixin extends AbstractContainerScreen<MerchantMenu> {
    @Unique private static final int holdcraft$TRADE_INTERVAL_TICKS = 12;
    @Unique private static final int holdcraft$RESULT_SLOT = 2;
    @Shadow private int shopItem;
    @Shadow private void postButtonClick() { throw new AssertionError(); }

    @Unique private Button holdcraft$tradeButton;
    @Unique private boolean holdcraft$userSelected;
    @Unique private boolean holdcraft$refilling;
    @Unique private int holdcraft$running = -1;
    @Unique private int holdcraft$delay;
    @Unique private int holdcraft$expectedSlot = -1;
    @Unique private ItemStack holdcraft$expectedStack = ItemStack.EMPTY;
    @Unique private MerchantOffer holdcraft$terms;
    @Unique private boolean holdcraft$hasRefilled;

    protected MerchantScreenMixin(MerchantMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void holdcraft$install(CallbackInfo ci) {
        holdcraft$stop();
        holdcraft$tradeButton = addRenderableWidget(Button.builder(Component.literal("Auto Trade"), button -> holdcraft$toggle())
                .bounds(leftPos + 191, topPos + 56, 74, 15)
                .tooltip(Tooltip.create(Component.literal("Select a trade first. Repeats one trade at a time while this menu stays open.")))
                .build());
        holdcraft$refresh();
    }

    @Inject(method = "postButtonClick", at = @At("HEAD"))
    private void holdcraft$offerClicked(CallbackInfo ci) {
        if (holdcraft$refilling) return;
        holdcraft$stop();
        holdcraft$userSelected = true;
        holdcraft$refresh();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void holdcraft$manualAction(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (holdcraft$running >= 0 && (holdcraft$tradeButton == null || event.button() != 0
                || !holdcraft$tradeButton.isMouseOver(event.x(), event.y()))) holdcraft$stop();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        holdcraft$refresh();
        if (holdcraft$running < 0) return;
        MerchantOffer offer = holdcraft$offer();
        if (!holdcraft$validContext() || shopItem != holdcraft$running || offer == null
                || !holdcraft$sameTerms(holdcraft$terms, offer)) {
            holdcraft$stop();
            return;
        }
        if (--holdcraft$delay > 0) return;

        if (holdcraft$expectedSlot >= 0 && !ItemStack.matches(menu.getSlot(holdcraft$expectedSlot).getItem(), holdcraft$expectedStack)) {
            holdcraft$stop(); // Correction, concurrent inventory change, or rejected trade.
            return;
        }
        holdcraft$expectedSlot = -1;
        if (offer.isOutOfStock()) {
            holdcraft$stop();
            return;
        }
        var resultSlot = menu.getSlot(holdcraft$RESULT_SLOT);
        ItemStack result = resultSlot.getItem();
        if (result.isEmpty()) {
            if (holdcraft$hasRefilled || !TradeInventory.canAfford(menu, offer)
                    || TradeInventory.outputSlot(menu, offer.getResult()) < 0) {
                holdcraft$stop();
                return;
            }
            holdcraft$refilling = true;
            try {
                postButtonClick(); // The ordinary merchant row action, including vanilla autofill.
            } finally {
                holdcraft$refilling = false;
            }
            holdcraft$hasRefilled = true;
            holdcraft$delay = holdcraft$TRADE_INTERVAL_TICKS;
            return;
        }
        if (!(resultSlot.container instanceof MerchantContainer merchant)
                || merchant.getActiveOffer() != offer || !ItemStack.matches(result, offer.getResult())) {
            holdcraft$stop();
            return;
        }
        int destination = TradeInventory.outputSlot(menu, result);
        if (destination < 0) {
            holdcraft$stop();
            return;
        }

        ItemStack expected = result.copy();
        expected.grow(menu.getSlot(destination).getItem().getCount());
        ItemStack taken = result.copy();
        int beforeUses = offer.getUses();
        // A plain left click takes a single result. Never Quick Move, which can consume an entire payment stack.
        minecraft.gameMode.handleInventoryMouseClick(menu.containerId, holdcraft$RESULT_SLOT, 0, ClickType.PICKUP, minecraft.player);
        if (!ItemStack.matches(menu.getCarried(), taken)) {
            holdcraft$stop();
            return;
        }
        minecraft.gameMode.handleInventoryMouseClick(menu.containerId, destination, 0, ClickType.PICKUP, minecraft.player);
        if (!menu.getCarried().isEmpty() || !ItemStack.matches(menu.getSlot(destination).getItem(), expected)
                || offer.getUses() != beforeUses + 1) {
            holdcraft$stop();
            return;
        }
        holdcraft$expectedSlot = destination;
        holdcraft$expectedStack = expected;
        holdcraft$delay = holdcraft$TRADE_INTERVAL_TICKS;
        holdcraft$hasRefilled = false;
    }

    @Unique private MerchantOffer holdcraft$offer() {
        return shopItem >= 0 && shopItem < menu.getOffers().size() ? menu.getOffers().get(shopItem) : null;
    }

    @Unique private boolean holdcraft$validContext() {
        return minecraft != null && minecraft.screen == (Object)this && minecraft.player != null
                && minecraft.gameMode != null && minecraft.getConnection() != null
                && minecraft.player.containerMenu == menu && menu.getCarried().isEmpty();
    }

    @Unique private void holdcraft$toggle() {
        if (holdcraft$running >= 0) {
            holdcraft$stop();
            return;
        }
        MerchantOffer offer = holdcraft$offer();
        if (!holdcraft$userSelected || !holdcraft$validContext() || offer == null || offer.isOutOfStock()
                || !TradeInventory.canAfford(menu, offer) || TradeInventory.outputSlot(menu, offer.getResult()) < 0) return;
        holdcraft$terms = offer.copy();
        holdcraft$running = shopItem;
        holdcraft$delay = holdcraft$TRADE_INTERVAL_TICKS;
        holdcraft$refresh();
    }

    @Unique private void holdcraft$stop() {
        holdcraft$running = -1;
        holdcraft$delay = 0;
        holdcraft$expectedSlot = -1;
        holdcraft$expectedStack = ItemStack.EMPTY;
        holdcraft$terms = null;
        holdcraft$hasRefilled = false;
        holdcraft$refresh();
    }

    @Unique private void holdcraft$refresh() {
        if (holdcraft$tradeButton == null) return;
        boolean running = holdcraft$running >= 0;
        holdcraft$tradeButton.active = running || (holdcraft$userSelected && holdcraft$offer() != null
                && !holdcraft$offer().isOutOfStock() && menu.getCarried().isEmpty());
        String label = running ? "Stop" : "Auto Trade";
        if (!holdcraft$tradeButton.getMessage().getString().equals(label))
            holdcraft$tradeButton.setMessage(Component.literal(label));
    }

    @Unique private static boolean holdcraft$sameTerms(MerchantOffer first, MerchantOffer second) {
        return first != null && first.getItemCostA().equals(second.getItemCostA())
                && first.getItemCostB().equals(second.getItemCostB())
                && ItemStack.matches(first.getCostA(), second.getCostA())
                && ItemStack.matches(first.getResult(), second.getResult())
                && first.getMaxUses() == second.getMaxUses();
    }
}
