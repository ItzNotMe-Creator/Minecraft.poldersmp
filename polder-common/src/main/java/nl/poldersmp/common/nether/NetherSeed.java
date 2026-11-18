package nl.poldersmp.common.nether;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.function.LongSupplier;

/** Picks a random Nether seed that is guaranteed to differ from every seed used before. */
public final class NetherSeed {

  private static final SecureRandom RANDOM = new SecureRandom();

  private NetherSeed() {}

  public static long next(Collection<Long> alreadyUsed) {
    return next(alreadyUsed, RANDOM::nextLong);
  }

  /** @param source random numbers; tests pass a scripted source */
  public static long next(Collection<Long> alreadyUsed, LongSupplier source) {
    for (int attempt = 0; attempt < 1000; attempt++) {
      long candidate = source.getAsLong();
      if (!alreadyUsed.contains(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("Could not find an unused seed");
  }
}
