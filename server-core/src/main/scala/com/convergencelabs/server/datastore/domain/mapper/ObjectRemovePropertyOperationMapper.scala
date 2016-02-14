package com.convergencelabs.server.datastore.domain.mapper

import java.util.{ List => JavaList }
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.language.implicitConversions
import com.convergencelabs.server.domain.model.ot.ObjectRemovePropertyOperation
import com.convergencelabs.server.util.JValueMapper
import com.orientechnologies.orient.core.record.impl.ODocument
import org.json4s.JsonAST.JObject
import com.convergencelabs.server.datastore.mapper.ODocumentMapper

object ObjectRemovePropertyOperationMapper extends ODocumentMapper {

  private[domain] implicit class ObjectRemovePropertyOperationToODocument(val s: ObjectRemovePropertyOperation) extends AnyVal {
    def asODocument: ODocument = objectRemovePropertyOperationToODocument(s)
  }

  private[domain] implicit def objectRemovePropertyOperationToODocument(obj: ObjectRemovePropertyOperation): ODocument = {
    val ObjectRemovePropertyOperation(path, noOp, prop) = obj
    val doc = new ODocument(DocumentClassName)
    doc.field(Fields.Path, path.asJava)
    doc.field(Fields.NoOp, noOp)
    doc.field(Fields.Prop, prop)
    doc
  }

  private[domain] implicit class ODocumentToObjectRemovePropertyOperation(val d: ODocument) extends AnyVal {
    def asObjectRemovePropertyOperation: ObjectRemovePropertyOperation = oDocumentToObjectRemovePropertyOperation(d)
  }

  private[domain] implicit def oDocumentToObjectRemovePropertyOperation(doc: ODocument): ObjectRemovePropertyOperation = {
    validateDocumentClass(doc, DocumentClassName)

    val path = doc.field(Fields.Path).asInstanceOf[JavaList[_]]
    val noOp = doc.field(Fields.NoOp).asInstanceOf[Boolean]
    val prop = doc.field(Fields.Prop).asInstanceOf[String]
    ObjectRemovePropertyOperation(path.asScala.toList, noOp, prop)
  }

  private[domain] val DocumentClassName = "ObjectRemovePropertyOperation"

  private[domain] object Fields {
    val Path = "path"
    val NoOp = "noOp"
    val Prop = "prop"
    val Val = "val"
  }
}
