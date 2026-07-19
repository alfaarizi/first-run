package com.firstrunhq;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

  // The generated gRPC stubs share the com.firstrunhq root (their package
  // mirrors the proto package), so module detection must not read them as a
  // twelfth module.
  ApplicationModules modules =
      ApplicationModules.of(
          FirstRunApplication.class, JavaClass.Predicates.resideInAPackage("com.firstrunhq.v1.."));

  @Test
  void verifiesModuleStructure() {
    modules.verify();
  }

  @Test
  void declaresAllElevenModules() {
    assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
        .containsExactlyInAnyOrder(
            "identity",
            "apps",
            "ingestion",
            "funnel",
            "decisioning",
            "actions",
            "ledger",
            "knowledge",
            "analytics",
            "billing",
            "notifications");
  }

  @Test
  void ledgerNeverUpdatesOrDeletes() {
    JavaClasses productionClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.firstrunhq");
    noMethods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("com.firstrunhq.ledger..")
        .should()
        .haveNameMatching("(update|delete|remove).*")
        .allowEmptyShould(true)
        .because("ledger rows are immutable and corrections are new rows")
        .check(productionClasses);
  }
}
