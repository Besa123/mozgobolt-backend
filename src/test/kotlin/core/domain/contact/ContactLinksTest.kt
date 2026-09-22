package com.mozgobolt.core.domain.contact

import kotlin.test.Test
import kotlin.test.assertEquals

class ContactLinksTest {
    @Test
    fun `whatsAppLink strips everything but digits from an E164 number`() {
        assertEquals("https://wa.me/36201234567", whatsAppLink("+36201234567"))
    }

    @Test
    fun `whatsAppLink strips spaces and dashes too, not just the leading plus`() {
        assertEquals("https://wa.me/36201234567", whatsAppLink("+36 20 123 4567"))
        assertEquals("https://wa.me/36201234567", whatsAppLink("+36-20-123-4567"))
    }

    @Test
    fun `viberLink builds the official chat deep link with digits only`() {
        assertEquals("viber://chat?number=36201234567", viberLink("+36201234567"))
    }

    @Test
    fun `messengerLink builds the official m me deep link verbatim`() {
        assertEquals("https://m.me/family.frost.bakery", messengerLink("family.frost.bakery"))
    }
}
