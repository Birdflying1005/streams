package com.acevedo.actors

import akka.actor.Actor
import com.acevedo.actors.TotalActor._

class TotalActor extends Actor {
  var total: Long = 0
  override def receive: Receive = {
    case Increment(value) =>
      println(s"About to increment $total with $value")
      total += value
      sender ! Ack
    case GetTotal => sender ! CurrentTotal(total)
    case Complete(id) =>
      println(s"WebSocket terminated for Id : $id")
    case _ => sender ! Ack
  }
}

object TotalActor {
  case class Increment(value: Long)
  case class Complete(id: Long)
  case object Init
  case object Ack
  case object GetTotal
  case class CurrentTotal(total: Long)
}