package dk.itu.datasys;

import java.util.List;

/**
 * A SELECT's operator pipeline, plus the scan statistics the planning
 * decided -- one ScanStats per table scanned, in FROM-list order.
 *
 * joinOrder is the order the tables are joined in (aliases), mode the join
 * mode that chose it, and shape the plan tree with the build input first in
 * every join, e.g. {@code J(products J(J(cities customers) orders))}.
 */
public record Plan(Operator root, List<ScanStats> stats, List<String> joinOrder, JoinMode mode, String shape) {
}
