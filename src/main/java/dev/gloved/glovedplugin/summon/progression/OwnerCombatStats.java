package dev.gloved.glovedplugin.summon.progression;

/** Snapshot of owner AuraSkills combat stats relevant to minions (cached by resolver). */
public record OwnerCombatStats(
        boolean available,
        double critChancePercentPoints,   // AuraSkills crit_chance stat level (1 point ~= 1%)
        double critDamageTrait,           // effective crit_damage trait/bonus (percent points, base 0 typical)
        double attackDamageTrait,         // effective attack-damage trait from Strength (for minion damage bonus)
        double strengthStatLevel,         // AuraSkills strength stat level
        double healthStatLevel,           // AuraSkills health stat level (max-health bonus for minions)
        double toughnessStatLevel         // AuraSkills toughness stat level (minion damage reduction)
) {
    public static OwnerCombatStats unavailable() {
        return new OwnerCombatStats(false, 0, 0, 0, 0, 0, 0);
    }
}
