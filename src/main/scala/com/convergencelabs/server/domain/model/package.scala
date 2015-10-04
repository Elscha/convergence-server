package com.convergencelabs.server.domain

import scala.concurrent.duration.FiniteDuration
import com.convergencelabs.server.datastore.domain.ModelData
import com.convergencelabs.server.datastore.domain.SnapshotMetaData
import org.json4s.JsonAST.JValue
import com.convergencelabs.server.domain.model.ot.ops.Operation
import akka.actor.ActorRef

package model {

  // Config
  case class SnapshotConfig(
      triggerByVersion: Boolean,
      minimumVersionInterval: Long,
      maximumVersionInterval: Long,
      triggerByTime: Boolean,
      minimumTimeInterval: Long,
      maximumTimeInterval: Long) {

    def snapshotRequired(
      previousVersion: Long,
      currentVersion: Long,
      previousTime: Long,
      currentTime: Long): Boolean = {

      val versionInterval = currentVersion - previousVersion
      val allowedByVersion = versionInterval >= minimumVersionInterval
      val requiredByVersion = versionInterval > maximumVersionInterval && triggerByVersion

      val timeInterval = currentTime - previousTime
      val allowedByTime = timeInterval >= minimumTimeInterval
      val requiredByTime = timeInterval > maximumTimeInterval && triggerByTime

      allowedByVersion && allowedByVersion && (requiredByTime || requiredByVersion)
    }
  }

  //
  // Data Classes
  //
  case class ModelFqn(collectionId: String, modelId: String)

  //
  // Incoming Messages From Client
  //
  case class OpenRequestRecord(clientActor: ActorRef, askingActor: ActorRef)

  case class OpenRealtimeModelRequest(modelFqn: ModelFqn, clientActor: ActorRef)

  case class CreateModelRequest(modelFqn: ModelFqn, modelData: JValue)

  case class DeleteModelRequest(modelFqn: ModelFqn)

  case class CloseRealtimeModelRequest(clientId: String)

  case class OperationSubmission(clientId: String, contextVersion: Long, operation: Operation)

  case class ClientModelDataResponse(modelData: JValue)

  //
  // Incoming Messages From Domain
  //
  case class ModelDeleted()

  //
  // Incoming Messages From Self
  //
  case class DatabaseModelResponse(modelData: ModelData, snapshotMetaData: SnapshotMetaData)
  case class DatabaseModelFailure(cause: Throwable)

  //
  // Outgoing Messages
  //
  case class OpenModelResponse(realtimeModelActor: ActorRef, modelResourceId: String, ccId: String, metaData: OpenModelMetaData, modelData: JValue)

  case class OpenModelMetaData(version: Long, createdTime: Long, modifiedTime: Long)

  case class ClientModelDataRequest(modelFqn: ModelFqn)

  case class ModelShutdownRequest(modelFqn: ModelFqn)

  case class OperationAcknowledgement(modelFqn: ModelFqn, modelSessionId: String, contextVersion: Long)

  case class OutgoingOperation(resourceId: String, modelSessionId: String, contextVersion: Long, timestampe: Long, operation: Operation)

  case class RemoteSessionClosed(modelFqn: ModelFqn, modelSessionId: String)

  case class CloseRealtimeModelSuccess()

  case class ModelForceClose(modelFqn: ModelFqn, modelSessionId: String, reason: String)
}