package lt.dvim.untappd.history

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Attributes
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import ciris._
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Json
import io.circe.optics.JsonPath._
import io.circe.parser._

import lt.dvim.untappd.history.Checkins._
import lt.dvim.untappd.history.FirestoreOps._
import lt.dvim.untappd.history.model.VilniusPub.DailyCheckins

object LocalPubHistory {

  def main(args: Array[String]): Unit = {
    implicit val db = FirestoreOptions
      .getDefaultInstance()
      .toBuilder()
      .setProjectId(config.projectId)
      .build()
      .getService()

    implicit val sys = ActorSystem("untappd")
    import sys.dispatcher

    Http().bindAndHandle(routes(), config.httpInterface, config.httpPort)
    ()
  }

  def routes()(implicit sys: ActorSystem, db: Firestore, ece: ExecutionContextExecutor) =
    cors() {
      path("checkins") {
        get {
          val stored = storeCheckins().map(c => s"Successfully stored [$c] checkins")
          complete(stored)
        }
      } ~ path("process-daily") {
        get {
          val result = gatherDailyCheckins()
            .runWith(Sink.head)
            .flatMap { daily =>
              val ref = db.collection("daily").document("checkins")
              ref.setAsync(daily.checkins.view.mapValues(_.toString).toMap)
            }
            .map(_ => "Done")
          complete(result)
        }
      } ~ path("daily") {
        val ref = db.document("daily/checkins").getAsync()
        val daily = ref.map(data =>
          DailyCheckins(data.view.mapValues {
            case str: String => Integer.parseInt(str)
          }.toMap)
        )
        complete(daily)
      }
    }

  def storeCheckins()(implicit sys: ActorSystem, db: Firestore, ece: ExecutionContextExecutor) =
    checkinStream()
      .mapAsync(parallelism = 1)((storeCheckin _).tupled)
      .log("Checking stored")
      .withAttributes(
        Attributes.logLevels(
          onElement = Attributes.LogLevels.Info,
          onFinish = Attributes.LogLevels.Info
        )
      )
      .runFold(0)((count, _) => count + 1)

  def checkinStream()(implicit sys: ActorSystem): Source[(Int, Json), NotUsed] =
    Source
      .single(HttpRequest(uri = untappdUri(config)))
      .mapAsync(parallelism = 1)(Http().singleRequest(_))
      .mapAsync(parallelism = 1)(resp => Unmarshal(resp.entity).to[String])
      .map(body => parse(body).toTry.get)
      .mapConcat(Optics.items.getOption(_).toList.flatten)
      .map(json => (Optics.checkinId.getOption(json).get, json))

  def storeCheckin(checkinId: Int, data: Json)(implicit db: Firestore, ece: ExecutionContextExecutor): Future[Int] = {
    val checkinRef = db.collection("checkins").document(checkinId.toString())
    val attributes = Map("data" -> data.toString)
    checkinRef.setAsync(attributes).map(_ => checkinId)
  }

  private def untappdUri(config: Config): Uri = {
    val query = Query(
      "client_id" -> config.clientId,
      "client_secret" -> config.clientSecret.value,
      "lat" -> config.location.lat.toString,
      "lng" -> config.location.lng.toString
    )

    Uri("https://api.untappd.com/v4/thepub/local").withQuery(query)
  }

  object Optics {
    val items = root.response.checkins.items.arr
    val checkinId = root.checkin_id.int
    val createdAt = root.created_at.string
  }

  case class Location(lat: Double, lng: Double)

  case class Config(
      location: Location,
      clientId: String,
      clientSecret: Secret[String],
      projectId: String,
      httpInterface: String,
      httpPort: Int
  )

  import lt.dvim.ciris.Hocon._
  final val config = {
    val hocon = hoconAt("untappd.history")
    loadConfig(
      hocon[String]("client-id").flatMapValue {
        case "" => Left(ConfigError("Please provide client id"))
        case id => Right(id)
      },
      hocon[Secret[String]]("client-secret").flatMapValue {
        case Secret("") => Left(ConfigError("Please provide client secret"))
        case secret => Right(secret)
      },
      hocon[String]("http.interface"),
      hocon[Int]("http.port")
    ) { (clientId, clientSecret, interface, port) =>
      Config(
        Location(54.688567, 25.275775), // Vilnius
        clientId,
        clientSecret,
        "untappd-263504",
        interface,
        port
      )
    }.orThrow()
  }

}
