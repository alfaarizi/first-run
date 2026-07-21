package com.firstrunhq.decisioning.internal;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * The data payload of the {@code done} stream frame closing an answer. It carries the complete
 * answer text so a widget that missed tokens to a reconnect or a reload heals from it, and the
 * citations the answer traces to. The message id binds the frame to the question the widget sent
 * (api/openapi/messages.yaml).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record DoneFrame(String messageId, String text, List<Citation> citations) implements AnswerFrame {

  @Override
  public String event() {
    return "done";
  }

  /** A source the answer cites, in the shape the widget renders. */
  record Citation(String title, String url) {}
}
