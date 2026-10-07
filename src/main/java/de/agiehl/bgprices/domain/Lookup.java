package de.agiehl.bgprices.domain;

public record Lookup(String originalName, String normalizedName, Long bggId, String key) { }
