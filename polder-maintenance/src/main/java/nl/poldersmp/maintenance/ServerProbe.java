package nl.poldersmp.maintenance;

import java.util.List;

/** Answers one question: is the Minecraft server process really stopped? Replaceable in tests. */
public interface ServerProbe {

  /**
   * @param stopped true only when nothing indicates a running server AND something positively
   *     indicates it is stopped (see {@link ServerStopCheck})
   * @param reasons why (running evidence, or what proved it stopped, or why it is unknown)
   */
  record Result(boolean stopped, List<String> reasons) {}

  Result check();
}
