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

import java.util.concurrent.TimeUnit;

/**
 * A task which is executed after the delay specified with
 * {@link Timer#newTimeout(TimerTask, long, TimeUnit)} (TimerTask, long, TimeUnit)}.
 *
 * 代表调度器要执行的任务
 */
public interface TimerTask {

    /***
     *  在指定的延迟之后，会通过timeout执行指定的任务
     * @param timeout 是指被指派用于在指定时间后执行指定任务的对象
     * @throws Exception
     */
    void run(Timeout timeout) throws Exception;
}