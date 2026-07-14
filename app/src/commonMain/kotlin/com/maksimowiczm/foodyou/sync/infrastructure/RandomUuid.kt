package com.maksimowiczm.foodyou.sync.infrastructure

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A random UUID v4 string, per the server contract's entry id (DESIGN §3). */
@OptIn(ExperimentalUuidApi::class) internal fun randomUuid(): String = Uuid.random().toString()
