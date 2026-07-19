package com.firstrunhq.funnel.internal;

import com.firstrunhq.identity.TenantContext;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.stereotype.Controller;

/** Serves the milestone mutations in api/graphql/funnel.graphqls. */
@Controller
class MilestoneController {

  private final MilestoneCatalog catalog;

  MilestoneController(MilestoneCatalog catalog) {
    this.catalog = catalog;
  }

  /** Defines one activation milestone for the input's app. */
  @MutationMapping
  CreateMilestonePayload createMilestone(
      @Argument CreateMilestoneInput input,
      @ContextValue(name = TenantContext.TENANT_ID_KEY, required = false) @Nullable UUID tenantId) {
    if (tenantId == null) {
      throw MilestoneDefinitionException.unauthorized();
    }
    return new CreateMilestonePayload(catalog.create(tenantId, input));
  }

  /** Turns the module's client-safe exception into its GraphQL error. */
  @GraphQlExceptionHandler
  GraphQLError handle(MilestoneDefinitionException exception, DataFetchingEnvironment env) {
    return GraphqlErrorBuilder.newError(env)
        .errorType(exception.errorType())
        .message(exception.clientMessage())
        .build();
  }
}
