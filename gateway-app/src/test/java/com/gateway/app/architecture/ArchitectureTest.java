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
    assertThat(classes.size()).as("ArchUnit imported too few classes; the rules would pass vacuously").isGreaterThan(30);
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

  @ArchTest
  static final ArchRule domainHasNoSpringOrJpa =
      noClasses().that().resideInAPackage("..domain..")
          .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..");
}
