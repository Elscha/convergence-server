package com.convergencelabs.server.frontend.realtime.proto

import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JNumber
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JValue

import com.convergencelabs.server.domain.model.ModelFqn
import com.convergencelabs.server.domain.model.OpenMetaData

// Main class
sealed trait ProtocolMessage

sealed trait IncomingProtocolMessage extends ProtocolMessage
sealed trait IncomingProtocolNormalMessage extends IncomingProtocolMessage
sealed trait IncomingProtocolRequestMessage extends IncomingProtocolMessage
sealed trait IncomingProtocolResponseMessage extends IncomingProtocolMessage


sealed trait OutgoingProtocolMessage extends ProtocolMessage

sealed trait OutgoingProtocolNormalMessage extends ProtocolMessage
sealed trait OutgoingProtocolResponseMessage extends ProtocolMessage



// Client Messages
case class HandshakeRequestMessage(reconnect: scala.Boolean, reconnectToken: Option[String], options: Option[ProtocolOptionsData]) extends IncomingProtocolMessage
case class HandshakeResponseMessage(success: scala.Boolean, error: Option[ErrorData], sessionId: Option[String], reconnectToken: Option[String]) extends OutgoingProtocolMessage

case class ProtocolOptionsData()
case class ErrorData(code: String, message: String)


// Model Messages
sealed trait IncomingModelMessage extends IncomingProtocolNormalMessage
case class OperationSubmissionMessage(resourceId: String, modelSessionId: String, operation: OperationData) extends IncomingModelMessage

sealed trait IncomingModelRequestMessage extends IncomingProtocolRequestMessage
case class OpenRealtimeModelRequestMessage(modelFqn: ModelFqn) extends IncomingModelRequestMessage
case class CloseRealtimeModelRequestMessage(resourceId: String) extends IncomingModelRequestMessage

// Outgoing Model Messages
case class OpenRealtimeModelResponseMessage(resourceId: String, modelSessionId: String, metaData: OpenMetaData, modelData: JValue) extends OutgoingProtocolResponseMessage
case class CloseRealtimeModelResponseMessage(resourceId: String) extends OutgoingProtocolResponseMessage

case class RemoteOperationMessage(resourceId: String, modelSessionId: String, operation: OperationData) extends OutgoingProtocolNormalMessage



//
// Operations
//
sealed trait OperationData

case class CompoundOperationData(ops: List[DiscreteOperationData]) extends OperationData

sealed trait DiscreteOperationData extends OperationData {
  def path: List[Any]
  def noOp: Boolean
}

sealed trait StringOperaitonData extends DiscreteOperationData
case class StringInsertOperationData(path: List[Any], noOp: Boolean, idx: Int, `val`: String) extends StringOperaitonData
case class StringRemoveOperationData(path: List[Any], noOp: Boolean, idx: Int, `val`: String) extends StringOperaitonData
case class SetStringOperationData(path: List[Any], noOp: Boolean, `val`: String) extends StringOperaitonData

sealed trait ArrayOperaitonData extends DiscreteOperationData
case class ArrayInsertOperationData(path: List[Any], noOp: Boolean, idx: Int, newVal: JValue) extends ArrayOperaitonData
case class ArrayRemoveOperationData(path: List[Any], noOp: Boolean, idx: Int, oldVal: JValue) extends ArrayOperaitonData
case class ArrayReplaceOperationData(path: List[Any], noOp: Boolean, idx: Int, oldVal: JValue, newVal: JValue) extends ArrayOperaitonData
case class ArrayMoveOperationData(path: List[Any], noOp: Boolean, fromIdx: Int, toIdx: Int) extends ArrayOperaitonData
case class SetArrayOperationData(path: List[Any], noOp: Boolean, array: JArray) extends ArrayOperaitonData

sealed trait ObjectOperaitonData extends DiscreteOperationData
case class ObjectAddPropertyOperationData(path: List[Any], noOp: Boolean, prop: String, newVal: JValue) extends ObjectOperaitonData
case class ObjectSetPropertyOperationData(path: List[Any], noOp: Boolean, prop: String, newVal: JValue, oldVal: JValue) extends ObjectOperaitonData
case class ObjectRemovePropertyOperationData(path: List[Any], noOp: Boolean, prop: String, oldVal: JValue) extends ObjectOperaitonData
case class SetObjectOperationData(path: List[Any], noOp: Boolean, obj: JObject) extends ObjectOperaitonData

sealed trait NumberOperaitonData extends DiscreteOperationData
case class NumberAddOperationData(path: List[Any], noOp: Boolean, delta: JNumber) extends NumberOperaitonData
case class SetNumberOperationData(path: List[Any], noOp: Boolean, num: JNumber) extends NumberOperaitonData

