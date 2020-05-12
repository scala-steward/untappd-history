/*
 * Copyright 2018 Untappd History
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package lt.dvim.untappd.history.model

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import io.circe._

object Untappd {
  case class Checkin(id: Int, createdAt: ZonedDateTime)

  final val Vilnius = ZoneId.of("Europe/Vilnius")

  implicit val decodeRfc1123ZonedDateTime =
    Decoder
      .decodeZonedDateTimeWithFormatter(DateTimeFormatter.RFC_1123_DATE_TIME)
      .map(_.withZoneSameInstant(Vilnius))

  implicit val checkinDecoder: Decoder[Checkin] = Decoder.forProduct2("checkin_id", "created_at")(Checkin.apply)
}

object VilniusPub {
  case class DailyCheckins(checkins: Map[String, Int])

  implicit val encodeStats: Encoder[DailyCheckins] =
    new Encoder[DailyCheckins] {
      final def apply(daily: DailyCheckins): Json =
        Json.arr(
          daily.checkins.toSeq
            .sortBy(_._1)
            .map {
              case (date, count) =>
                Json.obj(
                  ("name", Json.fromString(date)),
                  ("checkins", Json.fromInt(count))
                )
            }: _*
        )
    }
}
