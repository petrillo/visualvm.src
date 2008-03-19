/*
 * Copyright 2007-2008 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

package com.sun.tools.visualvm.jvm;


import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.tools.visualvm.application.Application;
import com.sun.tools.visualvm.application.MonitoredData;
import com.sun.tools.visualvm.core.datasupport.DataRemovedListener;
import com.sun.tools.visualvm.core.options.GlobalPreferences;
import com.sun.tools.visualvm.tools.jmx.JvmJmxModel;
import com.sun.tools.visualvm.tools.jmx.JvmJmxModelFactory;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.lang.management.LockInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MonitorInfo;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import javax.management.InstanceNotFoundException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.swing.Timer;
import org.openide.ErrorManager;
import org.openide.util.RequestProcessor;

/**
 *
 * @author Tomas Hurka
 */
public class JmxSupport implements DataRemovedListener {
    private static final String HOTSPOT_DIAGNOSTIC_MXBEAN_NAME =
            "com.sun.management:type=HotSpotDiagnostic";
    private static final String PERM_GEN = "Perm Gen";
    private static final String PS_PERM_GEN = "PS Perm Gen";
    private Application application;
    private JvmJmxModel jmxModel;
    private JVMImpl jvm;
    // HotspotDiagnostic
    private boolean hotspotDiagnosticInitialized;
    private Object hotspotDiagnosticLock = new Object();
    private HotSpotDiagnosticMXBean hotspotDiagnosticMXBean;
    private Timer timer;
    private MemoryPoolMXBean permGenPool;
    
    JmxSupport(Application app,JVMImpl vm) {
        jvm = vm;
        application = app;
        app.notifyWhenRemoved(this);
    }
    
    RuntimeMXBean getRuntime() {
        JvmJmxModel jmx = getJmxModel();
        if (jmx != null) return jmx.getRuntimeMXBean();
        return null;
    }
    
    synchronized JvmJmxModel getJmxModel() {
        if (jmxModel == null) {
            jmxModel = JvmJmxModelFactory.getJvmJmxModelFor(application);
        }
        return jmxModel;
    }
    
    Properties getSystemProperties() {
        RuntimeMXBean runtime = getRuntime();
        if (runtime != null) {
            Properties prop = new Properties();
            prop.putAll(runtime.getSystemProperties());
            return prop;
        }
        return null;
    }
    
    String getJvmArgs() {
        RuntimeMXBean runtime = getRuntime();
        if (runtime != null) {
            StringBuilder buf = new StringBuilder();
            List<String> args = runtime.getInputArguments();
            for (String arg : args) {
                buf.append(arg).append(' ');
            }
            return buf.toString();
        }
        return null;
    }
    
    HotSpotDiagnosticMXBean getHotSpotDiagnostic() {
        synchronized (hotspotDiagnosticLock) {
            if (hotspotDiagnosticInitialized) {
                return hotspotDiagnosticMXBean;
            }
            JvmJmxModel jmx = getJmxModel();
            if (jmx != null) {
                try {
                    hotspotDiagnosticMXBean = jmx.getMXBean(
                            ObjectName.getInstance(HOTSPOT_DIAGNOSTIC_MXBEAN_NAME),
                            HotSpotDiagnosticMXBean.class);
                } catch (MalformedObjectNameException e) {
                    ErrorManager.getDefault().log(ErrorManager.WARNING,
                            "Couldn't find HotSpotDiagnosticMXBean: " +
                            e.getLocalizedMessage());
                } catch (IllegalArgumentException ex) {
                    ErrorManager.getDefault().notify(ErrorManager.INFORMATIONAL,ex);
                } 
            }
            hotspotDiagnosticInitialized = true;
            return hotspotDiagnosticMXBean;
        }
    }
    
    String takeThreadDump() {
        JvmJmxModel jmx = getJmxModel();
        RuntimeMXBean runtimeMXBean;
        ThreadMXBean threadMXBean;
        
        if (jmx == null) {
            return null;
        }
        runtimeMXBean = getRuntime();
        threadMXBean = jmx.getThreadMXBean();
        if (runtimeMXBean == null || threadMXBean == null) {
            return null;
        }
        StringBuffer sb = new StringBuffer(4096);
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sb.append(df.format(new Date()) + "\n");
        sb.append("Full thread dump " + jvm.getVMName() +
                " (" + jvm.getVmVersion() + " " +
                jvm.getVMInfo() + "):\n");
        if (jvm.is15()) {
            long[] threadIds = threadMXBean.getAllThreadIds();
            for (long threadId : threadIds) {
                ThreadInfo thread = threadMXBean.getThreadInfo(threadId, Integer.MAX_VALUE);
                if (thread != null) {
                    sb.append("\n\"" + thread.getThreadName() +
                            "\" - Thread t@" + thread.getThreadId() + "\n");
                    sb.append("   java.lang.Thread.State: " + thread.getThreadState());
                    if (thread.getLockName() != null) {
                        sb.append(" on " + thread.getLockName());
                        if (thread.getLockOwnerName() != null) {
                            sb.append(" owned by: " + thread.getLockOwnerName());
                        }
                    }
                    sb.append("\n");
                    for (StackTraceElement st : thread.getStackTrace()) {
                        sb.append("        at " + st.toString() + "\n");
                    }
                }
            }
        } else {
            ThreadInfo[] threads = threadMXBean.dumpAllThreads(true, true);
            for (ThreadInfo thread : threads) {
                MonitorInfo[] monitors = null;
                if (threadMXBean.isObjectMonitorUsageSupported()) {
                    monitors = thread.getLockedMonitors();
                }
                sb.append("\n\"" + thread.getThreadName() +
                        "\" - Thread t@" + thread.getThreadId() + "\n");
                sb.append("   java.lang.Thread.State: " + thread.getThreadState());
                if (thread.getLockName() != null) {
                    sb.append(" on " + thread.getLockName());
                    if (thread.getLockOwnerName() != null) {
                        sb.append(" owned by: " + thread.getLockOwnerName());
                    }
                }
                sb.append("\n");
                int index = 0;
                for (StackTraceElement st : thread.getStackTrace()) {
                    sb.append("\tat " + st.toString() + "\n");
                    if (monitors != null) {
                        for (MonitorInfo mi : monitors) {
                            if (mi.getLockedStackDepth() == index) {
                                sb.append("\t- locked " + mi.toString() + "\n");
                            }
                        }
                    }
                    index++;
                }
                if (threadMXBean.isSynchronizerUsageSupported()) {
                    sb.append("\n   Locked ownable synchronizers:");
                    LockInfo[] synchronizers = thread.getLockedSynchronizers();
                    if (synchronizers != null || synchronizers.length == 0) {
                        sb.append("\n\t- None\n");
                    } else {
                        for (LockInfo li : synchronizers) {
                            sb.append("\n\t- locked " + li.toString() + "\n");
                        }
                    }
                }
            }
        }
        return sb.toString();
    }
    
    MemoryPoolMXBean getPermGenPool() {
        if (permGenPool == null) {
            Collection<MemoryPoolMXBean> pools = getJmxModel().getMemoryPoolMXBeans();
            for (MemoryPoolMXBean pool : pools) {
                if (pool.getType().equals(MemoryType.NON_HEAP) &&
                        (PERM_GEN.equals(pool.getName()) ||
                        PS_PERM_GEN.equals(pool.getName()))) {
                    permGenPool = pool;
                    break;
                }
            }
        }
        return permGenPool;
    }
    
    void initTimer() {
        int interval = GlobalPreferences.sharedInstance().getMonitoredDataPoll() * 1000;
        final JvmJmxModel jmx = getJmxModel();
        timer = new Timer(interval, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                RequestProcessor.getDefault().post(new Runnable() {
                    public void run() {
                        MonitoredData data = new MonitoredDataImpl(jvm,JmxSupport.this,jmx);
                        jvm.notifyListeners(data);
                    }
                });
            }
        });
        timer.setCoalesce(true);
        timer.start();
    }
    
    void disableTimer() {
        if (timer != null) {
            timer.stop();
        }
    }
    
    public void dataRemoved(Object dataSource) {
        disableTimer();
    }
    
}
