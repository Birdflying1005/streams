package com.acevedo.actors

import akka.actor.{Actor, ActorSystem, Props}
import akka.stream.{ActorMaterializer, KillSwitches}
import akka.stream.scaladsl.{Keep, Sink, Source}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class PrintMoreNumbers (implicit val materializer: ActorMaterializer, val ec: ExecutionContext) extends Actor {
  private val (killSwitch, done) =
    Source.tick(0.seconds, 1.second, 1)
    .scan(0)(_ + _)
    .viaMat(KillSwitches.single)(Keep.right)
    .toMat(Sink.foreach(println))(Keep.both)
    .run()

  done.map(_ => self ! "done")

  override def receive: Receive = {
    case "stop" =>
      println(s"Stopping")
      killSwitch.shutdown()
    case "done" =>
      println(s"Done")
      context.stop(self)
  }
}

object LessTrivialExample extends App {
  implicit val system: ActorSystem = ActorSystem("less-trivial-example")
  implicit val actorMaterializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  val actorRef = system.actorOf(Props(classOf[PrintMoreNumbers], actorMaterializer, ec))
  system.scheduler.scheduleOnce(6.seconds) {
    actorRef ! "stop"
  }
}
