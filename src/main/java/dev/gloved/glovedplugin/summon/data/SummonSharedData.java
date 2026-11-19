package dev.gloved.glovedplugin.summon.data;

import java.util.UUID;

/**
 * Shared state for an NMS-backed summon entity (owner, combat mode, soul link).
 * Combat modes are only DEFENSIVE and AGGRESSIVE (no passive mode).
 */
public class SummonSharedData {
    private final UUID ownerId;
    private String combatMode = "AGGRESSIVE";
    private UUID soulId;
    private String profileName;

    public SummonSharedData(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getCombatMode() {
        return combatMode;
    }

    public void setCombatMode(String combatMode) {
        this.combatMode = normalizeCombatMode(combatMode);
    }

    public static String normalizeCombatMode(String combatMode) {
        if (combatMode == null) return "AGGRESSIVE";
        return "DEFENSIVE".equalsIgnoreCase(combatMode.trim()) ? "DEFENSIVE" : "AGGRESSIVE";
    }

    public UUID getSoulId() {
        return soulId;
    }

    public void setSoulId(UUID soulId) {
        this.soulId = soulId;
    }

    public String getProfileName() {
        return profileName;
    }

    public void setProfileName(String profileName) {
        this.profileName = profileName;
    }
}
