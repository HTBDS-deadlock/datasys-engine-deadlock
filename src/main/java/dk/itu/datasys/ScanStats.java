package dk.itu.datasys;
// gives us the number of partitions in a table, how many were read and how many were pruned
public record ScanStats(int partitionsTotal, int partitionsRead, int partitionsPruned) {}
