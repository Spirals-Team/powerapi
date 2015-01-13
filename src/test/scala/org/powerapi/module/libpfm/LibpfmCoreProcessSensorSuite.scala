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
package org.powerapi.module.libpfm

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.powerapi.UnitTest

class LibpfmCoreProcessSensorSuite(system: ActorSystem) extends UnitTest(system) {
  import akka.actor.Props
  import akka.util.Timeout
  import org.powerapi.core.MessageBus
  import scala.concurrent.duration.DurationDouble

  def this() = this(ActorSystem("LibpfmCoreProcessSensorSuite"))

  val timeout = Timeout(1.seconds)

  override def afterAll() = {
    TestKit.shutdownActorSystem(system)
  }

  trait Bus {
    val eventBus = new MessageBus
  }

  "A LibpfmCoreProcessSensor" should "aggregate the performance counters" ignore new Bus {
    import akka.actor.Terminated
    import akka.pattern.gracefulStop
    import akka.testkit.{TestActorRef, TestProbe}
    import akka.util.Timeout
    import java.util.{BitSet, UUID}
    import org.powerapi.core.{LinuxHelper, Process}
    import org.powerapi.core.ClockChannel.ClockTick
    import org.powerapi.core.MonitorChannel.MonitorTick
    import org.powerapi.module.SensorChannel.monitorAllStopped
    import PerformanceCounterChannel.{PCReport, subscribePCReport}
    import scala.collection.mutable.ArrayBuffer
    import scala.concurrent.{Await, Future}
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.sys.process.stringSeqToProcess

    val configuration = new BitSet()
    configuration.set(0)
    configuration.set(1)
    val events = Array("cycles", "instructions")
    val cores = Map(0 -> List(0, 1))
    val muid1 = UUID.randomUUID()
    val muid2 = UUID.randomUUID()
    val buffer = ArrayBuffer[PCReport]()

    val basepath = getClass.getResource("/").getPath
    val pid1 = Seq("bash", s"${basepath}test-pc.bash").lineStream(0).trim.toInt
    Seq("taskset", "-cp", "0" ,s"$pid1").!
    val pid2 = Seq("bash", s"${basepath}test-pc.bash").lineStream(0).trim.toInt
    Seq("taskset", "-cp", "1" ,s"$pid2").!

    LibpfmHelper.init()

    val reaper = TestProbe()(system)
    val sensor = TestActorRef(Props(classOf[LibpfmCoreProcessSensor], eventBus, Timeout(1.seconds), new LinuxHelper, configuration, events, cores, true), "sensor1")(system)

    subscribePCReport(eventBus)(testActor)

    Seq("kill", "-SIGCONT", s"$pid1").!!
    Seq("kill", "-SIGCONT", s"$pid2").!!
    sensor ! MonitorTick("monitor", muid1, Process(pid1), ClockTick("clock", 1.seconds))
    sensor ! MonitorTick("monitor", muid2, Process(pid2), ClockTick("clock", 1.seconds))
    Thread.sleep(1000)
    sensor ! MonitorTick("monitor", muid1, Process(pid1), ClockTick("clock", 1.seconds))
    buffer += expectMsgClass(classOf[PCReport])
    sensor ! MonitorTick("monitor", muid2, Process(pid2), ClockTick("clock", 1.seconds))
    buffer += expectMsgClass(classOf[PCReport])
    Thread.sleep(1000)
    sensor ! MonitorTick("monitor", muid1, Process(pid1), ClockTick("clock", 1.seconds))
    buffer += expectMsgClass(classOf[PCReport])
    sensor ! MonitorTick("monitor", muid2, Process(pid2), ClockTick("clock", 1.seconds))
    buffer += expectMsgClass(classOf[PCReport])
    Seq("kill", "-SIGKILL", s"$pid1").!!
    Seq("kill", "-SIGKILL", s"$pid2").!!

    buffer.foreach(msg => {
      msg match {
        case PCReport(_, _, target, wrappers, _) => {
          target should (equal(Process(pid1)) or equal(Process(pid2)))
          wrappers.size should equal(cores.size * events.size)
          wrappers.count(_.event == "cycles") should equal(cores.size)
          wrappers.count(_.event == "instructions") should equal(cores.size)
          wrappers.foreach(wrapper => wrapper.values.size should equal(events.size))
        }
      }

      for(wrapper <- msg.wrappers) {
        Future.sequence(wrapper.values) onSuccess {
          case coreValues: List[Long] => {
            val aggValue = coreValues.foldLeft(0l)((acc, value) => acc + value)
            aggValue should be >= 0l
            println(s"muid: ${msg.muid}; core: ${wrapper.core}; target: ${msg.target}; event: ${wrapper.event}; value: $aggValue")
          }
        }
      }
    })

    val children = sensor.children.toArray.clone()
    children.foreach(child => reaper watch child)

    monitorAllStopped()(eventBus)
    for(_ <- 0 until children.size) {
      reaper.expectMsgClass(classOf[Terminated])
    }

    Await.result(gracefulStop(sensor, 1.seconds), 1.seconds)
    LibpfmHelper.deinit()
  }
}