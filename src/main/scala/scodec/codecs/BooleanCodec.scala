package scodec
package codecs

import scalaz.\/
import scalaz.syntax.std.either._

import scodec.bits.BitVector

private[codecs] object BooleanCodec extends Codec[Boolean] {

  override def encode(b: Boolean) =
    \/.right(if (b) BitVector.high(1) else BitVector.low(1))

  override def decode(buffer: BitVector) =
    buffer.consume(1) { b => Right(b.head) }.disjunction

  override def toString = "bool"
}
