package com.zerostudio.idea.treesitter.tsh.api

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Binds a host-owned document to a registered TextAccessProvider.
 *
 * The document and provider remain host-owned. This binding owns only the Provider registration
 * (when requested), Attachment, change/disposal subscriptions and lifecycle bookkeeping.
 */
class TshTextAccessBinding private constructor(
    private val engine: TshEngine,
    val provider: TshTextAccessProvider,
    val document: Any,
    val attachment: TshAttachmentReport,
    private val ownsProviderRegistration: Boolean
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val subscriptions = LinkedHashSet<TshTextSubscription>()

    @Volatile private var cleanupRegistration: AutoCloseable? = null
    @Volatile private var detachSubscription: AutoCloseable? = null
    @Volatile private var disposalSubscription: TshTextSubscription? = null

    val isClosed: Boolean get() = closed.get()

    fun acquireSnapshot(): TshResult<TshTextSnapshotLease> {
        if (closed.get()) return failure(
            "TSH-TEXT-BINDING-001", "TextAccessBinding is closed"
        )
        return provider.acquireSnapshot(document)
    }

    fun subscribeChanges(listener: TshTextChangeListener): TshResult<TshTextSubscription> {
        if (closed.get()) return failure(
            "TSH-TEXT-BINDING-001", "TextAccessBinding is closed"
        )
        if (!provider.supportsChangeSubscriptions) return failure(
            "TSH-TEXT-BINDING-002", "TextAccessProvider does not advertise change subscriptions"
        )
        val result = provider.subscribe(document, listener)
        if (result is TshResult.Failure) return result
        val subscription = (result as TshResult.Success<TshTextSubscription>).value
        synchronized(lock) {
            if (closed.get()) {
                runCatching { subscription.close() }
                return failure("TSH-TEXT-BINDING-001", "TextAccessBinding closed while subscribing")
            }
            subscriptions += subscription
        }
        return TshResult.Success(subscription, result.diagnostics)
    }

    internal fun installDetachSubscription(registration: AutoCloseable) {
        detachSubscription = registration
        if (closed.get()) {
            detachSubscription = null
            runCatching { registration.close() }
        }
    }

    internal fun installCleanup(registration: AutoCloseable) {
        cleanupRegistration = registration
        if (closed.get()) {
            cleanupRegistration = null
            runCatching { registration.close() }
        }
    }

    internal fun installDisposalSubscription(subscription: TshTextSubscription) {
        disposalSubscription = subscription
        if (closed.get()) {
            disposalSubscription = null
            runCatching { subscription.close() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val pendingSubscriptions = synchronized(lock) {
            subscriptions.toList().also { subscriptions.clear() }
        }
        pendingSubscriptions.forEach { runCatching { it.close() } }
        disposalSubscription?.let { runCatching { it.close() } }
        disposalSubscription = null

        engine.detach(attachment.handle)
        if (ownsProviderRegistration) engine.providers.unregister(provider.id)

        detachSubscription?.let { runCatching { it.close() } }
        detachSubscription = null
        cleanupRegistration?.let { runCatching { it.close() } }
        cleanupRegistration = null
    }

    companion object {
        fun attach(
            engine: TshEngine,
            provider: TshTextAccessProvider,
            document: Any,
            ownerScope: TshOwnerScope,
            objectType: String = "text-document",
            objectCapabilities: TshCapabilitySet = TshCapabilitySet.EMPTY,
            metadata: Map<String, String> = emptyMap(),
            requiredCapabilities: TshCapabilitySet = TshCapabilitySet.of(TshCapability("text.snapshot", 1)),
            optionalCapabilities: TshCapabilitySet? = null,
            registerProvider: Boolean = true
        ): TshResult<TshTextAccessBinding> {
            val optional = optionalCapabilities ?: TshCapabilitySet.from(
                provider.capabilities.all().filterNot { requiredCapabilities.contains(it.id) }
            )
            val overlap = requiredCapabilities.all().map { it.id }
                .intersect(optional.all().map { it.id }.toSet())
            if (overlap.isNotEmpty()) return failure(
                "TSH-TEXT-BINDING-003",
                "Capabilities cannot be both required and optional: ${overlap.sorted().joinToString()}"
            )
            if (!provider.supports(document)) return failure(
                "TSH-TEXT-BINDING-004", "TextAccessProvider does not support the supplied host document"
            )
            if (registerProvider) {
                try {
                    engine.providers.registerUnique(provider)
                } catch (error: Exception) {
                    return failure(
                        "TSH-TEXT-BINDING-005",
                        "Cannot register TextAccessProvider: ${error.message}",
                        error
                    )
                } catch (error: LinkageError) {
                    return failure(
                        "TSH-TEXT-BINDING-006",
                        "TextAccessProvider registration failed with a linkage error",
                        error
                    )
                }
            }

            val attached = try {
                engine.attach(
                    TshAttachmentRequest(
                        instance = document,
                        objectType = objectType,
                        capabilities = objectCapabilities,
                        metadata = metadata,
                        requiredCapabilities = requiredCapabilities,
                        optionalCapabilities = optional
                    ),
                    ownerScope
                )
            } catch (error: Exception) {
                if (registerProvider) engine.providers.unregister(provider.id)
                return failure(
                    "TSH-TEXT-BINDING-007", "Cannot attach TextAccessProvider to host document: ${error.message}", error
                )
            } catch (error: LinkageError) {
                if (registerProvider) engine.providers.unregister(provider.id)
                return failure(
                    "TSH-TEXT-BINDING-008", "TextAccessProvider attachment failed with a linkage error", error
                )
            }
            if (attached is TshResult.Failure) {
                if (registerProvider) engine.providers.unregister(provider.id)
                return attached
            }
            val report = (attached as TshResult.Success<TshAttachmentReport>).value
            val binding = TshTextAccessBinding(engine, provider, document, report, registerProvider)

            val detachSubscription = engine.attachments.subscribeDetached(report.handle) {
                binding.close()
            }
            if (detachSubscription == null) {
                binding.close()
                return failure(
                    "TSH-TEXT-BINDING-009", "Attachment detached before TextAccessBinding lifecycle setup"
                )
            }
            binding.installDetachSubscription(detachSubscription)
            if (binding.isClosed) return failure(
                "TSH-TEXT-BINDING-010", "Attachment detached while TextAccessBinding was being installed"
            )

            if (provider.supportsDisposalNotifications) {
                val disposal = provider.subscribeDisposed(document) { binding.close() }
                if (disposal is TshResult.Failure) {
                    binding.close()
                    return TshResult.Failure(disposal.diagnostics)
                }
                binding.installDisposalSubscription(
                    (disposal as TshResult.Success<TshTextSubscription>).value
                )
            }

            val cleanup = try {
                ownerScope.registerCleanup { binding.close() }
            } catch (error: Exception) {
                binding.close()
                return failure(
                    "TSH-TEXT-BINDING-011", "Cannot register TextAccessBinding cleanup: ${error.message}", error
                )
            } catch (error: LinkageError) {
                binding.close()
                return failure(
                    "TSH-TEXT-BINDING-012", "TextAccessBinding cleanup registration failed with a linkage error", error
                )
            }
            if (cleanup == null) {
                binding.close()
                return failure(
                    "TSH-TEXT-BINDING-013", "OwnerScope closed while TextAccessBinding was being installed"
                )
            }
            binding.installCleanup(cleanup)
            if (binding.isClosed) return failure(
                "TSH-TEXT-BINDING-014", "OwnerScope or Engine detached TextAccessBinding during setup"
            )
            return TshResult.Success(binding, report.diagnostics)
        }

        private fun failure(code: String, message: String, cause: Throwable? = null) =
            TshResult.Failure(listOf(TshDiagnostic(
                code, TshDiagnosticSeverity.ERROR, TshDiagnosticStage.BINDING, message, cause = cause
            )))
    }
}
