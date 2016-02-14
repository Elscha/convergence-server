package com.convergencelabs.server.domain.model.ot

class ArrayMoveMoveExhaustiveSpec extends ArrayOperationExhaustiveSpec[ArrayMoveOperation, ArrayMoveOperation] {

  val serverOperationType: String = "ArrayMoveOperation"
  val clientOperationType: String = "ArrayMoveOperation"

  def generateCases(): List[TransformationCase[ArrayMoveOperation, ArrayMoveOperation]] = {
    val ranges = generateMoveRanges()
    for { r1 <- ranges; r2 <- ranges } yield TransformationCase(
      ArrayMoveOperation(List(), false, r1.fromIndex, r1.toIndex),
      ArrayMoveOperation(List(), false, r2.fromIndex, r2.toIndex))
  }

  def transform(s: ArrayMoveOperation, c: ArrayMoveOperation): (DiscreteOperation, DiscreteOperation) = {
    ArrayMoveMoveTF.transform(s, c)
  }
}
