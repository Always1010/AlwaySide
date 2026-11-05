package com.alwayside.android

/** Process-local request revocation. Editing model parameters or switching active profiles does not cancel a request. */
internal object ActiveServiceRequests {
    private val requests = mutableMapOf<RequestCancellation, AppConfig>()
    private var latest: ServiceCatalog? = null

    @Synchronized fun track(config: AppConfig, cancellation: RequestCancellation) {
        if (config.serviceCatalog == null) return
        latest?.let { if (!ProviderConnections.credentialCurrent(config, it)) cancellation.cancel() }
        cancellation.throwIfCancelled()
        requests[cancellation] = config
    }

    @Synchronized fun release(cancellation: RequestCancellation) { requests.remove(cancellation) }

    @Synchronized fun revoke(catalog: ServiceCatalog) {
        latest = catalog
        requests.entries.filter { !ProviderConnections.credentialCurrent(it.value, catalog) }
            .forEach { (cancellation, _) -> cancellation.cancel() }
    }
}
