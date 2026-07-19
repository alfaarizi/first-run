package com.firstrunhq.decisioning.internal;

import java.util.List;

/** The data payload of the {@code done} stream frame closing an answer, with its citations. */
record DoneFrame(List<Citation> citations) {

  /** A source the answer cites, in the shape the widget renders. */
  record Citation(String title, String url) {}
}
