package dk.itu.datasys;

public final class ConflictException extends RuntimeException {
    private final String tableName;
    private final int baseVersion;
    private final int currentVersion;

    public ConflictException(String tableName, int baseVersion, int currentVersion) {
        super("conflict table=" + tableName + " base=" + baseVersion + " current=" + currentVersion);
        this.tableName = tableName;
        this.baseVersion = baseVersion;
        this.currentVersion = currentVersion;
    }

    public String tableName() {
        return tableName;
    }

    public int baseVersion() {
        return baseVersion;
    }

    public int currentVersion() {
        return currentVersion;
    }
}
