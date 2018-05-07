package com.acevedo.actors

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.ws.WebSocketRequest
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketUpgradeResponse}
import akka.stream._
import akka.stream.scaladsl.{Flow, GraphDSL, Keep, Sink, Source}
import com.acevedo.actors.WindTurbineSimulator._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success}

object WindTurbineSimulator {
  def props(id: String, endpoint: String)(implicit materializer: ActorMaterializer, ec: ExecutionContext) =
    Props(classOf[WindTurbineSimulator], id, endpoint, materializer, ec)

  final case object Upgraded
  final case object Connected
  final case object Terminated
  final case class ConnectionFailure(ex: Throwable)
  final case class FailedUpgrade(statusCode: StatusCode)
}

case class WindTurbineSimulatorException(id: String) extends Exception

class WindTurbineSimulator(id: String, endpoint: String)
                          (implicit materializer: ActorMaterializer, ec: ExecutionContext)
  extends Actor with ActorLogging {
  implicit private val system = context.system
  val webSocket = WebSocketClient(id, endpoint, self)

  override def postStop() = {
    log.info(s"$id : Stopping WebSocket connection")
    webSocket.killSwitch.shutdown()
  }

  override def receive: Receive = {
    case Upgraded =>
      log.info(s"$id : ____________------------------->WebSocket upgraded")
    case FailedUpgrade(statusCode) =>
      log.error(s"$id : Failed to upgrade WebSocket connection : $statusCode")
      throw WindTurbineSimulatorException(id)
    case ConnectionFailure(ex) =>
      log.error(s"$id : Failed to establish WebSocket connection $ex")
      throw WindTurbineSimulatorException(id)
    case Connected =>
      log.info(s"$id : WebSocket connected")
      context.become(running)
  }

  def running: Receive = {
    case Terminated =>
      log.error(s"$id : WebSocket connection terminated")
      throw WindTurbineSimulatorException(id)
  }
}

object WebSocketClient {
  def apply(id: String, endpoint: String, supervisor: ActorRef)
           (implicit system: ActorSystem,
            materializer: ActorMaterializer,
            ec: ExecutionContext) = new WebSocketClient(id, endpoint, supervisor)(system, materializer, ec)
}

class WebSocketClient(id: String, endpoint: String, supervisor: ActorRef)
                     (implicit
                      system: ActorSystem,
                      materializer: ActorMaterializer,
                      executionContext: ExecutionContext) {
  val webSocket: Flow[Message, Message, Future[WebSocketUpgradeResponse]] = {
    val websocketUri = s"$endpoint/$id"
    Http().webSocketClientFlow(WebSocketRequest(websocketUri))
  }

  val outgoing = GraphDSL.create() { implicit builder =>
    val data = WindTurbineData(id)

    val flow = builder.add {
      Source.tick(2 seconds, 1 seconds, ())
        .map(_ => TextMessage(data.getNext))
    }

    SourceShape(flow.out)
  }



  val incoming: Graph[FlowShape[Message, Unit], NotUsed] = GraphDSL.create() { implicit builder =>
    val flow = builder.add {
      Flow[Message]
        .collect {
          case TextMessage.Strict(text) =>
            Future.successful(text)
          case TextMessage.Streamed(textStream) =>
            textStream.runFold("")(_ + _)
              .flatMap(Future.successful)
        }
        .mapAsync(1)(identity)
        .map(println)
        //.toMat(Sink.foreach(println))(Keep.both)
    }

    FlowShape(flow.in, flow.out)
  }

  val ((upgradeResponse, killSwitch), closed) = Source.fromGraph(outgoing)
    .viaMat(webSocket)(Keep.right) // keep the materialized Future[WebSocketUpgradeResponse]
    .viaMat(KillSwitches.single)(Keep.both) // also keep the KillSwitch
    .via(incoming)
    .toMat(Sink.ignore)(Keep.both) // also keep the Future[Done]
    //.toMat(Sink.fromGraph(incoming))(Keep.both)
    .run()

  val connected = upgradeResponse.map { upgrade =>
      upgrade.response.status match {
        case StatusCodes.SwitchingProtocols => supervisor ! Upgraded
        case statusCode => supervisor ! FailedUpgrade(statusCode)
      }
    }

  connected.onComplete {
    case Success(_) => supervisor ! Connected
    case Failure(ex) => supervisor ! ConnectionFailure(ex)
  }

  closed.map { _ =>
    supervisor ! Terminated
  }
}

object WindTurbineData {
  def apply(id: String) = new WindTurbineData(id)
}

class WindTurbineData(id: String) {
  val random = Random

  def getNext: String = {
    val timestamp = System.currentTimeMillis / 1000
    val power = f"${random.nextDouble() * 10}%.2f"
    val rotorSpeed = f"${random.nextDouble() * 10}%.2f"
    val windSpeed = f"${random.nextDouble() * 100}%.2f"

    s"""{
       |    "id": "$id",
       |    "timestamp": $timestamp,
       |    "measurements": {
       |        "power": $power,
       |        "rotor_speed": $rotorSpeed,
       |        "wind_speed": $windSpeed
       |    }
       |}""".stripMargin
  }
}

object SimulateWindTurbines extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  val id = java.util.UUID.randomUUID.toString
  system.actorOf(WindTurbineSimulator.props(id, "ws://echo.websocket.org"), id)

//  Source(1 to 10)
//    .throttle(
//      elements = 100,
//      per = 1 second,
//      maximumBurst = 100,
//      mode = ThrottleMode.shaping
//    )
//    .map { _ =>
//      val id = java.util.UUID.randomUUID.toString
//      system.actorOf(WindTurbineSimulator.props(id, "ws://echo.websocket.org"), id)
//    }
//    .runWith(Sink.ignore)
}