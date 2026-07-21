package com.firstrunhq.decisioning.internal;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One validated end-user message with the conversation coordinates it belongs to. {@code ref} names
 * the nudge the message answers, absent when the user opened the chat unprompted.
 */
record EndUserMessage(
    UUID messageId, UUID sessionId, String endUserHash, String text, @Nullable UUID ref) {}
