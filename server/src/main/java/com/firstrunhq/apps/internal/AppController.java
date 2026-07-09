package com.firstrunhq.apps.internal;

import com.firstrunhq.apps.App;
import com.firstrunhq.identity.TenantContext;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/** Serves the app queries in api/graphql/apps.graphqls. */
@Controller
class AppController {

  private final AppReader reader;

  AppController(AppReader reader) {
    this.reader = reader;
  }

  @QueryMapping
  @Nullable App app(
      @Argument String id,
      @ContextValue(name = TenantContext.TENANT_ID_KEY, required = false) @Nullable UUID tenantId) {
    if (tenantId == null) {
      throw AppQueryException.unauthorized();
    }
    UUID appId;
    try {
      appId = UUID.fromString(id);
    } catch (IllegalArgumentException unparseable) {
      return null;
    }
    return reader.find(tenantId, appId).orElse(null);
  }

  @GraphQlExceptionHandler
  GraphQLError handle(AppQueryException exception, DataFetchingEnvironment env) {
    return GraphqlErrorBuilder.newError(env)
        .errorType(exception.errorType())
        .message(exception.clientMessage())
        .build();
  }
}
