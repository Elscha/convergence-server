package com.convergencelabs.server.datastore

import java.time.Duration
import java.util.{ List => JavaList }
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.immutable.HashMap
import com.convergencelabs.server.domain.DomainFqn
import com.convergencelabs.server.domain.model.SnapshotConfig
import com.orientechnologies.orient.core.db.OPartitionedDatabasePool
import com.orientechnologies.orient.core.db.record.OTrackedMap
import com.orientechnologies.orient.core.metadata.schema.OType
import com.orientechnologies.orient.core.record.impl.ODocument
import com.orientechnologies.orient.core.sql.OCommandSQL
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery
import grizzled.slf4j.Logging
import java.util.ArrayList
import com.convergencelabs.server.datastore.mapper.DomainConfigMapper._

object DomainConfigurationStore extends Logging {

  val Domain = "Domain"

  val Id = "id"
  val Namespace = "namespace"
  val DomainId = "domainId"
  val DisplayName = "displayName"

  val DBUsername = "dbUsername"
  val DBPassword = "dbPassword"

  val Keys = "keys"
  val KeyId = "id"
  val KeyName = "name"
  val KeyDescription = "description"
  val KeyDate = "keyDate"
  val Key = "key"
  val KeyEnabled = "enabled"

  val AdminKeyPair = "adminUIKeyPair"
  val PrivateKey = "privateKey"
  val PublicKey = "publicKey"

  val SnapshotConfigField = "snapshotConfig"

  def listToKeysMap(doc: JavaList[OTrackedMap[Any]]): Map[String, TokenPublicKey] = {
    val keys = new HashMap[String, TokenPublicKey]
    doc.foreach { docKey =>
      keys + docKey.get(KeyId).asInstanceOf[String] -> mapToTokenPublicKey(docKey.asInstanceOf[OTrackedMap[Any]])
    }
    keys
  }

  def mapToTokenPublicKey(doc: OTrackedMap[Any]): TokenPublicKey = {
    TokenPublicKey(
      doc.get(KeyId).asInstanceOf[String],
      doc.get(KeyName).asInstanceOf[String],
      doc.get(KeyDescription).asInstanceOf[String],
      doc.get(KeyDate).asInstanceOf[Long],
      doc.get(Key).asInstanceOf[String],
      doc.get(KeyEnabled).asInstanceOf[Boolean])
  }
}

class DomainConfigurationStore(dbPool: OPartitionedDatabasePool) extends Logging {

  def createDomainConfig(domainConfig: DomainConfig) = {
    val db = dbPool.acquire()
    db.save(domainConfig)
    db.close()
  }

  def domainExists(domainFqn: DomainFqn): Boolean = {
    val db = dbPool.acquire()
    val queryString =
      """SELECT id 
        |FROM Domain 
        |WHERE 
        |  namespace = :namespace AND 
        |  domainId = :domainId""".stripMargin

    val query = new OSQLSynchQuery[ODocument](queryString)
    val params = Map("namespace" -> domainFqn.namespace, "domainId" -> domainFqn.domainId)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)
    db.close()

    result.asScala.toList match {
      case first :: Nil  => true
      case first :: rest => false // FIXME log
      case _             => false
    }
  }

  def getDomainConfigByFqn(domainFqn: DomainFqn): Option[DomainConfig] = {
    val db = dbPool.acquire()
    try {
      val queryString = "SELECT FROM Domain WHERE namespace = :namespace AND domainId = :domainId"
      val query = new OSQLSynchQuery[ODocument](queryString)

      val params = Map(
        "namespace" -> domainFqn.namespace,
        "domainId" -> domainFqn.domainId)

      val result: JavaList[ODocument] = db.command(query).execute(params.asJava)

      QueryUtil.mapSingleResult(result) { doc => doc.asDomainConfig }

    } finally {
      db.close()
    }
  }

  def getDomainConfigById(id: String): Option[DomainConfig] = {
    val db = dbPool.acquire()
    try {
      val query = new OSQLSynchQuery[ODocument]("SELECT * FROM Domain WHERE id = :id")
      val params = Map("id" -> id)
      val result: JavaList[ODocument] = db.command(query).execute(params.asJava)

      QueryUtil.mapSingleResult(result) { doc => doc.asDomainConfig }
    } finally {
      db.close()
    }
  }

  def getDomainConfigsInNamespace(namespace: String): List[DomainConfig] = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM Domain WHERE namespace = :namespace")
    val params = Map("namespace" -> namespace)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)
    db.close()
    result.asScala.toList map { doc => doc.asDomainConfig }
  }

  def removeDomainConfig(id: String): Unit = {
    val db = dbPool.acquire()
    val command = new OCommandSQL("DELETE FROM Domain WHERE id = :id")
    val params = Map("id" -> id)
    db.command(command).execute(params.asJava)
    db.close()
  }

  def updateDomainConfig(newConfig: DomainConfig): Unit = {
    val db = dbPool.acquire()

    val query = new OSQLSynchQuery[ODocument]("SELECT FROM Domain WHERE id = :id")
    val params = Map("id" -> newConfig.id)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)

    result.asScala.toList match {
      case first :: rest => {
        first.merge(newConfig, false, false)
        db.save(first)
      }
      case Nil =>
    }
  }

  def getDomainKey(domainFqn: DomainFqn, keyId: String): Option[TokenPublicKey] = {
    val db = dbPool.acquire()
    val queryString =
      "SELECT keys[id = :keyId].asList() FROM Domain WHERE namespace = :namespace AND domainId = :domainId"
    val query = new OSQLSynchQuery[ODocument](queryString)
    val params = Map("namespace" -> domainFqn.namespace, "domainId" -> domainFqn.domainId, "keyId" -> keyId)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)

    db.close()

    QueryUtil.flatMapSingleResult(result) { doc =>
      val keysList: java.util.List[OTrackedMap[Any]] = doc.field("keys", OType.EMBEDDEDLIST)
      QueryUtil.mapSingleResult(keysList) { key =>
        DomainConfigurationStore.mapToTokenPublicKey(key)
      }
    }
  }

  def getDomainKeys(domainFqn: DomainFqn): Option[Map[String, TokenPublicKey]] = {
    val db = dbPool.acquire()
    val sql = "SELECT keys FROM Domain WHERE namespace = :namespace and domainId = :domainId"
    val query = new OSQLSynchQuery[ODocument](sql)
    val params = Map("namespace" -> domainFqn.namespace, "domainId" -> domainFqn.domainId)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)
    db.close()

    QueryUtil.mapSingleResult(result) { doc =>
      DomainConfigurationStore.listToKeysMap(doc.field(DomainConfigurationStore.Keys, OType.EMBEDDEDLIST))
    }
  }
}