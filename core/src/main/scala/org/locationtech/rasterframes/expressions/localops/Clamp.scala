package org.locationtech.rasterframes.expressions.localops

import org.apache.spark.sql.Column
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult.{TypeCheckFailure, TypeCheckSuccess}
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.catalyst.expressions.{Expression, ExpressionDescription, TernaryExpression}
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.types.DataType
import org.locationtech.rasterframes.expressions.DynamicExtractors._
import org.locationtech.rasterframes.expressions.{RasterResult, row}

@ExpressionDescription(
  usage = "_FUNC_(tile, min, max) - Return the tile with its values limited to a range defined by min and max," +
            " doing so cellwise if min or max are tile type",
  arguments = """
      Arguments:
        * tile - the tile to operate on
        * min - scalar or tile setting the minimum value for each cell
        * max - scalar or tile setting the maximum value for each cell"""
)
case class Clamp(first: Expression, second: Expression, third: Expression) extends TernaryExpression with CodegenFallback with RasterResult with Serializable {
  def dataType: DataType = first.dataType

  override val nodeName = "rf_local_clamp"

  override def checkInputDataTypes(): TypeCheckResult = {
    if (!tileExtractor.isDefinedAt(first.dataType)) {
      TypeCheckFailure(s"Input type '${first.dataType}' does not conform to a Tile type")
    } else if (!tileExtractor.isDefinedAt(second.dataType) && !numberArgExtractor.isDefinedAt(second.dataType)) {
      TypeCheckFailure(s"Input type '${second.dataType}' does not conform to a Tile or numeric type")
    } else if (!tileExtractor.isDefinedAt(third.dataType) && !numberArgExtractor.isDefinedAt(third.dataType)) {
      TypeCheckFailure(s"Input type '${third.dataType}' does not conform to a Tile or numeric type")
    }
    else TypeCheckSuccess
  }

  override protected def nullSafeEval(input1: Any, input2: Any, input3: Any): Any = {
    val (targetTile, targetCtx) = tileExtractor(first.dataType)(row(input1))
    val minVal = tileOrNumberExtractor(second.dataType)(input2)
    val maxVal = tileOrNumberExtractor(third.dataType)(input3)

    val result = (minVal, maxVal) match {
      case (mn: TileArg, mx: TileArg) => targetTile.localMin(mx.tile).localMax(mn.tile)
      case (mn: TileArg, mx: IntegerArg) => targetTile.localMin(mx.value).localMax(mn.tile)
      case (mn: TileArg, mx: DoubleArg) => targetTile.localMin(mx.value).localMax(mn.tile)
      case (mn: IntegerArg, mx: TileArg) => targetTile.localMin(mx.tile).localMax(mn.value)
      case (mn: IntegerArg, mx: IntegerArg) => targetTile.localMin(mx.value).localMax(mn.value)
      case (mn: IntegerArg, mx: DoubleArg) => targetTile.localMin(mx.value).localMax(mn.value)
      case (mn: DoubleArg, mx: TileArg) => targetTile.localMin(mx.tile).localMax(mn.value)
      case (mn: DoubleArg, mx: IntegerArg) => targetTile.localMin(mx.value).localMax(mn.value)
      case (mn: DoubleArg, mx: DoubleArg) => targetTile.localMin(mx.value).localMax(mn.value)
    }

    toInternalRow(result, targetCtx)
  }

  override protected def withNewChildrenInternal(newFirst: Expression, newSecond: Expression, newThird: Expression): Expression =
    copy(newFirst, newSecond, newThird)
}
object Clamp {
  def apply(tile: Column, min: Column, max: Column): Column = new Column(Clamp(tile.expr, min.expr, max.expr))
  def apply[N: Numeric](tile: Column, min: N, max: Column): Column = new Column(Clamp(tile.expr, lit(min).expr, max.expr))
  def apply[N: Numeric](tile: Column, min: Column, max: N): Column = new Column(Clamp(tile.expr, min.expr, lit(max).expr))
  def apply[N: Numeric](tile: Column, min: N, max: N): Column = new Column(Clamp(tile.expr, lit(min).expr, lit(max).expr))
}
