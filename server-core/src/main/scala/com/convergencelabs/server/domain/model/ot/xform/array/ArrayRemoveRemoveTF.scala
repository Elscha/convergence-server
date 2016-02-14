package com.convergencelabs.server.domain.model.ot

private[ot] object ArrayRemoveRemoveTF extends OperationTransformationFunction[ArrayRemoveOperation, ArrayRemoveOperation] {
  def transform(s: ArrayRemoveOperation, c: ArrayRemoveOperation): (ArrayRemoveOperation, ArrayRemoveOperation) = {
    if (s.index == c.index) {
      // A-RR-2
      (s.copy(noOp = true), c.copy(noOp = true))
    } else if (s.index < c.index) {
      // A-RR-1
      (s, c.copy(index = c.index - 1))
    } else {
      // A-RR-3
      (s.copy(index = s.index - 1), c)
    }
  }
}
