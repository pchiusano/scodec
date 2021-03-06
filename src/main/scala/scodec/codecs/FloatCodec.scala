package scodec
package codecs

import java.nio.{ ByteBuffer, ByteOrder }

import scalaz.syntax.id._
import scalaz.syntax.std.either._
import scodec.bits.BitVector

private[codecs] final class FloatCodec(bigEndian: Boolean) extends Codec[Float] {

  private val byteOrder = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN

  override def encode(value: Float) =
    BitVector(ByteVector.view(ByteBuffer.allocate(4).order(byteOrder).putFloat(value).array)).right

  override def decode(buffer: BitVector) =
    buffer.consume(32) { b =>
      Right(ByteBuffer.wrap(b.toByteArray).order(byteOrder).getFloat)
    }.disjunction
}
