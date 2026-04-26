package io.deepmedia.tools.knee.runtime.compiler

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow

@Suppress("unused")
@PublishedApi
internal suspend fun <T> kneeImportedCollectFlow(
    flow: Flow<T>,
    collector: FlowCollector<T>,
) {
    flow.collect(collector)
}

@Suppress("unused")
@PublishedApi
internal suspend fun <T> kneeImportedCollectSharedFlow(
    flow: SharedFlow<T>,
    collector: FlowCollector<T>,
): Nothing {
    return flow.collect(collector)
}

@Suppress("unused")
@PublishedApi
internal suspend fun <T> kneeImportedEmitFlowCollector(
    collector: FlowCollector<T>,
    value: T,
) {
    collector.emit(value)
}
