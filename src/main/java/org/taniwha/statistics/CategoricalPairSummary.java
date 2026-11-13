package org.taniwha.statistics;

import java.util.List;

/** Complete, disclosure-checked contingency table for a released feature pair. */
public record CategoricalPairSummary(String featureX, String featureY, long count, List<CategoricalPairCount> frequencies) {}
