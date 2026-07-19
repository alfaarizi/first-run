package com.firstrunhq.decisioning.internal;

/** The data payload of one {@code token} stream frame, one streamed span of answer text. */
record TokenFrame(String text) {}
