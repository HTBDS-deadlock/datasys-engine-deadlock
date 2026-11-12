package dk.itu.datasys;

import java.util.List;

/**
 * A SELECT's operator pipeline, plus the scan statistics the planning
 * decided -- one ScanStats per table scanned, in FROM-list order.
 */
public record Plan(Operator root, List<ScanStats> stats) {
}
