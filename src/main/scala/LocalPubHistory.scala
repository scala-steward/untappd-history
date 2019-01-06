package lt.dvim.untappd.history

import akka.NotUsed
import akka.actor.typed
import akka.actor.typed._
import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Attributes
import akka.stream.typed.scaladsl.{ActorMaterializer, ActorSink}
import akka.stream.scaladsl.{Sink, Source}
import ciris._
import io.circe.parser._
import io.circe.optics.JsonPath._
import lt.dvim.untappd.history.History.StoreCheckin

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.{Duration, FiniteDuration}

object LocalPubHistory {

  def main(args: Array[String]): Unit = {
    implicit val ec = ExecutionContext.global
    val termination = for {
      _ <- Schema.createSchemaIfNotExists()
      sys = typed.ActorSystem(mainActor, "untappd-history")
      _ <- sys.whenTerminated
    } yield ()

    Await.ready(termination, Duration.Inf)
    ()
  }

  val mainActor: Behavior[NotUsed] = Behaviors.setup { ctx =>
    implicit val mat = ActorMaterializer()(ctx.system)
    ctx.spawn(History.behavior, "history")
    ctx.spawn(DailyCheckins.behavior, "daily-checkins")
    Behaviors.empty
  }

  def storeHistoryBefore(checkinId: Option[Int], target: ActorRef[History.Command])(implicit sys: ActorSystem,
                                                                                    mat: ActorMaterializer): Unit = {
    val request = HttpRequest(uri = untappdUri(config, checkinId))
    queryAndStoreResults(request, target)
    ()
  }

  private def queryAndStoreResults(request: HttpRequest, target: ActorRef[History.Command])(implicit sys: ActorSystem,
                                                                                            mat: ActorMaterializer) =
    Source
      .single(request)
      .mapAsync(parallelism = 1)(Http().singleRequest(_))
      .mapAsync(parallelism = 1)(resp => Unmarshal(resp.entity).to[String])
      .map(body => parse(body).toTry.get)
      .mapConcat(Optics.items.getOption(_).toList.flatten)
      .map(json => (Optics.checkinId.getOption(json).get, json))
      .map(StoreCheckin.tupled)
      .log("Single checkin", _.id)
      .alsoTo(ActorSink.actorRef(target, History.CompleteStream, _ => History.FailStream))
      .withAttributes(Attributes.logLevels(onElement = Logging.InfoLevel))
      .runWith(Sink.fold(0) { case (i, _) => i + 1 })

  private def untappdUri(config: Config, fromCheckin: Option[Int]): Uri = {
    val query = Query(
      "client_id" -> config.clientId,
      "client_secret" -> config.clientSecret.value,
      "lat" -> config.location.lat.toString,
      "lng" -> config.location.lng.toString
    )

    Uri("https://api.untappd.com/v4/thepub/local").withQuery(
      fromCheckin.fold(query)(id => query.+:("min_id" -> id.toString))
    )
  }

  object Optics {
    val items = root.response.checkins.items.arr
    val checkinId = root.checkin_id.int
  }

  case class Location(lat: Double, lng: Double)

  case class Config(location: Location, clientId: String, clientSecret: Secret[String], streamBackoff: FiniteDuration)

  import Hocon._
  final val config = {
    val hocon = hoconAt("untappd.history")
    loadConfig(
      hocon[String]("client-id"),
      hocon[Secret[String]]("client-secret"),
      hocon[FiniteDuration]("stream-backoff")
    ) { (clientId, clientSecret, streamBackoff) =>
      Config(
        Location(54.688567, 25.275775), // Vilnius
        clientId,
        clientSecret,
        streamBackoff
      )
    }.orThrow()
  }

}
