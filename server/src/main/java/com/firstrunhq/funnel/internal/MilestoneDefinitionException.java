package com.firstrunhq.funnel.internal;

import org.springframework.graphql.execution.ErrorType;

/** Rejects a milestone definition with a message safe to hand to the client. */
class MilestoneDefinitionException extends RuntimeException {

  private final ErrorType errorType;
  private final String clientMessage;

  private MilestoneDefinitionException(ErrorType errorType, String clientMessage) {
    super(clientMessage);
    this.errorType = errorType;
    this.clientMessage = clientMessage;
  }

  static MilestoneDefinitionException unauthorized() {
    return new MilestoneDefinitionException(
        ErrorType.UNAUTHORIZED, "The request carries no tenant.");
  }

  static MilestoneDefinitionException invalidInput(String clientMessage) {
    return new MilestoneDefinitionException(ErrorType.BAD_REQUEST, clientMessage);
  }

  static MilestoneDefinitionException appNotFound(String appId) {
    return new MilestoneDefinitionException(
        ErrorType.NOT_FOUND, "Could not resolve to an app with the id '%s'.".formatted(appId));
  }

  ErrorType errorType() {
    return errorType;
  }

  String clientMessage() {
    return clientMessage;
  }
}
