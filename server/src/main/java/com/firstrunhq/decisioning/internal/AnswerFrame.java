package com.firstrunhq.decisioning.internal;

/**
 * One widget answer frame paired with the stream event name that carries it, so a frame can never
 * ride the wrong event.
 */
sealed interface AnswerFrame permits TokenFrame, DoneFrame {

  /** The SSE event name the widget dispatches on. */
  String event();
}
