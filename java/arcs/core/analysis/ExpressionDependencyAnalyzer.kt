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
package arcs.core.analysis

import arcs.core.data.Claim
import arcs.core.data.ParticleSpec
import arcs.core.data.expression.Expression

private typealias Scope = DependencyNode.BufferedScope

/**
 * A visitor that parses Paxel [Expression]s to produce data flow dependencies.
 *
 * For each [Expression], this visitor produces a [DependencyNode], which can be translated into a
 * set of [Claim] relationships.
 *
 * [DependencyNode]s are DAG structures that help map handle connections in a [ParticleSpec] to the
 * target [Expression].
 */
class ExpressionDependencyAnalyzer : Expression.Visitor<DependencyNode, Scope> {
  override fun <E, T> visit(expr: Expression.UnaryExpression<E, T>, ctx: Scope): DependencyNode =
    expr.expr.accept(this, ctx).modified()

  override fun <L, R, T> visit(expr: Expression.BinaryExpression<L, R, T>, ctx: Scope) =
    DependencyNode.Nodes(expr.left.accept(this, ctx), expr.right.accept(this, ctx)).modified()

  override fun <T> visit(expr: Expression.FieldExpression<T>, ctx: Scope): DependencyNode {
    return when (val qualifier = expr.qualifier?.accept(this, ctx)) {
      null -> ctx[expr.field] ?: DependencyNode.Node(expr.field)
      is DependencyNode.Node -> DependencyNode.Node(expr.field, parent=qualifier)
      is DependencyNode.DerivedNode -> DependencyNode.DerivedNode(expr.field, parent=qualifier)
      is DependencyNode.Nodes -> {
        val target = requireNotNull(qualifier.nodes.find { it.id == expr.field }) {
          "Identifier '${expr.field}' is not found in '${expr.qualifier}'."
        }
        when (target.dependency.size) {
          0 -> DependencyNode.Nodes()
          1 -> target.dependency.first()
          else -> DependencyNode.Nodes(target.dependency.toList())
        }
      }
      is DependencyNode.BufferedScope -> requireNotNull(qualifier[expr.field]) {
        "Identifier '${expr.field}' is not found in '${expr.qualifier}'."
      }
      else -> throw UnsupportedOperationException( // TODO(rm)
        "Field access is not defined on a $this."
      )
    }
  }

  override fun <T> visit(expr: Expression.QueryParameterExpression<T>, ctx: Scope): DependencyNode {
    TODO("Not yet implemented")
  }

  override fun visit(expr: Expression.NumberLiteralExpression, ctx: Scope) = DependencyNode.LITERAL

  override fun visit(expr: Expression.TextLiteralExpression, ctx: Scope) = DependencyNode.LITERAL

  override fun visit(expr: Expression.BooleanLiteralExpression, ctx: Scope) = DependencyNode.LITERAL

  override fun visit(expr: Expression.NullLiteralExpression, ctx: Scope) = DependencyNode.LITERAL

  override fun visit(expr: Expression.FromExpression, ctx: Scope): DependencyNode {
    val scope = (expr.qualifier?.accept(this, ctx) ?: ctx) as Scope
    return scope.add(expr.iterationVar to expr.source.accept(this, scope))
  }

  override fun <T> visit(expr: Expression.SelectExpression<T>, ctx: Scope): DependencyNode {
    val qualifier = expr.qualifier.accept(this, ctx) as Scope
    return expr.expr.accept(this, qualifier)
  }

  override fun visit(expr: Expression.LetExpression, ctx: Scope): DependencyNode {
    val qualifier = expr.qualifier.accept(this, ctx) as Scope
    return qualifier.add(
      expr.variableName to expr.variableExpr.accept(this, qualifier)
    )
  }

  override fun <T> visit(expr: Expression.FunctionExpression<T>, ctx: Scope): DependencyNode {
    TODO("Not yet implemented")
  }

  override fun visit(expr: Expression.NewExpression, ctx: Scope) = DependencyNode.Nodes(
    *expr.fields.map { (id, expression) -> id to expression.accept(this, ctx) }.toTypedArray()
  )

  override fun <T> visit(expr: Expression.OrderByExpression<T>, ctx: Scope): DependencyNode {
    TODO("Not yet implemented")
  }

  override fun visit(expr: Expression.WhereExpression, ctx: Scope): DependencyNode {
    TODO("Not yet implemented")
  }
}

/** Analyze data flow relationships in a Paxel [Expression]. */
fun <T> Expression<T>.analyze() = this.accept(
  ExpressionDependencyAnalyzer(),
  DependencyNode.BufferedScope()
)

fun DependencyNode.modified(): DependencyNode {
  return when (this) {
    is DependencyNode.Nodes -> DependencyNode.Nodes(nodes.map { it.modified() })
    is DependencyNode.BufferedScope -> copy(ctx.mapValues { it.value.modified() })
    else -> this
  }
}

fun DependencyNode.Nodelike.modified(): DependencyNode {
  return when (this) {
    is DependencyNode.Node -> DependencyNode.DerivedNode(accessPath, dependency, influence)
    else -> this as DependencyNode
  }
}
