package com.hotwire.fisiontv.networkqual

import android.content.Context
import com.hotwire.fisiontv.networkqual.config.RuntimeConfigProvider
import com.hotwire.fisiontv.networkqual.data.AppDatabase
import com.hotwire.fisiontv.networkqual.publish.PublishQueue

/**
 * Process-wide service container.
 *
 * One instance per Application — created in [FisionApp.onCreate], read
 * by [MainViewModel] (and any future ViewModels). Holds the long-lived
 * singletons in a single, audit-able place so construction order is
 * explicit and tests can substitute alternatives.
 *
 * No DI framework on purpose — the dependency graph is small enough that
 * the readability cost of Hilt/Koin would exceed the benefit. If the
 * graph grows past ~10 services, revisit.
 */
class AppContainer(context: Context) {
    private val applicationContext: Context = context.applicationContext

    val configProvider: RuntimeConfigProvider = RuntimeConfigProvider()

    val database: AppDatabase = AppDatabase.get(applicationContext)

    val publishQueue: PublishQueue = PublishQueue(database.pendingPublishDao())
}
