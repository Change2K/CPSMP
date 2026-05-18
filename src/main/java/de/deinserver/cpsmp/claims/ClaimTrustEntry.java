package de.deinserver.cpsmp.claims;

import java.util.UUID;

public record ClaimTrustEntry(long claimId, UUID trustedUuid, String trustedName, long createdAt) {
}
