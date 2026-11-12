package dev.gloved.glovedplugin.summon.data;

import java.util.UUID;

/**
 * Shared state for an NMS-backed summon entity (owner, combat mode, soul link).
 */
public class SummonSharedData {
    private final UUID ownerId;
    private String combatMode = "PASSIVE";
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
        this.combatMode = combatMode == null ? "PASSIVE" : combatMode;
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
