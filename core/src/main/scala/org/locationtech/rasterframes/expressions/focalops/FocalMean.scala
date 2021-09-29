/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2020 Astraea, Inc.
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

package org.locationtech.rasterframes.expressions.focalops

import geotrellis.raster.{BufferTile, Tile}
import geotrellis.raster.mapalgebra.focal.Neighborhood
import org.apache.spark.sql.Column
import org.apache.spark.sql.catalyst.expressions.{Expression, ExpressionDescription}

@ExpressionDescription(
  usage = "_FUNC_(tile, neighborhood) - Performs focalMean on tile in the neighborhood.",
  arguments = """
  Arguments:
    * tile - a tile to apply operation
    * neighborhood - a focal operation neighborhood""",
  examples = """
  Examples:
    > SELECT _FUNC_(tile, 'square-1');
       ..."""
)
case class FocalMean(left: Expression, right: Expression) extends FocalNeighborhoodOp {
  override def nodeName: String = FocalMean.name
  protected def op(t: Tile, neighborhood: Neighborhood): Tile = t match {
    case bt: BufferTile => bt.focalMean(neighborhood)
    case _ => t.focalMean(neighborhood)
  }
}

object FocalMean {
  def name:String = "rf_focal_mean"
  def apply(tile: Column, neighborhood: Column): Column = new Column(FocalMean(tile.expr, neighborhood.expr))
}
