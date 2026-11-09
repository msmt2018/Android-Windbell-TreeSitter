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
    @Volatile private var cleanupRegistration: AutoCloseable? = null

    internal fun installCleanup(registration: AutoCloseable) {
        cleanupRegistration = registration
        if (closed.get()) {
            cleanupRegistration = null
            runCatching { registration.close() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        engine.detach(attachment.handle)
        engine.providers.unregister(renderer.id)
        cleanupRegistration?.let { runCatching { it.close() } }
        cleanupRegistration = null
    }

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
            return TshResult.Success(binding, success.diagnostics)
        }

        private fun failure(code: String, message: String, cause: Throwable? = null) =
            TshResult.Failure(listOf(TshDiagnostic(
                code, TshDiagnosticSeverity.ERROR, TshDiagnosticStage.BINDING, message, cause = cause
            )))
    }
}
