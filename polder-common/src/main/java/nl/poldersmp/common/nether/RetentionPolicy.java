package nl.poldersmp.common.nether;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which old Nether backups may be deleted. Rules:
 * <ul>
 *   <li>Only backups marked valid and verified count as "successful"; others are never auto-deleted.
 *   <li>The newest {@code keep} valid backups stay (default 3, never fewer than 1).
 *   <li>The latest valid recovery backup is never selected, whatever the setting says.
 * </ul>
 */
public final class RetentionPolicy {

  private RetentionPolicy() {}

  public record BackupInfo(Path dir, long createdMillis, boolean valid) {}

  /** @return backups that may be deleted, oldest first */
  public static List<BackupInfo> selectForDeletion(List<BackupInfo> backups, int keep) {
    int effectiveKeep = Math.max(1, keep);
    List<BackupInfo> valid = new ArrayList<>();
    for (BackupInfo b : backups) {
      if (b.valid()) {
        valid.add(b);
      }
    }
    valid.sort(Comparator.comparingLong(BackupInfo::createdMillis).reversed());
    if (valid.size() <= effectiveKeep) {
      return List.of();
    }
    List<BackupInfo> doomed = new ArrayList<>(valid.subList(effectiveKeep, valid.size()));
    doomed.sort(Comparator.comparingLong(BackupInfo::createdMillis));
    return doomed;
  }

  /** Lists the backup folders (one level deep) in a backup root. */
  public static List<BackupInfo> list(Path backupRoot) throws IOException {
    List<BackupInfo> out = new ArrayList<>();
    if (Files.notExists(backupRoot)) {
      return out;
    }
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(backupRoot)) {
      for (Path dir : entries) {
        if (!Files.isDirectory(dir) || Files.isSymbolicLink(dir)) {
          continue;
        }
        long created = Files.getLastModifiedTime(dir).toMillis();
        out.add(new BackupInfo(dir, created, BackupService.isMarkedValid(dir)));
      }
    }
    return out;
  }
}
