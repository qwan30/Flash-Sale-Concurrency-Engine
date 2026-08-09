package com.xxxx.ddd.application.reservation.port;

import com.xxxx.ddd.domain.reservation.InventorySnapshot;

import java.util.Optional;
import java.util.OptionalLong;

public interface InventoryRepository {

    Optional<InventorySnapshot> findSnapshot(long ticketItemId);

    OptionalLong findFenceVersion(long ticketItemId);

    boolean decrementIfAvailable(long ticketItemId, int quantity, long fenceVersion);

    boolean restoreIfAdmitted(long ticketItemId, int quantity, long fenceVersion);
}
