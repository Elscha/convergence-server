package com.convergencelabs.server.domain.model.ot

import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JString

class ArraySetMoveExhaustiveSpec extends ArrayOperationExhaustiveSpec[ArraySetOperation, ArrayMoveOperation] {

  val serverOperationType: String = "ArraySetOperation"
  val clientOperationType: String = "ArrayMoveOperation"

  def generateCases(): List[TransformationCase[ArraySetOperation, ArrayMoveOperation]] = {
    for { r <- generateMoveRanges() } yield TransformationCase(
      ArraySetOperation(List(), false, JArray(List(JString("X")))),
      ArrayMoveOperation(List(), false, r.fromIndex, r.toIndex))
  }

  def transform(s: ArraySetOperation, c: ArrayMoveOperation): (DiscreteOperation, DiscreteOperation) = {
    ArraySetMoveTF.transform(s, c)
  }
}
