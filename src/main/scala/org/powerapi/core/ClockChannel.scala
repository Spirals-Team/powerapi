/**
 * This software is licensed under the GNU Affero General Public License, quoted below.
 *
 * This file is a part of PowerAPI.
 * 
 * Copyright (C) 2011-2014 Inria, University of Lille 1.
 * 
 * PowerAPI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * PowerAPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with PowerAPI.

 * If not, please consult http://www.gnu.org/licenses/agpl-3.0.html.
 */

package org.powerapi.core

import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorRef

/**
 * Clock channel and messages.
 */
object ClockChannel extends Channel {

  type M = ClockMessage

  trait ClockMessage extends Message

  /**
   * ClockTick is represented as a dedicated type of message.
   * 
   * @param topic: subject used for routing the message.
   * @param frequency: clock frequency.
   */
  case class ClockTick(topic: String,
                       frequency: FiniteDuration,
                       timestamp: Long = System.currentTimeMillis) extends ClockMessage

  /**
   * ClockTickSubscription is represented as a dedicated type of message.
   * 
   * @param topic: subject used for routing the message.
   * @param frequency: clock frequency.
   */
  case class ClockTickSubscription(topic: String,
                                   frequency: FiniteDuration) extends ClockMessage

  /**
   * ClockStart is represented as a dedicated type of message.
   *
   * @param topic: subject used for routing the message.
   * @param frequency: clock frequency.
   */
  case class ClockStart(topic: String, frequency: FiniteDuration) extends ClockMessage

  /**
   * ClockStop is represented as a dedicated type of message.
   *
   * @param topic: subject used for routing the message.
   * @param frequency: clock frequency.
   */
  case class ClockStop(topic: String, frequency: FiniteDuration) extends ClockMessage

  /**
   * ClockStopAll is represented as a dedicated type of message.
   *
  * @param topic: subject used for routing the message.
   */
  case class ClockStopAll(topic: String) extends ClockMessage

  /** 
   * Topic for communicating with the Clock.
   */
  private val topic = "tick:subscription"

  /**
   * External methods used by the Monitor actors to subscribe/unsubscribe,
   * start/stop a clock which runs at a frequency.
   */
  def subscribeClock(frequency: FiniteDuration): MessageBus => ActorRef => Unit = {
    subscribe(clockTickTopic(frequency)) _
  }

  def unsubscribeClock(frequency: FiniteDuration): MessageBus => ActorRef => Unit = {
    unsubscribe(clockTickTopic(frequency)) _
  }

  def startClock(frequency: FiniteDuration): MessageBus => Unit = {
    publish(ClockStart(topic, frequency)) _
  }

  def stopClock(frequency: FiniteDuration): MessageBus => Unit ={
    publish(ClockStop(topic, frequency)) _
  }

  /**
   * Internal methods used by the Clocks actor for interacting with the bus.
   */
  def subscribeTickSubscription: MessageBus => ActorRef => Unit = {
    subscribe(topic) _
  }

  lazy val stopAllClock = ClockStopAll(topic)

  /**
   * Internal methods used by the ClockChild actors for interacting with the bus.
   */
  def publishTick(frequency: FiniteDuration): MessageBus => Unit = {
    publish(ClockTick(clockTickTopic(frequency), frequency)) _
  }

  /**
   * Use to format the ClockChild name.
   */
  def formatClockChildName(frequency: FiniteDuration) = {
    s"clock-${frequency.toNanos}"
  }

  /**
   * Use to format a freqyency to an associated topic.
   */
  private def clockTickTopic(frequency: FiniteDuration) = {
    s"tick:${frequency.toNanos}"
  }
}
