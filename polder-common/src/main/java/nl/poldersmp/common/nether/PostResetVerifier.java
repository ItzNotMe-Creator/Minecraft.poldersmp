package nl.poldersmp.common.nether;

import java.util.ArrayList;
import java.util.List;

/**
 * Judges whether a reset really worked, from facts the plugin reads after the restart. The seed
 * check is the important one: the world's real seed (read from the loaded world) must equal the
 * seed that was prepared AND differ from the previous Nether's seed. Nothing here trusts a config
 * value; it compares what the server reports.
 */
public final class PostResetVerifier {

  private PostResetVerifier() {}

  public record Facts(
      boolean worldLoaded,
      boolean environmentIsNether,
      long observedSeed,
      Long expectedSeed,
      Long previousSeed,
      boolean folderMatchesRecordedPath,
      boolean overworldStillLoaded,
      boolean endStillLoaded) {}

  /** @return empty list = verified. Otherwise one sentence per failed check. */
  public static List<String> verify(Facts f, boolean requireNewSeed) {
    List<String> failures = new ArrayList<>();
    if (!f.worldLoaded()) {
      failures.add("The new Nether world did not load.");
      return failures;
    }
    if (!f.environmentIsNether()) {
      failures.add("The loaded world is not a Nether-type dimension.");
    }
    if (f.expectedSeed() == null) {
      failures.add("No prepared seed is recorded, so the new seed cannot be confirmed.");
    } else if (f.observedSeed() != f.expectedSeed()) {
      failures.add("The world's real seed (" + f.observedSeed() + ") is not the prepared seed (" + f.expectedSeed() + ").");
    }
    if (requireNewSeed && f.previousSeed() != null && f.observedSeed() == f.previousSeed()) {
      failures.add("The new Nether has the same seed as the old one; this is not a new Nether.");
    }
    if (!f.folderMatchesRecordedPath()) {
      failures.add("The world folder is not the folder the maintenance tool prepared.");
    }
    if (!f.overworldStillLoaded()) {
      failures.add("The Overworld is not loaded.");
    }
    if (!f.endStillLoaded()) {
      failures.add("The End is not loaded.");
    }
    return failures;
  }
}
