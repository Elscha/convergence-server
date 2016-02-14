package com.convergencelabs.server.domain.model.ot

import org.json4s.JString

class ArrayRemoveReplaceExhaustiveSpec extends ArrayOperationExhaustiveSpec[ArrayRemoveOperation, ArrayReplaceOperation] {

  val serverOperationType: String = "ArrayRemoveOperation"
  val clientOperationType: String = "ArrayReplaceOperation"

  def generateCases(): List[TransformationCase[ArrayRemoveOperation, ArrayReplaceOperation]] = {
    val indices = generateIndices()
    for { i1 <- indices; i2 <- indices } yield TransformationCase(
      ArrayRemoveOperation(List(), false, i1),
      ArrayReplaceOperation(List(), false, i2, JString("X")))
  }

  def transform(s: ArrayRemoveOperation, c: ArrayReplaceOperation): (DiscreteOperation, DiscreteOperation) = {
    ArrayRemoveReplaceTF.transform(s, c)
  }
}
