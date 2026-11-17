package com.holdcraft.mixin;

import com.holdcraft.CraftSession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
abstract class CraftingScreenMixin {
    @Shadow @Final protected AbstractContainerMenu menu;
    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Unique private CraftSession holdcraft$session;

    @Inject(method = "init", at = @At("TAIL"))
    private void holdcraft$init(CallbackInfo ci) {
        if (holdcraft$session == null) holdcraft$session = CraftSession.open((Screen)(Object)this, menu);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void holdcraft$tick(CallbackInfo ci) {
        if (holdcraft$session != null) holdcraft$session.tick();
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void holdcraft$close(CallbackInfo ci) {
        if (holdcraft$session != null) holdcraft$session.close();
    }

    // Recipe-book screens call the common contents method directly rather than render().
    @Inject(method = "renderContents", at = @At("TAIL"))
    private void holdcraft$button(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (holdcraft$session != null) holdcraft$session.renderButton(graphics, mouseX, mouseY, leftPos, topPos);
    }

    @Inject(method = "renderSlot", at = @At("TAIL"))
    private void holdcraft$ghost(GuiGraphics graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (holdcraft$session != null) holdcraft$session.renderGhost(graphics, slot);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void holdcraft$press(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (holdcraft$session != null && holdcraft$session.press(event.x(), event.y(), event.button(), leftPos, topPos)) cir.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void holdcraft$release(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (holdcraft$session != null && holdcraft$session.release(event.button())) cir.setReturnValue(true);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void holdcraft$drag(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (holdcraft$session != null && holdcraft$session.drag(event.x(), event.y(), event.button(), leftPos, topPos)) cir.setReturnValue(true);
    }

    @Inject(method = "slotClicked", at = @At("HEAD"))
    private void holdcraft$manualSlot(Slot slot, int slotId, int button, ClickType type, CallbackInfo ci) {
        if (holdcraft$session != null) holdcraft$session.manuallyClicked(slotId, type);
    }
}
