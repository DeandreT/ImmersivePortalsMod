package qouteall.imm_ptl.peripheral.riftwoven.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Draft(UUID id, UUID owner, List<Contribution> contributions) {
    public Draft { contributions = List.copyOf(contributions); }

    public Map<String, Integer> recipe() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Contribution contribution : contributions) result.merge(contribution.item(), contribution.count(), Math::addExact);
        return Map.copyOf(result);
    }
}
