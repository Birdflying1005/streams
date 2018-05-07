package com.acevedo
import akka.pattern.ask
import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.{ActorMaterializer, ClosedShape, FlowShape, OverflowStrategy}
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink, Source}
import akka.util.Timeout
import com.acevedo.actors.TotalActor
import com.acevedo.actors.TotalActor._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn
import scala.util.{Failure, Success}

object SocketHandler extends App {
  implicit val system: ActorSystem = ActorSystem("total-counter")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val materialzer: ActorMaterializer = ActorMaterializer()

  val total = system.actorOf(Props[TotalActor], "total-counter-actor")

  def measurementFlow(id: Long): Flow[Message, Message, NotUsed] =
    Flow[Message]
    .collect {
      case TextMessage.Strict(message) => Future.successful(message)
      case TextMessage.Streamed(textStream) => textStream.runFold("")(_ + _)
    }
    .mapAsync(1)(identity)
    .groupedWithin(1000, 1.second)
    .map(messages => Increment(messages.map(_.toInt).sum))
    .alsoTo(Sink.actorRefWithAck(total, Init, Ack, Complete(id)))
    .map(increment => TextMessage(s"Just incremented ${increment.value}"))

  val a = Source.actorPublisher(Props[TotalActor])
  val b = Source.actorRef(100, OverflowStrategy.fail)
  val route =
    path("measurements"  / LongNumber) { id =>
        extractUpgradeToWebSocket { upgrade =>

          complete(upgrade.handleMessages(measurementFlow(id)))
        }
    } ~
    path("total") {
      get {
        implicit val askTimeout: Timeout = Timeout(30.seconds)
        onComplete(total ? GetTotal) {
          case Success(CurrentTotal(value)) => complete(s"The total is $value")
          case Success(_) => failWith(new Error)
          case Failure(err) => failWith(err)
        }
      }
    }

  val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)
  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine() // let it run until user presses return
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ ⇒ system.terminate()) // and shutdown when done

}
