package com.convergencelabs.server.domain.model

import akka.actor.ActorLogging
import akka.actor.Actor
import scala.collection.mutable
import akka.actor.ActorRef
import com.convergencelabs.server.domain.DomainFqn
import com.convergencelabs.server.datastore.domain.DomainPersistenceProvider
import com.convergencelabs.server.ProtocolConfiguration
import akka.actor.PoisonPill
import scala.compat.Platform
import java.io.IOException
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import akka.actor.Props
import com.convergencelabs.server.datastore.domain.DomainPersistenceProvider
import com.convergencelabs.server.datastore.domain.DomainPersistenceManagerActor
import java.time.Instant
import java.time.Duration
import java.time.temporal.TemporalUnit
import java.time.temporal.ChronoUnit
import com.convergencelabs.server.domain.ModelSnapshotConfig
import scala.util.Success
import scala.util.Failure
import com.convergencelabs.server.UnknownErrorResponse
import scala.util.Try
import akka.actor.Status
import akka.actor.Terminated
import com.convergencelabs.server.domain.model.data.ObjectValue
import scala.util.control.NonFatal
import com.convergencelabs.server.datastore.DuplicateValueExcpetion
import com.convergencelabs.server.datastore.InvalidValueExcpetion
import com.convergencelabs.server.datastore.domain.ModelPermissions
import com.convergencelabs.server.datastore.domain.CollectionPermissions
import com.convergencelabs.server.datastore.UnauthorizedException

case class QueryModelsRequest(sk: SessionKey, query: String)
case class QueryOrderBy(field: String, ascending: Boolean)

case class QueryModelsResponse(result: List[Model])

class ModelManagerActor(
  private[this] val domainFqn: DomainFqn,
  private[this] val protocolConfig: ProtocolConfiguration)
    extends Actor with ActorLogging {

  private[this] var openRealtimeModels = Map[ModelFqn, ActorRef]()
  private[this] var nextModelResourceId: Long = 0

  var persistenceProvider: DomainPersistenceProvider = _

  def receive: Receive = {
    case message: OpenRealtimeModelRequest   => onOpenRealtimeModel(message)
    case message: CreateModelRequest         => onCreateModelRequest(message)
    case message: DeleteModelRequest         => onDeleteModelRequest(message)
    case message: QueryModelsRequest         => onQueryModelsRequest(message)
    case message: ModelShutdownRequest       => onModelShutdownRequest(message)
    case message: GetModelPermissionsRequest => onGetModelPermissions(message)
    case message: SetModelPermissionsRequest => onSetModelPermissions(message)
    case Terminated(actor)                   => onActorDeath(actor)
    case message: Any                        => unhandled(message)
  }

  private[this] def onOpenRealtimeModel(openRequest: OpenRealtimeModelRequest): Unit = {
    this.openRealtimeModels.get(openRequest.modelFqn) match {
      case Some(modelActor) =>
        // Model already open
        modelActor forward openRequest
      case None =>
        if (openRequest.sk.admin || getModelUserPermissions(openRequest.modelFqn, openRequest.sk.uid).read) {
          // Model not already open, load it
          val resourceId = "" + nextModelResourceId
          nextModelResourceId += 1
          val collectionId = openRequest.modelFqn.collectionId

          (for {
            exists <- persistenceProvider.collectionStore.ensureCollectionExists(collectionId)
            snapshotConfig <- getSnapshotConfigForModel(collectionId)
            permissions <- this.getModelPermissions(openRequest.modelFqn)
          } yield {
            val props = RealtimeModelActor.props(
              self,
              domainFqn,
              openRequest.modelFqn,
              resourceId,
              persistenceProvider.modelStore,
              persistenceProvider.modelOperationProcessor,
              persistenceProvider.modelSnapshotStore,
              5000, // FIXME hard-coded time.  Should this be part of the protocol?
              snapshotConfig,
              permissions.collectionWorld,
              permissions.modelWorld,
              permissions.modelUsers)

            val modelActor = context.actorOf(props, resourceId)
            this.openRealtimeModels += (openRequest.modelFqn -> modelActor)
            this.context.watch(modelActor)
            modelActor forward openRequest
            ()
          }) recover {
            case cause: Exception =>
              log.error(cause, s"Error opening model: ${openRequest.modelFqn}")
              sender ! UnknownErrorResponse("Could not open model due to an unexpected server error.")
          }
        }
    }
  }

  private[this] def getCollectionUserPermissions(collectionId: String, username: String): CollectionPermissions = {
    val permissionsStore = this.persistenceProvider.modelPermissionsStore
    val userPermissions = permissionsStore.getCollectionUserPermissions(collectionId, username).get
    userPermissions.getOrElse({
      val collectionWorldPermissions = permissionsStore.getCollectionWorldPermissions(collectionId).get
      collectionWorldPermissions.getOrElse(CollectionPermissions(false, false, false, false, false))
    })
  }

  private[this] def getModelUserPermissions(fqn: ModelFqn, username: String): ModelPermissions = {
    val permissionsStore = this.persistenceProvider.modelPermissionsStore
    val userPermissions = permissionsStore.getModelUserPermissions(fqn, username).get
    userPermissions.getOrElse({
      val modelWorldPermissions = permissionsStore.getModelWorldPermissions(fqn).get
      modelWorldPermissions.getOrElse {
        permissionsStore.getCollectionWorldPermissions(fqn.collectionId).map {
          case Some(CollectionPermissions(create, read, write, remove, manage)) => ModelPermissions(read, write, remove, manage)
          case None => ModelPermissions(false, false, false, false)
        }.get
      }
    })
  }

  private[this] def getModelPermissions(fqn: ModelFqn): Try[RealTimeModelPermissions] = {
    //FIXME: after implementing collection permissions
    val permissionsStore = this.persistenceProvider.modelPermissionsStore
    for {
      collectionWorld <- permissionsStore.getCollectionWorldPermissions(fqn.collectionId)
      modelWorld <- permissionsStore.getModelWorldPermissions(fqn)
      users <- permissionsStore.getAllModelUserPermissions(fqn)
    } yield (RealTimeModelPermissions(collectionWorld, modelWorld, users))
  }

  private[this] def getSnapshotConfigForModel(collectionId: String): Try[ModelSnapshotConfig] = {
    persistenceProvider.collectionStore.getOrCreateCollection(collectionId).flatMap { c =>
      if (c.overrideSnapshotConfig) {
        Success(c.snapshotConfig)
      } else {
        persistenceProvider.configStore.getModelSnapshotConfig()
      }
    }
  }

  private[this] def onCreateModelRequest(createRequest: CreateModelRequest): Unit = {
    val CreateModelRequest(sk, collectionId, modelId, data, worldPermissions) = createRequest
    // FIXME perhaps these should be some expected error type, like InvalidArgument
    if (collectionId.length == 0) {
      sender ! UnknownErrorResponse("The collecitonId can not be empty when creating a model")
    } else {
      createModel(sk, collectionId, modelId, data, worldPermissions)
    }
  }

  private[this] def createModel(sk: SessionKey, collectionId: String, modelId: Option[String], data: ObjectValue, worldPermissions: Option[ModelPermissions]): Unit = {
    if (sk.admin || getCollectionUserPermissions(collectionId, sk.uid).create) {
      persistenceProvider.collectionStore.ensureCollectionExists(collectionId) flatMap { _ =>
        persistenceProvider.modelStore.createModel(collectionId, modelId, data, worldPermissions)
      } flatMap { model =>
        val ModelMetaData(fqn, version, created, modeified, worldPermissions) = model.metaData
        val snapshot = ModelSnapshot(ModelSnapshotMetaData(fqn, version, created), model.data)
        // Give the creating user unlimited access to the model
        // TODO: Change this to use defaults
        persistenceProvider.modelPermissionsStore.updateModelUserPermissions(fqn, sk.uid, ModelPermissions(true, true, true, true)).get
        persistenceProvider.modelSnapshotStore.createSnapshot(snapshot) map { _ => model }
      } map { model =>
        sender ! ModelCreated(model.metaData.fqn)
      } recover {
        case e: DuplicateValueExcpetion =>
          sender ! ModelAlreadyExists
        case e: InvalidValueExcpetion =>
          sender ! UnknownErrorResponse("Could not create model beause it contained an invalid value")
        case e: Exception =>
          sender ! UnknownErrorResponse("Could not create model: " + e.getMessage)
      }
    } else {
      sender ! UnauthorizedException("Insufficient privlidges to create models for this collection")
    }
  }

  private[this] def onDeleteModelRequest(deleteRequest: DeleteModelRequest): Unit = {
    val DeleteModelRequest(sk, modelFqn) = deleteRequest
    if (sk.admin || getModelUserPermissions(modelFqn, sk.uid).remove) {
      if (openRealtimeModels.contains(modelFqn)) {
        val closed = openRealtimeModels(modelFqn)
        closed ! ModelDeleted
        openRealtimeModels -= modelFqn
      }

      persistenceProvider.modelStore.deleteModel(modelFqn) map { _ =>
        sender ! ModelDeleted
      } recover {
        case cause: Exception => sender ! Status.Failure(cause)
      }
    } else {
      sender ! UnauthorizedException("Insufficient privlidges to delete model")
    }
  }

  private[this] def onQueryModelsRequest(request: QueryModelsRequest): Unit = {
    val QueryModelsRequest(sk, query) = request
    val username = if(request.sk.admin) {
      None
    } else {
      Some(request.sk.uid)
    }
    persistenceProvider.modelStore.queryModels(query, username) match {
      case Success(result) =>sender ! QueryModelsResponse(result)
      case Failure(cause)  => sender ! Status.Failure(cause)
    }
  }

  private[this] def onGetModelPermissions(request: GetModelPermissionsRequest): Unit = {
    val GetModelPermissionsRequest(collectionId, modelId) = request
    // FIXME need to implement getting this from the database
    sender ! GetModelPermissionsResponse(ModelPermissions(true, true, true, true), Map())
  }

  private[this] def onSetModelPermissions(request: SetModelPermissionsRequest): Unit = {
    val SetModelPermissionsRequest(sk, collectionId, modelId, setWorld, world, setAllUsers, users) = request
    val modelFqn = ModelFqn(collectionId, modelId)
    this.openRealtimeModels.get(modelFqn).foreach { _ forward request }
    if (setWorld) {
      persistenceProvider.modelPermissionsStore.setModelWorldPermissions(modelFqn, world)
    }

    if (setAllUsers) {
      persistenceProvider.modelPermissionsStore.deleteAllModelUserPermissions(modelFqn)
    }

    persistenceProvider.modelPermissionsStore.updateAllModelUserPermissions(modelFqn, users)
  }

  private[this] def onModelShutdownRequest(shutdownRequest: ModelShutdownRequest): Unit = {
    val fqn = shutdownRequest.modelFqn
    openRealtimeModels.get(fqn) map (_ ! ModelShutdown)
    openRealtimeModels -= (fqn)
  }

  private[this] def onActorDeath(actor: ActorRef): Unit = {
    // TODO might be more efficient ay to do this.
    openRealtimeModels = openRealtimeModels filter {
      case (fqn, modelActorRef) =>
        actor != modelActorRef
    }
  }

  override def postStop(): Unit = {
    log.debug("ModelManagerActor({}) received shutdown command.  Shutting down all Realtime Models.", this.domainFqn)
    openRealtimeModels = Map()
    DomainPersistenceManagerActor.releasePersistenceProvider(self, context, domainFqn)
  }

  override def preStart(): Unit = {
    DomainPersistenceManagerActor.acquirePersistenceProvider(self, context, domainFqn) match {
      case Success(provider) =>
        persistenceProvider = provider
      case Failure(cause) =>
        throw new IllegalStateException("Could not obtain a persistence provider", cause)
    }
  }
}

object ModelManagerActor {

  val RelativePath = "modelManager"

  def props(domainFqn: DomainFqn,
            protocolConfig: ProtocolConfiguration): Props = Props(
    new ModelManagerActor(
      domainFqn,
      protocolConfig))
}
