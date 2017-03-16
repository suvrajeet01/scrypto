package scorex.crypto.authds.avltree.batch

import scorex.crypto.authds.TwoPartyDictionary.Label
import scorex.crypto.authds.legacy.avltree.{AVLModifyProof, AVLTree}

import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}

sealed trait BatchProvingResultSimple

case class BatchSuccessSimple(proofs: Seq[AVLModifyProof]) extends BatchProvingResultSimple


trait BatchProof

sealed trait BatchProvingResult

case class BatchSuccess(proof: BatchProof) extends BatchProvingResult

case class BatchFailure(error: Throwable, reason: Operation)
  extends Exception with BatchProvingResultSimple with BatchProvingResult

class LegacyProver(tree: AVLTree[_]) {
  def applyBatchSimple(modifications: Seq[Operation]): BatchProvingResultSimple = {
    applyUpdates(modifications)
  }

  def applyUpdates(modifications: Seq[Operation]): BatchProvingResultSimple = Try {
    val aggregatedProofs = modifications.foldLeft(ArrayBuffer[AVLModifyProof]()) { (a, m) =>
      tree.modify(m) match {
        case Success(proof) => proof +: a
        case Failure(e) => throw BatchFailure(e, m)
      }
    }
    BatchSuccessSimple(aggregatedProofs)
  } match {
    case Success(p) => p
    case Failure(e: BatchFailure) => e
  }

  def rootHash: Label = tree.rootHash()
}


class LegacyVerifier(digest: Label) {
  def verifyBatchSimple(modifications: Seq[Operation], batch: BatchSuccessSimple): Boolean = {
    require(modifications.size == batch.proofs.size)
    batch.proofs.zip(modifications).foldLeft(Some(digest): Option[Label]) {
      case (digestOpt, (proof, mod)) =>
        digestOpt.flatMap(digest => proof.verify(digest, mod.updateFn))
    }.isDefined
  }

  def verifyBatchComprehensive(modifications: Seq[Operation], batch: BatchSuccess): Boolean = ???
}
