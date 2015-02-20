/*
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
 *
 * If not, please consult http://www.gnu.org/licenses/agpl-3.0.html.
 */
package org.powerapi.core

import java.util.UUID
import akka.actor.{Actor, ActorNotFound, ActorRef, ActorSystem, Props}
import akka.pattern.gracefulStop
import akka.testkit.{EventFilter, TestKit, TestProbe}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.powerapi.UnitTest
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class MonitorMockSubscriber(eventBus: MessageBus) extends Actor {
  import org.powerapi.core.MonitorChannel.{MonitorTick, subscribeMonitorTick}

  override def preStart() = {
    subscribeMonitorTick(eventBus)(self)
    super.preStart()
  }

  def receive = active(0)

  def active(acc: Int): Actor.Receive = {
    case _: MonitorTick => context become active(acc + 1)
    case "reset" => context become active(0)
    case "get" => sender ! acc
  }
}

class MonitorSuite(system: ActorSystem) extends UnitTest(system) {
  import ClockChannel.{ ClockTick, formatClockChildName }
  import MonitorChannel.{ MonitorAggFunction, MonitorStart, MonitorStop, formatMonitorChildName, startMonitor, stopAllMonitor, stopMonitor }
  import org.powerapi.core.power._
  import org.powerapi.core.target.{All, intToProcess, stringToApplication, Target}
  import org.powerapi.module.PowerChannel.{ PowerReport, publishPowerReport, subscribeAggPowerReport }

  implicit val timeout = Timeout(1.seconds)

  def this() = this(ActorSystem("MonitorSuite"))

  val eventListener = ConfigFactory.parseResources("test.conf")

  override def afterAll() = {
    TestKit.shutdownActorSystem(system)
  }

  trait Bus {
    val eventBus = new MessageBus
  }

  "The Monitor actors" should "launch an exception when the messages received cannot be handled" in new Bus {
    val _system = ActorSystem("MonitorSuiteTest1", eventListener)

    val muid = UUID.randomUUID()
    val frequency = 50.milliseconds
    val targets = List[Target](1)
                                                            
    val monitor = _system.actorOf(Props(classOf[MonitorChild], eventBus, muid, frequency, targets), "monitor1")
    val monitors = _system.actorOf(Props(classOf[Monitors], eventBus), "monitors1")

    EventFilter.warning(occurrences = 1, source = monitor.path.toString).intercept({
      monitor ! MonitorStop("test", muid)
    })(_system)

    EventFilter.warning(occurrences = 1, source = monitor.path.toString).intercept({
      monitor ! MonitorStart("test", muid, Duration.Zero, targets)
    })(_system)

    // Not an exception, just an assessment (switching in the running state).
    EventFilter.info(occurrences = 1, source = monitor.path.toString).intercept({
      monitor ! MonitorStart("test", muid, frequency, targets)
    })(_system)

    EventFilter.warning(occurrences = 1, source = monitor.path.toString).intercept({
      monitor ! MonitorStart("test", muid, frequency, targets)
    })(_system)

    EventFilter.warning(occurrences = 1, source = monitor.path.toString).intercept({
      monitor ! MonitorStop("test", UUID.randomUUID())
    })(_system)

    EventFilter.warning(occurrences = 1, source = monitors.path.toString).intercept({
      stopMonitor(muid)(eventBus)
    })(_system)

    Await.result(gracefulStop(monitor, timeout.duration), timeout.duration)
    Await.result(gracefulStop(monitors, timeout.duration), timeout.duration)
    _system.shutdown()
    _system.awaitTermination(timeout.duration)
  }

  "A MonitorChild actor" should "start to listen ticks for its frequency and produce messages" in new Bus {
    import java.lang.Thread

    val _system = ActorSystem("MonitorSuiteTest2", eventListener)
    val clocks = _system.actorOf(Props(classOf[Clocks], eventBus), "clocks2")

    val frequency = 25.milliseconds
    val muid = UUID.randomUUID()
    val targets = List[Target](1, "java", All)
    
    val monitor = _system.actorOf(Props(classOf[MonitorChild], eventBus, muid, frequency, targets), "monitor2")
    val subscriber = _system.actorOf(Props(classOf[MonitorMockSubscriber], eventBus))
    val watcher = TestProbe()(_system)
    watcher.watch(monitor)

    EventFilter.info(occurrences = 1, source = monitor.path.toString).intercept({
      monitor ! MonitorStart("test", muid, frequency, targets)
    })(_system)

    Thread.sleep(250)

    EventFilter.info(occurrences = 1, source = monitor.path.toString).intercept({
      monitor ! MonitorStop("test", muid)
    })(_system)

    awaitAssert({
      watcher.expectTerminated(monitor)
    }, 20.seconds)

    awaitAssert({
      intercept[ActorNotFound] {
        Await.result(_system.actorSelection(formatClockChildName(frequency)).resolveOne(), timeout.duration)
      }
    }, 20.seconds)

    subscriber ! "get"
    // We assume a service quality of 90% (regarding the number of processed messages).
    expectMsgClass(classOf[Int]) should be >= (targets.size * (10 * 0.9)).toInt

    Await.result(gracefulStop(clocks, timeout.duration), timeout.duration)
    Await.result(gracefulStop(monitor, timeout.duration), timeout.duration)
    Await.result(gracefulStop(subscriber, timeout.duration), timeout.duration)
    Await.result(gracefulStop(watcher.ref, timeout.duration), timeout.duration)
    _system.shutdown()
    _system.awaitTermination(timeout.duration)
  }

  it can "handle a large number of targets" in new Bus {
    import java.lang.Thread

    val _system = ActorSystem("MonitorSuiteTest3")

    val frequency = 25.milliseconds
    val muid = UUID.randomUUID()
    val targets = scala.collection.mutable.ListBuffer[Target]()

    for(i <- 1 to 100) {
      targets += i
    }

    val clocks = _system.actorOf(Props(classOf[Clocks], eventBus), "clocks3")
    val monitor = _system.actorOf(Props(classOf[MonitorChild], eventBus, muid, frequency, targets.toList), "monitor3")
    val subscriber = _system.actorOf(Props(classOf[MonitorMockSubscriber], eventBus))
    val watcher = TestProbe()(_system)
    watcher.watch(monitor)

    monitor ! MonitorStart("test", muid, frequency, targets.toList)
    Thread.sleep(250)
    monitor ! MonitorStop("test", muid)

    awaitAssert({
      watcher.expectTerminated(monitor)
    }, 20.seconds)

    awaitAssert({
      intercept[ActorNotFound] {
        Await.result(_system.actorSelection(formatClockChildName(frequency)).resolveOne(), timeout.duration)
      }
    }, 20.seconds)

    subscriber ! "get"
    // We assume a service quality of 90% (regarding the number of processed messages).
    expectMsgClass(classOf[Int]) should be >= (targets.size * (10 * 0.9)).toInt

    Await.result(gracefulStop(clocks, timeout.duration), timeout.duration)
    Await.result(gracefulStop(monitor, timeout.duration), timeout.duration)
    Await.result(gracefulStop(subscriber, timeout.duration), timeout.duration)
    Await.result(gracefulStop(watcher.ref, timeout.duration), timeout.duration)
    _system.shutdown()
    _system.awaitTermination(timeout.duration)
  }

  "A Monitors actor" should "handle its MonitorChild actors and subscribers have to receive messages" in new Bus {
    import java.lang.Thread

    val _system = ActorSystem("MonitorSuiteTest4")

    val clocks = _system.actorOf(Props(classOf[Clocks], eventBus), "clocks4")
    val monitors = _system.actorOf(Props(classOf[Monitors], eventBus), "monitors4")
    
    val frequency = 25.milliseconds
    val targets = List[Target](1, "java")
                                                      
    val monitor = new Monitor(eventBus, _system)

    val subscribers = scala.collection.mutable.ListBuffer[ActorRef]()

    for(i <- 0 until 100) {
      subscribers += _system.actorOf(Props(classOf[MonitorMockSubscriber], eventBus))
    }

    startMonitor(monitor.muid, frequency, targets)(eventBus)
    Thread.sleep(250)
    monitor.cancel()

    awaitAssert({
      intercept[ActorNotFound] {
        Await.result(_system.actorSelection(formatMonitorChildName(monitor.muid)).resolveOne(), timeout.duration)
      }
    }, 20.seconds)

    awaitAssert({
      intercept[ActorNotFound] {
        Await.result(_system.actorSelection(formatClockChildName(frequency)).resolveOne(), timeout.duration)
      }
    }, 20.seconds)

    for(i <- 0 until 100) {
      subscribers(i) ! "get"
      // We assume a service quality of 90% (regarding the number of processed messages).
      expectMsgClass(classOf[Int]) should be >= (targets.size * (10 * 0.9)).toInt
      Await.result(gracefulStop(subscribers(i), timeout.duration), timeout.duration)
    }
    
    Await.result(gracefulStop(clocks, timeout.duration), timeout.duration)
    Await.result(gracefulStop(monitors, timeout.duration), timeout.duration)  
    _system.shutdown()
    _system.awaitTermination(timeout.duration)
  }

  it should "publish a message to the sensor actors for let them know that the monitor(s) is/are stopped" in new Bus {
    import akka.actor.Terminated
    import akka.pattern.ask
    import akka.testkit.TestActorRef
    import java.lang.Thread
    import org.powerapi.core.MonitorChannel.MonitorStarted
    import org.powerapi.module.SensorChannel.{MonitorStop, MonitorStopAll, subscribeSensorsChannel}
    import scala.concurrent.Future

    val _system = ActorSystem("MonitorSuiteTest5")

    val monitors = TestActorRef(Props(classOf[Monitors], eventBus), "monitors5")(_system)
    val reaper = TestProbe()(system)
    subscribeSensorsChannel(eventBus)(testActor)

    val monitor = new Monitor(eventBus, _system)
    val monitor2 = new Monitor(eventBus, _system)

    Await.result(monitors ? MonitorStart("", monitor.muid, 25.milliseconds, List[Target](1)), timeout.duration)
    startMonitor(monitor2.muid, 50.milliseconds, List[Target](2))(eventBus)
    Thread.sleep(250)
    val children = monitors.children.toArray.clone()
    children.foreach(child => reaper.watch(child))

    stopMonitor(monitor.muid)(eventBus)
    stopAllMonitor(eventBus)
    for(_ <- 0 until children.size) {
      reaper.expectMsgClass(classOf[Terminated])
    }

    expectMsgClass(classOf[MonitorStop]) match {
      case MonitorStop(_, id) => monitor.muid should equal(id)
    }
    expectMsgClass(classOf[MonitorStopAll])

    Await.result(gracefulStop(monitors, timeout.duration), timeout.duration)
    _system.shutdown()
    _system.awaitTermination(timeout.duration)
  }

  it should "handle a large number of monitors" in new Bus {
    import java.lang.Thread

    val _system = ActorSystem("MonitorSuiteTest5")
    val clocks = _system.actorOf(Props(classOf[Clocks], eventBus), "clocks5")
    val monitors = _system.actorOf(Props(classOf[Monitors], eventBus), "monitors5")

    val targets = List(All)

    for(frequency <- 50 to 100) {
      val monitor = new Monitor(eventBus, _system)
      startMonitor(monitor.muid, frequency.milliseconds, targets)(eventBus)
    }

    Thread.sleep(1000)

    Await.result(gracefulStop(clocks, timeout.duration), timeout.duration)
    Await.result(gracefulStop(monitors, timeout.duration), timeout.duration)
    _system.shutdown()
    _system.awaitTermination(timeout.duration)
  }

  "A MonitorChild actor" should "start to listen power reports for its MUID and produce aggregated messages" in new Bus {
    val _system = ActorSystem("MonitorSuiteTest6", eventListener)

    val muid = UUID.randomUUID()
    val frequency = 25.milliseconds
    val targets = List[Target](1, 2, 3)

    val device = "mock"
    val tickMock = ClockTick("ticktest", frequency)

    val monitor = _system.actorOf(Props(classOf[MonitorChild], eventBus, muid, frequency, targets), "monitor6")
    val watcher = TestProbe()(_system)
    watcher.watch(monitor)
    subscribeAggPowerReport(muid)(eventBus)(testActor)

    EventFilter.info(occurrences = 1, source = monitor.path.toString).intercept({
      monitor ! MonitorStart("test", muid, frequency, targets)
    })(_system)
    
    publishPowerReport(muid, 1, 4.W, device, tickMock)(eventBus)
    publishPowerReport(muid, 2, 5.W, device, tickMock)(eventBus)
    EventFilter.info(occurrences = 1, source = monitor.path.toString).intercept({
      monitor ! MonitorAggFunction("test", muid, MAX)
    })(_system)
    publishPowerReport(muid, 3, 6.W, device, tickMock)(eventBus)

    publishPowerReport(muid, 1, 11.W, device, tickMock)(eventBus)
    publishPowerReport(muid, 2, 10.W, device, tickMock)(eventBus)
    EventFilter.info(occurrences = 1, source = monitor.path.toString).intercept({
      monitor ! MonitorAggFunction("test", muid, MIN)
    })(_system)
    publishPowerReport(muid, 3, 12.W, device, tickMock)(eventBus)
    
    publishPowerReport(muid, 1, 9.W, device, tickMock)(eventBus)
    publishPowerReport(muid, 2, 3.W, device, tickMock)(eventBus)
    publishPowerReport(muid, 3, 7.W, device, tickMock)(eventBus)

    EventFilter.info(occurrences = 1, source = monitor.path.toString).intercept({
      monitor ! MonitorStop("test", muid)
    })(_system)

    awaitAssert({
      watcher.expectTerminated(monitor)
    }, 20.seconds)

    expectMsgClass(classOf[PowerReport]).power should equal(15.W)
    expectMsgClass(classOf[PowerReport]).power should equal(12.W)
    expectMsgClass(classOf[PowerReport]).power should equal(3.W)

    Await.result(gracefulStop(monitor, timeout.duration), timeout.duration)
    Await.result(gracefulStop(watcher.ref, timeout.duration), timeout.duration)
    _system.shutdown()
    _system.awaitTermination(timeout.duration)
  }

  it can "handle a large number of power reports" in new Bus {
    val _system = ActorSystem("MonitorSuiteTest7", eventListener)

    val muid = UUID.randomUUID()
    val frequency = 25.milliseconds
    val targets = scala.collection.mutable.ListBuffer[Target]()

    val device = "mock"
    val tickMock = ClockTick("ticktest", frequency)

    for(i <- 1 to 50) {
      targets += i
    }

    val monitor = _system.actorOf(Props(classOf[MonitorChild], eventBus, muid, frequency, targets.toList), "monitor7")
    val watcher = TestProbe()(_system)
    watcher.watch(monitor)
    subscribeAggPowerReport(muid)(eventBus)(testActor)

    EventFilter.info(occurrences = 1, source = monitor.path.toString).intercept({
      monitor ! MonitorStart("test", muid, frequency, targets.toList)
    })(_system)

    for(i <- 1 to 150) {
      publishPowerReport(muid, i, (i*3.0).W, device, tickMock)(eventBus)
    }

    EventFilter.info(occurrences = 1, source = monitor.path.toString).intercept({
      monitor ! MonitorStop("test", muid)
    })(_system)

    awaitAssert({
      watcher.expectTerminated(monitor)
    }, 20.seconds)

    expectMsgClass(classOf[PowerReport]).power should equal(3825.W)
    expectMsgClass(classOf[PowerReport]).power should equal(11325.W)
    expectMsgClass(classOf[PowerReport]).power should equal(18825.W)

    Await.result(gracefulStop(monitor, timeout.duration), timeout.duration)
    Await.result(gracefulStop(watcher.ref, timeout.duration), timeout.duration)
    _system.shutdown()
    _system.awaitTermination(timeout.duration)
  }
}