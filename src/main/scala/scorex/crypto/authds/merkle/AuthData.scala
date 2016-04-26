package scorex.crypto.authds.merkle

import com.google.common.primitives.{Ints, Longs}
import play.api.libs.json._
import scorex.crypto.authds.merkle.MerkleTree.Position
import scorex.crypto.encode.{Base16, Base58}
import scorex.crypto.hash.CryptographicHash

import scala.annotation.tailrec
import scala.util.Try

/**
  * @param data - data block
  * @param merklePath - segment position and merkle path, complementary to data block
  */
case class AuthData[HashFunction <: CryptographicHash](data: Array[Byte], merklePath: MerklePath[HashFunction]) {

  type Digest = HashFunction#Digest

  lazy val bytes: Array[Byte] = {
    require(this.merklePathHashes.nonEmpty, "Merkle path cannot be empty")
    val dataSize = Ints.toByteArray(this.data.length)
    val merklePathLength = Ints.toByteArray(this.merklePathHashes.length)
    val merklePathSize = Ints.toByteArray(this.merklePathHashes.head.length)
    val merklePathBytes = this.merklePathHashes.foldLeft(Array.empty: Array[Byte])((b, mp) => b ++ mp)
    dataSize ++ merklePathLength ++ merklePathSize ++ data ++ merklePathBytes ++ Longs.toByteArray(merklePath.index)
  }

  lazy val merklePathHashes = merklePath.hashes

  /**
    * Checks that this block is at position $index in tree with root hash = $rootHash
    */
  def check(rootHash: Digest)(implicit hashFunction: HashFunction): Boolean = {

    @tailrec
    def calculateHash(idx: Position, nodeHash: Digest, path: Seq[Digest]): Digest = {
      val hash = if (idx % 2 == 0) hashFunction(nodeHash ++ path.head) else hashFunction(path.head ++ nodeHash)
      if (path.size == 1) hash else calculateHash(idx / 2, hash, path.tail)
    }

    if (merklePathHashes.nonEmpty) {
      val calculatedRoot = calculateHash(merklePath.index, hashFunction(data), merklePathHashes)
      println("Calculated root: " + Base16.encode(calculatedRoot))
      calculatedRoot sameElements rootHash
    } else {
      false
    }
  }
}

object AuthData {
  def decode[HashFunction <: CryptographicHash](bytes: Array[Byte]): Try[AuthData[HashFunction]] = Try {
    val dataSize = Ints.fromByteArray(bytes.slice(0, 4))
    val merklePathLength = Ints.fromByteArray(bytes.slice(4, 8))
    val merklePathSize = Ints.fromByteArray(bytes.slice(8, 12))
    val data = bytes.slice(12, 12 + dataSize)
    val merklePathStart = 12 + dataSize
    val merklePath = (0 until merklePathLength).map { i =>
      bytes.slice(merklePathStart + i * merklePathSize, merklePathStart + (i + 1) * merklePathSize)
    }
    val index = Longs.fromByteArray(bytes.takeRight(8))
    AuthData(data, MerklePath(index, merklePath))
  }

  implicit def authDataBlockReads[T, HashFunction <: CryptographicHash]
  (implicit fmt: Reads[T]): Reads[AuthData[HashFunction]] = new Reads[AuthData[HashFunction]] {
    def reads(json: JsValue): JsResult[AuthData[HashFunction]] = JsSuccess(AuthData[HashFunction](
      Base58.decode((json \ "data").as[String]).get,
      MerklePath(
        (json \ "index").as[Long],
        (json \ "merklePath").get match {
          case JsArray(ts) => ts.map { t =>
            t match {
              case JsString(digest) =>
                Base58.decode(digest)
              case m =>
                throw new RuntimeException("MerklePath MUST be array of strings" + m + " given")
            }
          }.map(_.get)
          case m =>
            throw new RuntimeException("MerklePath MUST be a list " + m + " given")
        })
    ))
  }

  implicit def authDataBlockWrites[T, HashFunction <: CryptographicHash](implicit fmt: Writes[T]): Writes[AuthData[HashFunction]]
  = new Writes[AuthData[HashFunction]] {
    def writes(ts: AuthData[HashFunction]) = JsObject(Seq(
      "data" -> JsString(Base58.encode(ts.data)),
      "index" -> JsNumber(ts.merklePath.index),
      "merklePath" -> JsArray(
        ts.merklePathHashes.map(digest => JsString(Base58.encode(digest)))
      )
    ))
  }
}