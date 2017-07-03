package socketio

import akka.util.ByteString
import play.api.libs.json.{JsValue, Json, Reads, Writes}

case class SocketIOSession[T](sid: String, data: T)

case class SocketIOEvent(name: String, arguments: Seq[Either[JsValue, ByteString]], ack: Option[SocketIOEventAck])

trait SocketIOEventAck extends (Seq[Either[JsValue, ByteString]] => Unit)

object SocketIOEventAck {
  def apply(ack: Seq[Either[JsValue, ByteString]] => Unit): SocketIOEventAck = new SocketIOEventAck {
    override def apply(args: Seq[Either[JsValue, ByteString]]) = ack(args)
  }
}

object SocketIOEventCodec {

  def decoders[T](decoders: SocketIOEventDecoder[T]*): SocketIOEventDecoder[T] =
    SocketIOEventDecoder.compose(decoders: _*)

  def decodeJson[T: Reads](name: String): SocketIOEventDecoder[T] =
    SocketIOEventDecoder.json[T](name)

  def decodeJsonAck[T: Reads]: SocketIOAckDecoder[T] =
    SocketIOAckDecoder.json[T]

  def encoders[T](encoders: PartialFunction[Any, SocketIOEventEncoder[_ <: T]]): SocketIOEventEncoder[T] =
    SocketIOEventEncoder.compose[T](encoders)

  def encodeJson[T: Writes](name: String): SocketIOEventEncoder[T] =
    SocketIOEventEncoder.json[T](name)

  def encodeJsonAck[T: Writes]: SocketIOAckEncoder[T] =
    SocketIOAckEncoder.json[T]
}

trait SocketIOEventDecoder[+T] {
  self =>

  def decode: PartialFunction[SocketIOEvent, T]

  def map[S](f: T => S) = new SocketIOEventDecoder[S] {
    override def decode = self.decode.andThen(f)
  }

  def zip[A](decoder: SocketIOEventDecoder[A]): SocketIOEventDecoder[(T, A)] = new SocketIOEventDecoder[(T, A)] {
    override def decode = new PartialFunction[SocketIOEvent, (T, A)] {
      override def isDefinedAt(e: SocketIOEvent) = self.decode.isDefinedAt(e)
      override def apply(e: SocketIOEvent) = applyOrElse(e, (_: SocketIOEvent) => throw new MatchError(e))
      override def applyOrElse[E1 <: SocketIOEvent, B1 >: (T, A)](e: E1, default: (E1) => B1) = {
        self.decode.andThen { t =>
          (t, decoder.decode(e))
        }.applyOrElse(e, default)
      }
    }
  }

  def zipWith[A, S](decoder: SocketIOEventDecoder[A])(f: (T, A) => S): SocketIOEventDecoder[S] =
    zip(decoder).map(f.tupled)

  def withMaybeAck[A](ackEncoder: SocketIOAckEncoder[A]): SocketIOEventDecoder[(T, Option[A => Unit])] =
    zip(new SocketIOEventDecoder[Option[A => Unit]] {
      def decode = {
        case SocketIOEvent(_, _, None) => None
        case SocketIOEvent(_, _, Some(ack)) =>
          Some(ack.compose(ackEncoder.encode))
      }
    })

  def withAck[A](ackEncoder: SocketIOAckEncoder[A]): SocketIOEventDecoder[(T, A => Unit)] =
    withMaybeAck(ackEncoder).map {
      case (t, maybeAck) => (t, maybeAck.getOrElse(throw new RuntimeException("Excepted ack but none found")))
    }

}

object SocketIOEventDecoder {

  def apply[T](name: String)(decoder: SocketIOEvent => T): SocketIOEventDecoder[T] = new SocketIOEventDecoder[T] {
    override def decode = {
      case event if event.name == name => decoder(event)
    }
  }

  def compose[T](decoders: SocketIOEventDecoder[T]*): SocketIOEventDecoder[T] = new SocketIOEventDecoder[T] {
    override def decode = {
      decoders.foldLeft(PartialFunction.empty[SocketIOEvent, T])(_ orElse _.decode)
    }
  }

  def json[T: Reads](name: String): SocketIOEventDecoder[T] = apply(name) { event =>
    event.arguments.headOption.flatMap(_.left.toOption) match {
      case Some(jsValue) => jsValue.as[T]
      case None => throw new RuntimeException("No arguments found to decode")
    }
  }
}

trait SocketIOEventEncoder[T] { self =>

  def encode: PartialFunction[Any, SocketIOEvent]

  def contramap[S](f: S => T) = SocketIOEventEncoder {
    case s: S => self.encode(f(s))
  }

  def withAck[A, S](ackDecoder: SocketIOAckDecoder[A]): SocketIOEventEncoder[(T, A => Unit)] = new SocketIOEventEncoder[(T, A => Unit)] {
    override def encode = {
      case (t: T, ack: (A => Unit)) =>
        val decodedAck = SocketIOEventAck { args =>
          ack(ackDecoder.decode(args))
        }
        self.encode(t).copy(ack = Some(decodedAck))
    }
  }
}

object SocketIOEventEncoder {

  def apply[T](encoder: PartialFunction[Any, SocketIOEvent]): SocketIOEventEncoder[T] = new SocketIOEventEncoder[T] {
    override def encode = encoder
  }

  def compose[T](encoders: PartialFunction[Any, SocketIOEventEncoder[_ <: T]]): SocketIOEventEncoder[T] = new SocketIOEventEncoder[T] {
    override def encode = Function.unlift { t: Any =>
      encoders.lift(t).flatMap { encoder =>
        encoder.encode.lift(t)
      }
    }
  }

  def json[T: Writes](name: String): SocketIOEventEncoder[T] = apply {
    case t: T => SocketIOEvent(name, Seq(Left(Json.toJson(t))), None)
  }
}

trait SocketIOAckDecoder[+T] { self =>

  def decode(args: Seq[Either[JsValue, ByteString]]): T

  def map[S](f: T => S): SocketIOAckDecoder[S] = SocketIOAckDecoder[S] { args =>
    f(self.decode(args))
  }
}

object SocketIOAckDecoder {

  def apply[T](decoder: Seq[Either[JsValue, ByteString]] => T): SocketIOAckDecoder[T] = new SocketIOAckDecoder[T] {
    override def decode(args: Seq[Either[JsValue, ByteString]]) = decoder(args)
  }

  def json[T: Reads]: SocketIOAckDecoder[T] = SocketIOAckDecoder { args =>
    args.headOption.flatMap(_.left.toOption) match {
      case Some(jsValue) => jsValue.as[T]
      case None => throw new RuntimeException("No arguments found to decode")
    }
  }
}

trait SocketIOAckEncoder[T] { self =>

  def encode(t: T): Seq[Either[JsValue, ByteString]]

  def contramap[S](f: S => T): SocketIOAckEncoder[S] = SocketIOAckEncoder { s =>
    self.encode(f(s))
  }
}

object SocketIOAckEncoder {

  def apply[T](encoder: T => Seq[Either[JsValue, ByteString]]): SocketIOAckEncoder[T] = new SocketIOAckEncoder[T] {
    override def encode(t: T) = encoder(t)
  }

  def json[T: Writes]: SocketIOAckEncoder[T] = SocketIOAckEncoder { t: T =>
    Seq(Left(Json.toJson(t)))
  }
}