package org.taniwha.statistics;

/** A frequency cell, never an individual observation. */
public record NumericValueCount(double value, long count) {}
