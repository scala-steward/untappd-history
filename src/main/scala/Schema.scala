package lt.dvim.untappd.history

import slick.jdbc.H2Profile.api._
import slick.jdbc.meta.MTable

import scala.concurrent.{ExecutionContext, Future}

object Schema {
  class Journal(tag: Tag) extends Table[(Long, String, Long, Boolean, Option[String], Array[Byte])](tag, "JOURNAL") {
    def ordering = column[Long]("ORDERING", O.AutoInc)
    def persistenceId = column[String]("PERSISTENCE_ID", O.Length(255))
    def sequenceNumber = column[Long]("SEQUENCE_NUMBER")
    def deleted = column[Boolean]("DELETED", O.Default(false))
    def tags = column[Option[String]]("TAGS", O.Default(None), O.Length(255))
    def message = column[Array[Byte]]("MESSAGE")
    def jIdx = index("J_IDX", (persistenceId, sequenceNumber), unique = true)
    def * = (ordering, persistenceId, sequenceNumber, deleted, tags, message)
  }

  class Snapshot(tag: Tag) extends Table[(String, Long, Long, Array[Byte])](tag, "SNAPSHOT") {
    def persistenceId = column[String]("PERSISTENCE_ID", O.Length(255))
    def sequenceNumber = column[Long]("SEQUENCE_NUMBER")
    def created = column[Long]("CREATED")
    def snapshot = column[Array[Byte]]("SNAPSHOT")
    def sIdx = index("S_IDX", (persistenceId, sequenceNumber), unique = true)
    def * = (persistenceId, sequenceNumber, created, snapshot)
  }

  def createSchemaIfNotExists()(implicit ec: ExecutionContext): Future[Unit] = {
    val db = Database.forConfig("slick.db")
    val tables = Seq(TableQuery[Journal], TableQuery[Snapshot])

    db.run(MTable.getTables)
      .flatMap { existing =>
        val names = existing.map(_.name.name)
        val actions = tables
          .filter(table => !names.contains(table.baseTableRow.tableName))
          .map(_.schema.create)
        db.run(DBIO.sequence(actions))
      }
      .map(_ => ())
  }
}
