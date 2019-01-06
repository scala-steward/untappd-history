package lt.dvim.untappd.history

import java.time.{ZoneId, ZonedDateTime}
import java.time.format.DateTimeFormatter

import io.circe.{Decoder, Encoder}
import io.circe.java8.time.decodeZonedDateTimeWithFormatter
import lt.dvim.untappd.history.Model.CheckIn

object Model {

  case class CheckIn(id: Int, createdAt: ZonedDateTime)

}

object Codec {

  final val Vilnius = ZoneId.of("Europe/Vilnius")

  implicit val decodeRfc1123ZonedDateTime =
    decodeZonedDateTimeWithFormatter(DateTimeFormatter.RFC_1123_DATE_TIME)
      .map(_.withZoneSameInstant(Vilnius))

  implicit val decodeCheckIn: Decoder[CheckIn] =
    Decoder.forProduct2("checkin_id", "created_at")(CheckIn.apply)

  implicit val encodeStats: Encoder[DailyCheckins.Stats] =
    Encoder.forProduct1("stats")(_.dailyCheckins)

}
