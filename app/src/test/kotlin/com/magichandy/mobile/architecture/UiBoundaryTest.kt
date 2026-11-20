package com.magichandy.mobile.architecture

import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * The UI talks to everything behind it only through `core.api` (plan §5.1, `AGENTS.md`), so it
 * can't build motion payloads, reach a device or hold a fake.
 */
internal class UiBoundaryTest {
    private val ui = ClassFileImporter().importPackages("com.magichandy.mobile.ui")

    @Test
    fun `the UI depends on the core only through its API`() {
        noClasses()
            .that()
            .resideInAPackage("com.magichandy.mobile.ui..")
            .should()
            .dependOnClassesThat(
                resideInAPackage("com.magichandy.mobile.core..")
                    .and(not(resideInAPackage("com.magichandy.mobile.core.api..")))
            )
            .check(ui)
    }

    @Test
    fun `the UI never reaches device or provider transports`() {
        noClasses()
            .that()
            .resideInAPackage("com.magichandy.mobile.ui..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.magichandy.mobile.transport..")
            .check(ui)
    }
}
