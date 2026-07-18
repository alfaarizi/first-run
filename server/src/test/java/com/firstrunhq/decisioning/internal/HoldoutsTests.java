package com.firstrunhq.decisioning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the bucketing to fixed vectors, so a change to the hash can never silently reshuffle who the
 * control group is and corrupt every lift number measured against it.
 */
class HoldoutsTests {

  private static final UUID TENANT = UUID.fromString("019813f2-0000-7000-8000-000000000401");
  private static final UUID OTHER_TENANT = UUID.fromString("019813f2-0000-7000-8000-000000000501");

  private final Holdouts holdouts = new Holdouts();

  @Test
  void pinsTheBucketingToKnownVectors() {
    assertThat(holdouts.contains(TENANT, "user-control-2")).isTrue();
    assertThat(holdouts.contains(TENANT, "user-push")).isFalse();
  }

  @Test
  void seedsTheBucketWithTheTenant() {
    assertThat(holdouts.contains(TENANT, "user-control-2")).isTrue();
    assertThat(holdouts.contains(OTHER_TENANT, "user-control-2")).isFalse();
  }

  @Test
  void holdsAboutATenthOfUsers() {
    long held = 0;
    for (int user = 0; user < 10_000; user++) {
      if (holdouts.contains(TENANT, "user-" + user)) {
        held++;
      }
    }
    assertThat(held).isBetween(850L, 1150L);
  }
}
