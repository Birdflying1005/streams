package com.acevedo.actors

import akka.actor.{Actor, ActorSystem, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.pattern.{Backoff, BackoffSupervisor}
import akka.stream.{ActorMaterializer, ThrottleMode}
import akka.stream.scaladsl.{Sink, Source}
import com.acevedo.actors.WindTurbineSupervisor.{EntityEnvelope, StartClient, StartSimulator}
import com.typesafe.config.ConfigFactory
import scala.language.postfixOps
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object DistributedWindTurbineSimulator extends App {
  val port = args(0)
  val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port")
    .withFallback(ConfigFactory.parseString("akka.cluster.roles = [WindTurbineSimulator]"))
    .withFallback(ConfigFactory.load())

  implicit val system: ActorSystem = ActorSystem("ShardTurbineSupervisorActorSystem", config)
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  val windTurbineShardRegion = ClusterSharding(system).start(
    typeName ="WindTurbineSupervisorShardRegion",
    entityProps = WindTurbineSupervisor.props,
    settings = ClusterShardingSettings(system),
    extractEntityId = WindTurbineClusterConfig.extractEntityId,
    extractShardId = WindTurbineClusterConfig.extractShardId
  )

  Source(1 to 100000)
    .throttle(
      elements = 100,
      per = 1 second,
      maximumBurst = 100,
      mode = ThrottleMode.shaping
    )
    .map { _ =>
      val id = java.util.UUID.randomUUID.toString
      windTurbineShardRegion ! EntityEnvelope(id, StartSimulator)
    }
    .runWith(Sink.ignore)

  sys.addShutdownHook {
    Await.result(system.whenTerminated, Duration.Inf)
  }
}

object WindTurbineClusterConfig {
  private val numberOfShards = 100

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(id, payload) => (id, payload)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(id, _) => (id.hashCode % numberOfShards).toString
    case ShardRegion.StartEntity(id) => (id.hashCode % numberOfShards).toString
  }
}

object WindTurbineSupervisor {
  final case class EntityEnvelope(id: String, message: Any)
  final case object StartSimulator
  final case class StartClient(id: String)
  def props(implicit materializer: ActorMaterializer, ec: ExecutionContext) =
    Props(new WindTurbineSupervisor())
}

class WindTurbineSupervisor(implicit materializer: ActorMaterializer, ec: ExecutionContext) extends Actor {
  override def preStart(): Unit = {
    self ! StartClient(self.path.name)
  }

  def receive: Receive = {
    case StartClient(id) =>
      val supervisor = BackoffSupervisor.props(
        Backoff.onFailure(
          WindTurbineSimulator.props(id, "ws://echo.websocket.org"),
          childName = id,
          minBackoff = 1.second,
          maxBackoff = 30.seconds,
          randomFactor = 0.2
        )
      )
      context.actorOf(supervisor, name = s"$id-backoff-supervisor")
      context.become(running)
  }

  def running: Receive = Actor.emptyBehavior
}