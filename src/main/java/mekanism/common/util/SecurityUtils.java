package mekanism.common.util;

import java.util.UUID;
import mekanism.api.text.EnumColor;
import mekanism.client.MekanismClient;
import mekanism.common.Mekanism;
import mekanism.common.MekanismLang;
import mekanism.common.config.MekanismConfig;
import mekanism.common.frequency.Frequency;
import mekanism.common.security.IOwnerItem;
import mekanism.common.security.ISecurityItem;
import mekanism.common.security.ISecurityTile;
import mekanism.common.security.ISecurityTile.SecurityMode;
import mekanism.common.security.SecurityData;
import mekanism.common.security.SecurityFrequency;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.api.distmarker.Dist;

public final class SecurityUtils {

    public static boolean canAccess(PlayerEntity player, ItemStack stack) {
        // If protection is disabled, access is always granted
        if (!MekanismConfig.general.allowProtection.get()) {
            return true;
        }
        if (!(stack.getItem() instanceof ISecurityItem) && stack.getItem() instanceof IOwnerItem) {
            UUID owner = ((IOwnerItem) stack.getItem()).getOwnerUUID(stack);
            return owner == null || owner.equals(player.getUniqueID());
        }
        if (stack.isEmpty() || !(stack.getItem() instanceof ISecurityItem)) {
            return true;
        }
        ISecurityItem security = (ISecurityItem) stack.getItem();
        if (MekanismUtils.isOp(player)) {
            return true;
        }
        return canAccess(security.getSecurity(stack), player, security.getOwnerUUID(stack));
    }

    public static boolean canAccess(PlayerEntity player, TileEntity tile) {
        if (!(tile instanceof ISecurityTile) || !((ISecurityTile) tile).hasSecurity()) {
            //If this tile does not have security allow access
            return true;
        }
        ISecurityTile security = (ISecurityTile) tile;
        if (MekanismUtils.isOp(player)) {
            return true;
        }
        return canAccess(security.getSecurity().getMode(), player, security.getSecurity().getOwnerUUID());
    }

    private static boolean canAccess(SecurityMode mode, PlayerEntity player, UUID owner) {
        // If protection is disabled, access is always granted
        if (!MekanismConfig.general.allowProtection.get()) {
            return true;
        }
        if (owner == null || player.getUniqueID().equals(owner)) {
            return true;
        }
        SecurityFrequency freq = getFrequency(owner);
        if (freq == null) {
            return true;
        }
        if (freq.override) {
            mode = freq.securityMode;
        }
        if (mode == SecurityMode.PUBLIC) {
            return true;
        } else if (mode == SecurityMode.TRUSTED) {
            return freq.trusted.contains(player.getUniqueID());
        }
        return false;
    }

    public static SecurityFrequency getFrequency(UUID uuid) {
        if (uuid != null) {
            for (Frequency f : Mekanism.securityFrequencies.getFrequencies()) {
                if (f instanceof SecurityFrequency && f.ownerUUID.equals(uuid)) {
                    return (SecurityFrequency) f;
                }
            }
        }
        return null;
    }

    public static void displayNoAccess(PlayerEntity player) {
        player.sendMessage(MekanismLang.LOG_FORMAT.translateColored(EnumColor.DARK_BLUE, MekanismLang.MEKANISM, MekanismLang.NO_ACCESS.translateColored(EnumColor.RED)));
    }

    public static SecurityMode getSecurity(ISecurityTile security, Dist side) {
        if (!security.hasSecurity()) {
            return SecurityMode.PUBLIC;
        }
        if (side == Dist.DEDICATED_SERVER) {
            SecurityFrequency freq = security.getSecurity().getFrequency();
            if (freq != null && freq.override) {
                return freq.securityMode;
            }
        } else if (side == Dist.CLIENT) {
            SecurityData data = MekanismClient.clientSecurityMap.get(security.getSecurity().getOwnerUUID());
            if (data != null && data.override) {
                return data.mode;
            }
        }
        return security.getSecurity().getMode();
    }

    public static SecurityMode getSecurity(ItemStack stack, Dist side) {
        ISecurityItem security = (ISecurityItem) stack.getItem();
        SecurityMode mode = security.getSecurity(stack);
        if (security.getOwnerUUID(stack) != null) {
            if (side == Dist.DEDICATED_SERVER) {
                SecurityFrequency freq = getFrequency(security.getOwnerUUID(stack));
                if (freq != null && freq.override) {
                    mode = freq.securityMode;
                }
            } else if (side == Dist.CLIENT) {
                SecurityData data = MekanismClient.clientSecurityMap.get(security.getOwnerUUID(stack));
                if (data != null && data.override) {
                    mode = data.mode;
                }
            }
        }
        return mode;
    }

    public static boolean isOverridden(ItemStack stack, Dist side) {
        ISecurityItem security = (ISecurityItem) stack.getItem();
        if (security.getOwnerUUID(stack) == null) {
            return false;
        }
        if (side == Dist.DEDICATED_SERVER) {
            SecurityFrequency freq = getFrequency(security.getOwnerUUID(stack));
            return freq != null && freq.override;
        }
        SecurityData data = MekanismClient.clientSecurityMap.get(security.getOwnerUUID(stack));
        return data != null && data.override;
    }

    public static boolean isOverridden(TileEntity tile, Dist side) {
        ISecurityTile security = (ISecurityTile) tile;
        if (!security.hasSecurity() || security.getSecurity().getOwnerUUID() == null) {
            return false;
        }
        if (side == Dist.DEDICATED_SERVER) {
            SecurityFrequency freq = getFrequency(security.getSecurity().getOwnerUUID());
            return freq != null && freq.override;
        }
        SecurityData data = MekanismClient.clientSecurityMap.get(security.getSecurity().getOwnerUUID());
        return data != null && data.override;
    }
}