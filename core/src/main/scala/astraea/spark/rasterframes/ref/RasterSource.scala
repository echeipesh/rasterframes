/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2018 Astraea. Inc.
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
 *
 */

package astraea.spark.rasterframes.ref

import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import astraea.spark.rasterframes.NOMINAL_TILE_DIMS
import astraea.spark.rasterframes.model.{TileContext, TileDimensions}
import astraea.spark.rasterframes.ref.RasterSource.SINGLEBAND
import astraea.spark.rasterframes.tiles.ProjectedRasterTile
import astraea.spark.rasterframes.util.GeoTiffInfoSupport
import com.typesafe.scalalogging.LazyLogging
import geotrellis.proj4.CRS
import geotrellis.raster._
import geotrellis.raster.io.geotiff.reader.GeoTiffReader
import geotrellis.raster.io.geotiff.{GeoTiffSegmentLayout, Tags}
import geotrellis.spark.io.hadoop.HdfsRangeReader
import geotrellis.spark.io.s3.S3Client
import geotrellis.spark.io.s3.util.S3RangeReader
import geotrellis.spark.tiling.LayoutDefinition
import geotrellis.util.{FileRangeReader, RangeReader}
import geotrellis.vector.Extent
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.spark.annotation.Experimental
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.rf.RasterSourceUDT

import scala.collection.JavaConverters._
import scala.util.Try

/**
 * Abstraction over fetching geospatial raster data.
 *
 * @since 8/21/18
 */
@Experimental
sealed trait RasterSource extends ProjectedRasterLike with Serializable {
  def crs: CRS

  def extent: Extent

  def cellType: CellType

  def bandCount: Int

  def tags: Option[Tags]

  def read(bounds: GridBounds, bands: Seq[Int]): Raster[MultibandTile] =
    readBounds(Seq(bounds), bands).next()

  def read(extent: Extent, bands: Seq[Int] = SINGLEBAND): Raster[MultibandTile] =
    read(rasterExtent.gridBoundsFor(extent, clamp = true), bands)

  def readAll(
    dims: TileDimensions = NOMINAL_TILE_DIMS,
    bands: Seq[Int] = SINGLEBAND): Seq[Raster[MultibandTile]] = layoutBounds(dims).map(read(_, bands))

  protected def readBounds(bounds: Traversable[GridBounds], bands: Seq[Int]): Iterator[Raster[MultibandTile]]

  def rasterExtent = RasterExtent(extent, cols, rows)

  def cellSize = CellSize(extent, cols, rows)

  def gridExtent = GridExtent(extent, cellSize)

  def tileContext: TileContext = TileContext(extent, crs)

  def tileLayout(dims: TileDimensions): TileLayout = {
    require(dims.cols > 0 && dims.rows > 0, "Non-zero tile sizes")
    TileLayout(
      layoutCols = math.ceil(this.cols.toDouble / dims.cols).toInt,
      layoutRows = math.ceil(this.rows.toDouble / dims.rows).toInt,
      tileCols = dims.cols,
      tileRows = dims.rows
    )
  }

  def layoutExtents(dims: TileDimensions): Seq[Extent] = {
    val tl = tileLayout(dims)
    val layout = LayoutDefinition(extent, tl)
    val transform = layout.mapTransform
    for {
      col <- 0 until tl.layoutCols
      row <- 0 until tl.layoutRows
    } yield transform(col, row)
  }

  def layoutBounds(dims: TileDimensions): Seq[GridBounds] = {
    gridBounds.split(dims.cols, dims.rows).toSeq
//    val tl = tileLayout(dims)
//    for {
//      col <- 0 until tl.layoutCols
//      row <- 0 until tl.layoutRows
//      cmin = math.min(col * tl.tileCols, cols)
//      cmax = math.min((col + 1) * tl.tileCols, cols)
//      rmin = math.min(row * tl.tileRows, cols)
//      rmax = math.min((row + 1) * tl.tileRows, rows)
//    } yield GridBounds(cmin, rmin, cmax, rmax)
  }
}

object RasterSource extends LazyLogging {
  final val SINGLEBAND = Seq(0)
  implicit def rsEncoder: ExpressionEncoder[RasterSource] = {
    RasterSourceUDT // Makes sure UDT is registered first
    ExpressionEncoder()
  }

  def apply(source: URI, callback: Option[ReadCallback] = None): RasterSource =
    source.getScheme match {
      case GDALRasterSource()                        => GDALRasterSource(source, callback)
      case "http" | "https"                          => HttpGeoTiffRasterSource(source, callback)
      case "file"                                    => FileGeoTiffRasterSource(source, callback)
      case "hdfs" | "s3n" | "s3a" | "wasb" | "wasbs" =>
        // TODO: How can we get the active hadoop configuration
        // TODO: without having to pass it through?
        val config = () => new Configuration()
        HadoopGeoTiffRasterSource(source, config, callback)
      case "s3" =>
        val client = () => S3Client.DEFAULT
        S3GeoTiffRasterSource(source, client, callback)
      case s if s.startsWith("gdal+") =>
        val cleaned = new URI(source.toASCIIString.replace("gdal+", ""))
        apply(cleaned, callback)
      case s => throw new UnsupportedOperationException(s"Scheme '$s' not supported")
    }

  case class SimpleGeoTiffInfo(
    cellType: CellType,
    extent: Extent,
    rasterExtent: RasterExtent,
    crs: CRS,
    tags: Tags,
    segmentLayout: GeoTiffSegmentLayout,
    bandCount: Int,
    noDataValue: Option[Double]
  )

  object SimpleGeoTiffInfo {
    def apply(info: GeoTiffReader.GeoTiffInfo): SimpleGeoTiffInfo =
      SimpleGeoTiffInfo(
        info.cellType,
        info.extent,
        info.rasterExtent,
        info.crs,
        info.tags,
        info.segmentLayout,
        info.bandCount,
        info.noDataValue)
  }

  // According to https://goo.gl/2z8xx9 the GeoTIFF date format is 'YYYY:MM:DD HH:MM:SS'
  private val dateFormat = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

  trait URIRasterSource { _: RasterSource =>
    def source: URI

    abstract override def toString: String = {
      s"${getClass.getSimpleName}(${source})"
    }
  }

  case class InMemoryRasterSource(tile: Tile, extent: Extent, crs: CRS) extends RasterSource {
    def this(prt: ProjectedRasterTile) = this(prt, prt.extent, prt.crs)

    override def rows: Int = tile.rows

    override def cols: Int = tile.cols

    override def cellType: CellType = tile.cellType

    override def bandCount: Int = 1

    override def tags: Option[Tags] = None

    override protected def readBounds(bounds: Traversable[GridBounds], bands: Seq[Int]): Iterator[Raster[MultibandTile]] = {
      bounds.map(b => {
        val subext = rasterExtent.extentFor(b)
        Raster(MultibandTile(tile.crop(b)), subext)
      }).toIterator
    }
  }

  case class GDALRasterSource(source: URI, callback: Option[ReadCallback])
      extends RasterSource with URIRasterSource {
    import geotrellis.contrib.vlm.gdal.{GDALRasterSource => VLMRasterSource}

    @transient
    private lazy val gdal = {
      val cleaned = source.toASCIIString.replace("gdal+", "")
      VLMRasterSource(cleaned)
    }

    override def crs: CRS = gdal.crs

    override def extent: Extent = gdal.extent

    private def metadata = gdal.dataset
      .GetMetadata_Dict()
      .asInstanceOf[java.util.Dictionary[String, String]]
      .asScala
      .toMap

    override def cellType: CellType = gdal.cellType

    override def bandCount: Int = gdal.bandCount

    override def cols: Int = gdal.cols

    override def rows: Int = gdal.rows

//    override def read(extent: Extent): Raster[MultibandTile] = {
//      callback.foreach { cb =>
//        val grid = rasterExtent.gridBoundsFor(extent, clamp = false)
//        cb.readRange(this, 0, grid.size.toInt * cellType.bytes * bandCount)
//      }
//      gdal.read(extent).get
//    }
//
//    override def readAll(): Seq[Raster[MultibandTile]] = {
//      val grid = gdal.gridBounds
//
//      callback.foreach { cb =>
//        cb.readRange(this, 0, grid.size.toInt * cellType.bytes * bandCount)
//      }
//
//      val tiled = grid.split(NOMINAL_TILE_SIZE, NOMINAL_TILE_SIZE).toTraversable
//
//      gdal.readBounds(tiled).toSeq
//    }

    override def tags: Option[Tags] = Some(Tags(metadata, List.empty))
    override protected def readBounds(bounds: Traversable[GridBounds], bands: Seq[Int]): Iterator[Raster[MultibandTile]] =
      gdal.readBounds(bounds, bands)
  }

  object GDALRasterSource {
    private val preferGdal: Boolean = astraea.spark.rasterframes.rfConfig.getBoolean("prefer-gdal")
    @transient
    lazy val hasGDAL: Boolean = try {
      org.gdal.gdal.gdal.AllRegister()
      true
    }
    catch {
      case _: UnsatisfiedLinkError =>
        logger.warn("GDAL native bindings are not available. Falling back to JVM-based reader.")
        false
    }
    def unapply(scheme: String): Boolean = (preferGdal || scheme.startsWith("gdal+")) && hasGDAL
  }

  trait RangeReaderRasterSource extends RasterSource with GeoTiffInfoSupport with LazyLogging {
    protected def rangeReader: RangeReader

    private def realInfo =
      GeoTiffReader.readGeoTiffInfo(rangeReader, streaming = true, withOverviews = false)

    protected lazy val tiffInfo = SimpleGeoTiffInfo(realInfo)

    def crs: CRS = tiffInfo.crs

    def extent: Extent = tiffInfo.extent

    def timestamp: Option[ZonedDateTime] = dateFromMetadata(tiffInfo.tags.headTags)

    override def cols: Int = tiffInfo.rasterExtent.cols

    override def rows: Int = tiffInfo.rasterExtent.rows

    def cellType: CellType = tiffInfo.cellType

    def bandCount: Int = tiffInfo.bandCount

    override def tags: Option[Tags] = Option(tiffInfo.tags)

    def nativeLayout: Option[TileLayout] = {
      if (tiffInfo.segmentLayout.isTiled)
        Some(tiffInfo.segmentLayout.tileLayout)
      else None
    }

    override protected def readBounds(bounds: Traversable[GridBounds],
      bands: Seq[Int]): Iterator[Raster[MultibandTile]] = {
      val info = realInfo
      val geoTiffTile = GeoTiffReader.geoTiffMultibandTile(info)
      val intersectingBounds = bounds.flatMap(_.intersection(this)).toSeq
      geoTiffTile.crop(intersectingBounds, bands.toArray).map { case (gb, tile) =>
        Raster(tile, rasterExtent.extentFor(gb, clamp = true))
      }
    }

//    def read(extent: Extent): Raster[MultibandTile] = {
//      val info = realInfo
//      val geoTiffTile = GeoTiffReader.geoTiffMultibandTile(info)
//      val gt = new MultibandGeoTiff(
//        geoTiffTile,
//        info.extent,
//        info.crs,
//        info.tags,
//        info.options,
//        List.empty
//      )
//      gt.crop(extent).raster
//    }
//
//    def readAll(): Seq[Raster[MultibandTile]] = {
//      val info = realInfo
//
//      // Thanks to @pomadchin for showing us how to do this :-)
//      val windows = info.segmentLayout.listWindows(NOMINAL_TILE_SIZE)
//      val re = info.rasterExtent
//
//      val geotile = GeoTiffReader.geoTiffMultibandTile(info)
//
//      val rows = windows.map(gb => {
//        val tile = geotile.crop(gb)
//        val extent = re.extentFor(gb, clamp = false)
//        Raster(tile, extent)
//      })
//
//      rows.toSeq
//    }

  }

  case class FileGeoTiffRasterSource(source: URI, callback: Option[ReadCallback])
      extends RangeReaderRasterSource with URIRasterSource with URIRasterSourceDebugString { self =>
    @transient
    protected lazy val rangeReader = {
      val base = FileRangeReader(source.getPath)
      // TODO: DRY
      callback.map(cb => ReportingRangeReader(base, cb, self)).getOrElse(base)
    }
  }

  case class HadoopGeoTiffRasterSource(
    source: URI,
    config: () => Configuration,
    callback: Option[ReadCallback])
      extends RangeReaderRasterSource with URIRasterSource with URIRasterSourceDebugString { self =>
    @transient
    protected lazy val rangeReader = {
      val base = HdfsRangeReader(new Path(source.getPath), config())
      callback.map(cb => ReportingRangeReader(base, cb, self)).getOrElse(base)
    }
  }

  case class S3GeoTiffRasterSource(
    source: URI,
    client: () => S3Client,
    callback: Option[ReadCallback])
      extends RangeReaderRasterSource with URIRasterSource with URIRasterSourceDebugString { self =>
    @transient
    protected lazy val rangeReader = {
      val base = S3RangeReader(source, client())
      callback.map(cb => ReportingRangeReader(base, cb, self)).getOrElse(base)
    }
  }

  case class HttpGeoTiffRasterSource(source: URI, callback: Option[ReadCallback])
      extends RangeReaderRasterSource with URIRasterSource with URIRasterSourceDebugString { self =>

    @transient
    protected lazy val rangeReader = {
      val base = HttpRangeReader(source)
      callback.map(cb => ReportingRangeReader(base, cb, self)).getOrElse(base)
    }

    override def timestamp: Option[ZonedDateTime] =
      dateFromMetadata(tiffInfo.tags.headTags)
        .orElse {
          val hrr = rangeReader match {
            case h: HttpRangeReader                             => h
            case ReportingRangeReader(h: HttpRangeReader, _, _) => h
          }
          hrr.response.headers
            .get("Last-Modified")
            .flatMap(_.headOption)
            .flatMap(s =>
              Try(ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME)).toOption)
        }
  }

  private[ref] def dateFromMetadata(meta: Map[String, String]): Option[ZonedDateTime] =
    meta
      .get(Tags.TIFFTAG_DATETIME)
      .flatMap(ds =>
        Try({
          logger.debug("Parsing header date: " + ds)
          ZonedDateTime.parse(ds, dateFormat)
        }).toOption)

  /** Trait for registering a callback for logging or monitoring range reads.
   * NB: the callback will be invoked from within a Spark task, and therefore
   * is serialized along with its closure to executors. */
  trait ReadCallback extends Serializable {
    def readRange(source: RasterSource, start: Long, length: Int): Unit
  }

  private case class ReportingRangeReader(
    delegate: RangeReader,
    callback: ReadCallback,
    parent: RasterSource)
      extends RangeReader {
    override def totalLength: Long = delegate.totalLength

    override protected def readClippedRange(start: Long, length: Int): Array[Byte] = {
      callback.readRange(parent, start, length)
      delegate.readRange(start, length)
    }
  }

  trait URIRasterSourceDebugString { _: RangeReaderRasterSource with URIRasterSource with Product =>
    def toDebugString: String = {
      val buf = new StringBuilder()
      buf.append(productPrefix)
      buf.append("(")
      buf.append("source=")
      buf.append(source.toASCIIString)
      buf.append(", size=")
      buf.append(size)
      buf.append(", dimensions=")
      buf.append(dimensions)
      buf.append(", crs=")
      buf.append(crs)
      buf.append(", extent=")
      buf.append(extent)
      buf.append(", timestamp=")
      buf.append(timestamp)
      buf.append(")")
      buf.toString
    }
  }
}
