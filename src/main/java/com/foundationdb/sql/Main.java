/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.sql;

import com.foundationdb.server.service.config.ConfigurationService;
import com.foundationdb.server.service.dxl.DXLService;
import com.foundationdb.server.service.servicemanager.GuicedServiceManager;
import com.foundationdb.server.service.session.SessionService;
import com.foundationdb.server.store.Store;
import com.foundationdb.util.GCMonitor;
import com.foundationdb.util.OsUtils;
import com.foundationdb.util.Strings;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.foundationdb.server.error.TapBeanFailureException;
import com.foundationdb.server.manage.ManageMXBean;
import com.foundationdb.server.manage.ManageMXBeanImpl;
import com.foundationdb.server.service.Service;
import com.foundationdb.server.service.ServiceManager;
import com.foundationdb.server.service.jmx.JmxManageable;
import com.foundationdb.util.tap.Tap;

import javax.management.ObjectName;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

public class Main implements Service, JmxManageable, LayerInfoInterface
{
    private static final Logger LOG = LoggerFactory.getLogger(Main.class.getName());

    private static final String VERSION_STRING_FILE = "version/fdbsql_version";
    public static final String VERSION_STRING, SHORT_VERSION_STRING;
    public static final int VERSION_MAJOR, VERSION_MINOR, VERSION_PATCH;
    static {
        String vlong, vshort;
        int major = 0, minor = 0, patch = 0;
        try {
            vlong = Strings.join(Strings.dumpResource(null, VERSION_STRING_FILE));
        } catch (IOException e) {
            LOG.warn("Couldn't read resource file");
            vlong = "Error: " + e;
        }
        int endpos = vlong.indexOf('-');
        if (endpos < 0) endpos = vlong.length();
        vshort = vlong.substring(0, endpos);
        String[] nums = vshort.split("\\.");
        try {
            if (nums.length > 0)
                major = Integer.parseInt(nums[0]);
            if (nums.length > 1)
                minor = Integer.parseInt(nums[1]);
            if (nums.length > 2)
                patch = Integer.parseInt(nums[2]);
        }
        catch (NumberFormatException ex) {
            LOG.warn("Couldn't parse version number: " + vshort);
        }
        VERSION_STRING = vlong;
        SHORT_VERSION_STRING = vshort;
        VERSION_MAJOR = major; 
        VERSION_MINOR = minor; 
        VERSION_PATCH = patch;
    }

    private static final String NAME_PROP = "fdbsql.name";
    private static final String GC_INTERVAL_NAME = "fdbsql.gc_monitor.interval";
    private static final String GC_THRESHOLD_NAME = "fdbsql.gc_monitor.log_threshold_ms";
    private static final String PID_FILE_NAME = System.getProperty("fdbsql.pidfile");

    private static volatile ShutdownMXBeanImpl shutdownBean = null;

    private final JmxObjectInfo jmxObjectInfo;
    private final ConfigurationService config;
    private GCMonitor gcMonitor;

    @Inject
    public Main(Store store, DXLService dxl, SessionService sessionService, ConfigurationService config) {
        this.config = config;
        this.jmxObjectInfo = new JmxObjectInfo(
                "SQLLAYER",
                new ManageMXBeanImpl(store, dxl, sessionService),
                ManageMXBean.class
        );
    }

    @Override
    public void start() {
        int interval = Integer.parseInt(config.getProperty(GC_INTERVAL_NAME));
        int logThreshold = Integer.parseInt(config.getProperty(GC_THRESHOLD_NAME));
        if(interval > 0) {
            gcMonitor = new GCMonitor(interval, logThreshold);
            gcMonitor.start();
        }
        try {
            Tap.registerMXBean();
        } catch (Exception e) {
            throw new TapBeanFailureException (e.getMessage());
        }
    }

    @Override
    public void stop() 
    {
        if(gcMonitor != null) {
            gcMonitor.stopRunning();
            gcMonitor = null;
        }
        try {
            Tap.unregisterMXBean();
        } catch (Exception e) {
            throw new TapBeanFailureException(e.getMessage());
        }
    }
    
    @Override
    public void crash() {
        stop();
    }

    @Override
    public JmxObjectInfo getJmxObjectInfo() {
        return jmxObjectInfo;
    }

    @Override
    public String getServerName()
    {
        return config.getProperty(NAME_PROP);
    }

    @Override
    public String getServerVersion()
    {
        return VERSION_STRING;
    }

    @Override
    public String getServerShortVersion() {
        return SHORT_VERSION_STRING;
    }

    @Override
    public int getServerMajorVersion() {
        return VERSION_MAJOR;
    }

    @Override
    public int getServerMinorVersion() {
        return VERSION_MINOR;
    }

    @Override
    public int getServerPatchVersion() {
        return VERSION_PATCH;
    }

    public interface ShutdownMXBean {
        public void shutdown();
    }

    private static class ShutdownMXBeanImpl implements ShutdownMXBean {
        private static final String BEAN_NAME = "com.foundationdb:type=SHUTDOWN";
        private final ServiceManager sm;

        public ShutdownMXBeanImpl(ServiceManager sm) {
            this.sm = sm;
        }

        @Override
        public void shutdown() {
            try {
                if(sm != null) {
                    sm.stopServices();
                }
            } catch (Exception e) {
                LOG.error("Problem stopping services", e);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            doStartup();
        } catch(Throwable t) {
            LOG.error("Problem starting system", t);
            System.exit(1);
        }

        // Services started successfully, write pid to file.
        try {
            writePid();
        } catch(IOException e) {
            LOG.warn("Problem writing pid file {}", PID_FILE_NAME, e);
            // Do not abort on error as init scripts handle this fine.
        }
    }

    private static void doStartup() throws Exception {
        GuicedServiceManager.BindingsConfigurationProvider bindings = GuicedServiceManager.standardUrls();
        final ServiceManager serviceManager = new GuicedServiceManager(bindings);

        Main.shutdownBean = new ShutdownMXBeanImpl(serviceManager);

        // JVM shutdown hook.
        // Register before startServices() so services are still brought down on startup error.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdownBean.shutdown();
            }
        }, "ShutdownHook"));

        // Bring system up
        serviceManager.startServices();

        ObjectName name = new ObjectName(ShutdownMXBeanImpl.BEAN_NAME);
        ManagementFactory.getPlatformMBeanServer().registerMBean(shutdownBean, name);
    }

    private static void writePid() throws IOException {
        if (PID_FILE_NAME != null) {
            File pidFile = new File(PID_FILE_NAME);
            pidFile.deleteOnExit();
            FileWriter out = new FileWriter(pidFile);
            out.write(OsUtils.getProcessID());
            out.flush();
        }
    }


    /**
     * Start from procrun.
     * @see <a href="http://commons.apache.org/daemon/procrun.html">Daemon: Procrun</a>
     */
    private static final Semaphore PROCRUN_SEMAPHORE = new Semaphore(0);

    private static boolean isProcrunJVMMode(String[] args) {
        if(args.length != 1) {
            String message = "Expected exactly one argument (StartMode)";
            LOG.error("{}: {}", message, Arrays.toString(args));
            throw new IllegalArgumentException(message);
        }
        return "jvm".equals(args[0]);
    }

    @SuppressWarnings("unused") // Called by procrun
    public static void procrunStart(String[] args) throws Exception {
        boolean jvmMode = isProcrunJVMMode(args);
        main(args);
        if(jvmMode) {
            PROCRUN_SEMAPHORE.acquire();
        }
    }

    @SuppressWarnings("unused") // Called by procrun
    public static void procrunStop(String[] args) throws Exception {
        boolean jvmMode = isProcrunJVMMode(args);
        // Stop server from another thread.
        shutdownBean.shutdown();
        if(jvmMode) {
            PROCRUN_SEMAPHORE.release();
        }
    }
}