package dev.ethan.waypointer.diana;

public enum DianaBurrowType {
    START("Start Burrow", 0x4FE05A),
    MOB("Mob Burrow", 0xFF4040),
    TREASURE("Treasure Burrow", 0xFFB02E),
    GUESS("Burrow", 0x1E5E32);

    private final String label;
    private final int color;

    DianaBurrowType(String label, int color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return label;
    }

    public int color() {
        return color;
    }
}
