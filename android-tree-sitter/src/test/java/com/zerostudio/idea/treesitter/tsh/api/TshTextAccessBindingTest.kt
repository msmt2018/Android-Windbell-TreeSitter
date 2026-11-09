package com.zerostudio.idea.treesitter.tsh.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TshTextAccessBindingTest {
    private class Snapshot : TshTextSnapshot {
        override val documentId = "bound-doc"
        override val version = 3L
        override val lengthUtf16 = 4L
        override fun readUtf16(start: Long, end: Long): CharSequence = "text".substring(start.toInt(), end.toInt())
    }

    private class Subscription : TshTextSubscription {
        var closed = false
        override fun close() { closed = true }
    }

    private class Provider(private val host: Any? = null) : TshTextAccessProvider {
        override val id = "text-provider-${System.identityHashCode(this)}"
        override val threading = TshTextAccessThreading.ANY_THREAD
        override val supportsChangeSubscriptions = true
        override val supportsDisposalNotifications = true
        var changeListener: TshTextChangeListener? = null
        var disposalListener: TshTextDocumentDisposedListener? = null
        var changeSubscription: Subscription? = null
        var disposalSubscription: Subscription? = null

        override fun supports(document: Any) = host == null || document === host
        override fun snapshot(document: Any) = TshResult.Success(Snapshot())

        override fun subscribe(
            document: Any,
            listener: TshTextChangeListener
        ): TshResult<TshTextSubscription> {
            changeListener = listener
            return TshResult.Success(Subscription().also { changeSubscription = it })
        }

        override fun subscribeDisposed(
            document: Any,
            listener: TshTextDocumentDisposedListener
        ): TshResult<TshTextSubscription> {
            disposalListener = listener
            return TshResult.Success(Subscription().also { disposalSubscription = it })
        }
    }

    @Test
    fun ownerScopeClosesTextBindingSubscriptionsAndOwnedProviderRegistration() {
        val engine = TshEngine.create()
        engine.initialize()
        val document = Any()
        val provider = Provider(document)
        val scope = TshOwnerScope("text-document-scope")
        val binding = (TshTextAccessBinding.attach(
            engine, provider, document, scope
        ) as TshResult.Success<TshTextAccessBinding>).value

        val snapshot = (binding.acquireSnapshot() as TshResult.Success<TshTextSnapshotLease>).value
        assertEquals(3L, snapshot.snapshot.version)
        snapshot.close()
        val change = (binding.subscribeChanges(TshTextChangeListener { }) as
            TshResult.Success<TshTextSubscription>).value
        assertFalse((change as Subscription).closed)

        scope.close()

        assertTrue(binding.isClosed)
        assertTrue(provider.changeSubscription?.closed == true)
        assertTrue(provider.disposalSubscription?.closed == true)
        assertFalse(engine.providers.all().any { it.id == provider.id })
        assertNull(engine.objects.find(document))
        engine.close()
    }

    @Test
    fun sharedProviderRegistrationRemainsUntilTheLastDocumentBindingCloses() {
        val engine = TshEngine.create()
        engine.initialize()
        val provider = Provider()
        val firstDocument = Any()
        val secondDocument = Any()
        val firstScope = TshOwnerScope("first-doc")
        val secondScope = TshOwnerScope("second-doc")
        val first = (TshTextAccessBinding.attach(
            engine, provider, firstDocument, firstScope
        ) as TshResult.Success<TshTextAccessBinding>).value
        val second = (TshTextAccessBinding.attach(
            engine, provider, secondDocument, secondScope
        ) as TshResult.Success<TshTextAccessBinding>).value

        first.close()
        assertTrue(engine.providers.all().any { it === provider })
        assertTrue(second.acquireSnapshot() is TshResult.Success)

        second.close()
        assertFalse(engine.providers.all().any { it === provider })
        firstScope.close()
        secondScope.close()
        engine.close()
    }

    @Test
    fun externallyRegisteredTextProviderRemainsRegisteredAfterBindingClose() {
        val engine = TshEngine.create()
        engine.initialize()
        val document = Any()
        val provider = Provider(document)
        engine.providers.registerUnique(provider)
        val scope = TshOwnerScope("external-text-provider")
        val binding = (TshTextAccessBinding.attach(
            engine, provider, document, scope
        ) as TshResult.Success<TshTextAccessBinding>).value

        binding.close()

        assertTrue(engine.providers.all().any { it.id == provider.id })
        assertNull(engine.objects.find(document))
        scope.close()
        engine.close()
    }

    @Test
    fun documentDisposalAutomaticallyClosesItsTextBinding() {
        val engine = TshEngine.create()
        engine.initialize()
        val document = Any()
        val provider = Provider(document)
        val scope = TshOwnerScope("disposed-document")
        val binding = (TshTextAccessBinding.attach(
            engine, provider, document, scope
        ) as TshResult.Success<TshTextAccessBinding>).value

        provider.disposalListener!!.onDisposed("bound-doc", 3L)

        assertTrue(binding.isClosed)
        assertTrue(provider.disposalSubscription?.closed == true)
        assertFalse(engine.providers.all().any { it.id == provider.id })
        scope.close()
        engine.close()
    }
}
