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

package org.locationtech.rasterframes.datasource.stac.api

import org.locationtech.rasterframes.datasource.raster._
import org.locationtech.rasterframes.datasource.stac.api.encoders._

import com.azavea.stac4s.StacItem
import com.azavea.stac4s.api.client.SttpStacClient
import cats.syntax.option._
import cats.effect.IO
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegInt
import geotrellis.store.util.BlockingThreadPool
import geotrellis.vector.Point
import org.apache.spark.sql.functions.explode
import org.locationtech.rasterframes.TestEnvironment
import sttp.client3.asynchttpclient.cats.AsyncHttpClientCatsBackend
import sttp.client3.UriContext

class STACAPIDataSourceTest extends TestEnvironment { self =>

  describe("STAC API spark reader") {
    it("Should read from Franklin service") {
      import spark.implicits._

      val results =
        spark
          .read
          .stacApi("https://franklin.nasa-hsi.azavea.com/", searchLimit = (1: NonNegInt).some)
          .load

      results.printSchema()

      results.rdd.partitions.length shouldBe 1
      results.count() shouldBe 1

      println(results.as[StacItem].collect().toList)

      val ddf = results.select($"id", explode($"assets"))

      ddf.printSchema()

      println(ddf.select($"id", $"value.href" as "band").collect().toList)

    }

    it("Should read from Astraea Earth service") {
      import spark.implicits._

      val results =
        spark
          .read
          .stacApi("https://eod-catalog-svc-prod.astraea.earth/", searchLimit = (1: NonNegInt).some)
          .load

      results.printSchema()

      results.rdd.partitions.length shouldBe 1
      results.count() shouldBe 1

      println(results.as[StacItem].collect().toList)

      val ddf = results.select($"id", explode($"assets"))

      ddf.printSchema()

      println(ddf.select($"id", $"value.href" as "band").collect().toList)

    }

    ignore("manual test") {
      implicit val cs = IO.contextShift(BlockingThreadPool.executionContext)
      val realitems: List[StacItem] = AsyncHttpClientCatsBackend
        .resource[IO]()
        .use { backend =>
          SttpStacClient(backend, uri"https://eod-catalog-svc-prod.astraea.earth/")
            .search
            .take(1)
            .compile
            .toList
        }
        .unsafeRunSync()
        .map(_.copy(geometry = Point(1, 1)))

      import spark.implicits._

      println(sc.parallelize(realitems).toDF().as[StacItem].collect().toList.head)

    }

    it("should fetch rasters from Franklin service") {
      import spark.implicits._
      val items =
        spark
          .read
          .stacApi("https://eod-catalog-svc-prod.astraea.earth/", searchLimit = (1: NonNegInt).some)
          .load

      println(items.collect().toList.length)

      val assets = items.select($"id", explode($"assets")).select($"value.href" as "band").limit(1)

      println(assets.collect().toList)

      /*val bandPaths = Seq((
        l8SamplePath(1).toASCIIString,
        l8SamplePath(2).toASCIIString,
        l8SamplePath(3).toASCIIString))
        .toDF("B1", "B2", "B3")
        .withColumn("foo", lit("something"))

      val df = spark.read.raster
        .fromCatalog(bandPaths, "B1", "B2", "B3")
        .withTileDimensions(128, 128)
        .load()

      df.schema.size should be(7)
      df.select($"B1_path").distinct().count() should be (1)*/

      // println(df.collect().toList)

      val rasters = spark.read.raster
        .fromCatalog(assets, "band")
        .withTileDimensions(128, 128)
        .withBandIndexes(0)
        .load()

      rasters.printSchema()

      println(rasters.collect().toList)
    }
  }
}
