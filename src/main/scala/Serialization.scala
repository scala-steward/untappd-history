package lt.dvim.untappd.history

import java.nio.charset.StandardCharsets

import io.circe._
import io.circe.parser._

object Serialization {

  implicit final class SerializationOps[A](val encodable: A) extends AnyVal {
    final def asBytes(implicit encoder: Encoder[A]): Array[Byte] =
      encoder(encodable).noSpaces.getBytes(StandardCharsets.UTF_8)
  }

  def decodeBytes[A: Decoder](input: Any): Either[Error, A] = input match {
    case bytes: Array[Byte] => decode[A](new String(bytes, StandardCharsets.UTF_8))
    case _ => Left(ParsingFailure("Got non Array[Byte] to parse", new scala.Error()))
  }

}
