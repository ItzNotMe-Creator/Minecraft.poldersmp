package nl.poldersmp.cinematics;

/** Counts both packet fan-out and particle quantities; one dense burst cannot evade the cap. */
public final class EffectBudget {
  private final int packetLimit, particleLimit;
  private int packets, particles;

  public EffectBudget(int packets, int particles) {
    if (packets < 1 || particles < packets)
      throw new IllegalArgumentException("Invalid effect budget");
    packetLimit = packets;
    particleLimit = particles;
  }

  public void reset() {
    packets = 0;
    particles = 0;
  }

  public boolean take(int quantity) {
    if (quantity < 1 || packets >= packetLimit || quantity > particleLimit - particles)
      return false;
    packets++;
    particles += quantity;
    return true;
  }

  public int remainingPackets() {
    return packetLimit - packets;
  }

  public int particles() {
    return particles;
  }
}
