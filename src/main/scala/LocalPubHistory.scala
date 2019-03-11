package lt.dvim.untappd.history

import akka.NotUsed
import akka.actor.typed
import akka.actor.typed._
import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.server.Directives._
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.Attributes
import akka.stream.typed.scaladsl.{ActorMaterializer, ActorSink}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.{ByteString, Timeout}
import ciris._
import io.circe.parser._
import io.circe.optics.JsonPath._
import lt.dvim.untappd.history.DailyCheckins.GetStats
import lt.dvim.untappd.history.History.{CheckinStored, StoreCheckin}
import lt.dvim.untappd.history.Codec._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

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
    implicit val untyped = ctx.system.toUntyped
    implicit val mat = ActorMaterializer()(ctx.system)

    if (config.migration) {
      ctx.log.debug("Running migration")

      val migrator = ctx.spawn(Migrator.behavior(), "migrator")
      ctx.watch(migrator)

      PersistenceQuery(ctx.system.toUntyped)
        .readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
        .currentEventsByPersistenceId(History.History, 0, Long.MaxValue)
        .collect {
          case env =>
            env.event match {
              case e: CheckinStored => Migrator.StoreEvent(e)
            }
        }
        .runWith(ActorSink.actorRef(migrator, Migrator.CompleteStream, Migrator.FailStream.apply))

    } else {
      val history = ctx.spawn(History.behavior, "history")
      val dailyCheckins = ctx.spawn(DailyCheckins.behavior, "daily-checkins")

      Http().bindAndHandle(routes(dailyCheckins.narrow, history), config.httpInterface, config.httpPort)
    }

    Behaviors.receiveSignal {
      case (_, Terminated(_)) ⇒
        Behaviors.stopped
    }
  }

  def routes(dailyCheckins: ActorRef[DailyCheckins.GetStats], history: ActorRef[History.Command]) =
    extractActorSystem { sys =>
      implicit val timeout: Timeout = 3.seconds
      implicit val scheduler = sys.scheduler
      path("daily") {
        get {
          import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
          val checkins: Future[DailyCheckins.Stats] = dailyCheckins ? GetStats
          complete(checkins)
        }
      } ~ path("ingestion") {
        if (config.ingestion) {
          extractMaterializer { implicit mat =>
            import sys.dispatcher
            formField('min_id.as[Int]) { minId =>
              fileUpload("data") {
                case (_, byteSource) =>
                  val req = byteSource.runWith(Sink.fold(ByteString.empty)(_ ++ _)).map(_.utf8String)
                  val stored = Source
                    .fromFuture(req)
                    .runWith(parseAndStoreResults(history, minId))
                  complete(stored.map(r => s"Stored $r results"))
              }
            }
          }
        } else {
          complete("Ingestion disabled")
        }
      }
    }

  def storeHistoryBefore(
      checkinId: Option[Int],
      target: ActorRef[History.Command]
  )(implicit sys: ActorSystem, mat: ActorMaterializer): Future[Int] =
    Source
      .single(HttpRequest(uri = untappdUri(config, checkinId)))
      .mapAsync(parallelism = 1)(Http().singleRequest(_))
      .mapAsync(parallelism = 1)(resp => Unmarshal(resp.entity).to[String])
      .runWith(parseAndStoreResults(target, 0))

  private def parseAndStoreResults(target: ActorRef[History.Command], minId: Int) =
    Flow[String]
      .map(body => parse(body).toTry.get)
      .mapConcat(Optics.items.getOption(_).toList.flatten)
      .map(json => (Optics.checkinId.getOption(json).get, json))
      .map(StoreCheckin.tupled)
      .takeWhile(_.id > minId)
      .log("Single checkin", _.id)
      .alsoTo(ActorSink.actorRef(target, History.CompleteStream, _ => History.FailStream))
      .withAttributes(Attributes.logLevels(onElement = Logging.InfoLevel))
      .toMat(Sink.fold(0) { case (i, _) => i + 1 })(Keep.right)

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

  case class Config(location: Location,
                    clientId: String,
                    clientSecret: Secret[String],
                    streamBackoff: FiniteDuration,
                    httpInterface: String,
                    httpPort: Int,
                    migration: Boolean,
                    ingestion: Boolean)

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
      hocon[FiniteDuration]("stream-backoff"),
      hocon[String]("http.interface"),
      hocon[Int]("http.port"),
      hocon[Boolean]("migration"),
      hocon[Boolean]("ingestion")
    ) { (clientId, clientSecret, streamBackoff, interface, port, migration, ingestion) =>
      Config(
        Location(54.688567, 25.275775), // Vilnius
        clientId,
        clientSecret,
        streamBackoff,
        interface,
        port,
        migration,
        ingestion
      )
    }.orThrow()
  }

}
