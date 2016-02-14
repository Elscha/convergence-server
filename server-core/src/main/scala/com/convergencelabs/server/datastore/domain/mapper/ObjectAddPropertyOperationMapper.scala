package com.convergencelabs.server.datastore.domain.mapper

import java.util.{ List => JavaList }
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.language.implicitConversions
import com.convergencelabs.server.domain.model.ot.ObjectAddPropertyOperation
import com.convergencelabs.server.util.JValueMapper
import com.orientechnologies.orient.core.record.impl.ODocument
import org.json4s.JsonAST.JObject
import com.convergencelabs.server.datastore.mapper.ODocumentMapper

object ObjectAddPropertyOperationMapper extends ODocumentMapper {

  private[domain] implicit class ObjectAddPropertyOperationToODocument(val s: ObjectAddPropertyOperation) extends AnyVal {
    def asODocument: ODocument = objectAddPropertyOperationToODocument(s)
  }

  private[domain] implicit def objectAddPropertyOperationToODocument(obj: ObjectAddPropertyOperation): ODocument = {
    val ObjectAddPropertyOperation(path, noOp, prop, value) = obj
    val doc = new ODocument(DocumentClassName)
    doc.field(Fields.Path, path.asJava)
    doc.field(Fields.NoOp, noOp)
    doc.field(Fields.Prop, prop)
    doc.field(Fields.Val, JValueMapper.jValueToJava(value))
    doc
  }

  private[domain] implicit class ODocumentToObjectAddPropertyOperation(val d: ODocument) extends AnyVal {
    def asObjectAddPropertyOperation: ObjectAddPropertyOperation = oDocumentToObjectAddPropertyOperation(d)
  }

  private[domain] implicit def oDocumentToObjectAddPropertyOperation(doc: ODocument): ObjectAddPropertyOperation = {
    validateDocumentClass(doc, DocumentClassName)

    val path = doc.field(Fields.Path).asInstanceOf[JavaList[_]]
    val noOp = doc.field(Fields.NoOp).asInstanceOf[Boolean]
    val prop = doc.field(Fields.Prop).asInstanceOf[String]
    val value = JValueMapper.javaToJValue(doc.field(Fields.Val))
    ObjectAddPropertyOperation(path.asScala.toList, noOp, prop, value)
  }

  private[domain] val DocumentClassName = "ObjectAddPropertyOperation"

  private[domain] object Fields {
    val Path = "path"
    val NoOp = "noOp"
    val Prop = "prop"
    val Val = "val"
  }
}
