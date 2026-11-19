/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.di

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class FirebaseAppHandleTest {
    @Test
    fun `owned Firebase app is deleted when its handle closes`() {
        val app = newFirebaseApp("owned-close-test")

        FirebaseAppHandle(app, isOwned = true).close()

        assertFailsWith<IllegalStateException> { FirebaseApp.getInstance(app.name) }
    }

    @Test
    fun `borrowed Firebase app remains available when its handle closes`() {
        val app = newFirebaseApp("borrowed-close-test")

        try {
            FirebaseAppHandle(app, isOwned = false).close()

            assertSame(app, FirebaseApp.getInstance(app.name))
        } finally {
            app.delete()
        }
    }

    private fun newFirebaseApp(name: String): FirebaseApp =
        FirebaseApp.initializeApp(
            FirebaseOptions
                .builder()
                .setCredentials(
                    GoogleCredentials.create(
                        AccessToken("test-token", Date.from(Instant.now().plusSeconds(60))),
                    ),
                ).setProjectId("test-project")
                .build(),
            name,
        )
}
