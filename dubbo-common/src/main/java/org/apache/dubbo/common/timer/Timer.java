/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.dubbo.common.timer;

import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Schedules {@link TimerTask}s for one-time future execution in a background
 * thread.
 * 这个接口是一个调度的核心接口，从注释可以看出，它主要用于在后台执行一次性的调度。
 */
public interface Timer {

    /***
     * 调度指定的TimerTask在指定的延迟之后执行一次
     * @param task 需要执行的任务
     * @param delay 延迟的时间
     * @param unit 时间的单位
     * @throws IllegalStateException 如果调度器Timer已经停止了
     * @throws RejectedExecutionException 如果过期的超时任务Timeout太多了，则创建一个新的timeout会导致系统很不稳定，所以要拒绝
     * @return  返回与指定任务关联的handler
     *
     */
    Timeout newTimeout(TimerTask task, long delay, TimeUnit unit);

    /***
     * 停止调度器的运行，会释放当前调度器获取的所有资源，并且取消被调度但是还未执行的任务
     * @return
     */
    Set<Timeout> stop();

    /**
     * the timer is stop
     *
     * @return true for stop
     * 它有一个isStop方法，用来判断这个调度器是否停止运行
     */
    boolean isStop();
}