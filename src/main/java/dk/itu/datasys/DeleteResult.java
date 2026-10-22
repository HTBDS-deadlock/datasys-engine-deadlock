package dk.itu.datasys;

/**
 * Rows deleted, partitions physically rewritten, and partitions carried over
 * unchanged by one DELETE call, and the resulting version (unchanged from
 * before the call if rowsDeleted is 0).
 */
public record DeleteResult(int rowsDeleted, int partitionsRewritten, int partitionsCarried, int version) {
}
