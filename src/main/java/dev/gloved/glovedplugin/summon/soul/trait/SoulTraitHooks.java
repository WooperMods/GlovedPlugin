package dev.gloved.glovedplugin.summon.soul.trait;

import dev.gloved.glovedplugin.summon.soul.trait.ctx.*;

/** Behavior hooks for a trait. All methods are no-op by default. */
public interface SoulTraitHooks {
    SoulTraitHooks NONE = new SoulTraitHooks() {};

    default void modifyOutgoingDamage(OutgoingDamageContext ctx) {}
    default void afterPrimaryHit(HitContext ctx) {}
    default void modifyIncomingDamage(IncomingDamageContext ctx) {}
    default void modifyCrit(CritContext ctx) {}
    default void modifyAbility(AbilityContext ctx) {}
    default void contributePreview(PreviewContext ctx) {}
}
