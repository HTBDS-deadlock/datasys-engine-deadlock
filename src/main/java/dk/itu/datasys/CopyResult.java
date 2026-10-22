package dk.itu.datasys;

/** Rows copied and partitions created by one COPY call, and the resulting version. */
public record CopyResult(int rows, int partitions, int version) {
}
