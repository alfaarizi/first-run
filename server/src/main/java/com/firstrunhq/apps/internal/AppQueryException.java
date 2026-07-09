package com.firstrunhq.apps.internal;

import org.springframework.graphql.execution.ErrorType;

/** Rejects an app query with a message safe to hand to the client. */
class AppQueryException extends RuntimeException {

  private final ErrorType errorType;
  private final String clientMessage;

  private AppQueryException(ErrorType errorType, String clientMessage) {
    super(clientMessage);
    this.errorType = errorType;
    this.clientMessage = clientMessage;
  }

  static AppQueryException unauthorized() {
    return new AppQueryException(ErrorType.UNAUTHORIZED, "The request carries no tenant.");
  }

  ErrorType errorType() {
    return errorType;
  }

  String clientMessage() {
    return clientMessage;
  }
}
