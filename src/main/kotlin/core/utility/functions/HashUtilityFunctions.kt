package com.shelflife.core.utility.functions

import java.security.MessageDigest

fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
