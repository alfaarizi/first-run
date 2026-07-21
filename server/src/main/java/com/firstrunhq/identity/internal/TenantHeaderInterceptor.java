package com.firstrunhq.identity.internal;

import com.firstrunhq.identity.TenantContext;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Resolves the requesting tenant from the {@code X-FirstRun-Tenant} header into the GraphQL
 * context. The header is trusted as-is, so the resolver stays off unless the property below turns
 * it on, which only the local stack does. Authenticated login replaces it, and without a resolver
 * every tenant-scoped field fails closed as unauthorized.
 */
@Component
@ConditionalOnProperty("firstrun.identity.trusted-tenant-header")
class TenantHeaderInterceptor implements WebGraphQlInterceptor {

  private static final String TENANT_HEADER = "X-FirstRun-Tenant";

  /** Copies a parseable tenant header into the GraphQL context, ignoring the rest. */
  @Override
  public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
    String header = request.getHeaders().getFirst(TENANT_HEADER);
    if (header != null) {
      UUID tenantId;
      try {
        tenantId = UUID.fromString(header);
      } catch (IllegalArgumentException unparseable) {
        return chain.next(request);
      }
      request.configureExecutionInput(
          (executionInput, builder) ->
              builder
                  .graphQLContext(context -> context.put(TenantContext.TENANT_ID_KEY, tenantId))
                  .build());
    }
    return chain.next(request);
  }
}
