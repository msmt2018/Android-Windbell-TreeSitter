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
    private val providerRegistration: AutoCloseable?
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val subscriptions = LinkedHashSet<TshTextSubscription>()

    @Volatile var closeDiagnostics: List<TshDiagnostic> = emptyList()
        private set

    @Volatile private var cleanupRegistration: AutoCloseable? = null
    @Volatile private var detachSubscription: AutoCloseable? = null
    @Volatile private var disposalSubscription: TshTextSubscription? = null

    val isClosed: Boolean get() = closed.get()

    fun acquireSnapshot(): TshResult<TshTextSnapshotLease> {
        if (closed.get()) return failure(
            "TSH-TEXT-BINDING-001", "TextAccessBinding is closed"
        )
        return try {
            provider.acquireSnapshot(document)
        } catch (error: Exception) {
            failure("TSH-TEXT-BINDING-015", "Snapshot acquisition failed: ${error.message}", error)
        } catch (error: LinkageError) {
            failure("TSH-TEXT-BINDING-016", "Snapshot acquisition failed with a linkage error", error)
        }
    }

    fun subscribeChanges(listener: TshTextChangeListener): TshResult<TshTextSubscription> {
        if (closed.get()) return failure(
            "TSH-TEXT-BINDING-001", "TextAccessBinding is closed"
        )
        val supportsChanges = try {
            provider.supportsChangeSubscriptions
        } catch (error: Exception) {
            return failure("TSH-TEXT-BINDING-039", "Cannot inspect TextAccessProvider change capability: ${error.message}", error)
        } catch (error: LinkageError) {
            return failure("TSH-TEXT-BINDING-040", "TextAccessProvider change capability lookup failed with a linkage error", error)
        }
        if (!supportsChanges) return failure(
            "TSH-TEXT-BINDING-002", "TextAccessProvider does not advertise change subscriptions"
        )
        val result = try {
            provider.subscribe(document, listener)
        } catch (error: Exception) {
            return failure("TSH-TEXT-BINDING-017", "Text change subscription failed: ${error.message}", error)
        } catch (error: LinkageError) {
            return failure("TSH-TEXT-BINDING-018", "Text change subscription failed with a linkage error", error)
        }
        if (result is TshResult.Failure) return result
        val subscription = (result as TshResult.Success<TshTextSubscription>).value
        val rejected = synchronized(lock) {
            if (closed.get()) true else {
                subscriptions += subscription
                false
            }
        }
        if (rejected) {
            closeSubscription(subscription)
            return failure("TSH-TEXT-BINDING-001", "TextAccessBinding closed while subscribing")
        }
        return TshResult.Success(subscription, result.diagnostics)
    }

    internal fun installDetachSubscription(registration: AutoCloseable) {
        detachSubscription = registration
        if (closed.get()) {
            detachSubscription = null
            closeRegistration(registration, "TSH-TEXT-BINDING-019")
        }
    }

    internal fun installCleanup(registration: AutoCloseable) {
        cleanupRegistration = registration
        if (closed.get()) {
            cleanupRegistration = null
            closeRegistration(registration, "TSH-TEXT-BINDING-020")
        }
    }

    internal fun installDisposalSubscription(subscription: TshTextSubscription) {
        disposalSubscription = subscription
        if (closed.get()) {
            disposalSubscription = null
            closeSubscription(subscription)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val diagnostics = ArrayList<TshDiagnostic>()
        val pendingSubscriptions = synchronized(lock) {
            subscriptions.toList().also { subscriptions.clear() }
        }
        pendingSubscriptions.forEach { subscription ->
            try { subscription.close() } catch (error: Exception) {
                diagnostics += closeDiagnostic("TSH-TEXT-BINDING-021", "Text change subscription close failed: ${error.message}", error)
            } catch (error: LinkageError) {
                diagnostics += closeDiagnostic("TSH-TEXT-BINDING-022", "Text change subscription close failed with a linkage error", error)
            }
        }
        disposalSubscription?.let { subscription ->
            try { subscription.close() } catch (error: Exception) {
                diagnostics += closeDiagnostic("TSH-TEXT-BINDING-023", "Document disposal subscription close failed: ${error.message}", error)
            } catch (error: LinkageError) {
                diagnostics += closeDiagnostic("TSH-TEXT-BINDING-024", "Document disposal subscription close failed with a linkage error", error)
            }
        }
        disposalSubscription = null

        try {
            engine.detach(attachment.handle)
        } catch (error: Exception) {
            diagnostics += closeDiagnostic("TSH-TEXT-BINDING-025", "Text document detach failed: ${error.message}", error)
        } catch (error: LinkageError) {
            diagnostics += closeDiagnostic("TSH-TEXT-BINDING-026", "Text document detach failed with a linkage error", error)
        }
        providerRegistration?.let { registration ->
            try {
                registration.close()
            } catch (error: Exception) {
                diagnostics += closeDiagnostic("TSH-TEXT-BINDING-027", "TextAccessProvider registration lease close failed: ${error.message}", error)
            } catch (error: LinkageError) {
                diagnostics += closeDiagnostic("TSH-TEXT-BINDING-028", "TextAccessProvider registration lease close failed with a linkage error", error)
            }
        }
        detachSubscription?.let { registration ->
            try { registration.close() } catch (error: Exception) {
                diagnostics += closeDiagnostic("TSH-TEXT-BINDING-029", "Detach subscription close failed: ${error.message}", error)
            } catch (error: LinkageError) {
                diagnostics += closeDiagnostic("TSH-TEXT-BINDING-030", "Detach subscription close failed with a linkage error", error)
            }
        }
        detachSubscription = null
        cleanupRegistration?.let { registration ->
            try { registration.close() } catch (error: Exception) {
                diagnostics += closeDiagnostic("TSH-TEXT-BINDING-031", "OwnerScope cleanup close failed: ${error.message}", error)
            } catch (error: LinkageError) {
                diagnostics += closeDiagnostic("TSH-TEXT-BINDING-032", "OwnerScope cleanup close failed with a linkage error", error)
            }
        }
        cleanupRegistration = null
        closeDiagnostics = diagnostics.toList()
    }

    private fun closeSubscription(subscription: TshTextSubscription) {
        try { subscription.close() } catch (error: Exception) {
            closeDiagnostics = closeDiagnostics + closeDiagnostic(
                "TSH-TEXT-BINDING-033", "Cannot close late text subscription: ${error.message}", error
            )
        } catch (error: LinkageError) {
            closeDiagnostics = closeDiagnostics + closeDiagnostic(
                "TSH-TEXT-BINDING-034", "Late text subscription close failed with a linkage error", error
            )
        }
    }

    private fun closeRegistration(registration: AutoCloseable, code: String) {
        try { registration.close() } catch (error: Exception) {
            closeDiagnostics = closeDiagnostics + closeDiagnostic(code, "Lifecycle registration close failed: ${error.message}", error)
        } catch (error: LinkageError) {
            closeDiagnostics = closeDiagnostics + closeDiagnostic(code, "Lifecycle registration close failed with a linkage error", error)
        }
    }

    private fun closeDiagnostic(code: String, message: String, cause: Throwable) = TshDiagnostic(
        code, TshDiagnosticSeverity.WARNING, TshDiagnosticStage.LIFECYCLE, message, cause = cause
    )

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
            val providerCapabilities = try {
                provider.capabilities
            } catch (error: Exception) {
                return failure("TSH-TEXT-BINDING-041", "Cannot inspect TextAccessProvider capabilities: ${error.message}", error)
            } catch (error: LinkageError) {
                return failure("TSH-TEXT-BINDING-042", "TextAccessProvider capability lookup failed with a linkage error", error)
            }
            val optional = optionalCapabilities ?: TshCapabilitySet.from(
                providerCapabilities.all().filterNot { requiredCapabilities.contains(it.id) }
            )
            val overlap = requiredCapabilities.all().map { it.id }
                .intersect(optional.all().map { it.id }.toSet())
            if (overlap.isNotEmpty()) return failure(
                "TSH-TEXT-BINDING-003",
                "Capabilities cannot be both required and optional: ${overlap.sorted().joinToString()}"
            )
            val providerSupportsDocument = try {
                provider.supports(document)
            } catch (error: Exception) {
                return failure("TSH-TEXT-BINDING-035", "TextAccessProvider supports() failed: ${error.message}", error)
            } catch (error: LinkageError) {
                return failure("TSH-TEXT-BINDING-036", "TextAccessProvider supports() failed with a linkage error", error)
            }
            if (!providerSupportsDocument) return failure(
                "TSH-TEXT-BINDING-004", "TextAccessProvider does not support the supplied host document"
            )
            val providerRegistration = if (registerProvider) {
                try {
                    engine.providers.acquireScoped(provider)
                } catch (error: Exception) {
                    return failure(
                        "TSH-TEXT-BINDING-005",
                        "Cannot acquire TextAccessProvider registration: ${error.message}",
                        error
                    )
                } catch (error: LinkageError) {
                    return failure(
                        "TSH-TEXT-BINDING-006",
                        "TextAccessProvider registration failed with a linkage error",
                        error
                    )
                }
            } else null

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
                runCatching { providerRegistration?.close() }
                return failure(
                    "TSH-TEXT-BINDING-007", "Cannot attach TextAccessProvider to host document: ${error.message}", error
                )
            } catch (error: LinkageError) {
                runCatching { providerRegistration?.close() }
                return failure(
                    "TSH-TEXT-BINDING-008", "TextAccessProvider attachment failed with a linkage error", error
                )
            }
            if (attached is TshResult.Failure) {
                runCatching { providerRegistration?.close() }
                return attached
            }
            val report = (attached as TshResult.Success<TshAttachmentReport>).value
            val binding = TshTextAccessBinding(engine, provider, document, report, providerRegistration)

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

            val supportsDisposal = try {
                provider.supportsDisposalNotifications
            } catch (error: Exception) {
                binding.close()
                return failure("TSH-TEXT-BINDING-043", "Cannot inspect document disposal capability: ${error.message}", error)
            } catch (error: LinkageError) {
                binding.close()
                return failure("TSH-TEXT-BINDING-044", "Document disposal capability lookup failed with a linkage error", error)
            }
            if (supportsDisposal) {
                val disposal = try {
                    provider.subscribeDisposed(document) { binding.close() }
                } catch (error: Exception) {
                    binding.close()
                    return failure("TSH-TEXT-BINDING-037", "Document disposal subscription failed: ${error.message}", error)
                } catch (error: LinkageError) {
                    binding.close()
                    return failure("TSH-TEXT-BINDING-038", "Document disposal subscription failed with a linkage error", error)
                }
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
