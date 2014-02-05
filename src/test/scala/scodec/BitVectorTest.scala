package scodec

import scalaz.syntax.id._
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks


class BitVectorTest extends FunSuite with Matchers with GeneratorDrivenPropertyChecks {

  val flatBytes = genBitVector(500, 7)                 // regular flat bit vectors
  val balancedTrees = genConcat(genBitVector(2000, 7)) // balanced trees of concatenations
  val splitVectors = genSplit(5000)                    // split bit vectors: b.take(m) ++ b.drop(m)
  val concatSplitVectors = genConcat(genSplit(5000))   // concatenated split bit vectors

  val chunk = Gen.oneOf(flatBytes, balancedTrees, splitVectors, concatSplitVectors)
  val bitStreams = genBitStream(chunk, Gen.choose(0,5)) // streams of chunks

  implicit val arbitraryBitVector: Arbitrary[BitVector] = Arbitrary {
    Gen.oneOf(flatBytes, balancedTrees, splitVectors, concatSplitVectors, bitStreams)
  }

  implicit val shrinkBitVector: Shrink[BitVector] =
    Shrink[BitVector] { b =>
      if (b.nonEmpty)
        Stream.iterate(b.take(b.size / 2))(b2 => b2.take(b2.size / 2)).takeWhile(_.nonEmpty) ++
        Stream(BitVector.empty)
      else Stream.empty
    }

  def genBitStream(g: Gen[BitVector], steps: Gen[Int]): Gen[BitVector] = for {
    n <- steps
    chunks <- Gen.listOfN(n, g)
  } yield BitVector.unfold(chunks)(s => s.headOption.map((_,s.tail)))

  def genBitVector(maxBytes: Int, maxAdditionalBits: Int): Gen[BitVector] = for {
    byteSize <- Gen.choose(0, maxBytes)
    additionalBits <- Gen.choose(0, maxAdditionalBits)
    size = byteSize * 8 + additionalBits
    bytes <- Gen.listOfN((size + 7) / 8, Gen.choose(0, 255))
  } yield BitVector(ByteVector(bytes: _*)).take(size)

  def genSplit(maxSize: Long) = for {
    n <- Gen.choose(0L, maxSize)
    b <- genBitVector(15, 7)
  } yield {
    val m = if (b.nonEmpty) (n % b.size).abs else 0
    b.take(m) ++ b.drop(m)
  }

  def genConcat(g: Gen[BitVector]) =
    genBitVector(2000, 7).map { b =>
      b.toIndexedSeq.foldLeft(BitVector.empty)(
        (acc,high) => acc ++ BitVector.bit(high)
      )
    }

  test("hashCode/equals") {
    forAll { (b: BitVector, b2: BitVector, m: Long) =>
      val n = if (b.nonEmpty) (m % b.size).abs else 0
      (b.take(m) ++ b.drop(m)).hashCode shouldBe b.hashCode
      if (b.take(3) == b2.take(3)) {
        // kind of weak, since this will only happen 1/8th of attempts on average
        b.take(3).hashCode shouldBe b2.take(3).hashCode
      }
    }
  }

  test("construction via high") {
    BitVector.high(1).toByteVector shouldBe ByteVector(0x80)
    BitVector.high(2).toByteVector shouldBe ByteVector(0xc0)
    BitVector.high(3).toByteVector shouldBe ByteVector(0xe0)
    BitVector.high(4).toByteVector shouldBe ByteVector(0xf0)
    BitVector.high(5).toByteVector shouldBe ByteVector(0xf8)
    BitVector.high(6).toByteVector shouldBe ByteVector(0xfc)
    BitVector.high(7).toByteVector shouldBe ByteVector(0xfe)
    BitVector.high(8).toByteVector shouldBe ByteVector(0xff)
    BitVector.high(9).toByteVector shouldBe ByteVector(0xff, 0x80)
    BitVector.high(10).toByteVector shouldBe ByteVector(0xff, 0xc0)
  }

  test("empty toByteVector") {
    BitVector.empty.toByteVector shouldBe ByteVector.empty
  }

  test("apply") {
    val vec = BitVector(ByteVector(0xf0, 0x0f))
    vec(0) should be (true)
    vec(1) should be (true)
    vec(2) should be (true)
    vec(3) should be (true)
    vec(4) should be (false)
    vec(5) should be (false)
    vec(6) should be (false)
    vec(7) should be (false)
    vec(8) should be (false)
    vec(9) should be (false)
    vec(10) should be (false)
    vec(11) should be (false)
    vec(12) should be (true)
    vec(13) should be (true)
    vec(14) should be (true)
    vec(15) should be (true)
  }

  test("updated") {
    val vec = BitVector.low(16)
    vec.set(6).get(6) should be (true)
    vec.set(10).get(10) should be (true)
    vec.set(10).clear(10).get(10) should be (false)
  }

  test("drop") {
    BitVector.high(8).drop(4).toByteVector shouldBe ByteVector(0xf0)
    BitVector.high(8).drop(3).toByteVector shouldBe ByteVector(0xf8)
    BitVector.high(10).drop(3).toByteVector shouldBe ByteVector(0xfe)
    BitVector.high(10).drop(3) shouldBe BitVector.high(7)
    BitVector.high(12).drop(3).toByteVector shouldBe ByteVector(0xff, 0x80)
    BitVector.empty.drop(4) shouldBe BitVector.empty
    BitVector.high(4).drop(8) shouldBe BitVector.empty
    forAll { (x: BitVector, n: Long) =>
      val m = if (x.nonEmpty) (n % x.size).abs else 0
      x.compact.drop(m).toIndexedSeq.take(4) shouldBe x.toIndexedSeq.drop(m.toInt).take(4)
      x.compact.drop(m).compact.toIndexedSeq.take(4) shouldBe x.toIndexedSeq.drop(m.toInt).take(4)
    }
  }

  test("take/drop") {
    BitVector.high(8).take(4).toByteVector shouldBe ByteVector(0xf0)
    BitVector.high(8).take(4) shouldBe BitVector.high(4)
    BitVector.high(8).take(5).toByteVector shouldBe ByteVector(0xf8)
    BitVector.high(8).take(5) shouldBe BitVector.high(5)
    BitVector.high(10).take(7).toByteVector shouldBe ByteVector(0xfe)
    BitVector.high(10).take(7) shouldBe BitVector.high(7)
    BitVector.high(12).take(9).toByteVector shouldBe ByteVector(0xff, 0x80)
    BitVector.high(12).take(9) shouldBe BitVector.high(9)
    BitVector.high(4).take(100).toByteVector shouldBe ByteVector(0xf0)
    forAll { (x: BitVector, n0: Long, m0: Long) =>
      val m = if (x.nonEmpty) (m0 % x.size).abs else 0
      val n = if (x.nonEmpty) (n0 % x.size).abs else 0
      (x.take(m) ++ x.drop(m)).compact shouldBe x
      x.take(m+n).compact.take(n) shouldBe x.take(n)
      x.drop(m+n).compact shouldBe x.drop(m).compact.drop(n)
      x.drop(n).take(m).toIndexedSeq shouldBe BitVector.bits(x.drop(n).toIndexedSeq).take(m).toIndexedSeq
    }
  }

  test("dropRight") {
    BitVector.high(12).clear(0).dropRight(4).toByteVector shouldBe ByteVector(0x7f)
    forAll { (x: BitVector, n0: Long, m0: Long) =>
      val m = if (x.nonEmpty) (m0 % x.size).abs else 0
      val n =  if (x.nonEmpty) (n0 % x.size).abs else 0
      x.dropRight(m).dropRight(n) shouldBe x.dropRight(m + n)
      x.dropRight(m) shouldBe x.take(x.size - m)
    }
  }

  test("takeRight") {
    BitVector.high(12).clear(0).takeRight(4).toByteVector shouldBe ByteVector(0xf0)
    forAll { (x: BitVector, n0: Long, m0: Long) =>
      val m = if (x.nonEmpty) (m0 % x.size).abs else 0
      val n =  if (x.nonEmpty) (n0 % x.size).abs else 0
      x.takeRight(m max n).takeRight(n).compact shouldBe x.takeRight(n)
      x.takeRight(m) shouldBe x.drop(x.size - m)
    }
  }

  test("compact") {
    forAll { (x: BitVector) =>
      x.compact shouldBe x
      x.force.depthExceeds(16) shouldBe false
    }
  }

  test("++") {
    (BitVector.low(7) ++ BitVector.high(1)).toByteVector shouldBe ByteVector(1: Byte)
    (BitVector.high(8) ++ BitVector.high(8)).toByteVector shouldBe ByteVector(-1: Byte, -1: Byte)
    (BitVector.high(4) ++ BitVector.low(4)).toByteVector shouldBe ByteVector(0xf0)
    (BitVector.high(4) ++ BitVector.high(4)).toByteVector shouldBe ByteVector(-1: Byte)
    (BitVector.high(4) ++ BitVector.high(5)).toByteVector shouldBe ByteVector(-1: Byte, 0x80)
    (BitVector.low(2) ++ BitVector.high(4)).toByteVector shouldBe ByteVector(0x3c)
    (BitVector.low(2) ++ BitVector.high(4) ++ BitVector.low(2)).toByteVector shouldBe ByteVector(0x3c)
    forAll { (x: BitVector, y: BitVector) =>
      (x ++ y).compact.toIndexedSeq shouldBe (x.toIndexedSeq ++ y.toIndexedSeq)
    }
  }

  test("b.take(n).drop(n) == b") {
    implicit val intGen = Arbitrary(Gen.choose(0,10000))
    forAll { (xs: List[Boolean], n0: Int, m0: Int) =>
      whenever(xs.nonEmpty) {
        val n = n0.abs % xs.size
        val m = m0.abs % xs.size
        xs.drop(m).take(n) shouldBe xs.take(m+n).drop(m)
      }
    }
    forAll { (xs: BitVector, n0: Long) =>
      val m = if (xs.nonEmpty) n0 % xs.size else 0
      (xs.take(m) ++ xs.drop(m)).compact shouldBe xs
    }
  }

  test("<<") {
    (BitVector.high(8) << 0).toByteVector shouldBe ByteVector(0xff)
    (BitVector.high(8) << 4).toByteVector shouldBe ByteVector(0xf0)
    (BitVector.high(10) << 1).toByteVector shouldBe ByteVector(0xff, 0x80)
    (BitVector.high(10) << 3).toByteVector shouldBe ByteVector(0xfe, 0x00)
    (BitVector.high(32) << 16).toByteVector shouldBe ByteVector(0xff, 0xff, 0, 0)
    (BitVector.high(32) << 15).toByteVector shouldBe ByteVector(0xff, 0xff, 0x80, 0)
  }

  test(">>") {
    (BitVector.high(8) >> 8) shouldBe BitVector.high(8)
  }

  test(">>>") {
    (BitVector.high(8) >>> 7).toByteVector shouldBe ByteVector(0x01)
  }

  test("padTo") {
    BitVector.high(2).padTo(8).toByteVector shouldBe ByteVector(0xc0)
    BitVector.high(16).padTo(32).toByteVector shouldBe ByteVector(0xff, 0xff, 0, 0)
  }

  test("~") {
    ~BitVector.high(12) shouldBe BitVector.low(12)
    ~BitVector.low(12) shouldBe BitVector.high(12)
    ~BitVector(10, 10) shouldBe BitVector(245, 245)
    ~BitVector(245, 245) shouldBe BitVector(10, 10)
  }

  test("&") {
    BitVector.high(16) & BitVector.high(16) shouldBe BitVector.high(16)
    BitVector.low(16) & BitVector.high(16) shouldBe BitVector.low(16)
    BitVector.high(16) & BitVector.low(16) shouldBe BitVector.low(16)
    BitVector.low(16) & BitVector.low(16) shouldBe BitVector.low(16)
    BitVector.high(16) & BitVector.high(9) shouldBe BitVector.high(9)
  }

  test("|") {
    BitVector.high(16) | BitVector.high(16) shouldBe BitVector.high(16)
    BitVector.low(16) | BitVector.high(16) shouldBe BitVector.high(16)
    BitVector.high(16) | BitVector.low(16) shouldBe BitVector.high(16)
    BitVector.low(16) | BitVector.low(16) shouldBe BitVector.low(16)
    BitVector.high(16) | BitVector.low(9) shouldBe BitVector.high(9)
  }

  test("^") {
    BitVector.high(16) ^ BitVector.high(16) shouldBe BitVector.low(16)
    BitVector.low(16) ^ BitVector.high(16) shouldBe BitVector.high(16)
    BitVector.high(16) ^ BitVector.low(16) shouldBe BitVector.high(16)
    BitVector.low(16) ^ BitVector.low(16) shouldBe BitVector.low(16)
    BitVector.high(16) ^ BitVector.low(9) shouldBe BitVector.high(9)
    BitVector(10, 245) ^ BitVector(245, 10) shouldBe BitVector.high(16)
  }

  test("toIndexedSeq") {
    BitVector.high(8).toIndexedSeq shouldBe List.fill(8)(true)
  }

  test("reverse") {
    BitVector(0x03).reverse shouldBe BitVector(0xc0)
    BitVector(0x03, 0x80).reverse shouldBe BitVector(0x01, 0xc0)
    BitVector(0x01, 0xc0).reverse shouldBe BitVector(0x03, 0x80)
    BitVector(0x30).take(4).reverse shouldBe BitVector(0xc0).take(4)
    forAll { (bv: BitVector) =>
      bv.reverse.reverse shouldBe bv
    }
  }

  test("reverseByteOrder") {
    BitVector(0x00, 0x01).reverseByteOrder shouldBe BitVector(0x01, 0x00)
    // Double reversing should yield original if size is divisible by 8
    forAll(genBitVector(500, 0)) { (bv: BitVector) =>
      bv.reverseByteOrder.reverseByteOrder shouldBe bv
    }
  }

  test("toHex") {
    BitVector(0x01, 0x02).toHex shouldBe "0102"
    BitVector(0x01, 0x02).drop(4).toHex shouldBe "102"
    BitVector(0x01, 0x02).drop(5).toHex shouldBe "204"
    forAll { (bv: BitVector) =>
      if (bv.size % 8 == 0 || bv.size % 8 > 4) bv.toHex shouldBe bv.toByteVector.toHex
      else bv.toHex shouldBe bv.toByteVector.toHex.init
    }
  }

  test("fromHex") {
    BitVector.fromHex("0x012") shouldBe BitVector(0x01, 0x20).take(12).right
    BitVector.fromHex("0x01gg") shouldBe "Invalid octet 'gg' at position 2".left
    forAll { (bv: BitVector) =>
      val x = bv.padTo((bv.size + 3) / 4 * 4)
      BitVector.fromValidHex(x.toHex) shouldBe x
    }
  }

  test("fromValidHex") {
    BitVector.fromValidHex("0x012") shouldBe BitVector(0x01, 0x20).take(12)
    evaluating { BitVector.fromValidHex("0x01gg") } should produce[IllegalArgumentException]
  }

  test("toBin") {
    BitVector(0x01, 0x02, 0xff).toBin shouldBe "000000010000001011111111"
    BitVector(0x01, 0x02, 0xff).drop(3).toBin shouldBe "000010000001011111111"
    forAll { (bv: BitVector) =>
      bv.toBin shouldBe bv.toByteVector.toBin.take(bv.size.toInt)
    }
  }

  test("fromBin") {
    forAll { (bv: BitVector) =>
      BitVector.fromBin(bv.toBin) shouldBe bv.right
    }
    BitVector.fromBin("0102") shouldBe "Invalid bit '2' at position 3".left
  }

  test("fromValidBin") {
    forAll { (bv: BitVector) =>
      BitVector.fromValidBin(bv.toBin) shouldBe bv
    }
    evaluating { BitVector.fromValidBin("0x0102") } should produce[IllegalArgumentException]
  }

  test("bin string interpolator") {
    bin"0010" shouldBe BitVector(0x20).take(4)
    val x = BitVector.fromValidBin("10")
    bin"00$x" shouldBe BitVector(0x20).take(4)
    // TODO Upon upgrade to ScalaTest 2.1, make this "..." shouldNot compile
    // evaluating { bin"asdf" } should produce[IllegalArgumentException]
  }
}
