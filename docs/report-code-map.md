# Report → code map (exam crib sheet)

All paths under `src/main/java/dk/itu/datasys/`.

## Core data structures (report §1)
| Report concept | Code |
|---|---|
| Table metadata + version number | `StorageEngine.java:1430` `TableSchema` (columns, dataFile, partitions, version) |
| Current version per table | `StorageEngine.java:78` `tables` |
| All committed versions | `StorageEngine.java:79` `versionHistory` |
| A pinned read view | `StorageEngine.java:1407` `TableSnapshot` |
| Pin latest / pin specific version | `StorageEngine.java:801` `snapshot(name)` / `:814` `snapshot(name, version)` |
| Publish a new version | `StorageEngine.java:896` `publishSchema` |

## Commit + conflicts (report §2.1)
| Report concept | Code |
|---|---|
| Validate base version, first committer wins | `StorageEngine.java:902` `commitSchema` |
| Metadata lock | `StorageEngine.java:70` `commitLock` |
| Data-file append lock | `StorageEngine.java:71` `partitionWriteLock` |
| Test seam | `StorageEngine.java:76` `beforeCommit` |
| Conflict error | `ConflictException.java` |

## Rebase (report §2.2)
| Report concept | Code |
|---|---|
| Max 3 attempts | `StorageEngine.java:52` `MAX_COMMIT_ATTEMPTS` |
| COPY rebase (merge own partitions onto fresh base) | `StorageEngine.java:917` `commitCopy` |
| DELETE rebase (recompute from fresh snapshot) | `StorageEngine.java:444` `delete` |

## Persistence (report §3)
| Report concept | Code |
|---|---|
| catalog.json via temp file + atomic rename | `StorageEngine.java:1239-1241` |
