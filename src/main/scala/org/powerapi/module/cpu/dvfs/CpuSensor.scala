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
package org.powerapi.module.cpu.dvfs

import org.powerapi.core.{TimeInStates, MessageBus, OSHelper}
import org.powerapi.module.Cache

/**
 * CPU sensor component that collects data from a /proc and /sys directories
 * which are typically presents under a Linux platform.
 *
 * @see http://www.kernel.org/doc/man-pages/online/pages/man5/proc.5.html
 *
 * @author Aurélien Bourdon <aurelien.bourdon@gmail.com>
 * @author Maxime Colmant <maxime.colmant@gmail.com>
 */
class CpuSensor(eventBus: MessageBus, osHelper: OSHelper) extends org.powerapi.module.cpu.simple.CpuSensor(eventBus, osHelper) {
  import org.powerapi.core.{All,Application,Process, TargetUsageRatio}
  import org.powerapi.core.MonitorChannel.MonitorTick
  import org.powerapi.module.CacheKey
  import org.powerapi.module.cpu.UsageMetricsChannel.publishUsageReport
  import scala.reflect.ClassTag

  lazy val frequenciesCache = new Cache[TimeInStates]

  def timeInStates(monitorTick: MonitorTick): TimeInStates = {
    val key = CacheKey(monitorTick.muid, monitorTick.target)

    val processClaz = implicitly[ClassTag[Process]].runtimeClass
    val appClaz = implicitly[ClassTag[Application]].runtimeClass

    val now = osHelper.getTimeInStates
    val old = frequenciesCache.getOrElse(key, now)
    val diffTimeInStates = now - old

    if(diffTimeInStates.times.count(_._2 < 0) == 0) {
      frequenciesCache.update(key, now)
      diffTimeInStates
    }
    else TimeInStates(Map())
  }

  override def sense(monitorTick: MonitorTick): Unit = {
    publishUsageReport(monitorTick.muid, monitorTick.target, targetCpuUsageRatio(monitorTick), timeInStates(monitorTick), monitorTick.tick)(eventBus)
  }
}