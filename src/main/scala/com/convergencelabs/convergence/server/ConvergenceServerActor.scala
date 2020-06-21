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

package com.convergencelabs.convergence.server

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.typed._
import akka.util.Timeout
import com.convergencelabs.convergence.common.Ok
import com.convergencelabs.convergence.server.ConvergenceServerActor.Message
import com.convergencelabs.convergence.server.api.realtime.{ClientActorCreator, ConvergenceRealtimeApi}
import com.convergencelabs.convergence.server.api.rest.ConvergenceRestApi
import com.convergencelabs.convergence.server.datastore.convergence.DomainStore
import com.convergencelabs.convergence.server.datastore.domain.DomainPersistenceManagerActor
import com.convergencelabs.convergence.server.db.provision.DomainLifecycleTopic
import com.convergencelabs.convergence.server.db.{ConvergenceDatabaseInitializerActor, PooledDatabaseProvider}
import com.convergencelabs.convergence.server.domain.activity.{ActivityActor, ActivityActorSharding}
import com.convergencelabs.convergence.server.domain.chat.{ChatActor, ChatActorSharding, ChatDeliveryActor, ChatDeliveryActorSharding}
import com.convergencelabs.convergence.server.domain.model.{RealtimeModelActor, RealtimeModelSharding}
import com.convergencelabs.convergence.server.domain.rest.{DomainRestActor, DomainRestActorSharding}
import com.convergencelabs.convergence.server.domain.{DomainActor, DomainActorSharding}
import com.orientechnologies.orient.core.db.{OrientDB, OrientDBConfig}
import com.typesafe.config.ConfigRenderOptions
import grizzled.slf4j.Logging

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

/**
 * This is the main ConvergenceServer class. It is responsible for starting
 * up all services including the Akka Actor System.
 *
 * @param context The configuration to use for the server.
 */
class ConvergenceServerActor(private[this] val context: ActorContext[Message])
  extends AbstractBehavior[Message](context)
    with Logging {

  import ConvergenceServerActor._

  private[this] val config = context.system.settings.config

  private[this] var cluster: Option[Cluster] = None
  private[this] var backend: Option[BackendServices] = None
  private[this] var orientDb: Option[OrientDB] = None
  private[this] var rest: Option[ConvergenceRestApi] = None
  private[this] var realtime: Option[ConvergenceRealtimeApi] = None
  private[this] var clusterListener: Option[ActorRef[MemberEvent]] = None

  override def onMessage(msg: Message): Behavior[Message] = {
    msg match {
      case msg: StartRequest =>
        start(msg)
      case msg: StopRequest =>
        stop(msg)
      case msg: StartBackendServices =>
        startBackend(msg)
      case msg: BackendInitializationFailure =>
        onBackendFailure(msg)
    }
  }

  /**
   * Starts the Convergence Server and returns itself, supporting
   * a fluent API.
   *
   * @return This instance of the ConvergenceServer
   */
  private[this] def start(msg: StartRequest): Behavior[Message] = {
    info(s"Convergence Server (${BuildInfo.version}) starting up...")

    debug(s"Rendering configuration: \n ${config.root().render(ConfigRenderOptions.concise())}")

    val cluster = Cluster(context.system)
    this.cluster = Some(cluster)
    this.clusterListener = Some(context.spawn(AkkaClusterDebugListener(cluster), "clusterListener"))

    val roles = config.getStringList(ConvergenceServer.AkkaConfig.AkkaClusterRoles).asScala.toSet
    info(s"Convergence Server Roles: ${roles.mkString(", ")}")


    val shardCount = context.system.settings.config.getInt("convergence.shard-count")

    val domainLifeCycleTopic = context.spawn(DomainLifecycleTopic.TopicBehavior, DomainLifecycleTopic.TopicName)

    val sharding = ClusterSharding(context.system)

    val modelShardRegion = RealtimeModelSharding(context.system.settings.config, sharding, shardCount)
    val activityShardRegion = ActivityActorSharding(context.system, sharding, shardCount)
    val chatDeliveryShardRegion = ChatDeliveryActorSharding(sharding, shardCount)
    val chatShardRegion = ChatActorSharding(sharding, shardCount, chatDeliveryShardRegion.narrow[ChatDeliveryActor.Send])
    val domainShardRegion = DomainActorSharding(context.system.settings.config, sharding, shardCount, () => {
      domainLifeCycleTopic
    })

    val domainRestShardRegion = DomainRestActorSharding(context.system.settings.config, sharding, shardCount)


    val restStartupFuture = if (roles.contains(ServerClusterRoles.RestApi)) {
      this.processRestApiRole(domainRestShardRegion, modelShardRegion, chatShardRegion)
    } else {
      Future.successful(())
    }

    val realtimeStartupFuture = if (roles.contains(ServerClusterRoles.RealtimeApi)) {
      this.processRealtimeApiRole(
        domainShardRegion,
        activityShardRegion,
        modelShardRegion,
        chatShardRegion,
        chatDeliveryShardRegion,
        domainLifeCycleTopic)
    } else {
      Future.successful(())
    }

    val backendStartupFuture = if (roles.contains(ServerClusterRoles.Backend)) {
      this.processBackendRole(domainLifeCycleTopic, msg)
    } else {
      Future.successful(())
    }

    implicit val ec: ExecutionContext = ExecutionContext.global
    (for {
      _ <- restStartupFuture
      _ <- realtimeStartupFuture
      _ <- backendStartupFuture
    } yield {
      msg.replyTo ! StartResponse(Right(Ok()))
    }).recover { cause => {
      error("The was an error starting the ConvergenceServerActor", cause)
      msg.replyTo ! StartResponse(Left(()))
    }}

    Behaviors.same
  }

  private[this] def startBackend(msg: StartBackendServices): Behavior[Message] = {
    val StartBackendServices(domainLifecycleTopic, promise) = msg
    val persistenceConfig = config.getConfig("convergence.persistence")
    val dbServerConfig = persistenceConfig.getConfig("server")

    val baseUri = dbServerConfig.getString("uri")
    orientDb = Some(new OrientDB(baseUri, OrientDBConfig.defaultConfig()))

    val convergenceDbConfig = persistenceConfig.getConfig("convergence-database")
    val convergenceDatabase = convergenceDbConfig.getString("database")
    val username = convergenceDbConfig.getString("username")
    val password = convergenceDbConfig.getString("password")

    val poolMin = convergenceDbConfig.getInt("pool.db-pool-min")
    val poolMax = convergenceDbConfig.getInt("pool.db-pool-max")

    val dbProvider = new PooledDatabaseProvider(baseUri, convergenceDatabase, username, password, poolMin, poolMax)
    dbProvider.connect().get

    val domainStore = new DomainStore(dbProvider)

    context.spawn(DomainPersistenceManagerActor(baseUri, domainStore, domainLifecycleTopic), "DomainPersistenceManager")

    val backend = new BackendServices(context, dbProvider, domainLifecycleTopic)
    backend.start()
    this.backend = Some(backend)

    promise.success(())

    Behaviors.same
  }

  private[this] def onBackendFailure(msg: BackendInitializationFailure): Behavior[Message] = {
    val BackendInitializationFailure(cause, p) = msg
    p.failure(cause)
    Behaviors.same
  }

  /**
   * Stops the Convergence Server.
   */
  private[this] def stop(msg: StopRequest): Behavior[Message] = {
    shutdown()
    msg.replyTo ! StopResponse()
    Behaviors.stopped
  }

  /**
   * Stops the Convergence Server.
   */
  private[this] def shutdown(): Unit = {
    logger.info(s"Stopping the Convergence Server...")

    clusterListener.foreach(context.stop(_))

    this.backend.foreach(backend => backend.stop())
    this.rest.foreach(rest => rest.stop())
    this.realtime.foreach(realtime => realtime.stop())
    this.orientDb.foreach(db => db.close())

    logger.info(s"Leaving the cluster")
    cluster.foreach(c => c.manager ! Leave(c.selfMember.address))
  }

  /**
   * A helper method that will bootstrap the backend node.
   */
  private[this] def processBackendRole(domainLifecycleTopic: ActorRef[DomainLifecycleTopic.TopicMessage],
                                       startRequest: StartRequest): Future[Unit] = {
    info("Role 'backend' detected, activating Backend Services...")

    val singletonManager = ClusterSingleton(context.system)
    val convergenceDatabaseInitializerActor = singletonManager.init(
      SingletonActor(Behaviors.supervise(ConvergenceDatabaseInitializerActor())
        .onFailure[Exception](SupervisorStrategy.restart), "ConvergenceDatabaseInitializer")
        .withSettings(ClusterSingletonSettings(context.system).withRole(ServerClusterRoles.Backend))
    )

    info("Ensuring convergence database is initialized")
    val initTimeout = config.getDuration("convergence.persistence.convergence-database.initialization-timeout")
    implicit val timeout: Timeout = Timeout.durationToTimeout(Duration.fromNanos(initTimeout.toNanos))

    val p = Promise[Unit]()

    context.ask(convergenceDatabaseInitializerActor, ConvergenceDatabaseInitializerActor.AssertInitialized) {
      case Success(ConvergenceDatabaseInitializerActor.Initialized()) =>
        StartBackendServices(domainLifecycleTopic, p)
      case Success(ConvergenceDatabaseInitializerActor.InitializationFailed(cause)) =>
        BackendInitializationFailure(cause, p)
      case Failure(cause) =>
        BackendInitializationFailure(cause, p)
    }

    p.future
  }

  /**
   * A helper method that will bootstrap the Rest API.
   */
  private[this] def processRestApiRole(domainRestRegion: ActorRef[DomainRestActor.Message],
                                       modelClusterRegion: ActorRef[RealtimeModelActor.Message],
                                       chatClusterRegion: ActorRef[ChatActor.Message]): Future[Unit] = {
    info("Role 'restApi' detected, activating REST API...")
    val host = config.getString("convergence.rest.host")
    val port = config.getInt("convergence.rest.port")
    val restFrontEnd = new ConvergenceRestApi(
      host,
      port,
      context,
      domainRestRegion,
      modelClusterRegion,
      chatClusterRegion
    )
    this.rest = Some(restFrontEnd)
    restFrontEnd.start()
  }

  /**
   * A helper method that will bootstrap the Realtime Api.
   */
  private[this] def processRealtimeApiRole(domainRegion: ActorRef[DomainActor.Message],
                                           activityShardRegion: ActorRef[ActivityActor.Message],
                                           modelShardRegion: ActorRef[RealtimeModelActor.Message],
                                           chatShardRegion: ActorRef[ChatActor.Message],
                                           chatDeliveryShardRegion: ActorRef[ChatDeliveryActor.Message],
                                           domainLifecycleTopic: ActorRef[DomainLifecycleTopic.TopicMessage]): Future[Unit] = {

    info("Role 'realtimeApi' detected, activating the Realtime API...")
    val protoConfig = ProtocolConfigUtil.loadConfig(context.system.settings.config)
    val clientCreator = context.spawn(ClientActorCreator(
      protoConfig,
      domainRegion,
      activityShardRegion,
      modelShardRegion,
      chatShardRegion,
      chatDeliveryShardRegion,
      domainLifecycleTopic),
      "ClientCreatorActor")

    val host = config.getString("convergence.realtime.host")
    val port = config.getInt("convergence.realtime.port")

    val realTimeFrontEnd = new ConvergenceRealtimeApi(context.system, clientCreator, host, port)
    this.realtime = Some(realTimeFrontEnd)
    realTimeFrontEnd.start()
  }
}

object ConvergenceServerActor {

  def apply(): Behavior[Message] = Behaviors.setup(new ConvergenceServerActor(_))

  /////////////////////////////////////////////////////////////////////////////
  // Message Protocol
  /////////////////////////////////////////////////////////////////////////////

  sealed trait Message

  //
  // Start
  //
  final case class StartRequest(replyTo: ActorRef[StartResponse]) extends Message

  final case class StartResponse(response: Either[Unit, Ok])

  //
  // Stop
  //
  final case class StopRequest(replyTo: ActorRef[StopResponse]) extends Message

  final case class StopResponse()

  final case class CreateClient()


  //////////////////////
  // Internal Messages
  //////////////////////

  private final case class StartBackendServices(domainLifecycleTopic: ActorRef[DomainLifecycleTopic.TopicMessage],
                                                startPromise: Promise[Unit]) extends Message

  private final case class BackendInitializationFailure(cause: Throwable,

                                                        startPromise: Promise[Unit]) extends Message

}


