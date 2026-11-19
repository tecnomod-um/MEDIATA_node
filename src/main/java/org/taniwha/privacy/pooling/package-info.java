/**
 * Protected statistical releases and secure aggregation of those releases.
 *
 * <p>{@link org.taniwha.privacy.pooling.PrivatePoolingService} generates and
 * durably reuses noisy histograms. Its allowance is per dataset content version;
 * it does not cover exact Discovery outputs or the same patient in overlapping
 * files and revisions. The default site allowance of 80 favors utility and
 * provides weak row-level protection.
 *
 * <p>{@link org.taniwha.privacy.pooling.SecurePoolInputs} maintains independently
 * protected inputs with a separate allowance. {@link
 * org.taniwha.privacy.pooling.SecurePoolService} masks and aggregates them using
 * a trusted participant registry. This assumes non-colluding contributors and
 * requires all participants; cryptographic masking does not strengthen epsilon.
 * Exact descriptive aggregation and postprocessing of released distributions
 * are separate from the node's noise generation, accounting and private keys.
 */
package org.taniwha.privacy.pooling;
