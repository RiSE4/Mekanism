package mekanism.common.tile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import mekanism.api.Chunk3D;
import mekanism.api.Coord4D;
import mekanism.api.IHeatTransfer;
import mekanism.api.TileNetworkList;
import mekanism.api.gas.Gas;
import mekanism.api.gas.GasStack;
import mekanism.api.gas.GasTankInfo;
import mekanism.api.gas.IGasHandler;
import mekanism.api.transmitters.TransmissionType;
import mekanism.common.Mekanism;
import mekanism.common.MekanismBlock;
import mekanism.common.SideData.IOState;
import mekanism.common.Upgrade;
import mekanism.common.base.FluidHandlerWrapper;
import mekanism.common.base.IFluidHandlerWrapper;
import mekanism.common.base.ISideConfiguration;
import mekanism.common.base.ITankManager;
import mekanism.common.base.IUpgradeTile;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.chunkloading.IChunkLoader;
import mekanism.common.config.MekanismConfig;
import mekanism.common.content.entangloporter.InventoryFrequency;
import mekanism.common.frequency.Frequency;
import mekanism.common.frequency.FrequencyManager;
import mekanism.common.frequency.IFrequencyHandler;
import mekanism.common.integration.computer.IComputerIntegration;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentChunkLoader;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.TileComponentUpgrade;
import mekanism.common.util.CableUtils;
import mekanism.common.util.CapabilityUtils;
import mekanism.common.util.FluidContainerUtils;
import mekanism.common.util.HeatUtils;
import mekanism.common.util.InventoryUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.PipeUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;

public class TileEntityQuantumEntangloporter extends TileEntityMekanism implements ISideConfiguration, ITankManager, IFluidHandlerWrapper, IFrequencyHandler,
      IGasHandler, IHeatTransfer, IComputerIntegration, IChunkLoader, IUpgradeTile {

    private static final int INV_SIZE = 1;//this.inventory size, used for upgrades. Manually handled
    private static final String[] methods = new String[]{"setFrequency"};
    public InventoryFrequency frequency;
    public double heatToAbsorb = 0;
    public double lastTransferLoss;
    public double lastEnvironmentLoss;
    public List<Frequency> publicCache = new ArrayList<>();
    public List<Frequency> privateCache = new ArrayList<>();
    public TileComponentEjector ejectorComponent;
    public TileComponentConfig configComponent;
    public TileComponentChunkLoader chunkLoaderComponent;
    public TileComponentUpgrade<TileEntityQuantumEntangloporter> upgradeComponent;

    public TileEntityQuantumEntangloporter() {
        super(MekanismBlock.QUANTUM_ENTANGLOPORTER);
        configComponent = new TileComponentConfig(this, TransmissionType.ITEM, TransmissionType.FLUID, TransmissionType.GAS, TransmissionType.ENERGY, TransmissionType.HEAT);

        for (TransmissionType type : TransmissionType.values()) {
            if (type != TransmissionType.HEAT) {
                configComponent.setIOConfig(type);
            } else {
                configComponent.setInputConfig(type);
            }
        }

        configComponent.getOutputs(TransmissionType.ITEM).get(2).availableSlots = new int[]{0};
        configComponent.getOutputs(TransmissionType.FLUID).get(2).availableSlots = new int[]{0};
        configComponent.getOutputs(TransmissionType.GAS).get(2).availableSlots = new int[]{1};

        ejectorComponent = new TileComponentEjector(this);
        ejectorComponent.setOutputData(TransmissionType.ITEM, configComponent.getOutputs(TransmissionType.ITEM).get(2));
        ejectorComponent.setOutputData(TransmissionType.FLUID, configComponent.getOutputs(TransmissionType.FLUID).get(2));
        ejectorComponent.setOutputData(TransmissionType.GAS, configComponent.getOutputs(TransmissionType.GAS).get(2));

        chunkLoaderComponent = new TileComponentChunkLoader(this);

        upgradeComponent = new TileComponentUpgrade<>(this, 0);
        upgradeComponent.clearSupportedTypes();
        upgradeComponent.setSupported(Upgrade.ANCHOR);
    }

    @Override
    public void onUpdate() {
        if (!world.isRemote) {
            if (configComponent.isEjecting(TransmissionType.ENERGY)) {
                CableUtils.emit(this);
            }
            double[] loss = simulateHeat();
            applyTemperatureChange();

            lastTransferLoss = loss[0];
            lastEnvironmentLoss = loss[1];

            FrequencyManager manager = getManager(frequency);
            Frequency lastFreq = frequency;

            if (manager != null) {
                if (frequency != null && !frequency.valid) {
                    frequency = (InventoryFrequency) manager.validateFrequency(getSecurity().getOwnerUUID(), Coord4D.get(this), frequency);
                    markDirty();
                }

                if (frequency != null) {
                    frequency = (InventoryFrequency) manager.update(Coord4D.get(this), frequency);
                    if (frequency == null) {
                        markDirty();
                    }
                }
            } else {
                frequency = null;
                if (lastFreq != null) {
                    markDirty();
                }
            }
        }
    }

    private boolean hasFrequency() {
        return frequency != null && frequency.valid;
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (!world.isRemote) {
            if (frequency != null) {
                FrequencyManager manager = getManager(frequency);
                if (manager != null) {
                    manager.deactivate(Coord4D.get(this));
                }
            }
        }
    }

    @Override
    public Frequency getFrequency(FrequencyManager manager) {
        if (manager == Mekanism.securityFrequencies) {
            return getSecurity().getFrequency();
        }
        return frequency;
    }

    public FrequencyManager getManager(Frequency freq) {
        if (getSecurity().getOwnerUUID() == null || freq == null) {
            return null;
        }
        if (freq.isPublic()) {
            return Mekanism.publicEntangloporters;
        } else if (!Mekanism.privateEntangloporters.containsKey(getSecurity().getOwnerUUID())) {
            FrequencyManager manager = new FrequencyManager(InventoryFrequency.class, InventoryFrequency.ENTANGLOPORTER, getSecurity().getOwnerUUID());
            Mekanism.privateEntangloporters.put(getSecurity().getOwnerUUID(), manager);
            manager.createOrLoad(world);
        }
        return Mekanism.privateEntangloporters.get(getSecurity().getOwnerUUID());
    }

    public void setFrequency(String name, boolean publicFreq) {
        FrequencyManager manager = getManager(new InventoryFrequency(name, null).setPublic(publicFreq));
        manager.deactivate(Coord4D.get(this));
        for (Frequency freq : manager.getFrequencies()) {
            if (freq.name.equals(name)) {
                frequency = (InventoryFrequency) freq;
                frequency.activeCoords.add(Coord4D.get(this));
                markDirty();
                return;
            }
        }

        Frequency freq = new InventoryFrequency(name, getSecurity().getOwnerUUID()).setPublic(publicFreq);
        freq.activeCoords.add(Coord4D.get(this));
        manager.addFrequency(freq);
        frequency = (InventoryFrequency) freq;
        MekanismUtils.saveChunk(this);
        markDirty();
    }

    @Override
    public void read(CompoundNBT nbtTags) {
        super.read(nbtTags);
        if (nbtTags.contains("frequency")) {
            frequency = new InventoryFrequency(nbtTags.getCompound("frequency"));
            frequency.valid = false;
        }

        ListNBT tagList = nbtTags.getList("upgradesInv", Constants.NBT.TAG_COMPOUND);
        //TODO: Given we only have one slot I think we can manually clear or something
        //inventory = NonNullList.withSize(INV_SIZE, ItemStack.EMPTY);
        for (int tagCount = 0; tagCount < tagList.size(); tagCount++) {
            CompoundNBT tagCompound = tagList.getCompound(tagCount);
            byte slotID = tagCompound.getByte("Slot");
            if (slotID >= 0 && slotID < getInventory().size()) {
                getInventory().set(slotID, ItemStack.read(tagCompound));
            }
        }

    }

    @Nonnull
    @Override
    public CompoundNBT write(CompoundNBT nbtTags) {
        super.write(nbtTags);
        if (frequency != null) {
            CompoundNBT frequencyTag = new CompoundNBT();
            frequency.write(frequencyTag);
            nbtTags.put("frequency", frequencyTag);
        }

        //Upgrades inventory
        ListNBT tagList = new ListNBT();
        for (int slotCount = 0; slotCount < getInventory().size(); slotCount++) {
            ItemStack stackInSlot = getInventory().get(slotCount);
            if (!stackInSlot.isEmpty()) {
                CompoundNBT tagCompound = new CompoundNBT();
                tagCompound.putByte("Slot", (byte) slotCount);
                stackInSlot.write(tagCompound);
                tagList.add(tagCompound);
            }
        }
        nbtTags.put("upgradesInv", tagList);
        return nbtTags;
    }

    @Override
    public void handlePacketData(PacketBuffer dataStream) {
        if (!world.isRemote) {
            int type = dataStream.readInt();
            if (type == 0) {
                String name = dataStream.readString();
                boolean isPublic = dataStream.readBoolean();
                setFrequency(name, isPublic);
            } else if (type == 1) {
                String freq = dataStream.readString();
                boolean isPublic = dataStream.readBoolean();
                FrequencyManager manager = getManager(new InventoryFrequency(freq, null).setPublic(isPublic));
                if (manager != null) {
                    manager.remove(freq, getSecurity().getOwnerUUID());
                }
            }
            return;
        }

        super.handlePacketData(dataStream);

        if (world.isRemote) {
            lastTransferLoss = dataStream.readDouble();
            lastEnvironmentLoss = dataStream.readDouble();
            if (dataStream.readBoolean()) {
                frequency = new InventoryFrequency(dataStream);
            } else {
                frequency = null;
            }

            publicCache.clear();
            privateCache.clear();

            int amount = dataStream.readInt();
            for (int i = 0; i < amount; i++) {
                publicCache.add(new InventoryFrequency(dataStream));
            }
            amount = dataStream.readInt();
            for (int i = 0; i < amount; i++) {
                privateCache.add(new InventoryFrequency(dataStream));
            }
        }
    }

    @Override
    public TileNetworkList getNetworkedData(TileNetworkList data) {
        super.getNetworkedData(data);
        data.add(lastTransferLoss);
        data.add(lastEnvironmentLoss);

        if (frequency != null) {
            data.add(true);
            frequency.write(data);
        } else {
            data.add(false);
        }

        data.add(Mekanism.publicEntangloporters.getFrequencies().size());
        for (Frequency freq : Mekanism.publicEntangloporters.getFrequencies()) {
            freq.write(data);
        }

        FrequencyManager manager = getManager(new InventoryFrequency(null, null).setPublic(false));
        if (manager != null) {
            data.add(manager.getFrequencies().size());
            for (Frequency freq : manager.getFrequencies()) {
                freq.write(data);
            }
        } else {
            data.add(0);
        }
        return data;
    }

    @Override
    public boolean canOutputEnergy(Direction side) {
        if (!hasFrequency()) {
            return false;
        }
        return configComponent.hasSideForData(TransmissionType.ENERGY, getDirection(), 2, side);
    }

    @Override
    public boolean canReceiveEnergy(Direction side) {
        if (!hasFrequency()) {
            return false;
        }
        return configComponent.hasSideForData(TransmissionType.ENERGY, getDirection(), 1, side);
    }

    @Override
    public double getMaxOutput() {
        return !hasFrequency() ? 0 : MekanismConfig.current().general.quantumEntangloporterEnergyTransfer.val();
    }

    @Override
    public double getEnergy() {
        return !hasFrequency() ? 0 : frequency.storedEnergy;
    }

    @Override
    public void setEnergy(double energy) {
        if (hasFrequency()) {
            frequency.storedEnergy = Math.min(MekanismConfig.current().general.quantumEntangloporterEnergyTransfer.val(), energy);
        }
    }

    @Override
    public double getMaxEnergy() {
        return !hasFrequency() ? 0 : MekanismConfig.current().general.quantumEntangloporterEnergyTransfer.val();
    }

    @Override
    public int fill(Direction from, @Nonnull FluidStack resource, boolean doFill) {
        return frequency.storedFluid.fill(resource, doFill);
    }

    @Override
    @Nullable
    public FluidStack drain(Direction from, int maxDrain, boolean doDrain) {
        return frequency.storedFluid.drain(maxDrain, doDrain);
    }

    @Override
    public boolean canFill(Direction from, @Nonnull FluidStack fluid) {
        if (hasFrequency() && configComponent.getOutput(TransmissionType.FLUID, from, getDirection()).ioState == IOState.INPUT) {
            return FluidContainerUtils.canFill(frequency.storedFluid.getFluid(), fluid);
        }
        return false;
    }

    @Override
    public boolean canDrain(Direction from, @Nullable FluidStack fluid) {
        if (hasFrequency() && configComponent.getOutput(TransmissionType.FLUID, from, getDirection()).ioState == IOState.OUTPUT) {
            return FluidContainerUtils.canDrain(frequency.storedFluid.getFluid(), fluid);
        }
        return false;
    }

    @Override
    public FluidTankInfo[] getTankInfo(Direction from) {
        if (hasFrequency()) {
            if (configComponent.getOutput(TransmissionType.FLUID, from, getDirection()).ioState != IOState.OFF) {
                return new FluidTankInfo[]{frequency.storedFluid.getInfo()};
            }
        }
        return PipeUtils.EMPTY;
    }

    @Override
    public FluidTankInfo[] getAllTanks() {
        return hasFrequency() ? new FluidTankInfo[]{frequency.storedFluid.getInfo()} : PipeUtils.EMPTY;
    }

    @Override
    public int receiveGas(Direction side, GasStack stack, boolean doTransfer) {
        return !hasFrequency() ? 0 : frequency.storedGas.receive(stack, doTransfer);
    }

    @Override
    public GasStack drawGas(Direction side, int amount, boolean doTransfer) {
        return !hasFrequency() ? null : frequency.storedGas.draw(amount, doTransfer);
    }

    @Override
    public boolean canReceiveGas(Direction side, Gas type) {
        if (hasFrequency() && configComponent.getOutput(TransmissionType.GAS, side, getDirection()).ioState == IOState.INPUT) {
            return frequency.storedGas.getGasType() == null || type == frequency.storedGas.getGasType();
        }
        return false;
    }

    @Override
    public boolean canDrawGas(Direction side, Gas type) {
        if (hasFrequency() && configComponent.getOutput(TransmissionType.GAS, side, getDirection()).ioState == IOState.OUTPUT) {
            return frequency.storedGas.getGasType() == null || type == frequency.storedGas.getGasType();
        }
        return false;
    }

    @Nonnull
    @Override
    public GasTankInfo[] getTankInfo() {
        return hasFrequency() ? new GasTankInfo[]{frequency.storedGas} : IGasHandler.NONE;
    }

    @Override
    public boolean handleInventory() {
        return false;
    }

    @Override
    public NonNullList<ItemStack> getInventory() {
        return hasFrequency() ? frequency.inventory : null;
    }

    @Override
    public double getTemp() {
        return hasFrequency() ? frequency.temperature : 0;
    }

    @Override
    public double getInverseConductionCoefficient() {
        return 1;
    }

    @Override
    public double getInsulationCoefficient(Direction side) {
        return 1000;
    }

    @Override
    public void transferHeatTo(double heat) {
        heatToAbsorb += heat;
    }

    @Override
    public double[] simulateHeat() {
        return HeatUtils.simulate(this);
    }

    @Override
    public double applyTemperatureChange() {
        if (hasFrequency()) {
            frequency.temperature += heatToAbsorb;
        }
        heatToAbsorb = 0;
        return hasFrequency() ? frequency.temperature : 0;
    }

    @Override
    public boolean canConnectHeat(Direction side) {
        return hasFrequency() && configComponent.getOutput(TransmissionType.HEAT, side, getDirection()).ioState != IOState.OFF;
    }

    @Nullable
    @Override
    public IHeatTransfer getAdjacent(Direction side) {
        TileEntity adj = Coord4D.get(this).offset(side).getTileEntity(world);
        if (hasFrequency() && configComponent.getOutput(TransmissionType.HEAT, side, getDirection()).ioState == IOState.INPUT) {
            return CapabilityUtils.getCapabilityHelper(adj, Capabilities.HEAT_TRANSFER_CAPABILITY, side.getOpposite()).getValue();
        }
        return null;
    }

    @Override
    public boolean canInsertItem(int slotID, @Nonnull ItemStack itemstack, @Nonnull Direction side) {
        return hasFrequency() && configComponent.getOutput(TransmissionType.ITEM, side, getDirection()).ioState == IOState.INPUT;
    }

    @Nonnull
    @Override
    public int[] getSlotsForFace(@Nonnull Direction side) {
        if (hasFrequency() && configComponent.getOutput(TransmissionType.ITEM, side, getDirection()).ioState != IOState.OFF) {
            return new int[]{0};
        }
        return InventoryUtils.EMPTY;
    }

    @Override
    public boolean canExtractItem(int slotID, @Nonnull ItemStack itemstack, @Nonnull Direction side) {
        return hasFrequency() && configComponent.getOutput(TransmissionType.ITEM, side, getDirection()).ioState == IOState.OUTPUT;
    }

    @Override
    public Object[] getTanks() {
        if (!hasFrequency()) {
            return null;
        }
        return new Object[]{frequency.storedFluid, frequency.storedGas};
    }

    @Override
    public TileComponentConfig getConfig() {
        return configComponent;
    }

    @Override
    public Direction getOrientation() {
        return getDirection();
    }

    @Override
    public TileComponentEjector getEjector() {
        return ejectorComponent;
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
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.orEmpty(capability, LazyOptional.of(() -> new FluidHandlerWrapper(this, side)));
        }
        return super.getCapability(capability, side);
    }

    @Override
    public boolean isCapabilityDisabled(@Nonnull Capability<?> capability, Direction side) {
        if (configComponent.isCapabilityDisabled(capability, side, getDirection())) {
            return true;
        } else if (capability == Capabilities.GAS_HANDLER_CAPABILITY || capability == Capabilities.HEAT_TRANSFER_CAPABILITY ||
                   capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return side != null && (!hasFrequency() || configComponent.isCapabilityDisabled(capability, side, getDirection()));
        }
        return super.isCapabilityDisabled(capability, side);
    }

    @Override
    public String[] getMethods() {
        return methods;
    }

    @Override
    public Object[] invoke(int method, Object[] arguments) throws NoSuchMethodException {
        if (method == 0) {
            if (!(arguments[0] instanceof String) || !(arguments[1] instanceof Boolean)) {
                return new Object[]{"Invalid parameters."};
            }
            String freq = ((String) arguments[0]).trim();
            boolean isPublic = (Boolean) arguments[1];
            setFrequency(freq, isPublic);
            return new Object[]{"Frequency set."};
        }
        throw new NoSuchMethodException();
    }

    @Override
    public TileComponentChunkLoader getChunkLoader() {
        return chunkLoaderComponent;
    }

    @Override
    public Set<ChunkPos> getChunkSet() {
        Set<ChunkPos> ret = new HashSet<>();
        ret.add(new Chunk3D(Coord4D.get(this)).getPos());
        return ret;
    }

    @Override
    public TileComponentUpgrade getComponent() {
        return this.upgradeComponent;
    }

    @Override
    public void recalculateUpgrades(Upgrade upgradeType) {

    }
}