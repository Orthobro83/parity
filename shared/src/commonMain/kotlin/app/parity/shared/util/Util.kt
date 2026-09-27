package app.parity.shared.util

import kotlin.time.Clock
import kotlin.uuid.Uuid

fun now(): Long = Clock.System.now().toEpochMilliseconds()

fun newId(): String = Uuid.random().toString()
