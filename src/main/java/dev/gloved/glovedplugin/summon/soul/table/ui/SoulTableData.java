package dev.gloved.glovedplugin.summon.soul.table.ui;

import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulQuality;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Loads and sorts owned souls for Soul Table screens, mirroring legacy SoulTableInv parity. */
public final class SoulTableData {

    public record Snapshot(List<SoulInstance> souls, Map<UUID, SoulInstance> index, int essence) {
        public static Snapshot empty() {
            return new Snapshot(List.of(), Map.of(), 0);
        }
    }

    private SoulTableData() {
    }

    public static Snapshot load(SoulService svc, UUID viewerId) {
        if (svc == null || viewerId == null) return Snapshot.empty();
        List<SoulInstance> souls = new ArrayList<>();
        for (int p = 0; p < 40; p++) {
            List<SoulInstance> chunk = svc.listSoulsFiltered(viewerId, null, null, p, 50);
            souls.addAll(chunk);
            if (chunk.size() < 50) break;
        }
        souls.sort(Comparator
                .comparingInt((SoulInstance s) -> s.getRarity().ordinal()).reversed()
                .thenComparing(Comparator.comparingDouble((SoulInstance s) -> SoulQuality.percent(s)).reversed())
                .thenComparing(Comparator.comparingLong((SoulInstance s) -> s.getCreatedAt()).reversed()));
        Map<UUID, SoulInstance> index = new HashMap<>(Math.max(16, souls.size() * 2));
        for (SoulInstance soul : souls) index.put(soul.getSoulId(), soul);
        int essence = svc.getEssence(viewerId);
        return new Snapshot(List.copyOf(souls), Map.copyOf(index), essence);
    }

    public static void pruneSelection(Collection<UUID> selected, Map<UUID, SoulInstance> index) {
        if (selected == null || index == null) return;
        selected.removeIf(id -> !index.containsKey(id));
    }
}
