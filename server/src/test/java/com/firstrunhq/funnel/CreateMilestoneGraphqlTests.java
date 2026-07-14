package com.firstrunhq.funnel;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstrunhq.IntegrationTest;
import com.firstrunhq.ingestion.AutoCapturedEvents;
import com.firstrunhq.testfixture.TestSeeder;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

/**
 * Exercises {@code createMilestone} over HTTP: name grammar, the reserved {@code fr.} prefix,
 * per-app uniqueness, the ten-milestone cap, and another tenant's app resolving to not found.
 */
@IntegrationTest
class CreateMilestoneGraphqlTests {

  private static final String TENANT_A = "019813f2-0000-7000-8000-0000000000e1";
  private static final String APP_A = "019813f2-0000-7000-8000-0000000000e2";
  private static final String TENANT_B = "019813f2-0000-7000-8000-0000000000e3";
  private static final String APP_B = "019813f2-0000-7000-8000-0000000000e4";
  private static final String APP_CAP = "019813f2-0000-7000-8000-0000000000e5";

  private static final String CREATE_MILESTONE =
      """
      mutation CreateMilestone($input: CreateMilestoneInput!) {
        createMilestone(input: $input) {
          milestone { id name title position createdAt }
        }
      }
      """;

  private final HttpGraphQlTester tester;
  private final DataSource dataSource;

  CreateMilestoneGraphqlTests(HttpGraphQlTester tester, DataSource dataSource) {
    this.tester = tester;
    this.dataSource = dataSource;
  }

  @BeforeEach
  void seedTenantsAndApps() throws SQLException {
    TestSeeder.tenant(dataSource, TENANT_A, "Milestone Tenant A");
    TestSeeder.tenant(dataSource, TENANT_B, "Milestone Tenant B");
    TestSeeder.app(dataSource, APP_A, TENANT_A, "App A");
    TestSeeder.app(dataSource, APP_B, TENANT_B, "App B");
    TestSeeder.app(dataSource, APP_CAP, TENANT_A, "App Cap");
  }

  @Test
  void createsAMilestoneKeyedByItsCompletionEventName() {
    GraphQlTester.Response response =
        create(asTenant(TENANT_A), APP_A, "project_created", "Create a project", 1);

    response
        .path("createMilestone.milestone.name")
        .entity(String.class)
        .isEqualTo("project_created");
    response
        .path("createMilestone.milestone.title")
        .entity(String.class)
        .isEqualTo("Create a project");
    response.path("createMilestone.milestone.position").entity(Integer.class).isEqualTo(1);

    UUID id =
        UUID.fromString(response.path("createMilestone.milestone.id").entity(String.class).get());
    assertThat(id.version()).isEqualTo(7);

    String createdAt =
        response.path("createMilestone.milestone.createdAt").entity(String.class).get();
    assertThat(OffsetDateTime.parse(createdAt)).isNotNull();
  }

  @Test
  void rejectsAReservedAutoCaptureName() {
    GraphQlTester.Response response =
        create(asTenant(TENANT_A), APP_A, AutoCapturedEvents.PAGE_VIEW, "Visited a page", 2);

    expectSingleError(
        response, ErrorType.BAD_REQUEST, "The fr. prefix is reserved for auto-captured events.");
  }

  @Test
  void rejectsANameOutsideTheEventGrammar() {
    GraphQlTester.Response response =
        create(asTenant(TENANT_A), APP_A, "projectCreated", "Create a project", 2);

    expectSingleError(
        response,
        ErrorType.BAD_REQUEST,
        "Milestone names are snake_case with a past-tense verb, such as project_created.");
  }

  @Test
  void rejectsANameOverSixtyFourCharacters() {
    GraphQlTester.Response response =
        create(asTenant(TENANT_A), APP_A, "a".repeat(65), "Too long", 2);

    expectSingleError(
        response, ErrorType.BAD_REQUEST, "Milestone names hold at most 64 characters.");
  }

  @Test
  void rejectsADuplicateNameWithinTheApp() {
    create(asTenant(TENANT_A), APP_A, "data_source_connected", "Connect a data source", 3);

    GraphQlTester.Response response =
        create(asTenant(TENANT_A), APP_A, "data_source_connected", "Connect again", 4);

    expectSingleError(
        response,
        ErrorType.BAD_REQUEST,
        "A milestone named 'data_source_connected' already exists for this app.");
  }

  @Test
  void rejectsATakenPositionWithinTheApp() {
    create(asTenant(TENANT_A), APP_A, "report_generated", "Generate a report", 5);

    GraphQlTester.Response response =
        create(asTenant(TENANT_A), APP_A, "invoice_sent", "Send an invoice", 5);

    expectSingleError(
        response, ErrorType.BAD_REQUEST, "A milestone already holds position 5 for this app.");
  }

  @Test
  void resolvesAnotherTenantsAppToNotFound() {
    GraphQlTester.Response response =
        create(asTenant(TENANT_A), APP_B, "project_created", "Create a project", 1);

    expectSingleError(
        response,
        ErrorType.NOT_FOUND,
        "Could not resolve to an app with the id '%s'.".formatted(APP_B));
  }

  @Test
  void rejectsARequestWithoutATenant() {
    GraphQlTester.Response response =
        create(tester, APP_A, "project_created", "Create a project", 6);

    expectSingleError(response, ErrorType.UNAUTHORIZED, "The request carries no tenant.");
  }

  @Test
  void capsAnAppAtTenMilestones() {
    HttpGraphQlTester client = asTenant(TENANT_A);
    for (int position = 1; position <= 10; position++) {
      create(client, APP_CAP, "step_%d_reached".formatted(position), "Step " + position, position)
          .errors()
          .verify();
    }

    GraphQlTester.Response response = create(client, APP_CAP, "step_11_reached", "Step 11", 11);

    expectSingleError(response, ErrorType.BAD_REQUEST, "An app holds at most 10 milestones.");
  }

  private HttpGraphQlTester asTenant(String tenantId) {
    return tester.mutate().headers(headers -> headers.set("X-FirstRun-Tenant", tenantId)).build();
  }

  private static GraphQlTester.Response create(
      HttpGraphQlTester client, String appId, String name, String title, int position) {
    return client
        .document(CREATE_MILESTONE)
        .variable(
            "input", Map.of("appId", appId, "name", name, "title", title, "position", position))
        .execute();
  }

  private static void expectSingleError(
      GraphQlTester.Response response, ErrorType errorType, String message) {
    response
        .errors()
        .satisfy(
            errors -> {
              assertThat(errors).hasSize(1);
              assertThat(errors.getFirst().getErrorType()).isEqualTo(errorType);
              assertThat(errors.getFirst().getMessage()).isEqualTo(message);
            });
  }
}
