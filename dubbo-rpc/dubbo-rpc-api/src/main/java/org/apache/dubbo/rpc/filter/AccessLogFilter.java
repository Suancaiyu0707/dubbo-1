/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.support.AccessLogData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.Constants.ACCESS_LOG_KEY;

/**
 * Record access log for the service.
 * <p>
 * Logger key is <code><b>dubbo.accesslog</b></code>.
 * In order to configure access log appear in the specified appender only, additivity need to be configured in log4j's
 * config file, for example:
 * <code>
 * <pre>
 * &lt;logger name="<b>dubbo.accesslog</b>" <font color="red">additivity="false"</font>&gt;
 *    &lt;level value="info" /&gt;
 *    &lt;appender-ref ref="foo" /&gt;
 * &lt;/logger&gt;
 * </pre></code>
 */

/***
 * 使用方是服务提供者
 * 用于打印每一次请求的访问日志。会创建一个定时任务，每隔5秒刷新一次日志
 */
@Activate(group = PROVIDER, value = ACCESS_LOG_KEY)
public class AccessLogFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(AccessLogFilter.class);

    private static final String LOG_KEY = "dubbo.accesslog";

    private static final int LOG_MAX_BUFFER = 5000;

    private static final long LOG_OUTPUT_INTERVAL = 5000;

    private static final String FILE_DATE_FORMAT = "yyyyMMdd";

    // It's safe to declare it as singleton since it runs on single thread only
    private static final DateFormat FILE_NAME_FORMATTER = new SimpleDateFormat(FILE_DATE_FORMAT);

    private static final Map<String, Set<AccessLogData>> LOG_ENTRIES = new ConcurrentHashMap<String, Set<AccessLogData>>();

    private static final ScheduledExecutorService LOG_SCHEDULED = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("Dubbo-Access-Log", true));

    /**
     * Default constructor initialize demon thread for writing into access log file with names with access log key
     * defined in url <b>accesslog</b>
     * 创建一个定时任务，每隔5秒刷新一次日志
     */
    public AccessLogFilter() {
        LOG_SCHEDULED.scheduleWithFixedDelay(this::writeLogToFile, LOG_OUTPUT_INTERVAL, LOG_OUTPUT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * This method logs the access log for service method invocation call.
     *
     * @param invoker service
     * @param inv     Invocation service method.
     * @return Result from service method.
     * @throws RpcException
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {
        try {
            //获得提供者配置日志key,通过accesslog属性指定
            String accessLogKey = invoker.getUrl().getParameter(ACCESS_LOG_KEY);
            //如果配置了accesslog，则创建一条调用日志
            if (ConfigUtils.isNotEmpty(accessLogKey)) {
                //创建一条日志
                AccessLogData logData = buildAccessLogData(invoker, inv);
                //根据accessLogKey聚合日志
                log(accessLogKey, logData);
            }
        } catch (Throwable t) {
            logger.warn("Exception in AccessLogFilter of service(" + invoker + " -> " + inv + ")", t);
        }
        return invoker.invoke(inv);
    }

    /**
     * 将日志写入缓冲区(缓冲区最大只有5000个，满了直接就丢弃掉)
     * @param accessLog 日志key
     * @param accessLogData  日志key需要添加的数据
     *
     */
    private void log(String accessLog, AccessLogData accessLogData) {
        Set<AccessLogData> logSet = LOG_ENTRIES.computeIfAbsent(accessLog, k -> new ConcurrentHashSet<>());

        if (logSet.size() < LOG_MAX_BUFFER) {
            logSet.add(accessLogData);
        } else {
            //TODO we needs use force writing to file so that buffer gets clear and new log can be written.
            logger.warn("AccessLog buffer is full skipping buffer ");
        }
    }

    /***
     * 1、如果缓冲区LOG_ENTRIES不为空
     * 2、如果缓冲区不为空，遍历缓冲区，并将日志写到磁盘中
     */
    private void writeLogToFile() {
        if (!LOG_ENTRIES.isEmpty()) {
            for (Map.Entry<String, Set<AccessLogData>> entry : LOG_ENTRIES.entrySet()) {
                try {
                    String accessLog = entry.getKey();
                    Set<AccessLogData> logSet = entry.getValue();
                    if (ConfigUtils.isDefault(accessLog)) {
                        processWithServiceLogger(logSet);
                    } else {
                        File file = new File(accessLog);
                        createIfLogDirAbsent(file);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Append log to " + accessLog);
                        }
                        renameFile(file);
                        processWithAccessKeyLogger(logSet, file);
                    }

                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }

    private void processWithAccessKeyLogger(Set<AccessLogData> logSet, File file) throws IOException {
        try (FileWriter writer = new FileWriter(file, true)) {
            for (Iterator<AccessLogData> iterator = logSet.iterator();
                 iterator.hasNext();
                 iterator.remove()) {
                writer.write(iterator.next().getLogMessage());
                writer.write(System.getProperty("line.separator"));
            }
            writer.flush();
        }
    }

    /***
     * 创建调用日志
     * @param invoker
     * @param inv
     * @return
     */
    private AccessLogData buildAccessLogData(Invoker<?> invoker, Invocation inv) {
        AccessLogData logData = AccessLogData.newLogData();
        logData.setServiceName(invoker.getInterface().getName());//接口名称
        logData.setMethodName(inv.getMethodName());//方法名称
        logData.setVersion(invoker.getUrl().getParameter(VERSION_KEY));//版本
        logData.setGroup(invoker.getUrl().getParameter(GROUP_KEY));//group组信息
        logData.setInvocationTime(new Date());//日期
        logData.setTypes(inv.getParameterTypes());//参数类型
        logData.setArguments(inv.getArguments());//参数值
        return logData;
    }

    private void processWithServiceLogger(Set<AccessLogData> logSet) {
        for (Iterator<AccessLogData> iterator = logSet.iterator();
             iterator.hasNext();
             iterator.remove()) {
            AccessLogData logData = iterator.next();
            LoggerFactory.getLogger(LOG_KEY + "." + logData.getServiceName()).info(logData.getLogMessage());
        }
    }

    private void createIfLogDirAbsent(File file) {
        File dir = file.getParentFile();
        if (null != dir && !dir.exists()) {
            dir.mkdirs();
        }
    }

    private void renameFile(File file) {
        if (file.exists()) {
            String now = FILE_NAME_FORMATTER.format(new Date());
            String last = FILE_NAME_FORMATTER.format(new Date(file.lastModified()));
            if (!now.equals(last)) {
                File archive = new File(file.getAbsolutePath() + "." + last);
                file.renameTo(archive);
            }
        }
    }
}
