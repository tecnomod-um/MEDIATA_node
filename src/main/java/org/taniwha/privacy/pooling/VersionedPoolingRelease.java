package org.taniwha.privacy.pooling;

/** Internal identity of the immutable input used for this protected release. */
record VersionedPoolingRelease(String contentHash,PrivatePoolingRelease release) {}
