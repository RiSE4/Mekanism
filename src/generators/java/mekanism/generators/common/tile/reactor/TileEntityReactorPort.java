package mekanism.generators.common.tile.reactor;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import mekanism.api.IConfigurable;
import mekanism.api.IHeatTransfer;
import mekanism.api.chemical.IChemicalTank;
import mekanism.api.chemical.gas.Gas;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.gas.IGasHandler;
import mekanism.api.text.EnumColor;
import mekanism.common.Mekanism;
import mekanism.common.MekanismLang;
import mekanism.common.base.FluidHandlerWrapper;
import mekanism.common.base.IFluidHandlerWrapper;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.registries.MekanismFluids;
import mekanism.common.util.CableUtils;
import mekanism.common.util.CapabilityUtils;
import mekanism.common.util.EmitUtils;
import mekanism.common.util.HeatUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.PipeUtils;
import mekanism.common.util.text.BooleanStateDisplay.InputOutput;
import mekanism.generators.common.GeneratorsLang;
import mekanism.generators.common.registries.GeneratorsBlocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Direction;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;

//TODO: Allow reactor controller inventory slot to be interacted with via the port again
public class TileEntityReactorPort extends TileEntityReactorBlock implements IFluidHandlerWrapper, IGasHandler, IHeatTransfer, IConfigurable {

    public TileEntityReactorPort() {
        super(GeneratorsBlocks.REACTOR_PORT);
    }

    @Override
    public boolean canHandleGas() {
        //Mark that we can handle gas
        return true;
    }

    @Override
    public boolean persistGas() {
        //But that we do not handle gas when it comes to syncing it/saving this tile to disk
        return false;
    }

    @Nonnull
    @Override
    public List<? extends IChemicalTank<Gas, GasStack>> getGasTanks(@Nullable Direction side) {
        if (!canHandleGas() || getReactor() == null) {
            return Collections.emptyList();
        }
        return getReactor().controller.getGasTanks(side);
    }

    @Override
    public boolean isFrame() {
        return false;
    }

    @Override
    public void onUpdate() {
        if (changed) {
            World world = getWorld();
            if (world != null) {
                world.notifyNeighborsOfStateChange(getPos(), getBlockType());
            }
        }

        super.onUpdate();
        if (!isRemote()) {
            CableUtils.emit(this);
            if (getActive() && getReactor() != null && !getReactor().getSteamTank().isEmpty()) {
                IFluidTank tank = getReactor().getSteamTank();
                EmitUtils.forEachSide(getWorld(), getPos(), EnumSet.allOf(Direction.class), (tile, side) -> {
                    if (!(tile instanceof TileEntityReactorPort)) {
                        CapabilityUtils.getCapability(tile, CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, side.getOpposite()).ifPresent(handler -> {
                            if (PipeUtils.canFill(handler, tank.getFluid())) {
                                tank.drain(handler.fill(tank.getFluid(), FluidAction.EXECUTE), FluidAction.EXECUTE);
                            }
                        });
                    }
                });
            }
        }
    }

    @Override
    public int fill(Direction from, @Nonnull FluidStack resource, FluidAction fluidAction) {
        return getReactor().getWaterTank().fill(resource, fluidAction);
    }

    @Nonnull
    @Override
    public FluidStack drain(Direction from, int maxDrain, FluidAction fluidAction) {
        return getReactor().getSteamTank().drain(maxDrain, fluidAction);
    }

    @Override
    public boolean canFill(Direction from, @Nonnull FluidStack fluid) {
        //TODO: Should this be switched to FluidTags.WATER
        return getReactor() != null && !getActive() && fluid.getFluid() == Fluids.WATER;
    }

    @Override
    public boolean canDrain(Direction from, @Nonnull FluidStack fluid) {
        return getReactor() != null && (fluid.isEmpty() || MekanismFluids.STEAM.fluidMatches(fluid));
    }

    @Override
    public IFluidTank[] getTankInfo(Direction from) {
        if (getReactor() == null) {
            return PipeUtils.EMPTY;
        }
        return new IFluidTank[]{getReactor().getWaterTank(), getReactor().getSteamTank()};
    }

    @Override
    public IFluidTank[] getAllTanks() {
        return getTankInfo(null);
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> capability, @Nullable Direction side) {
        if (isCapabilityDisabled(capability, side)) {
            return LazyOptional.empty();
        }
        if (capability == Capabilities.GAS_HANDLER_CAPABILITY) {
            return Capabilities.GAS_HANDLER_CAPABILITY.orEmpty(capability, LazyOptional.of(() -> this));
        }
        if (capability == Capabilities.HEAT_TRANSFER_CAPABILITY) {
            return Capabilities.HEAT_TRANSFER_CAPABILITY.orEmpty(capability, LazyOptional.of(() -> this));
        }
        if (capability == Capabilities.CONFIGURABLE_CAPABILITY) {
            return Capabilities.CONFIGURABLE_CAPABILITY.orEmpty(capability, LazyOptional.of(() -> this));
        }
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.orEmpty(capability, LazyOptional.of(() -> new FluidHandlerWrapper(this, side)));
        }
        return super.getCapability(capability, side);
    }

    @Override
    public double getEnergy() {
        if (getReactor() == null) {
            return 0;
        }
        return getReactor().getBufferedEnergy();
    }

    @Override
    public void setEnergy(double energy) {
        if (getReactor() != null) {
            getReactor().setBufferedEnergy(energy);
        }
    }

    @Override
    public double getMaxEnergy() {
        if (getReactor() == null) {
            return 0;
        }
        return getReactor().getBufferSize();
    }

    @Override
    public boolean canOutputEnergy(Direction side) {
        return true;
    }

    @Override
    public boolean canReceiveEnergy(Direction side) {
        return false;
    }

    @Override
    public double getMaxOutput() {
        return Integer.MAX_VALUE;
    }

    @Override
    public double getTemp() {
        if (getReactor() != null) {
            return getReactor().getTemp();
        }
        return 0;
    }

    @Override
    public double getInverseConductionCoefficient() {
        return 5;
    }

    @Override
    public double getInsulationCoefficient(Direction side) {
        if (getReactor() != null) {
            return getReactor().getInsulationCoefficient(side);
        }
        return 0;
    }

    @Override
    public void transferHeatTo(double heat) {
        if (getReactor() != null) {
            getReactor().transferHeatTo(heat);
        }
    }

    @Override
    public double[] simulateHeat() {
        return HeatUtils.simulate(this);
    }

    @Override
    public double applyTemperatureChange() {
        if (getReactor() != null) {
            return getReactor().applyTemperatureChange();
        }
        return 0;
    }

    @Nullable
    @Override
    public IHeatTransfer getAdjacent(Direction side) {
        TileEntity adj = MekanismUtils.getTileEntity(getWorld(), getPos().offset(side));
        if (adj instanceof TileEntityReactorBlock) {
            return null;
        }
        return MekanismUtils.toOptional(CapabilityUtils.getCapability(adj, Capabilities.HEAT_TRANSFER_CAPABILITY, side.getOpposite())).orElse(null);
    }

    @Override
    public boolean isCapabilityDisabled(@Nonnull Capability<?> capability, Direction side) {
        if (capability == Capabilities.GAS_HANDLER_CAPABILITY || capability == Capabilities.HEAT_TRANSFER_CAPABILITY) {
            return getReactor() == null;
        }
        return super.isCapabilityDisabled(capability, side);
    }

    @Override
    public ActionResultType onSneakRightClick(PlayerEntity player, Direction side) {
        if (!isRemote()) {
            boolean oldMode = getActive();
            setActive(!oldMode);
            player.sendMessage(MekanismLang.LOG_FORMAT.translateColored(EnumColor.DARK_BLUE, MekanismLang.MEKANISM,
                  GeneratorsLang.REACTOR_PORT_EJECT.translateColored(EnumColor.GRAY, InputOutput.of(!oldMode, true))));
            Mekanism.packetHandler.sendUpdatePacket(this);
            markDirty();
        }
        return ActionResultType.SUCCESS;
    }

    @Override
    public ActionResultType onRightClick(PlayerEntity player, Direction side) {
        return ActionResultType.PASS;
    }
}