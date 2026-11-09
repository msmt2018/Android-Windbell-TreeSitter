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

    @Volatile
    var closeDiagnostics: List<TshDiagnostic> = emptyList()
        private set

    @Volatile private var cleanupRegistration: AutoCloseable? = null
    @Volatile private var detachSubscription: AutoCloseable? = null

    val isClosed: Boolean get() = closed.get()

    internal fun installDetachSubscription(registration: AutoCloseable) {
        detachSubscription = registration
        if (closed.get()) {
            detachSubscription = null
            closeRegistration(registration, "TSH-ANDROID-BINDING-009")
        }
    }

    internal fun installCleanup(registration: AutoCloseable) {
        cleanupRegistration = registration
        if (closed.get()) {
            cleanupRegistration = null
            closeRegistration(registration, "TSH-ANDROID-BINDING-010")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val failures = ArrayList<TshDiagnostic>()
        failures += renderCoordinator.close().diagnostics()

        try {
            engine.detach(attachment.handle)
        } catch (error: Exception) {
            failures += closeDiagnostic(
                "TSH-ANDROID-BINDING-011",
                "Cannot detach Android renderer attachment: ${error.message}",
                error
            )
        } catch (error: LinkageError) {
            failures += closeDiagnostic(
                "TSH-ANDROID-BINDING-012",
                "Android renderer detach failed with a linkage error",
                error
            )
        }

        try {
            engine.providers.unregister(renderer.id)
        } catch (error: Exception) {
            failures += closeDiagnostic(
                "TSH-ANDROID-BINDING-013",
                "Cannot unregister Android renderer Provider: ${error.message}",
                error
            )
        } catch (error: LinkageError) {
            failures += closeDiagnostic(
                "TSH-ANDROID-BINDING-014",
                "Android renderer Provider unregister failed with a linkage error",
                error
            )
        }

        detachSubscription?.let { registration ->
            try {
                registration.close()
            } catch (error: Exception) {
                failures += closeDiagnostic(
                    "TSH-ANDROID-BINDING-016",
                    "Cannot remove Android attachment lifecycle subscription: ${error.message}",
                    error
                )
            } catch (error: LinkageError) {
                failures += closeDiagnostic(
                    "TSH-ANDROID-BINDING-017",
                    "Android attachment lifecycle subscription close failed with a linkage error",
                    error
                )
            }
        }
        detachSubscription = null
        cleanupRegistration?.let { registration ->
            try {
                registration.close()
            } catch (error: Exception) {
                failures += closeDiagnostic(
                    "TSH-ANDROID-BINDING-015",
                    "Cannot remove Android Binding OwnerScope cleanup registration: ${error.message}",
                    error
                )
            } catch (error: LinkageError) {
                failures += closeDiagnostic(
                    "TSH-ANDROID-BINDING-016",
                    "Android Binding cleanup unregister failed with a linkage error",
                    error
                )
            }
        }
        cleanupRegistration = null

        detachSubscription?.let { registration ->
            try {
                registration.close()
            } catch (error: Exception) {
                failures += closeDiagnostic(
                    "TSH-ANDROID-BINDING-017",
                    "Cannot remove attachment detach subscription: ${error.message}",
                    error
                )
            } catch (error: LinkageError) {
                failures += closeDiagnostic(
                    "TSH-ANDROID-BINDING-018",
                    "Attachment detach subscription failed with a linkage error",
                    error
                )
            }
        }
        detachSubscription = null
        closeDiagnostics = failures.toList()
    }

    private fun closeRegistration(registration: AutoCloseable, code: String) {
        try {
            registration.close()
        } catch (error: Exception) {
            closeDiagnostics = closeDiagnostics + closeDiagnostic(
                code, "Cannot remove Android Binding lifecycle registration: ${error.message}", error
            )
        } catch (error: LinkageError) {
            closeDiagnostics = closeDiagnostics + closeDiagnostic(
                code, "Android Binding lifecycle registration failed with a linkage error", error
            )
        }
    }

    private fun closeDiagnostic(code: String, message: String, cause: Throwable) = TshDiagnostic(
        code, TshDiagnosticSeverity.WARNING, TshDiagnosticStage.LIFECYCLE, message, cause = cause
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
            val binding = TshAndroidSpannableBinding(engine, renderer, success.value)

            val detachedSubscription = engine.attachments.subscribeDetached(success.value.handle) {
                binding.close()
            }
            if (detachedSubscription == null) {
                binding.close()
                return failure(
                    "TSH-ANDROID-BINDING-007",
                    "Attachment was detached before its lifecycle observer could be installed"
                )
            }
            binding.installDetachSubscription(detachedSubscription)
            if (binding.isClosed) {
                return failure(
                    "TSH-ANDROID-BINDING-008",
                    "Attachment was detached while the Android binding was being installed"
                )
            }

            val cleanup = try {
                ownerScope.registerCleanup { binding.close() }
            } catch (error: Exception) {
                binding.close()
                return failure(
                    "TSH-ANDROID-BINDING-019",
                    "Cannot register Android binding cleanup: ${error.message}",
                    error
                )
            } catch (error: LinkageError) {
                binding.close()
                return failure(
                    "TSH-ANDROID-BINDING-020",
                    "Android binding cleanup registration failed with a linkage error",
                    error
                )
            }
            if (cleanup == null) {
                binding.close()
                return failure(
                    "TSH-ANDROID-BINDING-021",
                    "OwnerScope closed while Android renderer binding was being installed"
                )
            }
            binding.installCleanup(cleanup)
            if (binding.isClosed) {
                return failure(
                    "TSH-ANDROID-BINDING-022",
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
