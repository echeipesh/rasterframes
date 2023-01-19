/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2017 Astraea, Inc.
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

package org.locationtech.rasterframes

import org.apache.spark.sql.rf._
import org.locationtech.rasterframes.model.LazyCRS
import org.scalatest.Inspectors

class BaseUdtSpec extends TestEnvironment with TestData with Inspectors {

  it("should (de)serialize CRS") {
    val udt = new CrsUDT()
    val in = geotrellis.proj4.LatLng
    val row = udt.serialize(crs)
    val out = udt.deserialize(row)
    out shouldBe in
    assert(out.isInstanceOf[LazyCRS])
    info(out.toString())
  }
}
