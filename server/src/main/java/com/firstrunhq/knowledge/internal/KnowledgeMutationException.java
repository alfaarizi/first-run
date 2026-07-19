package com.firstrunhq.knowledge.internal;

import org.springframework.graphql.execution.ErrorType;

/** Rejects a knowledge operation with a message safe to hand to the client. */
class KnowledgeMutationException extends RuntimeException {

  private final ErrorType errorType;
  private final String clientMessage;

  private KnowledgeMutationException(ErrorType errorType, String clientMessage) {
    super(clientMessage);
    this.errorType = errorType;
    this.clientMessage = clientMessage;
  }

  static KnowledgeMutationException unauthorized() {
    return new KnowledgeMutationException(ErrorType.UNAUTHORIZED, "The request carries no tenant.");
  }

  static KnowledgeMutationException invalidUrl() {
    return new KnowledgeMutationException(
        ErrorType.BAD_REQUEST, "The URL must be absolute http or https.");
  }

  ErrorType errorType() {
    return errorType;
  }

  String clientMessage() {
    return clientMessage;
  }
}
