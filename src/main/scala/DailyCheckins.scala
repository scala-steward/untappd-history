package lt.dvim.untappd.history

import java.time.format.DateTimeFormatter

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.typed.scaladsl.{ActorMaterializer, ActorSink}
import lt.dvim.untappd.history.Codec._
import lt.dvim.untappd.history.History.{CheckinStored, Event}
import lt.dvim.untappd.history.Model.CheckIn
import lt.dvim.untappd.history.Serialization._
import io.circe.generic.auto._
import org.slf4j.LoggerFactory

object DailyCheckins {

  sealed trait Message

  case class StartStream(from: Long) extends Message
  object StartStream {
    final val FromBeginning = StartStream(0L)
  }

  case object CompleteStream extends Message

  case class FailStream(ex: Throwable) extends Message

  case class CheckinEvent(seqNr: Long, checkIn: CheckinStored) extends Message

  case class GetStats(replyTo: ActorRef[Stats]) extends Message

  case class Stats(dailyCheckins: Map[String, Int])

  case class State(lastSeqNr: Long, dailyCheckins: Map[String, Int])
  object State {
    final val Initial = State(0L, Map.empty)
  }

  private final val log = LoggerFactory.getLogger(getClass)

  def behavior()(implicit mat: ActorMaterializer): Behavior[Message] = Behaviors.setup { implicit ctx =>
    def internalBehavior(state: State): Behavior[Message] =
      Behaviors.receiveMessage {
        case StartStream(from) =>
          log.debug("DailyCheckin Query stream starting")
          PersistenceQuery(ctx.system.toUntyped)
            .readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
            .eventsByPersistenceId(History.HistoryJson, from, Long.MaxValue)
            .map(envelope => envelope -> decodeBytes[Event](envelope.event))
            .collect {
              case (env, Right(e: CheckinStored)) => CheckinEvent(env.sequenceNr, e)
            }
            .runWith(ActorSink.actorRef(ctx.self, CompleteStream, FailStream.apply))
          Behaviors.same

        case CompleteStream =>
          log.debug("DailyCheckin Query stream completed")
          ctx.self ! StartStream(state.lastSeqNr)
          Behaviors.same

        case FailStream(ex) =>
          log.error("DailyCheckin Query stream failed", ex)
          ctx.self ! StartStream(state.lastSeqNr)
          Behaviors.same

        case CheckinEvent(seqNr, CheckinStored(id, data)) =>
          val checkIn = data.as[CheckIn]
          if (seqNr % 1000 == 0) {
            log.debug("{} current daily checkins {}", seqNr, state.dailyCheckins)
          }
          checkIn.fold(
            failure => {
              log.error("Unable to decode stored checkin with id [{}]", id, failure)
              internalBehavior(state.copy(lastSeqNr = seqNr))
            },
            checkIn => {
              val localDate = checkIn.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE)
              val dayCheckins = state.dailyCheckins.getOrElse(localDate, 0) + 1
              internalBehavior(state.copy(dailyCheckins = state.dailyCheckins.updated(localDate, dayCheckins)))
            }
          )

        case GetStats(replyTo) =>
          replyTo ! Stats(state.dailyCheckins)
          Behaviors.same
      }

    val state = State.Initial
    ctx.self ! StartStream(state.lastSeqNr)
    internalBehavior(state)
  }
}
