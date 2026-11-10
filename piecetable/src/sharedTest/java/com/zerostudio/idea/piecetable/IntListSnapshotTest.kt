package com.zerostudio.idea.piecetable

import com.zerostudio.idea.piecetable.common.IntList
import org.junit.Test
import kotlin.test.assertEquals

class IntListSnapshotTest {
    @Test
    fun snapshotViewRemainsStableAcrossAllBackingArrayMutations() {
        val values = IntList.of(0, 4, 9)
        val first = values.snapshotView()

        values[1] = 5
        values.shiftAll(2)
        values.add(13)
        values.addAll(IntList.of(17, 21))

        assertEquals(3, first.size)
        assertEquals(listOf(0, 4, 9), (0 until first.size).map { first[it] })
        assertEquals(listOf(2, 7, 11, 15, 17, 21),
            (0 until values.size).map { values[it] })

        val second = values.snapshotView()
        values.removeLast()
        values.clear()
        values.add(100)

        assertEquals(listOf(2, 7, 11, 15, 17, 21),
            (0 until second.size).map { second[it] })
        assertEquals(listOf(100), (0 until values.size).map { values[it] })
    }
}
