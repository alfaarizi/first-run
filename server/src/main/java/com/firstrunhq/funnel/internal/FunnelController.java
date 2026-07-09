package com.firstrunhq.funnel.internal;

import com.firstrunhq.apps.App;
import com.firstrunhq.identity.TenantContext;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

/** Serves the funnel read on {@code App} in api/graphql/apps.graphqls. */
@Controller
class FunnelController {

  private static final Duration DEFAULT_RANGE = Duration.ofDays(30);

  private final FunnelReader reader;

  FunnelController(FunnelReader reader) {
    this.reader = reader;
  }

  @SchemaMapping(typeName = "App")
  Funnel funnel(
      App app,
      @Argument @Nullable OffsetDateTime from,
      @Argument @Nullable OffsetDateTime to,
      @ContextValue(name = TenantContext.TENANT_ID_KEY, required = false) @Nullable UUID tenantId) {
    if (tenantId == null) {
      throw FunnelQueryException.unauthorized();
    }
    OffsetDateTime rangeEnd = to != null ? to : OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime rangeStart = from != null ? from : rangeEnd.minus(DEFAULT_RANGE);
    if (!rangeStart.isBefore(rangeEnd)) {
      throw FunnelQueryException.invalidRange();
    }
    return new Funnel(rangeStart, rangeEnd, reader.read(tenantId, app.id(), rangeStart, rangeEnd));
  }

  @GraphQlExceptionHandler
  GraphQLError handle(FunnelQueryException exception, DataFetchingEnvironment env) {
    return GraphqlErrorBuilder.newError(env)
        .errorType(exception.errorType())
        .message(exception.clientMessage())
        .build();
  }
}
