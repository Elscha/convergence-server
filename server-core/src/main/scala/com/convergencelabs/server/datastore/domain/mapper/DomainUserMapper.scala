package com.convergencelabs.server.datastore.domain.mapper

import com.orientechnologies.orient.core.record.impl.ODocument
import com.convergencelabs.server.domain.DomainUser
import scala.language.implicitConversions
import com.convergencelabs.server.datastore.mapper.ODocumentMapper

object DomainUserMapper extends ODocumentMapper {

  private[domain] implicit class DomainUserToODocument(val u: DomainUser) extends AnyVal {
    def asODocument: ODocument = domainUserToODocument(u)
  }

  private[domain] implicit def domainUserToODocument(obj: DomainUser): ODocument = {
    val doc = new ODocument(DocumentClassName)
    doc.field(Fields.Uid, obj.uid)
    doc.field(Fields.Username, obj.username)
    doc.field(Fields.FirstName, someOrNull(obj.firstName))
    doc.field(Fields.LastName, someOrNull(obj.lastName))
    doc.field(Fields.Email, someOrNull(obj.email))
    doc
  }

  private[domain] implicit class ODocumentToDomainUser(val d: ODocument) extends AnyVal {
    def asDomainUser: DomainUser = oDocumentToDomainUser(d)
  }

  private[domain] implicit def oDocumentToDomainUser(doc: ODocument): DomainUser = {
    validateDocumentClass(doc, DocumentClassName)

    DomainUser(
      doc.field(Fields.Uid),
      doc.field(Fields.Username),
      toOption(doc.field(Fields.FirstName)),
      toOption(doc.field(Fields.LastName)),
      toOption(doc.field(Fields.Email)))
  }

  private[domain] val DocumentClassName = "User"

  private[domain] object Fields {
    val Uid = "uid"
    val Username = "username"
    val FirstName = "firstName"
    val LastName = "lastName"
    val Email = "email"
  }
}
