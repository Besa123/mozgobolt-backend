/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

class ArchitectureRulesTest {
    @Test
    fun `domain code does not depend on frameworks or implementation layers`() {
        Konsist
            .scopeFromProduction()
            .files
            .withPackage("..domain..")
            .assertFalse { file ->
                file.imports.any { imported ->
                    val name = imported.name
                    name.startsWith("io.ktor.") ||
                        name.startsWith("org.jetbrains.exposed.") ||
                        name.startsWith("com.mozgobolt.core.data.") ||
                        name.startsWith("com.mozgobolt.core.database.") ||
                        (
                            name.startsWith("com.mozgobolt.feature.") &&
                                (".data." in name || ".service." in name)
                        )
                }
            }
    }

    @Test
    fun `feature service implementations use the I suffix`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .withPackage("..feature..service..")
            .assertTrue { declaration ->
                !declaration.name.contains("Service") || declaration.name.endsWith("ServiceI")
            }
    }
}
