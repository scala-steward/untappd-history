package lt.dvim.untappd.history

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.Source
import ciris._
import io.circe.parser._
import io.circe.optics.JsonPath._

import akka.NotUsed
import io.circe.Json
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Firestore
import scala.jdk.CollectionConverters._
import com.google.api.core.ApiFutures
import scala.concurrent.Promise
import com.google.cloud.firestore.WriteResult
import com.google.api.core.ApiFutureCallback
import scala.concurrent.Future
import scala.concurrent.ExecutionContextExecutor
import akka.stream.Attributes
import akka.http.scaladsl.server.Directives._

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
    path("checkins") {
      get {
        val stored = storeCheckins().map(c => s"Successfully stored [$c] checkins")
        complete(stored)
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
    val checkinRef = db.collection("checkins").document(checkinId.toString());
    val attributes = Map("data" -> data.toString)
    val promise = Promise[WriteResult]()
    ApiFutures.addCallback(
      checkinRef.set(attributes.asJava),
      new ApiFutureCallback[WriteResult] {
        def onFailure(t: Throwable) = promise.failure(t)
        def onSuccess(result: WriteResult) = promise.success(result)
      },
      ece
    )
    promise.future.map(_ => checkinId)
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
        case path if path.contains("/") => file[String](new java.io.File(path)).value
        case id => Right(id)
      },
      hocon[Secret[String]]("client-secret").flatMapValue {
        case Secret(path) if path.contains("/") => file[Secret[String]](new java.io.File(path)).value
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
