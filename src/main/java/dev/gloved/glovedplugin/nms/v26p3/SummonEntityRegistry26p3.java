package dev.gloved.glovedplugin.nms.v26p3;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.SummonEntityInjector;
import dev.gloved.glovedplugin.nms.v26p3.entities.*;
import org.bukkit.entity.EntityType;

public final class SummonEntityRegistry26p3 {
    private SummonEntityRegistry26p3() {}

    public static void registerAll(GlovedPlugin plugin) {
        SummonEntityInjector.register(EntityType.ALLAY, (level, data) -> new AllaySummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.ARMADILLO, (level, data) -> new ArmadilloSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.AXOLOTL, (level, data) -> new AxolotlSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.BEE, (level, data) -> new BeeSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.BLAZE, (level, data) -> new BlazeSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.BOGGED, (level, data) -> new BoggedSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.BREEZE, (level, data) -> new BreezeSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.CAT, (level, data) -> new CatSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.CAVE_SPIDER, (level, data) -> new CaveSpiderSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.CHICKEN, (level, data) -> new ChickenSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.COW, (level, data) -> new CowSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.CREAKING, (level, data) -> new CreakingSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.CREEPER, (level, data) -> new CreeperSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.DONKEY, (level, data) -> new DonkeySummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.DROWNED, (level, data) -> new DrownedSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.ENDERMAN, (level, data) -> new EndermanSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.ENDERMITE, (level, data) -> new EndermiteSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.EVOKER, (level, data) -> new EvokerSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.FOX, (level, data) -> new FoxSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.FROG, (level, data) -> new FrogSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.GHAST, (level, data) -> new GhastSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.GOAT, (level, data) -> new GoatSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.HOGLIN, (level, data) -> new HoglinSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.HORSE, (level, data) -> new HorseSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.HUSK, (level, data) -> new HuskSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.ILLUSIONER, (level, data) -> new IllusionerSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.IRON_GOLEM, (level, data) -> new IronGolemSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.LLAMA, (level, data) -> new LlamaSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.MAGMA_CUBE, (level, data) -> new MagmaCubeSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.MOOSHROOM, (level, data) -> new MushroomCowSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.MULE, (level, data) -> new MuleSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.OCELOT, (level, data) -> new OcelotSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.PANDA, (level, data) -> new PandaSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.PHANTOM, (level, data) -> new PhantomSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.PIG, (level, data) -> new PigSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.PIGLIN, (level, data) -> new PiglinSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.PIGLIN_BRUTE, (level, data) -> new PiglinBruteSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.PILLAGER, (level, data) -> new PillagerSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.POLAR_BEAR, (level, data) -> new PolarBearSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.RABBIT, (level, data) -> new RabbitSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.RAVAGER, (level, data) -> new RavagerSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.SHEEP, (level, data) -> new SheepSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.SILVERFISH, (level, data) -> new SilverfishSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.SKELETON, (level, data) -> new SkeletonSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.SKELETON_HORSE, (level, data) -> new SkeletonHorseSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.SLIME, (level, data) -> new SlimeSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.SNIFFER, (level, data) -> new SnifferSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.SNOW_GOLEM, (level, data) -> new SnowGolemSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.SPIDER, (level, data) -> new SpiderSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.STRAY, (level, data) -> new StraySummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.TRADER_LLAMA, (level, data) -> new TraderLlamaSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.TURTLE, (level, data) -> new TurtleSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.VEX, (level, data) -> new VexSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.VILLAGER, (level, data) -> new VillagerSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.VINDICATOR, (level, data) -> new VindicatorSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.WANDERING_TRADER, (level, data) -> new WanderingTraderSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.WARDEN, (level, data) -> new WardenSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.WITCH, (level, data) -> new WitchSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.WITHER, (level, data) -> new WitherBossSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.WITHER_SKELETON, (level, data) -> new WitherSkeletonSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.WOLF, (level, data) -> new WolfSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.ZOGLIN, (level, data) -> new ZoglinSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.ZOMBIE, (level, data) -> new ZombieSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.ZOMBIE_HORSE, (level, data) -> new ZombieHorseSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.ZOMBIE_VILLAGER, (level, data) -> new ZombieVillagerSummon(level, data, plugin));
        SummonEntityInjector.register(EntityType.ZOMBIFIED_PIGLIN, (level, data) -> new ZombifiedPiglinSummon(level, data, plugin));
    }
}
