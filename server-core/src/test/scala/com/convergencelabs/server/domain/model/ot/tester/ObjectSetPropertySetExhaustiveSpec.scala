package com.convergencelabs.server.domain.model.ot

import org.json4s.JsonDSL.int2jvalue

import ObjectOperationExhaustiveSpec.NewProperties
import ObjectOperationExhaustiveSpec.NewValues
import ObjectOperationExhaustiveSpec.SetObjects

class ObjectSetPropertySetExhaustiveSpec extends ObjectOperationExhaustiveSpec[ObjectSetPropertyOperation, ObjectSetOperation] {

  val serverOperationType: String = "ObjectSetPropertyOperation"
  val clientOperationType: String = "ObjectSetOperation"

  def generateCases(): List[TransformationCase[ObjectSetPropertyOperation, ObjectSetOperation]] = {
    for {
      prop1 <- NewProperties
      value1 <- NewValues
      newObject <- SetObjects
    } yield TransformationCase(
      ObjectSetPropertyOperation(List(), false, prop1, value1),
      ObjectSetOperation(List(), false, newObject))
  }

  def transform(s: ObjectSetPropertyOperation, c: ObjectSetOperation): (DiscreteOperation, DiscreteOperation) = {
    ObjectSetPropertySetTF.transform(s, c)
  }
}
