package lt.dvim.untappd.history

import ciris._
import ciris.api.Id
import com.typesafe.config.{Config, ConfigException, ConfigFactory}

import scala.util.Try
import scala.util.control.NonFatal

object Hocon {

  val HoconPathType = ConfigKeyType[String]("hocon at path")

  val hoconSource: ConfigSource[Id, String, Config] =
    ConfigSource.catchNonFatal(HoconPathType) {
      case path => ConfigFactory.load().getConfig(path)
    }

  final case class HoconKey(path: String, key: String) {
    override def toString: String = s"path=$path,key=$key"
  }

  val HoconKeyType = ConfigKeyType[HoconKey]("hocon at key")

  final class HoconAt(path: String) {
    private val hocon: Either[ConfigError, Config] =
      hoconSource
        .read(path)
        .value

    private def hoconKey(key: String): HoconKey =
      HoconKey(path, key)

    private def hoconAt(key: String): Either[ConfigError, String] =
      hocon.flatMap { props =>
        Try(props.getString(key)).toEither.left.map {
          case _: ConfigException.Missing =>
            ConfigError.missingKey(hoconKey(key), HoconKeyType)
          case NonFatal(ex) =>
            ConfigError.readException(hoconKey(key), HoconKeyType, ex)
        }
      }

    def apply[Value](key: String)(
        implicit decoder: ConfigDecoder[String, Value]
    ): ConfigEntry[Id, HoconKey, String, Value] =
      ConfigEntry(
        hoconKey(key),
        HoconKeyType,
        hoconAt(key)
      ).decodeValue[Value]

    override def toString: String =
      s"HoconAt($path)"
  }

  def hoconAt(path: String): HoconAt =
    new HoconAt(path)
}
