package com.firstrunhq.funnel.internal;

import org.springframework.graphql.execution.ErrorType;

/** Rejects a funnel read with a message safe to hand to the client. */
class FunnelQueryException extends RuntimeException {

  private final ErrorType errorType;
  private final String clientMessage;

  private FunnelQueryException(ErrorType errorType, String clientMessage) {
    super(clientMessage);
    this.errorType = errorType;
    this.clientMessage = clientMessage;
  }

  static FunnelQueryException unauthorized() {
    return new FunnelQueryException(ErrorType.UNAUTHORIZED, "The request carries no tenant.");
  }

  static FunnelQueryException invalidRange() {
    return new FunnelQueryException(ErrorType.BAD_REQUEST, "The range start must precede its end.");
  }

  ErrorType errorType() {
    return errorType;
  }

  String clientMessage() {
    return clientMessage;
  }
}
