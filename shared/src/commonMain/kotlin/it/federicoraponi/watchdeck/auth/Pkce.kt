package it.federicoraponi.watchdeck.auth

import okio.ByteString.Companion.encodeUtf8
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/** An unguessable value, usable in an address. Random UUIDs come from the platform's secure generator. */
@OptIn(ExperimentalUuidApi::class)
internal fun randomToken(): String = urlSafe.encode(Uuid.random().toByteArray() + Uuid.random().toByteArray())

/** The S256 challenge of a PKCE [verifier] (RFC 7636). */
internal fun pkceChallenge(verifier: String): String = urlSafe.encode(verifier.encodeUtf8().sha256().toByteArray())
