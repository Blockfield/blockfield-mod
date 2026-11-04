package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.network.packet.WorkshopAiPlanChunkS2CPacket;
import com.lunares.blockfield.workshop.WorkshopGeometry;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Ordered, bounded reassembly; stale and inconsistent transfers never render partial plans. */
final class WorkshopAiPlanTransfer {
    record Complete(WorkshopAiPlanChunkS2CPacket header, String plan) {}

    private static final class Pending {
        final WorkshopAiPlanChunkS2CPacket header;
        final StringBuilder text = new StringBuilder();
        final long deadline;
        int next;

        Pending(WorkshopAiPlanChunkS2CPacket header, long now) {
            this.header = header;
            deadline = now + 20_000;
        }
    }

    private final Map<UUID, Pending> pending = new HashMap<>();

    void clear() {
        pending.clear();
    }

    Complete accept(WorkshopAiPlanChunkS2CPacket p, long now) {
        pending.values().removeIf(v -> now > v.deadline);
        if (p.index() == 0) {
            pending.values()
                    .removeIf(
                            v ->
                                    v.header.ownerId().equals(p.ownerId())
                                            && v.header.jobId().equals(p.jobId())
                                            && v.header.hand() == p.hand());
            if (pending.size() >= 6) return null;
            pending.put(p.transferId(), new Pending(p, now));
        }
        Pending v = pending.get(p.transferId());
        if (v == null) return null;
        var h = v.header;
        boolean same =
                h.ownerId().equals(p.ownerId())
                        && h.jobId().equals(p.jobId())
                        && h.origin().equals(p.origin())
                        && h.name().equals(p.name())
                        && h.sx() == p.sx()
                        && h.sy() == p.sy()
                        && h.sz() == p.sz()
                        && h.rotY() == p.rotY()
                        && h.mirror() == p.mirror()
                        && h.hand() == p.hand()
                        && h.count() == p.count();
        if (!same
                || p.index() != v.next
                || v.text.length() + p.data().length() > WorkshopGeometry.MAX_MERGED_JSON) {
            pending.remove(p.transferId());
            return null;
        }
        v.text.append(p.data());
        v.next++;
        if (v.next < p.count()) return null;
        pending.remove(p.transferId());
        return new Complete(h, v.text.toString());
    }
}
