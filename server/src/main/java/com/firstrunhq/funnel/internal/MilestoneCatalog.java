package com.firstrunhq.funnel.internal;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import com.firstrunhq.identity.TenantContext;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Defines activation milestones, at most ten per app. */
@Component
class MilestoneCatalog {

  /**
   * Mirrors the custom-event grammar in api/openapi/ingest.yaml, since a milestone name doubles as
   * its completion event name. Dotless, so it already excludes {@code fr.}-prefixed names; the
   * prefix check ahead of it only buys a clearer error.
   */
  private static final Pattern NAME_GRAMMAR = Pattern.compile("[a-z][a-z0-9_]*");

  private static final int NAME_MAX_LENGTH = 64;
  private static final int MILESTONES_PER_APP_MAX = 10;
  private static final NoArgGenerator UUID_V7 = Generators.timeBasedEpochRandomGenerator();

  private final JdbcClient jdbc;
  private final TenantContext tenantContext;

  MilestoneCatalog(JdbcClient jdbc, TenantContext tenantContext) {
    this.jdbc = jdbc;
    this.tenantContext = tenantContext;
  }

  /** Defines one milestone under the app's cap, in the tenant's RLS scope. */
  @Transactional
  Milestone create(UUID tenantId, CreateMilestoneInput input) {
    validate(input);
    UUID appId = parseAppId(input.appId());
    tenantContext.scopeTo(tenantId);
    int defined =
        jdbc.sql("SELECT count(*) FROM milestone WHERE app_id = :app_id")
            .param("app_id", appId)
            .query(Integer.class)
            .single();
    if (defined >= MILESTONES_PER_APP_MAX) {
      throw MilestoneDefinitionException.invalidInput(
          "An app holds at most %d milestones.".formatted(MILESTONES_PER_APP_MAX));
    }
    UUID id = UUID_V7.generate();
    try {
      OffsetDateTime createdAt =
          jdbc.sql(
                  """
                  INSERT INTO milestone (id, tenant_id, app_id, name, title, position)
                  VALUES (:id, :tenant_id, :app_id, :name, :title, :position)
                  RETURNING created_at
                  """)
              .param("id", id)
              .param("tenant_id", tenantId)
              .param("app_id", appId)
              .param("name", input.name())
              .param("title", input.title())
              .param("position", input.position())
              .query(OffsetDateTime.class)
              .single();
      return new Milestone(id, input.name(), input.title(), input.position(), createdAt);
    } catch (DuplicateKeyException e) {
      throw duplicate(input, e);
    } catch (DataIntegrityViolationException e) {
      // The composite foreign key rejects an app that is absent or another tenant's, and both
      // read as not found so the error never confirms a foreign app exists.
      throw MilestoneDefinitionException.appNotFound(input.appId());
    }
  }

  /** Rejects an input that breaks the name grammar, a blank title, or a position under 1. */
  private static void validate(CreateMilestoneInput input) {
    if (input.name().startsWith("fr.")) {
      throw MilestoneDefinitionException.invalidInput(
          "The fr. prefix is reserved for auto-captured events.");
    }
    if (input.name().length() > NAME_MAX_LENGTH) {
      throw MilestoneDefinitionException.invalidInput(
          "Milestone names hold at most %d characters.".formatted(NAME_MAX_LENGTH));
    }
    if (!NAME_GRAMMAR.matcher(input.name()).matches()) {
      throw MilestoneDefinitionException.invalidInput(
          "Milestone names are snake_case with a past-tense verb, such as project_created.");
    }
    if (input.title().isBlank()) {
      throw MilestoneDefinitionException.invalidInput("The title cannot be blank.");
    }
    if (input.position() < 1) {
      throw MilestoneDefinitionException.invalidInput("Positions start at 1.");
    }
  }

  /** Parses the app id, answering an unparseable one as the same not-found an unknown one gets. */
  private static UUID parseAppId(String appId) {
    try {
      return UUID.fromString(appId);
    } catch (IllegalArgumentException unparseable) {
      throw MilestoneDefinitionException.appNotFound(appId);
    }
  }

  /** Names which unique constraint the insert hit, the milestone name or its position. */
  private static MilestoneDefinitionException duplicate(
      CreateMilestoneInput input, DuplicateKeyException e) {
    String detail = String.valueOf(e.getMessage());
    if (detail.contains("milestone_app_id_name_key")) {
      return MilestoneDefinitionException.invalidInput(
          "A milestone named '%s' already exists for this app.".formatted(input.name()));
    }
    return MilestoneDefinitionException.invalidInput(
        "A milestone already holds position %d for this app.".formatted(input.position()));
  }
}
