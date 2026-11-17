package com.holdcraft;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Per-screen state. All menu modifications are ordinary left/right pick-up actions. */
public final class CraftSession {
    private static final int BUTTON_WIDTH = 26;
    private static final int BUTTON_HEIGHT = 11;
    private static final int RESULT_WAIT_TICKS = 28;
    private static final int SETTLE_TICKS = 2;

    private final Minecraft mc;
    private final Screen screen;
    private final AbstractContainerMenu menu;
    private final LocalPlayer player;
    private final Slot result;
    private final List<Slot> grid;
    private final List<Slot> stock;

    private Pattern remembered;
    private Pattern running;
    private Pattern manualCandidate;
    private List<ItemStack> manualBefore;
    private List<ItemStack> expectedGrid;
    private List<ItemStack> expectedStock;
    private boolean capturedMouse;
    private int settle;
    private int waiting;
    private int idleCaptureDelay;
    private String blocked;

    private CraftSession(Minecraft mc, Screen screen, AbstractContainerMenu menu, Slot result, List<Slot> grid) {
        this.mc = mc;
        this.screen = screen;
        this.menu = menu;
        this.player = mc.player;
        this.result = result;
        this.grid = List.copyOf(grid);
        this.stock = new ArrayList<>(36);
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory() && slot.getContainerSlot() >= 0 && slot.getContainerSlot() < 36) {
                stock.add(slot);
            }
        }
    }

    public static CraftSession open(Screen screen, AbstractContainerMenu menu) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        if (menu instanceof CraftingMenu craft)
            return new CraftSession(mc, screen, craft, craft.getResultSlot(), craft.getInputGridSlots());
        if (menu instanceof InventoryMenu inventory)
            return new CraftSession(mc, screen, inventory, inventory.getResultSlot(), inventory.getInputGridSlots());
        return null;
    }

    public boolean press(double mouseX, double mouseY, int button, int left, int top) {
        if (button != 0 || !hit(mouseX, mouseY, left, top) || displayPattern() == null || !ready()) {
            stop();
            return false;
        }
        capturedMouse = true;
        Pattern recipe = displayPattern();
        blocked = null;
        if (!menu.getCarried().isEmpty()) {
            blocked = "Empty the cursor first.";
            return true;
        }
        if (!canStock(recipe)) {
            blocked = "Not enough matching ingredients.";
            return true;
        }
        if (!canStore(recipe.product)) {
            blocked = "Not enough space for the result.";
            return true;
        }
        running = recipe;
        settle = waiting = 0;
        track();
        return true;
    }

    public boolean release(int button) {
        if (button != 0 || !capturedMouse) return false;
        capturedMouse = false;
        stop();
        return true;
    }

    public boolean drag(double mouseX, double mouseY, int button, int left, int top) {
        if (button != 0 || !capturedMouse) return false;
        if (!hit(mouseX, mouseY, left, top)) stop();
        return true;
    }

    public void manuallyClicked(int slotId, ClickType type) {
        stop();
        blocked = null;
        if (slotId == result.index && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE)) {
            manualCandidate = livePattern();
            manualBefore = manualCandidate == null ? null : snapshot(grid);
            return;
        }
        for (Slot slot : grid) {
            if (slot.index == slotId || type == ClickType.QUICK_CRAFT) {
                remembered = null;
                manualBefore = null;
                manualCandidate = null;
                idleCaptureDelay = 2;
                break;
            }
        }
    }

    public void tick() {
        if (!ready()) {
            close();
            return;
        }
        if (idleCaptureDelay > 0) --idleCaptureDelay;
        if (manualCandidate != null) {
            if (consumed(manualBefore)) remembered = manualCandidate;
            manualBefore = null;
            manualCandidate = null;
        }
        if (remembered != null && !fitsGrid(remembered)) remembered = null;
        if (running == null) return;
        if (!capturedMouse || !mc.isWindowActive()
                || GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS
                || !menu.getCarried().isEmpty()
                || !matches(grid, expectedGrid) || !matches(stock, expectedStock) || !fitsGrid(running)) {
            stop();
            return;
        }
        if (settle > 0) {
            --settle;
            return;
        }
        if (!canStock(running)) {
            blocked = "Not enough matching ingredients.";
            stop();
            return;
        }
        if (!canStore(running.product)) {
            blocked = "Not enough space for the result.";
            stop();
            return;
        }
        for (int i = 0; i < grid.size(); i++) {
            if (!running.inputs.get(i).isEmpty() && grid.get(i).getItem().isEmpty()) {
                if (!refill(grid.get(i), running.inputs.get(i))) {
                    stop();
                    return;
                }
                track();
                settle = 1;
                waiting = 0;
                return;
            }
        }

        // A filled grid only becomes takeable when the server-supplied result is visible and exact.
        if (!ItemStack.matches(result.getItem(), running.product) || !result.mayPickup(player)) {
            if (++waiting >= RESULT_WAIT_TICKS) stop();
            return;
        }
        List<ItemStack> before = snapshot(grid);
        ItemStack output = running.product;
        click(result, 0);
        ItemStack taken = menu.getCarried().copy();
        if (taken.isEmpty()) {
            stop();
            return;
        }
        storeCursor();
        if (!menu.getCarried().isEmpty() || !ItemStack.matches(output, taken) || !consumed(before)) {
            stop();
            return;
        }
        remembered = running;
        track();
        settle = SETTLE_TICKS;
        waiting = 0;
    }

    private boolean refill(Slot target, ItemStack item) {
        Slot source = null;
        for (Slot candidate : stock) {
            if (!candidate.getItem().isEmpty() && same(candidate.getItem(), item) && candidate.mayPickup(player)) {
                source = candidate;
                if (candidate.getItem().getCount() == 1) break;
            }
        }
        if (source == null || !target.mayPlace(item) || !target.getItem().isEmpty()) return false;

        // Finish the pickup → one-item placement → restore as a single client-thread transaction.
        // Releasing between ticks never leaves an ingredient on the cursor.
        click(source, 0);
        if (same(menu.getCarried(), item) && target.getItem().isEmpty()) click(target, 1);
        if (!menu.getCarried().isEmpty()) {
            if (space(source, menu.getCarried()) >= menu.getCarried().getCount()) click(source, 0);
            if (!menu.getCarried().isEmpty()) storeCursor();
        }
        return menu.getCarried().isEmpty() && same(target.getItem(), item) && target.getItem().getCount() == 1;
    }

    private void storeCursor() {
        // Prefer a single legal slot, reducing vanilla clicks. Merge, then use truly empty slots.
        ItemStack initial = menu.getCarried();
        for (int pass = 0; pass < 2 && !initial.isEmpty(); pass++) {
            for (Slot slot : stock) {
                if (slot.getItem().isEmpty() == (pass == 1) && space(slot, initial) >= initial.getCount()) {
                    click(slot, 0);
                    if (menu.getCarried().isEmpty()) return;
                }
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (Slot slot : stock) {
                ItemStack cursor = menu.getCarried();
                if (cursor.isEmpty()) return;
                if (slot.getItem().isEmpty() != (pass == 1)) continue;
                if (space(slot, cursor) > 0) click(slot, 0);
            }
        }
    }

    private boolean canStore(ItemStack output) {
        int needed = output.getCount();
        for (Slot slot : stock) {
            needed -= space(slot, output);
            if (needed <= 0) return true;
        }
        return false;
    }

    private int space(Slot slot, ItemStack item) {
        if (item.isEmpty() || !slot.isActive() || !slot.mayPlace(item)) return 0;
        ItemStack current = slot.getItem();
        if (!current.isEmpty() && !same(current, item)) return 0;
        return Math.max(0, Math.min(item.getMaxStackSize(), slot.getMaxStackSize(item)) - current.getCount());
    }

    private boolean canStock(Pattern pattern) {
        if (!fitsGrid(pattern)) return false;
        int[] used = new int[stock.size()];
        for (int i = 0; i < grid.size(); i++) {
            ItemStack needed = pattern.inputs.get(i);
            if (needed.isEmpty() || grid.get(i).hasItem()) continue;
            boolean found = false;
            for (int j = 0; j < stock.size(); j++) {
                Slot slot = stock.get(j);
                ItemStack look = slot.getItem();
                if (same(needed, look) && look.getCount() > used[j] && slot.mayPickup(player)) {
                    ++used[j];
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private void click(Slot slot, int mouseButton) {
        if (ready()) mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, mouseButton, ClickType.PICKUP, player);
    }

    private boolean ready() {
        return mc.screen == screen && mc.player == player && player.containerMenu == menu
                && player.isAlive() && !player.isSpectator() && mc.gameMode != null;
    }

    private boolean consumed(List<ItemStack> before) {
        for (int i = 0; i < grid.size(); i++) {
            var old = before.get(i);
            if (!old.isEmpty() && (!same(old, grid.get(i).getItem())
                    || grid.get(i).getItem().getCount() < old.getCount())) return true;
        }
        return false;
    }

    private Pattern displayPattern() {
        if (running != null) return running;
        Pattern live = livePattern();
        if (live != null) return live;
        return remembered != null && fitsGrid(remembered) ? remembered : null;
    }

    private Pattern livePattern() {
        if (idleCaptureDelay > 0 || !result.hasItem()) return null;
        List<ItemStack> inputs = new ArrayList<>(grid.size());
        boolean any = false;
        for (Slot slot : grid) {
            ItemStack stack = slot.getItem();
            // Container remainder recipes are left to manual crafting: no chance of spilling a bucket, bottle, etc.
            if (!stack.isEmpty() && !stack.getItem().getCraftingRemainder().isEmpty()) return null;
            any |= !stack.isEmpty();
            inputs.add(stack.copyWithCount(1));
        }
        return any ? new Pattern(inputs, result.getItem().copy()) : null;
    }

    private boolean fitsGrid(Pattern recipe) {
        for (int i = 0; i < grid.size(); i++) {
            ItemStack stack = grid.get(i).getItem();
            if (!stack.isEmpty() && !same(stack, recipe.inputs.get(i))) return false;
        }
        return true;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameComponents(a, b);
    }

    private void track() {
        expectedGrid = snapshot(grid);
        expectedStock = snapshot(stock);
    }

    private static List<ItemStack> snapshot(List<Slot> slots) {
        return slots.stream().map(slot -> slot.getItem().copy()).toList();
    }

    private static boolean matches(List<Slot> slots, List<ItemStack> items) {
        if (items == null || slots.size() != items.size()) return false;
        for (int i = 0; i < slots.size(); i++) {
            if (!ItemStack.matches(slots.get(i).getItem(), items.get(i))) return false;
        }
        return true;
    }

    private void stop() {
        running = null;
        expectedGrid = null;
        expectedStock = null;
    }

    public void close() {
        capturedMouse = false;
        stop();
        remembered = manualCandidate = null;
        manualBefore = null;
    }

    private int buttonX(int left) { return left + result.x + 8 - BUTTON_WIDTH / 2; }
    private int buttonY(int top) { return top + result.y - BUTTON_HEIGHT - 3; }

    private boolean hit(double x, double y, int left, int top) {
        int bx = buttonX(left), by = buttonY(top);
        return x >= bx && x < bx + BUTTON_WIDTH && y >= by && y < by + BUTTON_HEIGHT;
    }

    public void renderButton(GuiGraphics graphics, int mouseX, int mouseY, int left, int top) {
        if (displayPattern() == null || !ready()) return;
        boolean over = hit(mouseX, mouseY, left, top);
        int x = buttonX(left), y = buttonY(top);
        graphics.nextStratum();
        graphics.fill(x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, running != null ? 0xFF8DE5BE : over ? 0xFF9AA5AC : 0xFF71777C);
        graphics.fill(x + 1, y + 1, x + BUTTON_WIDTH - 1, y + BUTTON_HEIGHT - 1,
                running != null ? 0xFF275747 : over ? 0xFF333A3D : 0xFF262A2D);
        graphics.drawCenteredString(mc.font, "Hold", x + BUTTON_WIDTH / 2, y + 2, 0xFFF1F4F5);
        if (over && running == null) {
            graphics.setTooltipForNextFrame(Component.literal(blocked != null ? blocked : "Hold to craft repeatedly"), mouseX, mouseY);
        }
    }

    public void renderGhost(GuiGraphics graphics, Slot slot) {
        if (remembered == null || slot.hasItem()) return;
        int index = grid.indexOf(slot);
        if (index < 0) return;
        ItemStack ghost = remembered.inputs.get(index);
        if (ghost.isEmpty()) return;
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x465CAF9E);
        graphics.renderFakeItem(ghost, slot.x, slot.y);
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x9AA6ACAA);
    }

    private record Pattern(List<ItemStack> inputs, ItemStack product) { }
}
