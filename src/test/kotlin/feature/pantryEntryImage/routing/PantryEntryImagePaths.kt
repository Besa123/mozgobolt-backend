package com.shelflife.feature.pantryEntryImage.routing

import com.shelflife.core.data.idempotency.FakeIdempotencyStore
import com.shelflife.core.data.idempotency.IdempotencyStore
import com.shelflife.core.installTestModules
import com.shelflife.core.routing.apiV1
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.append
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder

object PantryEntryImagePaths {
    private const val BASE = "/api/v1/pantry-entries"

    fun images(entryId: Int) = "$BASE/$entryId/images"

    fun imageById(
        entryId: Int,
        imageId: Int,
    ) = "$BASE/$entryId/images/$imageId"

    fun content(
        entryId: Int,
        imageId: Int,
    ) = "$BASE/$entryId/images/$imageId/content"
}

fun Application.installPantryEntryImageRoutesTestApp(
    pantryEntryImageService: FakePantryEntryImageService = FakePantryEntryImageService(),
    idempotencyStore: IdempotencyStore = FakeIdempotencyStore(),
) {
    installTestModules()
    routing {
        apiV1 {
            pantryEntryImageRoutes(pantryEntryImageService, idempotencyStore)
        }
    }
}

private fun multipartBody(bytes: ByteArray) =
    MultiPartFormDataContent(
        formData {
            // The dedicated (key, filename, contentType) { ... } overload is what actually makes the
            // server parse this as a PartData.FileItem — a plain append(key, ByteArray) part has no
            // filename in its Content-Disposition and is parsed as a form field instead.
            append("image", "test.jpg", ContentType.Image.JPEG) {
                write(bytes)
            }
        },
    )

suspend fun ApplicationTestBuilder.uploadImage(
    path: String,
    bytes: ByteArray = byteArrayOf(1, 2, 3),
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    client.post(path) {
        setBody(multipartBody(bytes))
        block()
    }

suspend fun ApplicationTestBuilder.replaceImage(
    path: String,
    bytes: ByteArray = byteArrayOf(1, 2, 3),
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    client.put(path) {
        setBody(multipartBody(bytes))
        block()
    }
