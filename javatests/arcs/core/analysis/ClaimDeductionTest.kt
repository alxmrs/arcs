package arcs.core.analysis

import arcs.core.data.AccessPath
import arcs.core.data.Claim
import arcs.core.data.EntityType
import arcs.core.data.FieldType
import arcs.core.data.HandleConnectionSpec
import arcs.core.data.HandleMode
import arcs.core.data.ParticleSpec
import arcs.core.data.Recipe
import arcs.core.data.Schema
import arcs.core.data.SchemaFields
import arcs.core.data.SchemaName
import arcs.core.data.SingletonType
import arcs.core.data.expression.PaxelParser
import arcs.core.testutil.assertThrows
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private fun accessPathOf(
  particle: Recipe.Particle,
  connection: HandleConnectionSpec,
  vararg selectors: String
) = AccessPath(particle, connection, selectors.map { AccessPath.Selector.Field(it) })

@RunWith(JUnit4::class)
class ClaimDeductionTest {
  private val inputPetConnection = HandleConnectionSpec(
    name = "input",
    direction = HandleMode.Read,
    type = SingletonType(EntityType(Schema(
      names = setOf(SchemaName("PetCount")),
      fields = SchemaFields(
        singletons = mapOf(
          "cat" to FieldType.Number,
          "dog" to FieldType.Number,
          "foo" to FieldType.Text
        ),
        collections = emptyMap()
      ),
      hash = "FooHousePetHash"
    )))
  )

  private val outputPetConnection = HandleConnectionSpec(
    name = "output",
    direction = HandleMode.Write,
    type = SingletonType(EntityType(Schema(
      names = setOf(SchemaName("Foo")),
      fields = SchemaFields(
        singletons = mapOf(
          "a" to FieldType.InlineEntity("BarHash"),
          "b" to FieldType.Text,
          "c" to FieldType.Number
        ),
        collections = emptyMap()
      ),
      hash = "FooHousePetHash"
    ))),
    expression = PaxelParser.parse(
      """
      new Foo {
        a: new Bar {
          x: input.cat, 
          y: input.dog
        },
        b: input.foo,
        c: 5
      }
      """.trimIndent()
    )
  )

  @Test
  fun particle_with_no_expressions_produces_no_claims() {
    val connections = mapOf(
      "input" to inputPetConnection,
      "output" to outputPetConnection.copy(expression = null)
    )
    val particleSpec = ParticleSpec(
      name = "FooHousePets",
      location = "arcs.analysis.FooHousePets",
      connections = connections
    )
    val particle = Recipe.Particle(particleSpec, emptyList())
    val actual = RecipeGraph.Node.Particle(particle).deduceClaims()

    assertThat(actual).isEmpty()
  }

  @Test
  fun expressions_must_use_new_object_as_root() {
    val connections = mapOf(
      "input" to inputPetConnection,
      "output" to outputPetConnection.copy(expression = PaxelParser.parse(
        "input.cat + input.dog"
      ))
    )
    val particleSpec = ParticleSpec(
      name = "FooHousePets",
      location = "arcs.analysis.FooHousePets",
      connections = connections
    )
    val particle = Recipe.Particle(particleSpec, emptyList())

    val e = assertThrows(IllegalArgumentException::class) {
      RecipeGraph.Node.Particle(particle).deduceClaims()
    }
    assertThat(e).hasMessageThat().contains("Expression on 'FooHousePets.output' is invalid.")
  }

  @Test
  fun deduction_fails_when_expression_references_a_missing_handle() {
    val connections = mapOf(
      "input" to inputPetConnection,
      "output" to outputPetConnection.copy(expression = PaxelParser.parse(
        "new Foo {a: notAHandle.cat}"
      ))
    )
    val particleSpec = ParticleSpec(
      name = "FooHousePets",
      location = "arcs.analysis.FooHousePets",
      connections = connections
    )
    val particle = Recipe.Particle(particleSpec, emptyList())

    val e = assertThrows(IllegalArgumentException::class) {
      RecipeGraph.Node.Particle(particle).deduceClaims()
    }
    assertThat(e).hasMessageThat().contains(
      "Particle 'FooHousePets' does not have a handle connection called 'notAHandle'."
    )
  }

  @Test
  fun particle_creates_claims_from_inputs() {
    val connections = mapOf(
      "input" to inputPetConnection,
      "output" to outputPetConnection
    )
    val particleSpec = ParticleSpec(
      name = "FooHousePets",
      location = "arcs.analysis.FooHousePets",
      connections = connections
    )
    val particle = Recipe.Particle(particleSpec, emptyList())
    val actual = RecipeGraph.Node.Particle(particle).deduceClaims()

    assertThat(actual).isEqualTo(
      listOf(
        Claim.DerivesFrom(
          accessPathOf(particle, connections["output"]!!, "a", "x"),
          accessPathOf(particle, connections["input"]!!, "cat")
        ),
        Claim.DerivesFrom(
          accessPathOf(particle, connections["output"]!!, "a", "y"),
          accessPathOf(particle, connections["input"]!!, "dog")
        ),
        Claim.DerivesFrom(
          accessPathOf(particle, connections["output"]!!, "b"),
          accessPathOf(particle, connections["input"]!!, "foo")
        )
      )
    )
  }

  @Test
  fun particle_creates_claims_from_derived_inputs() {
    val connections = mapOf(
      "input" to inputPetConnection,
      "output" to outputPetConnection.copy(
        expression = PaxelParser.parse(
          """
          new Foo {
            a: new Bar {
              x: input.cat * input.cat, 
              y: input.dog + input.cat
            }
          }
          """.trimIndent()
        )
      )
    )
    val particleSpec = ParticleSpec(
      name = "FooHousePets",
      location = "arcs.analysis.FooHousePets",
      connections = connections
    )
    val particle = Recipe.Particle(particleSpec, emptyList())
    val actual = RecipeGraph.Node.Particle(particle).deduceClaims()

    assertThat(actual).isEqualTo(
      listOf(
        Claim.DerivesFrom(
          accessPathOf(particle, connections["output"]!!, "a", "x"),
          accessPathOf(particle, connections["input"]!!, "cat")
        ),
        Claim.DerivesFrom(
          accessPathOf(particle, connections["output"]!!, "a", "y"),
          accessPathOf(particle, connections["input"]!!, "dog")
        ),
        Claim.DerivesFrom(
          accessPathOf(particle, connections["output"]!!, "a", "y"),
          accessPathOf(particle, connections["input"]!!, "cat")
        )
      )
    )
  }
}
