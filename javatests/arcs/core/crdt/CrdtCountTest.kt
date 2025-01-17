/*
 * Copyright 2020 Google LLC.
 *
 * This code may only be used under the BSD style license found at
 * http://polymer.github.io/LICENSE.txt
 *
 * Code distributed by Google as part of this project is also subject to an additional IP rights
 * grant found at
 * http://polymer.github.io/PATENTS.txt
 */

package arcs.core.crdt

import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [CrdtCount]. */
@RunWith(JUnit4::class)
class CrdtCountTest {
  lateinit var alice: CrdtCount
  lateinit var bob: CrdtCount

  @Before
  fun setup() {
    alice = CrdtCount()
    bob = CrdtCount()
  }

  @Test
  fun countInitializesToZero() {
    assertThat(alice.consumerView).isEqualTo(0)
  }

  @Test
  fun canApplyAnIncrementOp() {
    assertTrue(
      alice.applyOperation(CrdtCount.Operation.Increment("alice", VersionMap("alice" to 1)))
    )
    assertThat(alice.consumerView).isEqualTo(1)
  }

  @Test
  fun canApplyAnIncrementOp_withPlusEquals() {
    alice.forActor("alice") += 5
    assertThat(alice.consumerView).isEqualTo(5)
  }

  @Test
  fun canApplyTwoIncrementOps_fromDifferentActors() {
    assertTrue(
      alice.applyOperation(CrdtCount.Operation.Increment("alice", VersionMap("alice" to 1)))
    )
    assertTrue(
      alice.applyOperation(CrdtCount.Operation.Increment("bob", VersionMap("bob" to 1)))
    )
    assertThat(alice.consumerView).isEqualTo(2)
  }

  @Test
  fun canApplyTwoIncrementOps_fromSameActor() {
    assertTrue(
      alice.applyOperation(CrdtCount.Operation.Increment("alice", VersionMap("alice" to 1)))
    )
    assertTrue(
      alice.applyOperation(CrdtCount.Operation.Increment("alice", VersionMap("alice" to 2)))
    )
    assertThat(alice.consumerView).isEqualTo(2)
  }

  @Test
  fun canNotApplyTwoIncrementOps_fromSameActor_withSameVersions() {
    assertTrue(
      alice.applyOperation(CrdtCount.Operation.Increment("alice", VersionMap("alice" to 1)))
    )
    assertFalse(
      alice.applyOperation(CrdtCount.Operation.Increment("alice", VersionMap("alice" to 1)))
    )
    assertThat(alice.consumerView).isEqualTo(1)
  }

  @Test
  fun canApplyAMultiincrementOp() {
    assertTrue(
      alice.applyOperation(
        CrdtCount.Operation.MultiIncrement("alice", VersionMap("alice" to 1), 10)
      )
    )
    assertThat(alice.consumerView).isEqualTo(10)
  }

  @Test
  fun mergesTwoCounts_withIncrementsFromDifferentActors() {
    alice.forActor("alice") += 7
    bob.forActor("bob") += 13

    // merge bob into alice
    val results = alice.merge(bob.data)

    assertThat(alice.consumerView).isEqualTo(20)

    assertThat(results.modelChange).isInstanceOf(CrdtChange.Operations::class.java)
    assertThat((results.modelChange as CrdtChange.Operations)[0])
      .isEqualTo(CrdtCount.Operation.MultiIncrement("bob", VersionMap("bob" to 1), 13))

    assertThat(results.otherChange).isInstanceOf(CrdtChange.Operations::class.java)
    assertThat((results.otherChange as CrdtChange.Operations)[0])
      .isEqualTo(CrdtCount.Operation.MultiIncrement("alice", VersionMap("alice" to 1), 7))

    bob.applyChanges(results.otherChange)
    assertThat(bob.consumerView).isEqualTo(20)
  }

  @Test
  fun mergesTwoCounts_withIncrementsFromTheSameActor() {
    alice.forActor("alice") += 7
    bob.merge(alice.data)
    bob.forActor("alice") += 13

    // merge bob into alice
    val results = alice.merge(bob.data)

    assertThat(alice.consumerView).isEqualTo(20)

    assertThat(results.modelChange).isInstanceOf(CrdtChange.Operations::class.java)
    assertThat((results.modelChange as CrdtChange.Operations)[0])
      .isEqualTo(CrdtCount.Operation.MultiIncrement("alice", VersionMap("alice" to 2), 13))

    assertThat(results.otherChange).isInstanceOf(CrdtChange.Operations::class.java)
    // We already merged alice into bob.
    assertThat((results.otherChange as CrdtChange.Operations)).isEmpty()

    bob.applyChanges(results.otherChange)
    assertThat(bob.consumerView).isEqualTo(20)
  }

  @Test
  fun throwsOnDivergentModels() {
    assertTrue(
      alice.applyOperation(CrdtCount.Operation.MultiIncrement("alice", VersionMap("alice" to 1), 7))
    )
    assertTrue(
      bob.applyOperation(CrdtCount.Operation.MultiIncrement("alice", VersionMap("alice" to 1), 13))
    )
    assertFailsWith<CrdtException> {
      alice.merge(bob.data)
    }
  }

  @Test
  fun throwsOnApparentDecrement() {
    assertTrue(
      alice.applyOperation(CrdtCount.Operation.MultiIncrement("alice", VersionMap("alice" to 1), 7))
    )
    assertTrue(
      bob.applyOperation(CrdtCount.Operation.Increment("alice", VersionMap("alice" to 1)))
    )
    assertTrue(
      bob.applyOperation(CrdtCount.Operation.MultiIncrement("alice", VersionMap("alice" to 2), 3))
    )
    assertFailsWith<CrdtException> {
      alice.merge(bob.data)
    }
  }

  @Test
  fun mergesSeveralActors() {
    alice.forActor("a") += 6
    alice.forActor("c") += 12
    alice.forActor("d") += 22
    // Two increments for 'e' on alice trumps the single increment on bob.
    alice.forActor("e") += 4
    alice.forActor("e") += 13
    bob.forActor("b") += 5
    // Vice versa for 'c'.
    bob.forActor("c") += 12
    bob.forActor("c") += 6
    bob.forActor("d") += 22
    bob.forActor("e") += 4

    val results = alice.merge(bob.data)
    assertThat(alice.consumerView).isEqualTo(68)

    bob.applyChanges(results.otherChange)
    assertThat(bob.consumerView).isEqualTo(68)
  }
}
