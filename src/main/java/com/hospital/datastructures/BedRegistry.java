package com.hospital.datastructures;

import com.hospital.model.Bed;
import com.hospital.model.BedStatus;
import com.hospital.model.BedType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bed inventory.
 * <ul>
 *   <li>{@link ArrayList} keeps the beds in display order for the bed grid.</li>
 *   <li>{@link HashMap} gives O(1) lookup by bed id.</li>
 *   <li>{@link EnumMap} groups beds by type so the matcher only scans relevant wards.</li>
 * </ul>
 */
public class BedRegistry {

    private final List<Bed> beds = new ArrayList<>();
    private final Map<String, Bed> byId = new HashMap<>();
    private final Map<BedType, List<Bed>> byType = new EnumMap<>(BedType.class);

    public BedRegistry() {
        for (BedType type : BedType.values()) {
            byType.put(type, new ArrayList<>());
        }
    }

    public void add(Bed bed) {
        if (byId.containsKey(bed.getBedId())) {
            throw new IllegalArgumentException("Duplicate bed id " + bed.getBedId());
        }
        beds.add(bed);
        byId.put(bed.getBedId(), bed);
        byType.get(bed.getType()).add(bed);
    }

    public Bed findById(String bedId) { return byId.get(bedId); }

    public List<Bed> all() { return Collections.unmodifiableList(beds); }

    public List<Bed> ofType(BedType type) { return Collections.unmodifiableList(byType.get(type)); }

    public List<Bed> availableOfType(BedType type) {
        List<Bed> result = new ArrayList<>();
        for (Bed bed : byType.get(type)) {
            if (bed.isAvailable()) result.add(bed);
        }
        return result;
    }

    public int count(BedStatus status) {
        int n = 0;
        for (Bed bed : beds) {
            if (bed.getStatus() == status) n++;
        }
        return n;
    }

    public int count(BedType type, BedStatus status) {
        int n = 0;
        for (Bed bed : byType.get(type)) {
            if (bed.getStatus() == status) n++;
        }
        return n;
    }

    public int size() { return beds.size(); }

    public void clear() {
        beds.clear();
        byId.clear();
        byType.values().forEach(List::clear);
    }
}
