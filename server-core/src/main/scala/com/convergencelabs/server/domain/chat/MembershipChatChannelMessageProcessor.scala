package com.convergencelabs.server.domain.chat

import akka.cluster.pubsub.DistributedPubSub
import akka.actor.ActorContext
import akka.cluster.pubsub.DistributedPubSubMediator.Publish

abstract class MembershipChatChannelMessageProcessor(
  stateManager: ChatChannelStateManager,
  context: ActorContext)
    extends ChatChannelMessageProcessor(stateManager) {
  val mediator = DistributedPubSub(context.system).mediator

  def boradcast(message: Any): Unit = {
    val members = stateManager.state().members
    members.values.foreach { member =>
      val topic = ChatChannelActor.getChatUsernameTopicName(member.userId)
      mediator ! Publish(topic, message)
    }
  }
}
