package lt.dvim.untappd.history

import akka.NotUsed
import akka.actor.typed.Behavior
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.persistence.typed.scaladsl.EventSourcedBehavior.CommandHandler
import io.circe.generic.auto._
import lt.dvim.untappd.history.History._
import lt.dvim.untappd.history.Serialization._
import org.slf4j.LoggerFactory

object Migrator {

  sealed trait Command
  case class StoreEvent(e: History.Event) extends Command
  case object CompleteStream extends Command
  case class FailStream(t: Throwable) extends Command

  private final val log = LoggerFactory.getLogger(getClass)

  def behavior(): Behavior[Command] =
    EventSourcedBehavior[Command, Array[Byte], NotUsed](
      persistenceId = PersistenceId(HistoryJson),
      emptyState = NotUsed,
      commandHandler = CommandHandler.command {
        case e: StoreEvent =>
          Effect.persist(e.e.asBytes)
        case CompleteStream =>
          log.info("Migration stream completed")
          Effect.stop()
        case FailStream(t) =>
          log.error("Migration stream failed", t)
          Effect.none
      },
      eventHandler = {
        case (state, _) => state
      }
    )

}
