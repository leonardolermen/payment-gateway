package com.gateway.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The module boundary from spec §2, enforced by test. Each rule is named so a failure says which boundary fell. */
@AnalyzeClasses(packages = "com.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

  @ArchTest
  static void importSeesTheModules(JavaClasses classes) {
    // 202 main classes after Plan C's Bolecode work (`find gateway-*/src/main -name '*.java' | wc -l`);
    // the guard sits at roughly half that so a module accidentally dropped from the scan still trips it
    // well before the count could coincidentally clear the old `> 60`, which every module alone already cleared.
    assertThat(classes.size()).as("ArchUnit imported too few classes; the rules would pass vacuously").isGreaterThan(90);
  }

  @ArchTest
  static final ArchRule kernelImportsNothing =
      noClasses().that().resideInAPackage("com.gateway.kernel..")
          .should().dependOnClassesThat().resideInAnyPackage("com.gateway.merchants..", "com.gateway.orders..", "com.gateway.payments..",
              "com.gateway.providers..", "com.gateway.app..", "org.springframework..", "jakarta.persistence..", "com.barrier..");

  @ArchTest
  static final ArchRule nobodyImportsApp =
      noClasses().that().resideOutsideOfPackage("com.gateway.app..").should().dependOnClassesThat().resideInAPackage("com.gateway.app..");

  @ArchTest
  static final ArchRule businessModulesDoNotImportEachOther =
      noClasses().that().resideInAPackage("com.gateway.merchants..")
          .should().dependOnClassesThat().resideInAnyPackage("com.gateway.orders..", "com.gateway.payments..", "com.gateway.providers..");

  @ArchTest
  static final ArchRule onlyPaymentsKnowsProviders =
      noClasses().that().resideOutsideOfPackages("com.gateway.payments..", "com.gateway.providers..", "com.gateway.app..")
          .should().dependOnClassesThat().resideInAPackage("com.gateway.providers..");

  @ArchTest
  static final ArchRule paymentsDoesNotImportProviders =
      noClasses().that().resideInAPackage("com.gateway.payments..").should().dependOnClassesThat().resideInAPackage("com.gateway.providers..");

  @ArchTest
  static final ArchRule providersOnlyKnowsKernel =
      noClasses().that().resideInAPackage("com.gateway.providers..")
          .should().dependOnClassesThat().resideInAnyPackage("com.gateway.merchants..", "com.gateway.payments..", "com.gateway.orders..", "com.gateway.app..");

  @ArchTest
  static final ArchRule itauVocabularyStaysInProviders =
      noClasses().that().resideOutsideOfPackage("com.gateway.providers..")
          .should().haveSimpleNameContaining("Itau");

  @ArchTest
  static final ArchRule jpaEntitiesArePackagePrivate =
      classes().that().areAnnotatedWith(jakarta.persistence.Entity.class).should().bePackagePrivate();

  /**
   * Packages are by concept (payment, refund, jobs, ...), so "the domain" is no longer a folder. JPA is
   * confined to the persistence sub-packages, and the plain model types (records, enums, aggregates)
   * stay free of Spring: only the classes that are wiring by their nature may see it.
   */
  @ArchTest
  static final ArchRule jpaOnlyInPersistence =
      noClasses().that().resideOutsideOfPackages("..persistence..", "com.gateway.app..", "com.gateway.merchants.repository..")
          .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..");

  @ArchTest
  static final ArchRule modelsHaveNoSpring =
      noClasses().that().resideInAnyPackage("com.gateway.kernel..", "com.gateway.payments..", "com.gateway.merchants.domain..")
          .and().resideOutsideOfPackages("..persistence..", "..support..")
          .and().haveNameNotMatching(".*(Service|Runner|Gateway|Relay|Properties|Configuration|Events)$")
          .should().dependOnClassesThat().resideInAPackage("org.springframework..");
}
