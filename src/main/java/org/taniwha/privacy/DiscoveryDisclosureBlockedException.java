package org.taniwha.privacy;

public final class DiscoveryDisclosureBlockedException extends RuntimeException {
    public DiscoveryDisclosureBlockedException() {
        super(DiscoveryQueryLedger.BLOCKED);
    }
}
