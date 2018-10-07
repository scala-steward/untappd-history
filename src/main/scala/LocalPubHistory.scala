package lt.dvim.untappd.history

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.alpakka.dynamodb.scaladsl.DynamoDbExternal
import akka.stream.alpakka.dynamodb.{DynamoClient, DynamoSettings}
import akka.stream._
import akka.stream.contrib.PagedSource
import akka.stream.scaladsl.{Flow, GraphDSL, Keep, MergePreferred, RestartFlow, RestartSource, Sink, Source}
import ciris._
import ciris.syntax._
import com.amazonaws.auth.{AWSCredentials, AWSCredentialsProvider, AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, PutItemRequest, ScanRequest}
import akka.stream.alpakka.dynamodb.scaladsl.DynamoImplicits._
import com.typesafe.config.ConfigFactory
import io.circe.parser._
import io.circe.optics.JsonPath._

import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.collection.JavaConverters._

object LocalPubHistory {

  def main(args: Array[String]): Unit = {
    val actorConfig = ConfigFactory.parseString(
      """
        |akka.loglevel = INFO
      """.stripMargin)

    implicit val sys = ActorSystem("LocalPubHistory", actorConfig)
    implicit val mat = ActorMaterializer()
    import sys.dispatcher

    implicit val dynamo: DynamoClient = DynamoClient(config.dynamo)

    val future = args.toList match {
      case "current" :: Nil => storeLatest()
      case "before" :: checkinId :: Nil => storeHistoryBefore(checkinId)
      case "scan" :: Nil => scan()
      case _ => scala.sys.error("Unknown command")
    }

    future
      .andThen {
        case result =>
          Http().shutdownAllConnectionPools()
          sys.terminate()
          result
      }
      .onComplete {
        case Success(count) => println(s"Successfully processed $count results")
        case Failure(ex) => ex.printStackTrace()
      }
  }

  private def storeLatest()(implicit sys: ActorSystem, mat: Materializer, dynamo: DynamoClient) = {
    val request = HttpRequest(uri = untappdUri(config))
    queryAndStoreResults(request)
  }

  private def storeHistoryBefore(checkinId: String)(implicit sys: ActorSystem, mat: Materializer, dynamo: DynamoClient) = {
    val uri = untappdUri(config)
    val request = HttpRequest(uri = uri.withQuery(uri.query().+:("max_id" -> checkinId)))
    queryAndStoreResults(request)
  }

  private def queryAndStoreResults(request: HttpRequest)(implicit sys: ActorSystem, mat: Materializer, dynamo: DynamoClient) =
    Source.single(request)
      .mapAsync(parallelism = 1)(Http().singleRequest(_))
      .mapAsync(parallelism = 1)(resp => Unmarshal(resp.entity).to[String])
      .map(body => parse(body).toTry.get)
      .mapConcat(Optics.items.getOption(_).toList.flatten)
      .map(json => (json, Optics.checkinId.getOption(json).get))
      .log("Single checkin", _._2)
      .map {
        case (json, checkinId) =>
          new PutItemRequest()
            .withTableName(config.tableName)
            .addItemEntry("checkin_id", new AttributeValue(checkinId.toString))
            .addItemEntry("body", new AttributeValue(json.toString())).toOp
      }
      .via(DynamoDbExternal.flow[PutItem].throttle(1, 2.second, 1, ThrottleMode.shaping))
      .withAttributes(Attributes.logLevels(onElement = Logging.InfoLevel))
      .runWith(Sink.fold(0) { case (i, q) => i + 1 })

  private def scan()(implicit sys: ActorSystem, mat: Materializer, dynamo: DynamoClient) = {
    import sys.dispatcher
    ScanSource.from("638183270")
      .runWith(Sink.fold(0) { case (i, _) => i + 1 })
  }

  object ScanSource {
    def retryCycle[A, B](flow: Flow[A, Try[B], _]) = {
      Flow.fromGraph(GraphDSL.create(flow) { implicit b => inner =>
        import GraphDSL.Implicits._

        val mergeRetries = b.add(MergePreferred[A](1))

        mergeRetries.in(0) ~> flow

        val f = b.add(Flow[A])

        f.in ~> flow ~>

        FlowShape(f.in, ???)
      })
    }

    def from(firstKey: String)(implicit mat: Materializer, ec: ExecutionContext, dynamo: DynamoClient): Source[String, NotUsed] = PagedSource(firstKey) { key =>
      Source
        .single(
          new ScanRequest()
            .withTableName(config.tableName)
            //.withExclusiveStartKey(Map("checkin_id" -> new AttributeValue(key)).asJava)
            .withAttributesToGet("checkin_id").toOp
        )
        .via(DynamoDbExternal.tryFlow)
        .log("Last key", _.getLastEvaluatedKey)
        .map { scanResult =>
          val items = scanResult
            .getItems.asScala
            .flatMap(_.values().asScala)
            .toList
            .map(_.toString)
          val lastKey = Option(scanResult.getLastEvaluatedKey).map(_.toString)
          PagedSource.Page(items, lastKey)
        }
        .withAttributes(Attributes.logLevels(onElement = Logging.InfoLevel))
        .runWith(Sink.head)
    }
  }

  private def untappdUri(config: Config) = {
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
  case class Config(location: Location, clientId: String, clientSecret: Secret[String], dynamo: DynamoSettings, tableName: String)

  final val config =
    loadConfig(
      env[String]("UNTAPPD_CLIENT_ID").orElse(prop("untappd.client-id")),
      env[Secret[String]]("UNTAPPD_CLIENT_SECRET").orElse(prop("untappd.client-secret")),
      env[String]("DYNAMODB_ACCESS_KEY").orElse(prop("dynamodb.access-key")),
      env[Secret[String]]("DYNAMODB_SECRET_KEY").orElse(prop("dynamodb.secret-key")),
    ) { (clientId, clientSecret, accessKey, secretKey) =>
      Config(
        Location(54.688567, 25.275775), // Vilnius
        clientId,
        clientSecret,
        DynamoSettings("eu-central-1", "dynamodb.eu-central-1.amazonaws.com")
          .withCredentialsProvider(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey.value))),
        "untappd-local-pub"
      )
    } orThrow()

}
