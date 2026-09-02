package com.stageaccord.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.stageaccord.sharedkernel.architecture.BusinessModule;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

class ModuleArchitectureTest {

    private static final Set<String> MODULES = Set.of(
            "identityaccess", "workspacemembership", "publiccatalog", "intake", "agreement", "project",
            "collaboration", "filehandling", "privacy", "schedulenotification", "billing", "auditadmin");

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.stageaccord");

    private static final ArchRule DOMAIN_INDEPENDENCE = noClasses()
            .that().resideInAPackage("com.stageaccord..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "jakarta.servlet..",
                    "java.net..", "java.nio.file..",
                    "com.stageaccord..api..", "com.stageaccord..application..",
                    "com.stageaccord..infrastructure..")
            .allowEmptyShould(true);

    @Test
    void allBusinessModuleBoundariesAreDeclared() {
        Set<String> declared = classes.stream()
                .filter(type -> type.isAnnotatedWith(BusinessModule.class))
                .map(type -> moduleOf(type.getPackageName()))
                .collect(Collectors.toSet());

        assertThat(declared).isEqualTo(MODULES);
    }

    @Test
    void domainDoesNotDependOnFrameworksOrOuterLayers() {
        DOMAIN_INDEPENDENCE.check(classes);
    }

    @Test
    void domainRuleRejectsFrameworkCouplingFixture() {
        JavaClasses fixture = new ClassFileImporter().importClasses(
                com.stageaccord.architecture.fixture.domain.FrameworkCoupledDomain.class);

        assertThat(DOMAIN_INDEPENDENCE.evaluate(fixture).hasViolation()).isTrue();
    }

    @Test
    void applicationDoesNotDependOnApiOrInfrastructure() {
        noClasses().that().resideInAPackage("com.stageaccord..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.stageaccord..api..", "com.stageaccord..infrastructure..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void infrastructureDoesNotDependOnApi() {
        noClasses().that().resideInAPackage("com.stageaccord..infrastructure..")
                .should().dependOnClassesThat().resideInAPackage("com.stageaccord..api..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void modulesOnlyUseOtherModulesThroughApiPackages() {
        for (JavaClass origin : classes) {
            String originModule = moduleOf(origin.getPackageName());
            if (!MODULES.contains(originModule)) continue;
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                String targetModule = moduleOf(target.getPackageName());
                if (MODULES.contains(targetModule) && !originModule.equals(targetModule)) {
                    assertThat(target.getPackageName())
                            .as(dependency.getDescription())
                            .contains("." + targetModule + ".api");
                }
            }
        }
    }

    private static String moduleOf(String packageName) {
        String prefix = "com.stageaccord.";
        if (!packageName.startsWith(prefix)) return "";
        return packageName.substring(prefix.length()).split("\\.", 2)[0];
    }
}
