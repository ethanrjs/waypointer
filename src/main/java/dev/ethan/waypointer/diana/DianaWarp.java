package dev.ethan.waypointer.diana;

public enum DianaWarp {
    HUB("hub", "Hub", "warp hub", 0, 77, 0, true),
    CASTLE("castle", "Castle", "warp castle", -250, 130, 45, true),
    MUSEUM("museum", "Museum", "warp museum", 29, 72, 1, true),
    WIZARD("wizard", "Wizard", "warp wizard", 44, 119, 93, true),
    STONKS("stonks", "Stonks", "warp stonks", -36, 70, -81, true),
    DA("da", "Dark Auction", "warp da", 91, 75, 174, false),
    CRYPT("crypt", "Crypt", "warp crypt", -160, 62, -106, false);

    private final String id;
    private final String label;
    private final String command;
    private final int x;
    private final int y;
    private final int z;
    private final boolean defaultEnabled;

    DianaWarp(String id, String label, String command, int x, int y, int z, boolean defaultEnabled) {
        this.id = id;
        this.label = label;
        this.command = command;
        this.x = x;
        this.y = y;
        this.z = z;
        this.defaultEnabled = defaultEnabled;
    }

    public String id() { return id; }
    public String label() { return label; }
    public String command() { return command; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public boolean defaultEnabled() { return defaultEnabled; }
}
