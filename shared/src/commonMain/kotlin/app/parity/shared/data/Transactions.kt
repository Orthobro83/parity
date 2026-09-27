package app.parity.shared.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/** Runs [block] in one write transaction; DAO calls inside it join the transaction. */
suspend fun <R> ParityDatabase.useWriterConnectionTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
