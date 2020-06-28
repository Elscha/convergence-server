/*
 * Copyright (c) 2019 - Convergence Labs, Inc.
 *
 * This file is part of the Convergence Server, which is released under
 * the terms of the GNU General Public License version 3 (GPLv3). A copy
 * of the GPLv3 should have been provided along with this file, typically
 * located in the "LICENSE" file, which is part of this source code package.
 * Alternatively, see <https://www.gnu.org/licenses/gpl-3.0.html> for the
 * full text of the GPLv3 license, if it was not provided.
 */

package com.convergencelabs.convergence.server.domain.model.reference

import com.convergencelabs.convergence.server.domain.DomainUserSessionId
import com.convergencelabs.convergence.server.domain.model.ModelReferenceValues

/**
 *
 * @param target  The target of this reference, which is the object the
 *                reference is relative to.
 * @param session The session the created the reference.
 * @param key     The unique (within the target and session) key for
 *                this reference.
 * @tparam V The type of value the reference holds
 * @tparam T The type of object the reference targets.
 */
abstract class ModelReference[V, T](val target: T,
                                    val session: DomainUserSessionId,
                                    val key: String) {

  protected var values: List[V] = List[V]()

  def clear(): Unit = {
    this.values = List[V]()
  }

  def set(values: List[V]): Unit = {
    this.values = values
  }

  def get(): List[V] = {
    this.values
  }

  def isSet: Boolean = {
    this.get().nonEmpty
  }

  def handleModelValueSet(): Unit = {
    clear()
  }

  def toReferenceValues(): ModelReferenceValues
}
