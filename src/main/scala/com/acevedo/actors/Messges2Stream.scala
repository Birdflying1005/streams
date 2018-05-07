package com.acevedo.actors

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source}

import scala.concurrent.{ExecutionContext, Future}

object Messges2Stream extends App {
  implicit val system: ActorSystem = ActorSystem("messages2stream")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  val actorRef = Source.actorRef[Long](Int.MaxValue, OverflowStrategy.dropTail)
    .to(Sink.foreach(println))
    .run()

  Source(1 to Int.MaxValue)
    //.mapAsync(1)(n => Future.successful(n))
    .map(n => actorRef ! n)
    .runWith(Sink.ignore)
}
