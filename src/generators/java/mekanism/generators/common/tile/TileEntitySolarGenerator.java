package mekanism.generators.common.tile;

import javax.annotation.Nonnull;
import mekanism.api.providers.IBlockProvider;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableBoolean;
import mekanism.common.inventory.container.sync.SyncableDouble;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.util.MekanismUtils;
import mekanism.generators.common.config.MekanismGeneratorsConfig;
import mekanism.generators.common.registries.GeneratorsBlocks;
import net.minecraft.util.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.Biome.RainType;

public class TileEntitySolarGenerator extends TileEntityGenerator {

    private static final String[] methods = new String[]{"getEnergy", "getOutput", "getMaxEnergy", "getEnergyNeeded", "getSeesSun"};

    private boolean seesSun;
    private boolean needsRainCheck = true;
    private double peakOutput;
    private boolean settingsChecked;
    private double lastProductionAmount;

    private EnergyInventorySlot energySlot;

    public TileEntitySolarGenerator() {
        this(GeneratorsBlocks.SOLAR_GENERATOR, MekanismGeneratorsConfig.generators.solarGeneration.get() * 2);
    }

    public TileEntitySolarGenerator(IBlockProvider blockProvider, double output) {
        super(blockProvider, output);
    }

    @Nonnull
    @Override
    protected IInventorySlotHolder getInitialInventory() {
        InventorySlotHelper builder = InventorySlotHelper.forSide(this::getDirection);
        builder.addSlot(energySlot = EnergyInventorySlot.charge(this, 143, 35));
        return builder.build();
    }

    public boolean canSeeSun() {
        return seesSun;
    }

    protected void recheckSettings() {
        World world = getWorld();
        if (world == null) {
            return;
        }
        Biome b = world.getBiomeManager().getBiome(getPos());

        // Consider the best temperature to be 0.8; biomes that are higher than that
        // will suffer an efficiency loss (semiconductors don't like heat); biomes that are cooler
        // get a boost. We scale the efficiency to around 30% so that it doesn't totally dominate
        float tempEff = 0.3F * (0.8F - b.getTemperature(getPos()));

        // Treat rainfall as a proxy for humidity; any humidity works as a drag on overall efficiency.
        // As with temperature, we scale it so that it doesn't overwhelm production. Note the signedness
        // on the scaling factor. Also note that we only use rainfall as a proxy if it CAN rain; some dimensions
        // (like the End) have rainfall set, but can't actually support rain.
        float humidityEff = -0.3F * (b.getPrecipitation() != RainType.NONE ? b.getDownfall() : 0.0F);

        peakOutput = getConfiguredMax() * (1.0F + tempEff + humidityEff);
        needsRainCheck = b.getPrecipitation() != RainType.NONE;

        settingsChecked = true;
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (!isRemote()) {
            if (!settingsChecked) {
                recheckSettings();
            }
            energySlot.charge(this);
            // Sort out if the generator can see the sun; we no longer check if it's raining here,
            // since under the new rules, we can still generate power when it's raining, albeit at a
            // significant penalty.
            World world = getWorld();
            if (world != null) {
                seesSun = world.isDaytime() && canSeeSky() && !world.getDimension().isNether();
            }

            if (canOperate()) {
                setActive(true);
                lastProductionAmount = getProduction();
                setEnergy(getEnergy() + lastProductionAmount);
            } else {
                setActive(false);
                lastProductionAmount = 0;
            }
        }
    }

    protected boolean canSeeSky() {
        World world = getWorld();
        return world != null && world.canBlockSeeSky(getPos());
    }

    @Override
    public boolean canOperate() {
        return getEnergy() < getMaxEnergy() && seesSun && MekanismUtils.canFunction(this);
    }

    public double getProduction() {
        World world = getWorld();
        if (world == null) {
            return 0;
        }
        // Get the brightness of the sun; note that there are some implementations that depend on the base
        // brightness function which doesn't take into account the fact that rain can't occur in some biomes.
        float brightness = getSunBrightness(world, 1.0F);
        //TODO: Galacticraft
        /*if (MekanismUtils.existsAndInstance(world.provider, "micdoodle8.mods.galacticraft.api.world.ISolarLevel")) {
            brightness *= ((ISolarLevel) world.provider).getSolarEnergyMultiplier();
        }*/

        // Production is a function of the peak possible output in this biome and sun's current brightness
        double production = peakOutput * brightness;

        // If the generator is in a biome where it can rain and it's raining penalize production by 80%
        if (needsRainCheck && (world.isRaining() || world.isThundering())) {
            production *= 0.2;
        }
        return production;
    }

    //TODO: re-evaluate
    //Vanilla copy of ClientWorld#getSunBrightness used to be World#getSunBrightness
    private float getSunBrightness(World world, float partialTicks) {
        float f = world.getCelestialAngle(partialTicks);
        float f1 = 1.0F - (MathHelper.cos(f * ((float) Math.PI * 2F)) * 2.0F + 0.2F);
        f1 = MathHelper.clamp(f1, 0.0F, 1.0F);
        f1 = 1.0F - f1;
        f1 = (float) ((double) f1 * (1.0D - (double) (world.getRainStrength(partialTicks) * 5.0F) / 16.0D));
        f1 = (float) ((double) f1 * (1.0D - (double) (world.getThunderStrength(partialTicks) * 5.0F) / 16.0D));
        return f1 * 0.8F + 0.2F;
    }

    @Override
    public String[] getMethods() {
        return methods;
    }

    @Override
    public Object[] invoke(int method, Object[] arguments) throws NoSuchMethodException {
        switch (method) {
            case 0:
                return new Object[]{getEnergy()};
            case 1:
                return new Object[]{output};
            case 2:
                return new Object[]{getBaseStorage()};
            case 3:
                return new Object[]{getBaseStorage() - getEnergy()};
            case 4:
                return new Object[]{seesSun};
            default:
                throw new NoSuchMethodException();
        }
    }

    @Override
    public boolean canOutputEnergy(Direction side) {
        return side == Direction.DOWN;
    }

    protected double getConfiguredMax() {
        return MekanismGeneratorsConfig.generators.solarGeneration.get();
    }

    @Override
    public double getMaxOutput() {
        return peakOutput;
    }

    public double getLastProductionAmount() {
        return lastProductionAmount;
    }

    @Override
    public void addContainerTrackers(MekanismContainer container) {
        super.addContainerTrackers(container);
        container.track(SyncableBoolean.create(this::canSeeSun, value -> seesSun = value));
        container.track(SyncableDouble.create(this::getMaxOutput, value -> peakOutput = value));
        container.track(SyncableDouble.create(this::getLastProductionAmount, value -> lastProductionAmount = value));
    }
}