package com.firstrunhq.decisioning.internal;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * The data payload of one {@code token} stream frame, one streamed span of answer text. The message
 * id lets the widget bind the frame to the question it sent (api/openapi/messages.yaml).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record TokenFrame(String messageId, String text) {}
