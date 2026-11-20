package com.magichandy.mobile.transport.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.test.Test

internal class ArchitectureTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.magichandy.mobile")

    @Test
    fun `the JVM backend and semantic clients cannot depend on Android or device output`() {
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("android..", "androidx..")
            .check(classes)
        noClasses()
            .that()
            .resideInAPackage("..core..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..mobile.transport..")
            .check(classes)
        noClasses()
            .that()
            .resideInAnyPackage("..core.chat..", "..core.autopilot..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..core.motion..", "..core.session..")
            .check(classes)
        noClasses()
            .that()
            .resideInAnyPackage("..core.chat..", "..core.autopilot..")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("DeviceTransport")
            .check(classes)
    }

    @Test
    fun `model providers cannot import device drivers and drivers cannot import providers`() {
        val drivers =
            arrayOf(
                "..transport.ble..",
                "..transport.handy..",
                "..transport.autoblow..",
                "..transport.coyote..",
                "..transport.buttplug..",
            )
        noClasses()
            .that()
            .resideInAPackage("..transport.providers..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(*drivers)
            .check(classes)
        noClasses()
            .that()
            .resideInAnyPackage(*drivers)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..transport.providers..")
            .check(classes)
    }
}
