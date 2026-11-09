package com.zerostudio.idea.treesitter.tsh.android

import com.zerostudio.idea.treesitter.tsh.api.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scoped bridge between an existing host editor object, Engine Provider discovery and the
 * Android Spannable renderer. Closing it unregisters only TSH's binding/provider records.
 */
class TshAndroidSpannableBinding private constructor(
    private val engine: TshEngine,
    val renderer: TshAndroidSpannableRenderer,
    val attachment: TshAttachmentReport
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val renderCoordinator = TshRenderCoordinator(renderer)
    @Volatile var closeDiagnostics: List<TshDiagnostic> = emptyList()
        private set
    @Volatile private var cleanupRegistration: AutoCloseable? = null
    @Volatile private var detachSubscription: AutoCloseable? = null

    val isClosed: Boolean get() = closed.get()

    internal fun installDetachSubscription(registration: AutoCloseable) {
        detachSubscription = registration
        if (closed.get()) {
            detachSubscription = null
            runCatching { registration.close() }
        }
    }
    @Volatile private var closeFailureDiagnostics: List<TshDiagnostic> = emptyList()

    /** Cleanup failures are observable even though AutoCloseable.close() has no result value. */
    fun closeDiagnostics(): List<TshDiagnostic> = closeFailureDiagnostics

    internal fun installCleanup(registration: AutoCloseable) {
        cleanupRegistration = registration
        if (closed.get()) {
            cleanupRegistration = null
            closeRegistration(registration, "TSH-ANDROID-BINDING-009")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val failures = ArrayList<TshDiagnostic>()
        try {
            engine.detach(attachment.handle)
        } catch (error: Exception) {
            failures += closeDiagnostic(
                "TSH-ANDROID-BINDING-010",
                "Cannot detach Android renderer attachment: ${error.message}",
                error
            )
        } catch (error: LinkageError) {
            failures += closeDiagnostic(
                "TSH-ANDROID-BINDING-011",
                "Android renderer detach failed with a linkage error",
                error
            )
        }
        try {
            engine.providers.unregister(renderer.id)
        } catch (error: Exception) {
            failures += closeDiagnostic(
                "TSH-ANDROID-BINDING-012",
                "Cannot unregister Android renderer Provider: ${error.message}",
                error
            )
        } catch (error: LinkageError) {
            failures += closeDiagnostic(
                "TSH-ANDROID-BINDING-013",
                "Android renderer Provider unregister failed with a linkage error",
                error
            )
        }
        cleanupRegistration?.let { registration ->
            try {
                registration.close()
            } catch (error: Exception) {
                failures += closeDiagnostic(
                    "TSH-ANDROID-BINDING-014",
                    "Cannot remove Android Binding OwnerScope cleanup registration: ${error.message}",
                    error
                )
            } catch (error: LinkageError) {
                failures += closeDiagnostic(
                    "TSH-ANDROID-BINDING-015",
                    "Android Binding cleanup unregister failed with a linkage error",
                    error
                )
            }
        }
        cleanupRegistration = null
        closeFailureDiagnostics = failures.toList()
    }

    private fun closeRegistration(registration: AutoCloseable, code: String) {
        try {
            registration.close()
        } catch (error: Exception) {
            closeFailureDiagnostics = closeFailureDiagnostics + closeDiagnostic(
                code, "Cannot remove Android Binding cleanup registration: ${error.message}", error
            )
        } catch (error: LinkageError) {
            closeFailureDiagnostics = closeFailureDiagnostics + closeDiagnostic(
                code, "Android Binding cleanup registration failed with a linkage error", error
            )
        }
    }

    private fun closeDiagnostic(code: String, message: String, cause: Throwable) = TshDiagnostic(
        code, TshDiagnosticSeverity.ERROR, TshDiagnosticStage.LIFECYCLE, message, cause = cause
    )

    companion object {
        internal fun attach(
            engine: TshEngine,
            ownerScope: TshOwnerScope,
            renderer: TshAndroidSpannableRenderer,
            objectType: String,
            objectCapabilities: TshCapabilitySet,
            metadata: Map<String, String>,
            requiredCapabilities: TshCapabilitySet,
            optionalCapabilities: TshCapabilitySet
        ): TshResult<TshAndroidSpannableBinding> {
            val overlap = requiredCapabilities.all().map { it.id }
                .intersect(optionalCapabilities.all().map { it.id }.toSet())
            if (overlap.isNotEmpty()) return failure(
                "TSH-ANDROID-BINDING-003",
                "Capabilities cannot be both required and optional: ${overlap.sorted().joinToString()}"
            )
            try {
                engine.providers.registerUnique(renderer)
            } catch (error: Exception) {
                return failure(
                    "TSH-ANDROID-BINDING-001",
                    "Cannot register Android renderer Provider: ${error.message}",
                    error
                )
            } catch (error: LinkageError) {
                return failure(
                    "TSH-ANDROID-BINDING-004",
                    "Android renderer Provider registration failed with a linkage error: ${error.message}",
                    error
                )
            }
            val attached = try {
                engine.attach(
                    TshAttachmentRequest(
                        instance = renderer.hostObjectForBinding(),
                        objectType = objectType,
                        capabilities = objectCapabilities,
                        metadata = metadata,
                        requiredCapabilities = requiredCapabilities,
                        optionalCapabilities = optionalCapabilities
                    ),
                    ownerScope
                )
            } catch (error: Exception) {
                engine.providers.unregister(renderer.id)
                return failure(
                    "TSH-ANDROID-BINDING-005",
                    "Cannot attach Android renderer to the host object: ${error.message}",
                    error
                )
            } catch (error: LinkageError) {
                engine.providers.unregister(renderer.id)
                return failure(
                    "TSH-ANDROID-BINDING-006",
                    "Android host attachment failed with a linkage error: ${error.message}",
                    error
                )
            }
            if (attached is TshResult.Failure) {
                engine.providers.unregister(renderer.id)
                return attached
            }
            val success = attached as TshResult.Success<TshAttachmentReport>
            val report = success.value
            val binding = TshAndroidSpannableBinding(engine, renderer, report)
            val detachedSubscription = engine.attachments.subscribeDetached(report.handle) {
                binding.close()
            }
            if (detachedSubscription == null) {
                binding.close()
                return failure(
                    "TSH-ANDROID-BINDING-009",
                    "Attachment was detached before its lifecycle observer could be installed"
                )
            }
            binding.installDetachSubscription(detachedSubscription)
            if (binding.isClosed) {
                return failure(
                    "TSH-ANDROID-BINDING-010",
                    "Attachment was detached while the Android binding was being installed"
                )
            }
            val cleanup = try {
                ownerScope.registerCleanup { binding.close() }
            } catch (error: Exception) {
                binding.close()
                return failure(
                    "TSH-ANDROID-BINDING-007",
                    "Cannot register Android binding cleanup: ${error.message}",
                    error
                )
            } catch (error: LinkageError) {
                binding.close()
                return failure(
                    "TSH-ANDROID-BINDING-008",
                    "Android binding cleanup registration failed with a linkage error: ${error.message}",
                    error
                )
            }
            if (cleanup == null) {
                binding.close()
                return failure(
                    "TSH-ANDROID-BINDING-002",
                    "OwnerScope closed while Android renderer binding was being installed"
                )
            }
            binding.installCleanup(cleanup)
            if (binding.isClosed) {
                return failure(
                    "TSH-ANDROID-BINDING-011",
                    "OwnerScope or Engine detached the Android binding during setup"
                )
            }
            return TshResult.Success(binding, success.diagnostics)
        }

        private fun failure(code: String, message: String, cause: Throwable? = null) =
            TshResult.Failure(listOf(TshDiagnostic(
                code, TshDiagnosticSeverity.ERROR, TshDiagnosticStage.BINDING, message, cause = cause
            )))
    }
}
