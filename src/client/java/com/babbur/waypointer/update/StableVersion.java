package com.babbur.waypointer.update;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record StableVersion(int major, int minor, int patch) implements Comparable<StableVersion> {

    private static final Pattern PATTERN = Pattern.compile(
            "^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$");

    static Optional<StableVersion> parse(String value) {
        if (value == null) return Optional.empty();
        Matcher matcher = PATTERN.matcher(value.strip());
        if (!matcher.matches()) return Optional.empty();

        try {
            return Optional.of(new StableVersion(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(StableVersion other) {
        int majorOrder = Integer.compare(major, other.major);
        if (majorOrder != 0) return majorOrder;
        int minorOrder = Integer.compare(minor, other.minor);
        if (minorOrder != 0) return minorOrder;
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
