package com.firstrunhq.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstrunhq.IntegrationTest;
import com.firstrunhq.testfixture.TestSeeder;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

/**
 * Exercises the doc-source mutations over HTTP. The agent is deliberately absent, so every trigger
 * fails and each registered source must survive as PENDING for a later reindex.
 */
@IntegrationTest
class KnowledgeGraphqlTests {

  private static final String TENANT_A = "019813f2-0000-7000-8000-0000000000d1";
  private static final String APP_A = "019813f2-0000-7000-8000-0000000000d2";
  private static final String TENANT_B = "019813f2-0000-7000-8000-0000000000d3";
  private static final String APP_B = "019813f2-0000-7000-8000-0000000000d4";

  private static final String ADD_DOC_SOURCE =
      """
      mutation AddDocSource($input: AddDocSourceInput!) {
        addDocSource(input: $input) {
          docSource { id url status lastIndexedAt chunkCount }
        }
      }
      """;

  private static final String REINDEX_DOC_SOURCE =
      """
      mutation ReindexDocSource($input: ReindexDocSourceInput!) {
        reindexDocSource(input: $input) {
          docSource { id status }
        }
      }
      """;

  private static final String APP_DOC_SOURCES =
      """
      query AppDocSources($id: ID!) {
        app(id: $id) {
          docSources { id url status chunkCount }
        }
      }
      """;

  private final HttpGraphQlTester tester;
  private final DataSource dataSource;

  KnowledgeGraphqlTests(HttpGraphQlTester tester, DataSource dataSource) {
    this.tester = tester;
    this.dataSource = dataSource;
  }

  @BeforeEach
  void seedTenantsAndApps() throws SQLException {
    TestSeeder.tenant(dataSource, TENANT_A, "Knowledge Tenant A");
    TestSeeder.tenant(dataSource, TENANT_B, "Knowledge Tenant B");
    TestSeeder.app(dataSource, APP_A, TENANT_A, "App A");
    TestSeeder.app(dataSource, APP_B, TENANT_B, "App B");
  }

  @Test
  void registersADocSourceAsPendingWithNoChunks() {
    GraphQlTester.Response response = add(asTenant(TENANT_A), APP_A, "https://docs.example.com/");

    response
        .path("addDocSource.docSource.url")
        .entity(String.class)
        .isEqualTo("https://docs.example.com/");
    response.path("addDocSource.docSource.status").entity(String.class).isEqualTo("PENDING");
    response.path("addDocSource.docSource.chunkCount").entity(Integer.class).isEqualTo(0);
    UUID id =
        UUID.fromString(response.path("addDocSource.docSource.id").entity(String.class).get());
    assertThat(id.version()).isEqualTo(7);
  }

  @Test
  void addingTheSameUrlAgainReturnsTheExistingSource() {
    String first =
        add(asTenant(TENANT_A), APP_A, "https://docs.example.com/again")
            .path("addDocSource.docSource.id")
            .entity(String.class)
            .get();

    String second =
        add(asTenant(TENANT_A), APP_A, "https://docs.example.com/again")
            .path("addDocSource.docSource.id")
            .entity(String.class)
            .get();

    assertThat(second).isEqualTo(first);
  }

  @Test
  void listsAnAppsSourcesOnTheAppType() {
    add(asTenant(TENANT_A), APP_A, "https://docs.example.com/listed");

    asTenant(TENANT_A)
        .document(APP_DOC_SOURCES)
        .variable("id", APP_A)
        .execute()
        .path("app.docSources[*].url")
        .entityList(String.class)
        .contains("https://docs.example.com/listed");
  }

  @Test
  void reindexReturnsTheSourceItRetriggers() {
    String id =
        add(asTenant(TENANT_A), APP_A, "https://docs.example.com/reindex")
            .path("addDocSource.docSource.id")
            .entity(String.class)
            .get();

    asTenant(TENANT_A)
        .document(REINDEX_DOC_SOURCE)
        .variable("input", Map.of("docSourceId", id))
        .execute()
        .path("reindexDocSource.docSource.id")
        .entity(String.class)
        .isEqualTo(id);
  }

  @Test
  void rejectsAUrlThatIsNotAbsoluteHttp() {
    GraphQlTester.Response response = add(asTenant(TENANT_A), APP_A, "ftp://docs.example.com/");

    response
        .errors()
        .satisfy(
            errors -> {
              assertThat(errors).hasSize(1);
              assertThat(errors.getFirst().getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
              assertThat(errors.getFirst().getMessage())
                  .isEqualTo("The URL must be absolute http or https.");
            });
  }

  @Test
  void rejectsAnIdThatIsNotAUuid() {
    // A malformed id is a client bug and answers as one. The null payload is
    // reserved for rows genuinely absent from the tenant.
    asTenant(TENANT_A)
        .document(REINDEX_DOC_SOURCE)
        .variable("input", Map.of("docSourceId", "not-a-uuid"))
        .execute()
        .errors()
        .satisfy(
            errors -> {
              assertThat(errors).hasSize(1);
              assertThat(errors.getFirst().getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
              assertThat(errors.getFirst().getMessage()).isEqualTo("docSourceId must be a UUID.");
            });
  }

  @Test
  void resolvesAnotherTenantsAppToAnEmptyPayload() {
    add(asTenant(TENANT_A), APP_B, "https://docs.example.com/")
        .path("addDocSource.docSource")
        .valueIsNull();
  }

  @Test
  void rejectsARequestWithoutATenant() {
    GraphQlTester.Response response = add(tester, APP_A, "https://docs.example.com/");

    response
        .errors()
        .satisfy(
            errors -> {
              assertThat(errors).hasSize(1);
              assertThat(errors.getFirst().getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
            });
  }

  private HttpGraphQlTester asTenant(String tenantId) {
    return tester.mutate().headers(headers -> headers.set("X-FirstRun-Tenant", tenantId)).build();
  }

  private static GraphQlTester.Response add(HttpGraphQlTester client, String appId, String url) {
    return client
        .document(ADD_DOC_SOURCE)
        .variable("input", Map.of("appId", appId, "url", url))
        .execute();
  }
}
