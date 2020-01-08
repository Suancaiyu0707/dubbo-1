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

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link Timer} optimized for approximated(近似的) I/O timeout scheduling.
 *
 * <h3>Tick Duration</h3>
 * <p>
 * As described with 'approximated', this timer does not execute the scheduled
 * {@link TimerTask} on time.  {@link HashedWheelTimer}, on every tick(每一刻度), will
 * check if there are any {@link TimerTask}s behind the schedule and execute
 * them.
 * <p>
 * You can increase or decrease the accuracy（准确性） of the execution timing by
 * specifying smaller or larger tick duration in the constructor.  In most
 * network applications, I/O timeout does not need to be accurate(精确).  Therefore,
 * the default tick duration is 100 milliseconds and you will not need to try
 * different configurations in most cases.
 *
 * <h3>Ticks per Wheel (Wheel Size)</h3>
 * <p>
 * {@link HashedWheelTimer} maintains a data structure called 'wheel'.
 * To put simply, a wheel is a hash table of {@link TimerTask}s whose hash
 * function is 'dead line of the task'.  The default number of ticks per wheel
 * (i.e. the size of the wheel) is 512.  You could specify a larger value
 * if you are going to schedule a lot of timeouts.
 *
 * <h3>Do not create many instances.</h3>
 * <p>
 * {@link HashedWheelTimer} creates a new thread whenever it is instantiated and
 * started.  Therefore, you should make sure to create only one instance and
 * share it across your application.  One of the common mistakes, that makes
 * your application unresponsive, is to create a new instance for every connection.
 *
 * <h3>Implementation Details</h3>
 * <p>
 * {@link HashedWheelTimer} is based on
 * <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="http://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 *
 * 不提供准确的定时执行任务的功能，也就是不能指定几点几分几秒准时执行某个任务，而是在每个tick（也就是时间轮的一个“时间槽”）中，
 * 检测是否存在TimerTask已经落后于当前时间，如果是则执行它。
 * 我们可以通过设定更小或更大的tick duration（时间槽的持续时间），来提高或降低执行时间的准确率。
 */
public class HashedWheelTimer implements Timer {

    /**
     * may be in spi?
     */
    public static final String NAME = "hased";

    private static final Logger logger = LoggerFactory.getLogger(HashedWheelTimer.class);
    // 实例计数器，用于记录创建了多少个本类的对象
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
    // 用于对象数超过限制时的告警
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();
    // 实例上限
    private static final int INSTANCE_COUNT_LIMIT = 64;
    // 原子化更新workState变量的工具
    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");
    // 推动时间轮运转的执行类
    private final Worker worker = new Worker();
    // 绑定的执行线程
    private final Thread workerThread;
    // WORKER初始化状态
    private static final int WORKER_STATE_INIT = 0;
    // WORKER已开始状态
    private static final int WORKER_STATE_STARTED = 1;
    // WORKER已停止状态
    private static final int WORKER_STATE_SHUTDOWN = 2;

    /**
     * 0 - init, 1 - started, 2 - shut down
     */
    @SuppressWarnings({"unused", "FieldMayBeFinal"})
    private volatile int workerState;
    // 时间槽持续时间
    private final long tickDuration;
    // 时间槽数组
    private final HashedWheelBucket[] wheel;
    // 计算任务应该放到哪个时间槽时使用的掩码
    private final int mask;
    // 线程任务同步工具
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);
    // 保存任务调度的队列
    private final Queue<HashedWheelTimeout> timeouts = new LinkedBlockingQueue<>();
    // 已取消的任务调度队列
    private final Queue<HashedWheelTimeout> cancelledTimeouts = new LinkedBlockingQueue<>();
    // 等待中的任务调度数量
    private final AtomicLong pendingTimeouts = new AtomicLong(0);
    // 最大等待任务调度数量
    private final long maxPendingTimeouts;
    // 时间轮绑定的遍历线程启动时候的初始时间
    private volatile long startTime;

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}), default tick duration, and
     * default number of ticks per wheel.
     */
    public HashedWheelTimer() {
        this(Executors.defaultThreadFactory());
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}) and default number of ticks
     * per wheel.
     *
     * @param tickDuration the duration between tick
     * @param unit         the time unit of the {@code tickDuration}
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit) {
        this(Executors.defaultThreadFactory(), tickDuration, unit);
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}).
     *
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
    }

    /**
     * Creates a new timer with the default tick duration and default number of
     * ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public HashedWheelTimer(ThreadFactory threadFactory) {
        this(threadFactory, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new timer with the default number of ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this(threadFactory, tickDuration, unit, 512);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, -1);
    }

    /***
     * 创建一个新的调度器
     * @param threadFactory 一个用于创建执行TimerTask的线程的ThreadFactory
     * @param tickDuration 每个时间轮的每个槽的时间跨度
     * @param unit 每个槽的时间跨度的单位
     * @param ticksPerWheel 时间轮的大小(也就是包含多少个时间单元格)
     * @param maxPendingTimeouts 设置挂起超时的Timeout对象的最大数量，在此之后对newTimeout的调用将导致抛出RejectedExecutionException。如果该值为0或负数，则不假定最大未决超时限制。
     */
    public HashedWheelTimer(ThreadFactory threadFactory,long tickDuration,
                            TimeUnit unit, int ticksPerWheel,long maxPendingTimeouts) {
        //创建线程的工厂
        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }//时间单位
        if (unit == null) {
            throw new NullPointerException("unit");
        }//时间轮里每个slot槽位的时间间隔
        if (tickDuration <= 0) {
            throw new IllegalArgumentException("tickDuration must be greater than 0: " + tickDuration);
        }
        if (ticksPerWheel <= 0) {//时间轮中的slot数
            throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }
        //创建一个具有指定长度的HashedWheelBucket数组，长度=ticksPerWheel
        wheel = createWheel(ticksPerWheel);
        mask = wheel.length - 1;

        // 将每个时间槽slot的时间快读转换成nanos
        this.tickDuration = unit.toNanos(tickDuration);

        // Prevent overflow.
        if (this.tickDuration >= Long.MAX_VALUE / wheel.length) {
            throw new IllegalArgumentException(String.format(
                    "tickDuration: %d (expected: 0 < tickDuration in nanos < %d",
                    tickDuration, Long.MAX_VALUE / wheel.length));
        }//创建一个工作线程
        workerThread = threadFactory.newThread(worker);
        //设置挂起超时的Timeout对象的最大数量，在此之后对newTimeout的调用将导致抛出RejectedExecutionException。
        // 如果该值为0或负数，则不假定最大未决超时限制。
        this.maxPendingTimeouts = maxPendingTimeouts;
        //HashedWheelTimer不能太多，因为HashedWheelTimer本身是可以共享的，则会发出错误告警。
        if (INSTANCE_COUNTER.incrementAndGet() > INSTANCE_COUNT_LIMIT &&
                WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
            reportTooManyInstances();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            // This object is going to be GCed and it is assumed the ship has sailed to do a proper shutdown. If
            // we have not yet shutdown then we want to make sure we decrement the active instance count.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }
        }
    }
    //创建一个具有 ticksPerWheel 个slot的时间轮
    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException(
                    "ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }
        if (ticksPerWheel > 1073741824) {
            throw new IllegalArgumentException(
                    "ticksPerWheel may not be greater than 2^30: " + ticksPerWheel);
        }
        //时间轮槽数准成2的n次方
        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
        for (int i = 0; i < wheel.length; i++) {//维护一个具有ticksPerWheel元素的数组
            wheel[i] = new HashedWheelBucket();
        }
        return wheel;
    }

    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = ticksPerWheel - 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 2;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 4;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 8;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 16;
        return normalizedTicksPerWheel + 1;
    }

    /**
     * 此方法也很简单，就是启动定时器背后的执行线程，同时利用CountLatchDown等待startTime初始化为0，这里为什么要等待为0呢？
     * 答案就是上面的newTimeout方法中，在start之后会用到这个startTime，如果它没有初始化完成的化，计算会有问题。
     */
    public void start() {
        switch (WORKER_STATE_UPDATER.get(this)) {
            case WORKER_STATE_INIT:
                if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
                    //是绑定这个调度器的worker线程，用于不断的轮询时间轮
                    workerThread.start();
                }
                break;
            case WORKER_STATE_STARTED:
                break;
            case WORKER_STATE_SHUTDOWN:
                throw new IllegalStateException("cannot be started once stopped");
            default:
                throw new Error("Invalid WorkerState");
        }

        // 等待worker初始化完startTime后，才真正启动
        while (startTime == 0) {
            try {
                startTimeInitialized.await();
            } catch (InterruptedException ignore) {
                // Ignore - it will be ready very soon.
            }
        }
    }

    @Override
    public Set<Timeout> stop() {
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(
                    HashedWheelTimer.class.getSimpleName() +
                            ".stop() cannot be called from " +
                            TimerTask.class.getSimpleName());
        }

        if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
            // workerState can be 0 or 2 at this moment - let it always be 2.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }

            return Collections.emptySet();
        }

        try {
            boolean interrupted = false;
            while (workerThread.isAlive()) {
                workerThread.interrupt();
                try {
                    workerThread.join(100);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            INSTANCE_COUNTER.decrementAndGet();
        }
        return worker.unprocessedTimeouts();
    }

    @Override
    public boolean isStop() {
        return WORKER_STATE_SHUTDOWN == WORKER_STATE_UPDATER.get(this);
    }

    /***
     * 创建一个延迟等待执行的任务包装对象
     * @param task 需要执行的任务
     * @param delay 延迟的时间
     * @param unit 时间的单位
     * @return
     * 1、任务的基本信息的校验
     * 2、计算当前挂起等待的任务时候否超标了
     * 3、如果时间轮绑定的线程workerThread还没启动，则先启动workerThread线程，并记录下启动时间(startTime)，也就是时间轮里0号槽的时间
     * 4、根据任务下一次执行的时间和启动时间的差，计算当前待执行的任务应该挂在哪个时间槽里，这样当workerThread遍历到当前时间槽，就可以拿出执行了
     *
     */
    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        //等待超时的任务的个数
        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();
        //如果挂起等待执行的任务超过最大限制，则采用拒绝策略并抛出拒绝异常
        if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
            pendingTimeouts.decrementAndGet();
            throw new RejectedExecutionException("Number of pending timeouts ("
                    + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending "
                    + "timeouts (" + maxPendingTimeouts + ")");
        }
        //启动时间轮，这里要注意，并不是每次都会启动，只有在状态是初始化时候才会启动，所以只会启动一次
        //启动就意味着，和当前时间轮绑定的线程workerThread开始从时间轮的第一个槽开始工作了
        start();
        //startTime是 workerThread启动的开始时间，所以通过当前时间和初始时间的差，来计算当前的Timeout任务应该放在哪个slot里
        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

        if (delay > 0 && deadline < 0) {
            deadline = Long.MAX_VALUE;
        }
        //计算当前的Timeout任务应该放在哪个slot里
        HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
        timeouts.add(timeout);
        return timeout;
    }

    /**
     * Returns the number of pending timeouts of this {@link Timer}.
     */
    public long pendingTimeouts() {
        return pendingTimeouts.get();
    }
    //HashedWheelTimer是一个必须在JVM中重用的共享资源。所以只要创建几个实例就可以
    private static void reportTooManyInstances() {
        String resourceType = ClassUtils.simpleClassName(HashedWheelTimer.class);
        logger.error("You are creating too many " + resourceType + " instances. " +
                resourceType + " is a shared resource that must be reused across the JVM," +
                "so that only a few instances are created.");
    }

    private final class Worker implements Runnable {
        //没有处理的任务调度集合
        private final Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();
        //当前执行的tick（也就是当前执行到哪个时间槽了）
        private long tick;

        @Override
        public void run() {
            // 记录启动这个线程的时间
            startTime = System.nanoTime();//当前时间
            if (startTime == 0) {
                startTime = 1;//主要是为了和0区分开，因为0代表是未初始化
            }
            //唤醒在 startTimeInitialized 上等待的线程
            startTimeInitialized.countDown();
            //当定时器一直是已启动的状态时，不断地推进tick前进
            do {
                //等待下一个tick的到来
                final long deadline = waitForNextTick();
                if (deadline > 0) {//tick到来之后
                    //计算tick对应时间槽数组中的那个槽（这里tick&mask，就相当于对时间槽数组的长度取模运算）
                    int idx = (int) (tick & mask);
                    //处理已取消任务调度队列
                    processCancelledTasks();
                    //获取当前时间槽
                    HashedWheelBucket bucket = wheel[idx];
                    //将待处理任务队列中的任务放到它们应该放的槽中
                    transferTimeoutsToBuckets();
                    //执行当前时间槽执行它包含的任务，这个expireTimeouts会去调度任务真正的执行
                    bucket.expireTimeouts(deadline);
                    tick++;
                }
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);

            // 若时间轮已被停止
            for (HashedWheelBucket bucket : wheel) {//清理所有时间槽中的未处理任务调度
                bucket.clearTimeouts(unprocessedTimeouts);
            }
            //清理待处理任务调度队列，将未取消的加入到未处理集合中
            for (; ; ) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                if (!timeout.isCancelled()) {
                    unprocessedTimeouts.add(timeout);
                }
            }
            //处理已取消的任务调度队列
            processCancelledTasks();
        }

        private void transferTimeoutsToBuckets() {
            //这里只循环有限次，是为了防止待处理队列过大，导致这一次添加到对应槽的过程太过耗时
            for (int i = 0; i < 100000; i++) {
                //从待处理任务调度队列中取出第一个任务，进行校验
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) {
                    // Was cancelled in the meantime.
                    continue;
                }
                //根据取出的待处理任务调度，计算出一个槽
                long calculated = timeout.deadline / tickDuration;
                //设置此任务调度的remaininRounds（剩余圈数），因为时间轮是一个轮，所以可能会有还需要过几圈的时间才能执行到的任务
                timeout.remainingRounds = (calculated - tick) / wheel.length;
                //取计算出的槽和当前槽中的较大者，并进行取模
                final long ticks = Math.max(calculated, tick);
                int stopIndex = (int) (ticks & mask);
                //将此任务调度加入对应的槽中
                HashedWheelBucket bucket = wheel[stopIndex];
                bucket.addTimeout(timeout);
            }
        }

        private void processCancelledTasks() {
            for (; ; ) {
                HashedWheelTimeout timeout = cancelledTimeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                try {
                    timeout.remove();
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("An exception was thrown while process a cancellation task", t);
                    }
                }
            }
        }

        /***
         * 当下一个tick到了，才会返回这个时间
         * @return
         */
        private long waitForNextTick() {
            //计算下一个tick到来的是多久
            long deadline = tickDuration * (tick + 1);//tick初始是0

            for (; ; ) {
                //计算当前时间 距离下一次到来的时间还要多久
                final long currentTime = System.nanoTime() - startTime;
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;
                //如果时间已经过了
                if (sleepTimeMs <= 0) {//表示下一个时间轮的时间早就到了
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        return currentTime;
                    }
                }
                if (isWindows()) {
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                }

                try {
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ignored) {
                    if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
                        return Long.MIN_VALUE;
                    }
                }
            }
        }

        Set<Timeout> unprocessedTimeouts() {
            return Collections.unmodifiableSet(unprocessedTimeouts);
        }
    }

    /***
     *这个类逻辑比较简单，基本都是赋值或读取值的操作，或者是委托给HashedWheelBucket这个类进行操作
     */
    private static final class HashedWheelTimeout implements Timeout {
        // 初始化状态
        private static final int ST_INIT = 0;
        // 已取消状态
        private static final int ST_CANCELLED = 1;
        // 已超时状态
        private static final int ST_EXPIRED = 2;
        // state属性获取器
        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");
        // 生成当前Timeout的调度器
        private final HashedWheelTimer timer;
        // 当前Timeout绑定的调度任务
        private final TimerTask task;
        // 截止时间
        private final long deadline;

        @SuppressWarnings({"unused", "FieldMayBeFinal", "RedundantFieldInitialization"})
        private volatile int state = ST_INIT;

        /**
         * RemainingRounds will be calculated and set by Worker.transferTimeoutsToBuckets() before the
         * HashedWheelTimeout will be added to the correct HashedWheelBucket.
         */
        long remainingRounds;

        /**
         * 一个HashedWheelBucket会挂载多个HashedWheelTimeout，这个next和prev就是用于实现一个双向链表的结构,
         * 这样同属于一个HashedWheelBucket的HashedWheelTimeout就可以以双向链表的形式挂载在HashedWheelBucket上了
         */
        HashedWheelTimeout next;
        HashedWheelTimeout prev;

        /**
         * The bucket to which the timeout was added
         */
        HashedWheelBucket bucket;

        HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        /***
         * 将任务Timeout的状态设置为取消，并在取消队列里添加当前任务
         * @return
         */
        @Override
        public boolean cancel() {
            // only update the state it will be removed from HashedWheelBucket on next tick.
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }
            // If a task should be canceled we put this to another queue which will be processed on each tick.
            // So this means that we will have a GC latency of max. 1 tick duration which is good enough. This way
            // we can make again use of our MpscLinkedQueue and so minimize the locking / overhead as much as possible.
            timer.cancelledTimeouts.add(this);
            return true;
        }

        /***
         * 将自己从HashedWheelBucket槽中移除
         */
        void remove() {
            HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                bucket.remove(this);
            } else {
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        public boolean compareAndSetState(int expected, int state) {
            return STATE_UPDATER.compareAndSet(this, expected, state);
        }

        public int state() {
            return state;
        }

        @Override
        public boolean isCancelled() {
            return state() == ST_CANCELLED;
        }

        @Override
        public boolean isExpired() {
            return state() == ST_EXPIRED;
        }

        /***
         * 则开始执行这个任务
         */
        public void expire() {
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) {
                return;
            }

            try {
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        @Override
        public String toString() {
            final long currentTime = System.nanoTime();
            long remaining = deadline - currentTime + timer.startTime;
            String simpleClassName = ClassUtils.simpleClassName(this.getClass());

            StringBuilder buf = new StringBuilder(192)
                    .append(simpleClassName)
                    .append('(')
                    .append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining)
                        .append(" ns later");
            } else if (remaining < 0) {
                buf.append(-remaining)
                        .append(" ns ago");
            } else {
                buf.append("now");
            }

            if (isCancelled()) {
                buf.append(", cancelled");
            }

            return buf.append(", task: ")
                    .append(task())
                    .append(')')
                    .toString();
        }
    }

    /**
     * Bucket that stores HashedWheelTimeouts. These are stored in a linked-list like datastructure to allow easy
     * removal of HashedWheelTimeouts in the middle. Also the HashedWheelTimeout act as nodes themself and so no
     * extra object creation is needed.
     */
    /***
     * 每一个HashedWheelBucket维护了一个双向链表
     */
    private static final class HashedWheelBucket {

        /**
         * Used for the linked-list datastructure
         */
        private HashedWheelTimeout head;
        private HashedWheelTimeout tail;

        /**
         *
         * 双向链表的添加操作。
         */
        void addTimeout(HashedWheelTimeout timeout) {
            assert timeout.bucket == null;
            timeout.bucket = this;
            if (head == null) {
                head = tail = timeout;
            } else {
                tail.next = timeout;
                timeout.prev = tail;
                tail = timeout;
            }
        }

        /**
         * 这个方法就是实际将一个时间槽中所有挂载的任务调度执行的方法。
         * 可以看出逻辑也比较简单，就是从头遍历timeout的双向链表，
         * 对每一个timeout进行处理，处理的流程就是，先判断剩余圈数是否小于等于0，如果是，
         * 再判断它的截止时间是否小于当前截止时间，如果小于则进行expire，实际也就是包含了执行这个任务的操作
         * @param deadline
         */
        void expireTimeouts(long deadline) {
            HashedWheelTimeout timeout = head;

            // process all timeouts
            while (timeout != null) {
                HashedWheelTimeout next = timeout.next;
                //判断剩余圈数是否小于等于0
                if (timeout.remainingRounds <= 0) {//如果剩余圈数是否小于等于0
                    next = remove(timeout);
                    if (timeout.deadline <= deadline) {//判断它的截止时间是否小于当前截止时间
                        //开始执行这个任务
                        timeout.expire();
                    } else {
                        // The timeout was placed into a wrong slot. This should never happen.
                        throw new IllegalStateException(String.format(
                                "timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
                    }
                } else if (timeout.isCancelled()) {
                    next = remove(timeout);
                } else {
                    timeout.remainingRounds--;
                }
                timeout = next;
            }
        }

        /**
         * 双向链表的删除操作。
         * @param timeout
         * @return
         */
        public HashedWheelTimeout remove(HashedWheelTimeout timeout) {
            HashedWheelTimeout next = timeout.next;
            // remove timeout that was either processed or cancelled by updating the linked-list
            if (timeout.prev != null) {
                timeout.prev.next = next;
            }
            if (timeout.next != null) {
                timeout.next.prev = timeout.prev;
            }

            if (timeout == head) {
                // if timeout is also the tail we need to adjust the entry too
                if (timeout == tail) {
                    tail = null;
                    head = null;
                } else {
                    head = next;
                }
            } else if (timeout == tail) {
                // if the timeout is the tail modify the tail to be the prev node.
                tail = timeout.prev;
            }
            // null out prev, next and bucket to allow for GC.
            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         * Clear this bucket and return all not expired / cancelled {@link Timeout}s.
         */
        void clearTimeouts(Set<Timeout> set) {
            for (; ; ) {
                HashedWheelTimeout timeout = pollTimeout();
                if (timeout == null) {
                    return;
                }
                if (timeout.isExpired() || timeout.isCancelled()) {
                    continue;
                }
                set.add(timeout);
            }
        }

        private HashedWheelTimeout pollTimeout() {
            HashedWheelTimeout head = this.head;
            if (head == null) {
                return null;
            }
            HashedWheelTimeout next = head.next;
            if (next == null) {
                tail = this.head = null;
            } else {
                this.head = next;
                next.prev = null;
            }

            // null out prev and next to allow for GC.
            head.next = null;
            head.prev = null;
            head.bucket = null;
            return head;
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.US).contains("win");
    }
}
