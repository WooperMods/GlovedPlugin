package dev.gloved.glovedplugin.summon.soul;

public record SoulAffixRoll(SoulAffixType type, double value) {
    public SoulAffixRoll {
        if (type == null) throw new IllegalArgumentException("type");
        if (Double.isNaN(value) || Double.isInfinite(value)) value = 0.0;
    }
}
