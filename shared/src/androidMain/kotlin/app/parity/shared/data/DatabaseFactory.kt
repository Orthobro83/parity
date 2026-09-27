package app.parity.shared.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

fun buildParityDatabase(context: Context): ParityDatabase {
    val app = context.applicationContext
    return Room.databaseBuilder<ParityDatabase>(
        context = app,
        name = app.getDatabasePath("parity.db").absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
