package nl.poldersmp.core.market;

import java.math.*;

public final class MoneyRules {
  private MoneyRules() {}

  public static long amount(String text, long maximum) {
    if (text.length() > 32) throw new IllegalArgumentException("Amount text is too long");
    try {
      long n = new BigDecimal(text).longValueExact();
      if (n <= 0 || n > maximum)
        throw new IllegalArgumentException("Amount outside configured limit");
      return n;
    } catch (ArithmeticException | NumberFormatException e) {
      throw new IllegalArgumentException("Use an exact positive whole currency amount");
    }
  }

  public static long rotation(long now, long seconds) {
    if (seconds < 1) throw new IllegalArgumentException("Invalid refresh interval");
    return Math.floorDiv(now, Math.multiplyExact(seconds, 1000));
  }

  public static boolean mayRetry(String state) {
    return state.equals("PENDING");
  }
}
