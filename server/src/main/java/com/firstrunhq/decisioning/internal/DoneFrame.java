package com.firstrunhq.decisioning.internal;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * The data payload of the {@code done} stream frame closing an answer, with its citations. The
 * message id lets the widget bind the frame to the question it sent (api/openapi/messages.yaml).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record DoneFrame(String messageId, List<Citation> citations) implements AnswerFrame {

  @Override
  public String event() {
    return "done";
  }

  /** A source the answer cites, in the shape the widget renders. */
  record Citation(String title, String url) {}
}
