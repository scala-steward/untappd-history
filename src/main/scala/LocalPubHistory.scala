package lt.dvim.untappd.history

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, Attributes, Materializer, ThrottleMode}
import akka.stream.alpakka.dynamodb.impl.DynamoSettings
import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import akka.stream.scaladsl.{Keep, RestartFlow, Sink, Source}
import ciris._
import ciris.syntax._
import com.amazonaws.auth.{AWSCredentials, AWSCredentialsProvider, AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, PutItemRequest, ScanRequest}
import akka.stream.alpakka.dynamodb.scaladsl.DynamoImplicits._
import com.typesafe.config.ConfigFactory
import io.circe.parser._
import io.circe.optics.JsonPath._

import scala.util.{Failure, Success}
import scala.concurrent.duration._
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

    val dynamo = DynamoClient(config.dynamo)

    val future = args.toList match {
      case "current" :: Nil => storeLatest(dynamo)
      case "before" :: checkinId :: Nil => storeHistoryBefore(checkinId, dynamo)
      case "scan" :: Nil => scan(dynamo)
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

  private def storeLatest(dynamo: DynamoClient)(implicit sys: ActorSystem, mat: Materializer) = {
    val request = HttpRequest(uri = untappdUri(config))
    queryAndStoreResults(request, dynamo)
  }

  private def storeHistoryBefore(checkinId: String, dynamo: DynamoClient)(implicit sys: ActorSystem, mat: Materializer) = {
    val uri = untappdUri(config)
    val request = HttpRequest(uri = uri.withQuery(uri.query().+:("max_id" -> checkinId)))
    queryAndStoreResults(request, dynamo)
  }

  private def queryAndStoreResults(request: HttpRequest, dynamo: DynamoClient)(implicit sys: ActorSystem, mat: Materializer) =
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
      .via(dynamo.flow.throttle(1, 2.second, 1, ThrottleMode.shaping))
      .withAttributes(Attributes.logLevels(onElement = Logging.InfoLevel))
      .runWith(Sink.fold(0) { case (i, q) => i + 1 })

  private def scan(dynamo: DynamoClient)(implicit sys: ActorSystem, mat: Materializer) = {
    Source
      .single(
        new ScanRequest()
          .withTableName(config.tableName)
          .withExclusiveStartKey(Map("checkin_id" -> new AttributeValue("638183270")).asJava)
          .withAttributesToGet("checkin_id").toOp
      )
      .via(dynamo.flow)
      .log("Last key", _.getLastEvaluatedKey)
      .also
      .mapConcat(_.getItems.asScala.flatMap(_.values().asScala).toList)
      .log("Checkin")
      .withAttributes(Attributes.logLevels(onElement = Logging.InfoLevel))
      .runWith(Sink.fold(0) { case (i, q) => i + 1 })
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
        new DynamoSettings(
          "eu-central-1",
          "dynamodb.eu-central-1.amazonaws.com",
          443,
          1,
          new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey.value))
        ),
        "untappd-local-pub"
      )
    } orThrow()

}
