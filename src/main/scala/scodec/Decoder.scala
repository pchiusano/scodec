package scodec

import scalaz.{ \/, Monad, Monoid }

import scodec.bits.BitVector

/** Supports decoding a value of type `A` from a `BitVector`. */
trait Decoder[+A] { self =>

  /**
   * Attempts to decode a value of type `A` from the specified bit vector.
   *
   * @param bits bits to decode
   * @return error if value could not be decoded or the remaining bits and the decoded value
   */
  def decode(bits: BitVector): String \/ (BitVector, A)

  /** Converts this decoder to a `Decoder[B]` using the supplied `A => B`. */
  def map[B](f: A => B): Decoder[B] = new Decoder[B] {
    def decode(bits: BitVector) = self.decode(bits) map { case (rem, a) => (rem, f(a)) }
  }

  /** Converts this decoder to a `Decoder[B]` using the supplied `A => Decoder[B]`. */
  def flatMap[B](f: A => Decoder[B]): Decoder[B] = new Decoder[B] {
    def decode(bits: BitVector) = self.decode(bits) flatMap { case (rem, a) => f(a).decode(rem) }
  }
}

/** Provides functions for working with decoders. */
trait DecoderFunctions {

  /** Decodes the specified bit vector using the specified codec and discards the remaining bits. */
  final def decode[A](dec: Decoder[A], bits: BitVector): String \/ A = dec.decode(bits) map { case (_, value) => value }

  /** Decodes the specified bit vector in to a value of type `A` using an implicitly available codec and discards the remaining bits. */
  final def decode[A: Decoder](bits: BitVector): String \/ A = decode(Decoder[A], bits)

  /** Decodes a tuple `(A, B)` by first decoding `A` and then using the remaining bits to decode `B`. */
  final def decodeBoth[A, B](decA: Decoder[A], decB: Decoder[B])(buffer: BitVector): String \/ (BitVector, (A, B)) = (for {
    a <- DecodingContext(decA.decode)
    b <- DecodingContext(decB.decode)
  } yield (a, b)).run(buffer)

  /**
   * Repeatedly decodes values of type `A` from the specified vector, converts each value to a `B` and appends it to an accumulator of type `B` using the `Monoid[B]`.
   * Terminates when no more bits are available in the vector. Exits upon the first error from decoding.
   *
   * @return tuple consisting of the terminating error if any and the accumulated value
   */
  final def decodeAll[A: Decoder, B: Monoid](buffer: BitVector)(f: A => B): (Option[String], B) = {
    val decoder = Decoder[A]
    var remaining = buffer
    var acc = Monoid[B].zero
    while (remaining.nonEmpty) {
      decoder.decode(remaining).fold(
        { err => return (Some(err), acc) },
        { case (newRemaining, a) =>
            remaining = newRemaining
            acc = Monoid[B].append(acc, f(a))
        }
      )
    }
    (None, acc)
  }
}

/** Companion for [[Decoder]]. */
object Decoder extends DecoderFunctions {

  /** Provides syntaax for summoning a `Decoder[A]` from implicit scope. */
  def apply[A](implicit dec: Decoder[A]): Decoder[A] = dec

  /** Creates a decoder that always decodes the specified value and returns the input bit vector unmodified. */
  def point[A](a: => A): Decoder[A] = new Decoder[A] {
    private lazy val value = a
    def decode(bits: BitVector) = \/.right((bits, value))
    override def toString = s"const($value)"
  }

  implicit val monadInstance: Monad[Decoder] = new Monad[Decoder] {
    def point[A](a: => A) = Decoder.point(a)
    def bind[A, B](decoder: Decoder[A])(f: A => Decoder[B]) = decoder.flatMap(f)
  }

  implicit def monoidInstance[A: Monoid]: Monoid[Decoder[A]] = new Monoid[Decoder[A]] {
    def zero = Decoder.point(Monoid[A].zero)
    def append(x: Decoder[A], y: => Decoder[A]) = new Decoder[A] {
      private lazy val yy = y
      def decode(bits: BitVector) = for {
        first <- x.decode(bits)
        second <- yy.decode(first._1)
      } yield (second._1, Monoid[A].append(first._2, second._2))
    }
  }
}
