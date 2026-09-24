package dk.itu.datasys;

/** A SELECT's operator pipeline, plus the scan statistics the planning decided. */
public record Plan(Operator root, ScanStats stats) {
}
