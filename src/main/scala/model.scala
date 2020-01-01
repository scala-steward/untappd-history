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
      final def apply(daily: DailyCheckins): Json = Json.arr(
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
