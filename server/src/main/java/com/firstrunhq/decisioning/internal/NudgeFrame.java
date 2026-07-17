package com.firstrunhq.decisioning.internal;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.UUID;

/** The data payload of one {@code nudge} stream frame, which the widget renders as text. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record NudgeFrame(UUID id, String text) {}
