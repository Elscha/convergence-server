package com.convergencelabs.server.datastore.domain.mapper

import org.scalatest.Matchers
import org.scalatest.WordSpec

import com.convergencelabs.server.domain.model.ot.ArrayRemoveOperation
import com.orientechnologies.orient.core.record.impl.ODocument

import ArrayRemoveOperationMapper.ArrayRemoveOperationToODocument
import ArrayRemoveOperationMapper.ODocumentToArrayRemoveOperation

class ArrayRemoveOperationMapperSpec
    extends WordSpec
    with Matchers {

  val path = List(3, "foo", 4) // scalastyle:off magic.number

  "An ArrayRemoveOperationMapper" when {
    "when converting ArrayRemoveOperation operations" must {
      "correctly map and unmap a ArrayRemoveOperation" in {
        val op = ArrayRemoveOperation(path, true, 4)
        val opDoc = op.asODocument
        val reverted = opDoc.asArrayRemoveOperation
        op shouldBe reverted
      }

      "not allow an invalid document class name" in {
        val invalid = new ODocument("SomeClass")
        intercept[IllegalArgumentException] {
          invalid.asArrayRemoveOperation
        }
      }
    }
  }
}
