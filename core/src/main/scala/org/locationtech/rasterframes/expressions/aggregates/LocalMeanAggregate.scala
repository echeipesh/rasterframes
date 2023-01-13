/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2019 Astraea, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     [http://www.apache.org/licenses/LICENSE-2.0]
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.locationtech.rasterframes.expressions.aggregates

import org.locationtech.rasterframes._
import org.locationtech.rasterframes.expressions.UnaryRasterAggregate
import org.locationtech.rasterframes.expressions.localops.{BiasedAdd, Divide => DivideTiles}
import org.locationtech.rasterframes.expressions.transformers.SetCellType
import geotrellis.raster.Tile
import geotrellis.raster.mapalgebra.local
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, Expression, ExpressionDescription, If, IsNull, Literal, ScalaUDF}
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.{Column, TypedColumn}
import org.locationtech.rasterframes.expressions.accessors.{ExtractTile, RealizeTile}

@ExpressionDescription(
  usage = "_FUNC_(tile) - Computes a new tile contining the mean cell values across all tiles in column.",
  note = """"
    All tiles in the column must be the same size.
  """
)
case class LocalMeanAggregate(child: Expression) extends UnaryRasterAggregate {

  def dataType: DataType = tileUDT
  override def nodeName: String = "rf_agg_local_mean"

  private lazy val count = AttributeReference("count", dataType, true)()
  private lazy val sum = AttributeReference("sum", dataType, true)()

  def aggBufferAttributes: Seq[AttributeReference] = Seq(count, sum)

  private lazy val Defined: Expression => ScalaUDF = tileOpAsExpression("defined_cells", local.Defined.apply)

  lazy val initialValues: Seq[Expression] = Seq(
    Literal.create(null, dataType),
    Literal.create(null, dataType)
  )
  lazy val updateExpressions: Seq[Expression] = Seq(
    If(IsNull(count),
      SetCellType(RealizeTile(Defined(ExtractTile(child))), Literal("int32")),
      If(IsNull(child), count, BiasedAdd(count, Defined(RealizeTile(child))))
    ),
    If(IsNull(sum),
      SetCellType(RealizeTile(child), Literal("float64")),
      If(IsNull(child), sum, BiasedAdd(sum, child))
    )
  )
  val mergeExpressions: Seq[Expression] = Seq(
    BiasedAdd(count.left, count.right),
    BiasedAdd(sum.left, sum.right)
  )
  lazy val evaluateExpression: Expression = DivideTiles(sum, count)

  override protected def withNewChildrenInternal(newChildren: IndexedSeq[Expression]): Expression = copy(newChildren.head)
}
object LocalMeanAggregate {
  def apply(tile: Column): TypedColumn[Any, Tile] =
    new Column(new LocalMeanAggregate(tile.expr).toAggregateExpression()).as[Tile]

}
