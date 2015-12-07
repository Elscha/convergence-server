package com.convergencelabs.server.datastore.domain

import java.util.{ List => JavaList }
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.util.Try
import org.json4s.NoTypeHints
import org.json4s.jackson.Serialization
import com.convergencelabs.server.datastore.AbstractDatabasePersistence
import com.convergencelabs.server.datastore.QueryUtil
import com.convergencelabs.server.datastore.SortOrder
import com.convergencelabs.server.datastore.domain.mapper.DomainUserMapper.DomainUserToODocument
import com.convergencelabs.server.datastore.domain.mapper.DomainUserMapper.ODocumentToDomainUser
import com.lambdaworks.crypto.SCryptUtil
import com.orientechnologies.orient.core.db.OPartitionedDatabasePool
import com.orientechnologies.orient.core.metadata.schema.OType
import com.orientechnologies.orient.core.record.impl.ODocument
import com.orientechnologies.orient.core.sql.OCommandSQL
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery
import grizzled.slf4j.Logging
import com.convergencelabs.server.domain.DomainUser

/**
 * Manages the persistence of Domain Users.  This class manages both user profile records
 * as well as user credentials for users authenticated by Convergence itself.
 *
 * @constructor Creates a new DomainUserStore using the provided connection pool to
 * connect to the database
 *
 * @param dbPool The database pool to use.
 */
class DomainUserStore private[domain] (private[this] val dbPool: OPartitionedDatabasePool)
    extends AbstractDatabasePersistence(dbPool)
    with Logging {

  private[this] implicit val formats = Serialization.formats(NoTypeHints)

  /**
   * Creates a new domain user in the system, and optionally set a password.
   * Note the uid as well as the username of the user must be unique among all
   * users in this domain.
   *
   * @param domainUser The user to add to the system.
   *
   * @param password An optional password if internal authentication is to
   * be used for this user.
   * 
   * @return A String representing the created users uid.
   */
  def createDomainUser(domainUser: DomainUser, password: Option[String]): Try[String] = tryWithDb { db =>
    val userDoc = domainUser.asODocument
    db.save(userDoc)
    userDoc.reload()

    val pwDoc = db.newInstance("UserCredential")
    pwDoc.field("user", userDoc, OType.LINK) // FIXME verify this creates a link and now a new doc.

    val hash = password match {
      case Some(pass) => PasswordUtil.hashPassword(pass)
      case None => null
    }

    pwDoc.field("password", hash)
    db.save(pwDoc)
    
    val uid: String = userDoc.field("uid", OType.STRING)
    uid
  }

  /**
   * Deletes a single domain user by uid.
   *
   * @param the uid of the user to delete.
   */
  def deleteDomainUser(uid: String): Try[Unit] = tryWithDb { db =>
    val command = new OCommandSQL("DELETE FROM User WHERE uid = :uid")
    val params = Map("uid" -> uid)
    db.command(command).execute(params.asJava)
    Unit
  }

  /**
   * Updates a DomainUser with new information.  The uid of the domain user argument must
   * correspond to an existing user in the database.
   *
   * @param domainUser The user to update.
   */
  def updateDomainUser(domainUser: DomainUser): Try[Unit] = tryWithDb { db =>
    val updatedDoc = domainUser.asODocument

    val query = new OSQLSynchQuery[ODocument]("SELECT FROM User WHERE uid = :uid")
    val params = Map("uid" -> domainUser.uid)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)

    result.asScala.toList match {
      case doc :: Nil => {
        doc.merge(updatedDoc, false, false)
        db.save(doc)
      }
      case _ => ??? // FIXME
    }
    Unit
  }

  /**
   * Gets a single domain user by uid.
   *
   * @param uid The uid of the user to retrieve.
   *
   * @return Some(DomainUser) if a user with the specified uid exists, or None if no such user exists.
   */
  def getDomainUserByUid(uid: String): Try[Option[DomainUser]] = tryWithDb { db =>
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM User WHERE uid = :uid")
    val params = Map("uid" -> uid)
    val results: JavaList[ODocument] = db.command(query).execute(params.asJava)
    QueryUtil.mapSingletonList(results) { _.asDomainUser }
  }

  /**
   * Gets a list of domain users matching any of a list of uids.
   *
   * @param uids The list of uids of the users to retrieve.
   *
   * @return A list of users matching the list of supplied uids.
   */
  def getDomainUsersByUids(uids: List[String]): Try[List[DomainUser]] = tryWithDb { db =>
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM User WHERE uid in :uids")
    val params = Map("uids" -> uids)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)
    result.asScala.toList.map { _.asDomainUser }
  }

  /**
   * Gets a single domain user by usernam.
   *
   * @param username The uid of the user to retrieve.
   *
   * @return Some(DomainUser) if a user with the specified username exists, or None if no such user exists.
   */
  def getDomainUserByUsername(username: String): Try[Option[DomainUser]] = tryWithDb { db =>
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM User WHERE username = :username")
    val params = Map("username" -> username)
    val results: JavaList[ODocument] = db.command(query).execute(params.asJava)
    QueryUtil.mapSingletonList(results) { _.asDomainUser }
  }

  /**
   * Gets a list of domain users matching any of a list of usernames.
   *
   * @param uids The list of usernames of the users to retrieve.
   *
   * @return A list of users matching the list of supplied usernames.
   */
  def getDomainUsersByUsername(usernames: List[String]): Try[List[DomainUser]] = tryWithDb { db =>
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM User WHERE username in :usernames")
    val params = Map("usernames" -> usernames)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)
    result.asScala.toList.map { _.asDomainUser }
  }

  /**
   * Gets a single domain user by email.
   *
   * @param email The email of the user to retrieve.
   *
   * @return Some(DomainUser) if a user with the specified email exists, or None if no such user exists.
   */
  def getDomainUserByEmail(email: String): Try[Option[DomainUser]] = tryWithDb { db =>
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM User WHERE email = :email")
    val params = Map("email" -> email)
    val results: JavaList[ODocument] = db.command(query).execute(params.asJava)
    QueryUtil.mapSingletonList(results) { _.asDomainUser }
  }

  /**
   * Gets a list of domain users matching any of a list of emails.
   *
   * @param uids The list of emails of the users to retrieve.
   *
   * @return A list of users matching the list of supplied emails.
   */
  def getDomainUsersByEmail(emails: List[String]): Try[List[DomainUser]] = tryWithDb { db =>
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM User WHERE email in :emails")
    val params = Map("emails" -> emails)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)
    result.asScala.toList.map { doc => doc.asDomainUser }
  }

  /**
   * Checks to see if a given username exists in the system.
   *
   * @param username The username to check existence for.
   *
   * @return true if the user exists, false otherwise.
   */
  def domainUserExists(username: String): Try[Boolean] = tryWithDb { db =>
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM User WHERE username = :username")
    val params = Map("username" -> username)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)
    result.asScala.toList match {
      case doc :: Nil => true
      case _ => false
    }
  }

  /**
   * Gets a listing of all domain users based on ordering and paging.
   *
   * @param orderBy The property of the domain user to order by. Defaults to username.
   * @param sortOrder The order (ascending or descending) of the ordering. Defaults to descending.
   * @param limit maximum number of users to return.  Defaults to unlimited.
   * @param offset The offset into the ordering to start returning entries.  Defaults to 0.
   */
  def getAllDomainUsers(orderBy: Option[DomainUserOrder.Order], sortOrder: Option[SortOrder.Value], limit: Option[Int], offset: Option[Int]): Try[List[DomainUser]] = tryWithDb { db =>
    val order = orderBy.getOrElse(DomainUserOrder.Username)
    val sort = sortOrder.getOrElse(SortOrder.Descending)
    val baseQuery = s"SELECT * FROM User ORDER BY $order $sort"
    val query = new OSQLSynchQuery[ODocument](QueryUtil.buildPagedQuery(baseQuery, limit, offset))
    val result: JavaList[ODocument] = db.command(query).execute()

    result.asScala.toList.map { doc => doc.asDomainUser }
  }

  /**
   * Set the password for an existing user by username.
   *
   * @param username The unique username of the user.
   * @param password The new password to use for internal authentication
   */
  def setDomainUserPassword(username: String, password: String): Try[Unit] = tryWithDb { db =>
    val query = new OSQLSynchQuery[ODocument]("SELECT * FROM UserCredential WHERE user.username = :username")
    val params = Map("username" -> username)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)

    result.asScala.toList match {
      case doc :: Nil => {
        doc.field("password", SCryptUtil.scrypt(password, 16384, 8, 1))
        db.save(doc)
      }
      case _ => ??? // FIXME We did not find a user to update.
    }
    Unit
  }

  /**
   * Validated that the username and password combination are valid.
   *
   * @param username The username of the user to check the password for.
   * @param password The cleartext password of the user
   *
   * @return true if the username and passowrd match, false otherwise.
   */
  def validateCredentials(username: String, password: String): Try[Tuple2[Boolean, Option[String]]] = tryWithDb { db =>
    val query = new OSQLSynchQuery[ODocument]("SELECT password, user.uid AS uid FROM UserCredential WHERE user.username = :username")
    val params = Map("username" -> username)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)

    result.asScala.toList match {
      case doc :: Nil => {
        val pwhash: String = doc.field("password")
        PasswordUtil.checkPassword(password, pwhash) match {
          case true => {
            val uid: String = doc.field("uid")
            (true, Some(uid))
          }
          case false => (false, None)
        }
      }
      case _ => (false, None)
    }
  }
}

object DomainUserOrder extends Enumeration {
  type Order = Value
  val Username = Value("username")
  val FirstName = Value("firstName")
  val LastName = Value("lastName")
}