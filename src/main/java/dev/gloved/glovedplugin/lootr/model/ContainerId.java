package dev.gloved.glovedplugin.lootr.model;

import java.util.Objects;
import java.util.UUID;

public final class ContainerId {
    private final UUID world;
    private final int x, y, z;

    public ContainerId(UUID world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public UUID world() { return world; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }

    public String toStringKey() {
        return world.toString() + ":" + x + ":" + y + ":" + z;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContainerId)) return false;
        ContainerId that = (ContainerId) o;
        return x == that.x && y == that.y && z == that.z && world.equals(that.world);
    }
    @Override public int hashCode() { return Objects.hash(world, x, y, z); }
}
