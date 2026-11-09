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
            try {
                engine.providers.registerUnique(renderer)
            } catch (error: Exception) {
                return failure(
                    "TSH-ANDROID-BINDING-001",
                    "Cannot register Android renderer Provider: ${error.message}",
                    error
                )
            }
            val attached = engine.attach(
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
            if (attached is TshResult.Failure) {
                engine.providers.unregister(renderer.id)
                return attached
            }
            val report = (attached as TshResult.Success).value
            val binding = TshAndroidSpannableBinding(engine, renderer, report)
            val cleanup = ownerScope.registerCleanup { binding.close() }
            if (cleanup == null) {
                binding.close()
                return failure(
                    "TSH-ANDROID-BINDING-002",
                    "OwnerScope closed while Android renderer binding was being installed"
                )
            }
            binding.installCleanup(cleanup)
            return TshResult.Success(binding, attached.diagnostics())
        }

        private fun failure(code: String, message: String, cause: Throwable? = null) =
            TshResult.Failure(listOf(TshDiagnostic(
                code, TshDiagnosticSeverity.ERROR, TshDiagnosticStage.BINDING, message, cause = cause
            )))
    }
}
