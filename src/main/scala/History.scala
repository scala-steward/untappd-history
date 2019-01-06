package lt.dvim.untappd.history

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.persistence.typed.scaladsl.EventSourcedBehavior.CommandHandler
import akka.stream.ActorMaterializer
import io.circe.Json
import lt.dvim.untappd.history.LocalPubHistory.config
import org.slf4j.LoggerFactory

object History {

  sealed trait Command

  case class StoreCheckin(id: Int, data: Json) extends Command

  case object CompleteStream extends Command

  case object FailStream extends Command

  case object StartStream extends Command

  sealed trait Event

  case class CheckinStored(id: Int, data: Json) extends Event

  case class State(lastCheckin: Option[Int])

  final val History = "history"

  private final val log = LoggerFactory.getLogger(getClass)

  def behavior(implicit mat: ActorMaterializer): Behavior[Command] =
    Behaviors.setup { implicit ctx =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId(History),
        emptyState = State(None),
        commandHandler = CommandHandler.command {
          case StoreCheckin(id, data) =>
            Effect.persist(CheckinStored(id, data)).thenRun { _ =>
              log.debug("Persisted checkin_id={}", id)
            }
          case CompleteStream =>
            Effect.none.thenRun { state =>
              log.debug("Stream completed with last checkin_id={}", state.lastCheckin)
              ctx.scheduleOnce(config.streamBackoff, ctx.self, StartStream)
              ()
            }
          case FailStream => {
            Effect.none.thenRun { _ =>
              ctx.scheduleOnce(config.streamBackoff, ctx.self, StartStream)
              ()
            }
          }
          case StartStream =>
            Effect.none.thenRun { state =>
              log.debug("Starting checkin stream from checkin_id={}", state.lastCheckin)
              implicit val untyped = ctx.system.toUntyped
              LocalPubHistory.storeHistoryBefore(state.lastCheckin, ctx.self)
            }
        },
        eventHandler = {
          case (state, CheckinStored(id, _)) =>
            state.copy(lastCheckin = Some(Seq(id, state.lastCheckin.getOrElse(0)).max))
        }
      ).onRecoveryCompleted { _ ⇒
        ctx.scheduleOnce(config.streamBackoff, ctx.self, StartStream)
        ()
      }
    }

}
