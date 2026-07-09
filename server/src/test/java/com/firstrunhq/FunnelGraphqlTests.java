package com.firstrunhq;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

/**
 * Exercises the funnel read over HTTP: cohort counting inside a half-open range, step ordering,
 * medians, and the tenant guard. Cross-tenant isolation of the tables this read joins is proven
 * separately under a non-superuser role in the row-level security test classes, because this
 * harness connects as the container's superuser.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "firstrun.identity.trusted-tenant-header=true")
@AutoConfigureHttpGraphQlTester
@Import(TestcontainersConfiguration.class)
class FunnelGraphqlTests {

  private static final String TENANT = "019813f2-0000-7000-8000-000000000101";
  private static final String APP = "019813f2-0000-7000-8000-000000000102";
  private static final String APP_RECENT = "019813f2-0000-7000-8000-000000000103";
  private static final String MILESTONE_FIRST = "019813f2-0000-7000-8000-000000000104";
  private static final String MILESTONE_SECOND = "019813f2-0000-7000-8000-000000000105";
  private static final String MILESTONE_RECENT = "019813f2-0000-7000-8000-000000000106";

  /** The queried range. Entries count from the start inclusive to the end exclusive. */
  private static final String FROM = "2026-06-01T00:00:00Z";

  private static final String TO = "2026-07-01T00:00:00Z";

  private static final String GET_FUNNEL =
      """
      query GetFunnel($appId: ID!, $from: DateTime, $to: DateTime) {
        app(id: $appId) {
          id
          name
          funnel(from: $from, to: $to) {
            from
            to
            steps {
              milestone { id name title position createdAt }
              entered
              completed
              stuckSignals
              medianSecondsToComplete
            }
          }
        }
      }
      """;

  private final HttpGraphQlTester tester;
  private final DataSource dataSource;

  FunnelGraphqlTests(HttpGraphQlTester tester, DataSource dataSource) {
    this.tester = tester;
    this.dataSource = dataSource;
  }

  @BeforeEach
  void seedFunnelState() throws SQLException {
    try (var connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO tenant (id, name) VALUES ('%s', 'Funnel Tenant') ON CONFLICT (id) DO NOTHING"
              .formatted(TENANT));
      statement.execute(
          """
          INSERT INTO app (id, tenant_id, name, sdk_key, hmac_key)
          VALUES
            ('%s', '%s', 'Funnel App', 'key_funnel_102', 'hmac_funnel_102'),
            ('%s', '%s', 'Recent App', 'key_funnel_103', 'hmac_funnel_103')
          ON CONFLICT (id) DO NOTHING
          """
              .formatted(APP, TENANT, APP_RECENT, TENANT));

      // The second step lands first so ordering comes from position, not insertion.
      statement.execute(
          """
          INSERT INTO milestone (id, tenant_id, app_id, name, title, position) VALUES
            ('%s', '%s', '%s', 'data_source_connected', 'Connect a data source', 2),
            ('%s', '%s', '%s', 'project_created', 'Create a project', 1),
            ('%s', '%s', '%s', 'report_generated', 'Generate a report', 1)
          ON CONFLICT (id) DO NOTHING
          """
              .formatted(
                  MILESTONE_SECOND,
                  TENANT,
                  APP,
                  MILESTONE_FIRST,
                  TENANT,
                  APP,
                  MILESTONE_RECENT,
                  TENANT,
                  APP_RECENT));

      for (int user = 1; user <= 8; user++) {
        statement.execute(
            """
            INSERT INTO end_user (id, tenant_id, app_id, external_hash, first_seen_at)
            VALUES ('%s', '%s', '%s', 'hash_funnel_%d', '2026-05-01T00:00:00Z')
            ON CONFLICT (id) DO NOTHING
            """
                .formatted(endUser(user), TENANT, user <= 7 ? APP : APP_RECENT, user));
      }
      statement.execute(
          """
          INSERT INTO milestone_progress
            (tenant_id, end_user_id, milestone_id, state, started_at, completed_at)
          VALUES
            -- Completed inside the range, one day to complete.
            ('%1$s', '%2$s', '%3$s', 'COMPLETED', '2026-06-05T00:00:00Z', '2026-06-06T00:00:00Z'),
            -- Completed inside the range, two days to complete: the cohort median.
            ('%1$s', '%4$s', '%3$s', 'COMPLETED', '2026-06-10T00:00:00Z', '2026-06-12T00:00:00Z'),
            -- Still in progress.
            ('%1$s', '%5$s', '%3$s', 'IN_PROGRESS', '2026-06-15T00:00:00Z', NULL),
            -- Entered before the range, so the completion inside it counts nothing.
            ('%1$s', '%6$s', '%3$s', 'COMPLETED', '2026-05-20T00:00:00Z', '2026-06-02T00:00:00Z'),
            -- Entered inside the range and completed after it: counts as a completion.
            ('%1$s', '%7$s', '%3$s', 'COMPLETED', '2026-06-20T00:00:00Z', '2026-07-15T00:00:00Z'),
            -- Entered exactly on the range end, which is exclusive.
            ('%1$s', '%8$s', '%3$s', 'IN_PROGRESS', '2026-07-01T00:00:00Z', NULL),
            -- Entered exactly on the range start, which is inclusive.
            ('%1$s', '%9$s', '%3$s', 'IN_PROGRESS', '2026-06-01T00:00:00Z', NULL),
            -- The second step, entered by the two users who finished the first inside June.
            ('%1$s', '%2$s', '%10$s', 'COMPLETED', '2026-06-06T00:00:00Z', '2026-06-06T02:00:00Z'),
            ('%1$s', '%4$s', '%10$s', 'IN_PROGRESS', '2026-06-12T00:00:00Z', NULL)
          ON CONFLICT (end_user_id, milestone_id) DO NOTHING
          """
              .formatted(
                  TENANT,
                  endUser(1),
                  MILESTONE_FIRST,
                  endUser(2),
                  endUser(3),
                  endUser(4),
                  endUser(5),
                  endUser(6),
                  endUser(7),
                  MILESTONE_SECOND));
      statement.execute(
          """
          INSERT INTO milestone_progress
            (tenant_id, end_user_id, milestone_id, state, started_at, completed_at)
          VALUES ('%s', '%s', '%s', 'IN_PROGRESS', now() - interval '1 day', NULL)
          ON CONFLICT (end_user_id, milestone_id) DO NOTHING
          """
              .formatted(TENANT, endUser(8), MILESTONE_RECENT));
    }
  }

  @Test
  void countsTheCohortThatEnteredTheRange() {
    GraphQlTester.Response response = funnel(APP);
    response.path("app.name").entity(String.class).isEqualTo("Funnel App");
    response.path("app.funnel.steps[0].entered").entity(Integer.class).isEqualTo(5);
    response.path("app.funnel.steps[0].completed").entity(Integer.class).isEqualTo(3);
    response.path("app.funnel.steps[1].entered").entity(Integer.class).isEqualTo(2);
    response.path("app.funnel.steps[1].completed").entity(Integer.class).isEqualTo(1);
  }

  @Test
  void ordersStepsByPosition() {
    GraphQlTester.Response response = funnel(APP);
    List<String> names =
        response.path("app.funnel.steps[*].milestone.name").entityList(String.class).get();
    assertThat(names).containsExactly("project_created", "data_source_connected");
    response.path("app.funnel.steps[0].milestone.position").entity(Integer.class).isEqualTo(1);
    response
        .path("app.funnel.steps[0].milestone.title")
        .entity(String.class)
        .isEqualTo("Create a project");
  }

  @Test
  void mediansTheEnteredCohortsCompletions() {
    GraphQlTester.Response response = funnel(APP);
    response
        .path("app.funnel.steps[0].medianSecondsToComplete")
        .entity(Integer.class)
        .isEqualTo(172800);
    response
        .path("app.funnel.steps[1].medianSecondsToComplete")
        .entity(Integer.class)
        .isEqualTo(7200);
  }

  @Test
  void reportsNoStuckSignalsBeforeTheGateExists() {
    GraphQlTester.Response response = funnel(APP);
    response
        .path("app.funnel.steps[*].stuckSignals")
        .entityList(Integer.class)
        .satisfies(signals -> assertThat(signals).containsExactly(0, 0));
  }

  @Test
  void defaultsToTheLastThirtyDays() {
    GraphQlTester.Response response =
        asTenant().document(GET_FUNNEL).variable("appId", APP_RECENT).execute();
    OffsetDateTime from =
        OffsetDateTime.parse(response.path("app.funnel.from").entity(String.class).get());
    OffsetDateTime to =
        OffsetDateTime.parse(response.path("app.funnel.to").entity(String.class).get());
    assertThat(from).isEqualTo(to.minusDays(30));
    response.path("app.funnel.steps[0].entered").entity(Integer.class).isEqualTo(1);
    response.path("app.funnel.steps[0].completed").entity(Integer.class).isEqualTo(0);
    response.path("app.funnel.steps[0].medianSecondsToComplete").valueIsNull();
  }

  @Test
  void rejectsARangeStartingAtOrAfterItsEnd() {
    GraphQlTester.Response response =
        asTenant()
            .document(GET_FUNNEL)
            .variable("appId", APP)
            .variable("from", TO)
            .variable("to", FROM)
            .execute();
    response
        .errors()
        .satisfy(
            errors -> {
              assertThat(errors).hasSize(1);
              assertThat(errors.getFirst().getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
              assertThat(errors.getFirst().getMessage())
                  .isEqualTo("The range start must precede its end.");
            });
  }

  @Test
  void rejectsARequestWithoutATenant() {
    GraphQlTester.Response response = tester.document(GET_FUNNEL).variable("appId", APP).execute();
    response
        .errors()
        .satisfy(
            errors -> {
              assertThat(errors).hasSize(1);
              assertThat(errors.getFirst().getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
              assertThat(errors.getFirst().getMessage())
                  .isEqualTo("The request carries no tenant.");
            });
  }

  @Test
  void resolvesAnUnknownAppToNull() {
    asTenant()
        .document(GET_FUNNEL)
        .variable("appId", "019813f2-0000-7000-8000-0000000001ff")
        .execute()
        .path("app")
        .valueIsNull();
    asTenant()
        .document(GET_FUNNEL)
        .variable("appId", "not-a-uuid")
        .execute()
        .path("app")
        .valueIsNull();
  }

  private GraphQlTester.Response funnel(String appId) {
    return asTenant()
        .document(GET_FUNNEL)
        .variable("appId", appId)
        .variable("from", FROM)
        .variable("to", TO)
        .execute();
  }

  private HttpGraphQlTester asTenant() {
    return tester.mutate().headers(headers -> headers.set("X-FirstRun-Tenant", TENANT)).build();
  }

  private static String endUser(int user) {
    return "019813f2-0000-7000-8000-00000000011%d".formatted(user);
  }
}
