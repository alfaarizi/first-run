package com.firstrunhq.knowledge.internal;

import com.firstrunhq.apps.App;
import com.firstrunhq.identity.TenantContext;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.StatusRuntimeException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

/** Serves the doc-source fields and mutations in api/graphql/knowledge.graphqls. */
@Controller
class KnowledgeController {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

  private final DocSourceRepository repository;
  private final ReindexClient reindexClient;

  /** Persists doc sources locally and asks the agent to crawl them. */
  KnowledgeController(DocSourceRepository repository, ReindexClient reindexClient) {
    this.repository = repository;
    this.reindexClient = reindexClient;
  }

  @MutationMapping
  AddDocSourcePayload addDocSource(
      @Argument AddDocSourceInput input,
      @ContextValue(name = TenantContext.TENANT_ID_KEY, required = false) @Nullable UUID tenantId) {
    UUID tenant = requireTenant(tenantId);
    UUID appId = requireUuid(input.appId(), "appId");
    requireCrawlableUrl(input.url());
    DocSource source;
    try {
      source = repository.upsert(tenant, appId, input.url());
    } catch (DataIntegrityViolationException unknownApp) {
      return new AddDocSourcePayload(null);
    }
    triggerReindex(tenant, source);
    return new AddDocSourcePayload(source);
  }

  @MutationMapping
  ReindexDocSourcePayload reindexDocSource(
      @Argument ReindexDocSourceInput input,
      @ContextValue(name = TenantContext.TENANT_ID_KEY, required = false) @Nullable UUID tenantId) {
    UUID tenant = requireTenant(tenantId);
    UUID sourceId = requireUuid(input.docSourceId(), "docSourceId");
    DocSource source = repository.find(tenant, sourceId).orElse(null);
    if (source == null) {
      return new ReindexDocSourcePayload(null);
    }
    triggerReindex(tenant, source);
    return new ReindexDocSourcePayload(source);
  }

  @SchemaMapping(typeName = "App", field = "docSources")
  List<DocSource> docSources(
      App app,
      @ContextValue(name = TenantContext.TENANT_ID_KEY, required = false) @Nullable UUID tenantId) {
    return repository.findByApp(requireTenant(tenantId), app.id());
  }

  @SchemaMapping(typeName = "DocSource", field = "chunkCount")
  int chunkCount(
      DocSource source,
      @ContextValue(name = TenantContext.TENANT_ID_KEY, required = false) @Nullable UUID tenantId) {
    return repository.chunkCount(requireTenant(tenantId), source.id());
  }

  /** Turns the module's client-safe exception into its GraphQL error. */
  @GraphQlExceptionHandler
  GraphQLError handle(KnowledgeMutationException exception, DataFetchingEnvironment env) {
    return GraphqlErrorBuilder.newError(env)
        .errorType(exception.errorType())
        .message(exception.clientMessage())
        .build();
  }

  /** A failed trigger keeps the row, so the founder retries with reindexDocSource. */
  private void triggerReindex(UUID tenantId, DocSource source) {
    try {
      reindexClient.reindex(tenantId, source.appId(), source.id(), source.url());
    } catch (StatusRuntimeException agentUnreachable) {
      log.warn("reindex trigger failed for doc source {}", source.id(), agentUnreachable);
    }
  }

  /** Rejects a request that carries no tenant. Every field here is tenant-scoped. */
  private static UUID requireTenant(@Nullable UUID tenantId) {
    if (tenantId == null) {
      throw KnowledgeMutationException.unauthorized();
    }
    return tenantId;
  }

  /**
   * A malformed id is a client bug and answers as one. Only a row absent from this tenant resolves
   * to the not-found null payload.
   */
  private static UUID requireUuid(String id, String field) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException unparseable) {
      throw KnowledgeMutationException.invalidId(field);
    }
  }

  /** Rejects obvious non-URLs. The agent's crawler enforces the real egress policy. */
  private static void requireCrawlableUrl(String url) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException notAUrl) {
      throw KnowledgeMutationException.invalidUrl();
    }
    String scheme = uri.getScheme();
    boolean crawlable = ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null;
    if (!crawlable) {
      throw KnowledgeMutationException.invalidUrl();
    }
  }
}
