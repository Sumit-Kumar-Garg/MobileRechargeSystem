package com.app.recharge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * The team boundaries, enforced by the build. If one of these fails, a team has reached across a contract;
 * fix the dependency (or change the contract with its owner), do not relax the rule.
 */
@AnalyzeClasses(packages = "com.app.recharge", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule providerAdaptersSeeOnlyTheContracts =
            classes().that().resideInAPackage("..providers..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("..providers..", "..spi..", "java..", "org.springframework..", "org.slf4j..");

    @ArchTest
    static final ArchRule nobodyDependsOnAProviderAdapter =
            noClasses().that().resideOutsideOfPackage("..providers..")
                    .should().dependOnClassesThat().resideInAPackage("..providers..");

    @ArchTest
    static final ArchRule contractsAreSelfContained =
            classes().that().resideInAPackage("..spi..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage("..spi..", "java..");

    @ArchTest
    static final ArchRule theMoneyPathDoesNotKnowOptionalFeatures =
            noClasses().that().resideInAnyPackage("..order..", "..payment..", "..provisioning..")
                    .should().dependOnClassesThat().resideInAnyPackage("..notification..", "..recommendation..");

    @ArchTest
    static final ArchRule theCatalogTablesBelongToTheCatalogTeam =
            noClasses().that().resideOutsideOfPackage("..catalog..")
                    .should().dependOnClassesThat().haveSimpleName("PlanRepository");

    /**
     * Every table has one owning module. A repository may only be used by classes of its own module; other
     * modules go through that module's services (OrderStore, PaymentService, RefundService, ...).
     */
    @ArchTest
    static final ArchRule repositoriesAreUsedOnlyInsideTheirOwnModule =
            classes().that().haveSimpleNameEndingWith("Repository")
                    .should(beAccessedOnlyFromTheirOwnPackage());

    private static ArchCondition<JavaClass> beAccessedOnlyFromTheirOwnPackage() {
        return new ArchCondition<>("be used only by classes in their own module") {
            @Override
            public void check(JavaClass repository, ConditionEvents events) {
                repository.getDirectDependenciesToSelf().stream()
                        .filter(d -> !moduleOf(d.getOriginClass()).equals(moduleOf(repository)))
                        .forEach(d -> events.add(SimpleConditionEvent.violated(d,
                                d.getOriginClass().getName() + " uses " + repository.getName()
                                        + ", which belongs to another module")));
            }
        };
    }

    /** com.app.recharge.payment.spi.X -> "payment". Sub-packages belong to their module. */
    private static String moduleOf(JavaClass type) {
        String[] parts = type.getPackageName().split("\\.");
        return parts.length > 3 ? parts[3] : "";
    }

    /** The app must never be able to reach the model: only the agent's gateway may use an LLM client. */
    @ArchTest
    static final ArchRule onlyTheAgentTalksToTheModel =
            noClasses().that().resideOutsideOfPackages("..recommendation.agent..", "..recommendation.llm..",
                            "..recommendation.spi..")
                    .should().dependOnClassesThat().resideInAnyPackage("..recommendation.spi..", "..recommendation.llm..");

    /** The agent can read. It cannot order, pay, provision or notify. */
    @ArchTest
    static final ArchRule theAgentHasNoWayToChangeAnything =
            noClasses().that().resideInAnyPackage("..recommendation.agent..", "..recommendation.llm..")
                    .should().dependOnClassesThat().resideInAnyPackage("..payment..", "..provisioning..", "..notification..");

    @ArchTest
    static final ArchRule theAgentCannotChangeAnOrder =
            noClasses().that().resideInAnyPackage("..recommendation.agent..", "..recommendation.llm..")
                    .should().dependOnClassesThat().haveSimpleName("OrderTransitions");

    /** The mock providers keep their state in memory. Nothing but configuration may select them. */
    @ArchTest
    static final ArchRule nobodyDependsOnAMockProvider =
            noClasses().that().doNotHaveSimpleName("MockPspClient").and().doNotHaveSimpleName("MockOcsClient")
                    .should().dependOnClassesThat().haveSimpleNameStartingWith("Mock");
}
