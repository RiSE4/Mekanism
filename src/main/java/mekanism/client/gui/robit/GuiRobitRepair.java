package mekanism.client.gui.robit;

import com.mojang.blaze3d.systems.RenderSystem;
import javax.annotation.Nonnull;
import mekanism.common.MekanismLang;
import mekanism.common.inventory.container.entity.robit.RepairRobitContainer;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.IContainerListener;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CRenameItemPacket;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;

public class GuiRobitRepair extends GuiRobit<RepairRobitContainer> implements IContainerListener {

    //Use the vanilla anvil's gui texture
    private static final ResourceLocation ANVIL_RESOURCE = new ResourceLocation("textures/gui/container/anvil.png");
    private TextFieldWidget itemNameField;

    public GuiRobitRepair(RepairRobitContainer container, PlayerInventory inv, ITextComponent title) {
        super(container, inv, title);
    }

    @Override
    public void init() {
        super.init();
        minecraft.keyboardListener.enableRepeatEvents(true);
        addButton(itemNameField = new TextFieldWidget(font, getGuiLeft() + 62, getGuiTop() + 24, 103, 12, ""));
        itemNameField.setTextColor(-1);
        itemNameField.setDisabledTextColour(-1);
        itemNameField.setEnableBackgroundDrawing(false);
        itemNameField.setMaxStringLength(30);
        container.removeListener(this);
        container.addListener(this);
    }

    @Override
    public void onClose() {
        super.onClose();
        minecraft.keyboardListener.enableRepeatEvents(false);
        container.removeListener(this);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        RenderSystem.disableBlend();
        drawString(MekanismLang.ROBIT_REPAIR.translate(), 60, 6, 0x404040);
        int maximumCost = container.getMaximumCost();
        if (maximumCost > 0) {
            int k = 0x80FF20;
            boolean flag = true;
            ITextComponent component = MekanismLang.REPAIR_COST.translate(maximumCost);
            if (maximumCost >= 40 && !minecraft.player.isCreative()) {
                component = MekanismLang.REPAIR_EXPENSIVE.translate();
                k = 0xFF6060;
            } else {
                Slot slot = container.getSlot(2);
                if (!slot.getHasStack()) {
                    flag = false;
                } else if (!slot.canTakeStack(playerInventory.player)) {
                    k = 0xFF6060;
                }
            }

            if (flag) {
                int width = getXSize() - 8 - getStringWidth(component) - 2;
                fill(width - 2, 67, getXSize() - 8, 79, 0x4F000000);
                font.drawStringWithShadow(component.getFormattedText(), (float) width, 69.0F, k);
            }
        }
        super.drawGuiContainerForegroundLayer(mouseX, mouseY);
    }

    @Override
    public boolean charTyped(char c, int i) {
        if (itemNameField.charTyped(c, i)) {
            container.updateItemName(itemNameField.getText());
            minecraft.player.connection.sendPacket(new CRenameItemPacket(itemNameField.getText()));
            return true;
        }
        return super.charTyped(c, i);
    }

    @Override
    protected boolean shouldOpenGui(RobitGuiType guiType) {
        return guiType != RobitGuiType.REPAIR;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTick, int mouseX, int mouseY) {
        minecraft.textureManager.bindTexture(ANVIL_RESOURCE);
        blit(getGuiLeft(), getGuiTop(), 0, 0, getXSize(), getYSize());
        blit(getGuiLeft() + 59, getGuiTop() + 20, 0, getYSize() + (container.getSlot(0).getHasStack() ? 0 : 16), 110, 16);
        if ((container.getSlot(0).getHasStack() || container.getSlot(1).getHasStack()) && !container.getSlot(2).getHasStack()) {
            blit(getGuiLeft() + 99, getGuiTop() + 45, getXSize(), 0, 28, 21);
        }
    }

    @Override
    public void sendAllContents(@Nonnull Container container, @Nonnull NonNullList<ItemStack> list) {
        sendSlotContents(container, 0, container.getSlot(0).getStack());
    }

    @Override
    public void sendSlotContents(@Nonnull Container container, int slotID, @Nonnull ItemStack stack) {
        if (slotID == 0) {
            itemNameField.setText(stack.isEmpty() ? "" : stack.getDisplayName().getFormattedText());
            itemNameField.setEnabled(!stack.isEmpty());
            if (!stack.isEmpty()) {
                this.container.updateItemName(itemNameField.getText());
                minecraft.player.connection.sendPacket(new CRenameItemPacket(itemNameField.getText()));
            }
        }
    }

    @Override
    public void sendWindowProperty(@Nonnull Container container, int varToUpdate, int newValue) {
    }
}