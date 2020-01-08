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

/**
 * A handle associated with a {@link TimerTask} that is returned by a
 * {@link Timer}.
 * Timeout代表的是对一次任务的处理
 */
public interface Timeout {

    /**
     * Returns the {@link Timer} that created this handle.
     * 返回的就是创建这个Timeout的Timer对象，也就是延迟的时间对象
     */
    Timer timer();

    /**
     * Returns the {@link TimerTask} which is associated with this handle.
     * 返回的是这个Timeout要负责处理的任务
     */
    TimerTask task();

    /**
     * Returns {@code true} if and only if the {@link TimerTask} associated
     * with this handle has been expired.
     * isExpired代表的是这个任务是否已经超过它预设的时间
     */
    boolean isExpired();

    /**
     * Returns {@code true} if and only if the {@link TimerTask} associated
     * with this handle has been cancelled.
     * isCancelled是返回是否已取消此任务
     */
    boolean isCancelled();

    /**
     * Attempts to cancel the {@link TimerTask} associated with this handle.
     * If the task has been executed or cancelled already, it will return with
     * no side effect.
     *
     * @return True if the cancellation completed successfully, otherwise false
     * cancel则是取消此任务
     */
    boolean cancel();
}