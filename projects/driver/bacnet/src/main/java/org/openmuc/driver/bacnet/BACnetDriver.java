package org.openmuc.driver.bacnet;

import java.net.InetAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.openmuc.framework.config.ArgumentSyntaxException;
import org.openmuc.framework.config.ConfigService;
import org.openmuc.framework.config.DeviceConfig;
import org.openmuc.framework.config.DeviceScanInfo;
import org.openmuc.framework.config.DriverConfig;
import org.openmuc.framework.config.DriverInfo;
import org.openmuc.framework.config.ScanException;
import org.openmuc.framework.config.ScanInterruptedException;
import org.openmuc.framework.driver.spi.Connection;
import org.openmuc.framework.driver.spi.ConnectionException;
import org.openmuc.framework.driver.spi.DriverDeviceScanListener;
import org.openmuc.framework.driver.spi.DriverService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.exception.BACnetTimeoutException;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkUtils;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.util.DiscoveryUtils;
import org.osgi.service.component.annotations.Component;

@Component(
        service = DriverService.class,
        property = {
                "driverId=bacnet",
                "driverName=BACnet Driver"
        }
)
public class BACnetDriver implements DriverService {

    private static final Logger logger = LoggerFactory.getLogger(BACnetDriver.class);
    private static final long DEFAULT_DISCOVERY_SLEEP_TIME = 2000;

    private ConfigService configService;
    private final Map<Integer, RemoteDevice> remoteDevices = new ConcurrentHashMap<>();
    private volatile boolean deviceScanInterrupted = false;
    private final Object lock = new Object();

    private static final DriverInfo driverInfo = new DriverInfo(
            "bacnet",
            "BACnet/IP communication protocol driver",
            "BACnet device instance number is used as device address, optional host IP address e.g.: <instance_number>[;<host_ip>]",
            "See https://github.com/openmucextensions/bacnet/wiki/Connect-to-a-device#settings",
            "The technical designation is used as channel address",
            "See https://github.com/openmucextensions/bacnet/wiki/Scan-for-devices#settings"
    );

    public void activate(ComponentContext context, Map<String, Object> properties) {
        logger.info("BACnet communication driver activated");
    }

    public void deactivate(ComponentContext context) {
        LocalDeviceFactory.getInstance().dismissAll();
        logger.info("BACnet communication driver deactivated, all local devices terminated");
    }
    @Reference
    protected void setConfigService(ConfigService cs) {
        this.configService = cs;
    }

    protected void unsetConfigService(ConfigService cs) {
        if (configService == cs) {
            configService = null;
        }
    }

    @Override
    public DriverInfo getInfo() {
        return driverInfo;
    }

    @Override
    public void scanForDevices(String settingsString, DriverDeviceScanListener listener)
            throws UnsupportedOperationException, ArgumentSyntaxException, ScanException, ScanInterruptedException {

        deviceScanInterrupted = false;

        if (!Settings.isValidSettingsString(settingsString)) {
            throw new ArgumentSyntaxException("Settings string is invalid: " + settingsString);
        }

        Settings settings = new Settings(settingsString);
        long discoverySleepTime = getDiscoverySleepTime(settings);
        String broadcastIP = getBroadcastIP(settings);

        if (settings.containsKey(Settings.SETTING_SCAN_PORT)) {
            Integer scanPort = parsePort(settings.get(Settings.SETTING_SCAN_PORT));
            scanAtPort(broadcastIP, scanPort, listener, discoverySleepTime);
        } else if (System.getProperty("org.openmuc.driver.bacnet.port") != null) {
            Integer scanPort = parsePort(System.getProperty("org.openmuc.driver.bacnet.port"));
            scanAtPort(broadcastIP, scanPort, listener, discoverySleepTime);
        } else {
            int progress = 0;
            for (int scanPort = 0xBAC0; scanPort <= 0xBACF; scanPort++) {
                scanAtPort(broadcastIP, scanPort, listener, discoverySleepTime);
                progress += 6;
                if (listener != null) listener.scanProgressUpdate(progress);
                if (deviceScanInterrupted) return;
            }
        }
    }

    @Override
    public void interruptDeviceScan() throws UnsupportedOperationException {
        deviceScanInterrupted = true;
    }

    @Override
    public Connection connect(final String deviceAddress, final String settingsString)
            throws ArgumentSyntaxException, ConnectionException {

        logger.debug("Connecting to device {} with settings {}...", deviceAddress, settingsString);

        if (configService == null) {
            throw new ConnectionException("ConfigService is not set. Cannot connect.");
        }

        DeviceAddress d = parseDeviceAddress(deviceAddress);
        String hostIp = d.hostIp();
        Integer remoteInstance = d.remoteInstance();

        if (!Settings.isValidSettingsString(settingsString)) {
            throw new ArgumentSyntaxException("Settings string is invalid: " + settingsString);
        }
        Settings settings = new Settings(settingsString);

        LocalDevice localDevice;
        boolean isServer;

        boolean timeSync = Boolean.parseBoolean(settings.getOrDefault(Settings.SETTING_TIME_SYNC, "false"));

        try {
            String broadcastIP = settings.getOrDefault(Settings.SETTING_BROADCAST_IP, IpNetwork.DEFAULT_BROADCAST_IP);
            String localBindAddress = settings.get(Settings.SETTING_LOCALBIND_ADDRESS);

            Integer devicePort = null;
            if (settings.containsKey(Settings.SETTING_DEVICE_PORT)) {
                devicePort = parsePort(settings.get(Settings.SETTING_DEVICE_PORT));
            } else if (settings.containsKey(Settings.SETTING_LOCAL_PORT)) {
                devicePort = parsePort(settings.get(Settings.SETTING_LOCAL_PORT));
            }

            Integer localDeviceInstanceNumber = settings.containsKey(Settings.SETTING_LOCAL_DVC_INSTANCENUMBER)
                    ? parseDeviceAddress(settings.get(Settings.SETTING_LOCAL_DVC_INSTANCENUMBER)).remoteInstance()
                    : null;

            isServer = Boolean.parseBoolean(settings.getOrDefault(Settings.SETTING_ISSERVER, "false"));

            localDevice = LocalDeviceFactory.getInstance()
                    .obtainLocalDevice(broadcastIP, localBindAddress, devicePort, localDeviceInstanceNumber, timeSync);

        } catch (Exception e) {
            throw new ConnectionException("Error while getting/creating local device", e);
        }

        if (isServer) {

            final DriverConfig driverConfig = configService.getConfig().getDriver(driverInfo.getId());

            final Optional<DeviceConfig> deviceConfig = driverConfig.getDevices()
                    .stream()
                    .filter(dc -> deviceAddress.equals(dc.getDeviceAddress())
                            && settingsString.equals(dc.getSettings()))
                    .findAny();

            if (!deviceConfig.isPresent()) {
                throw new ConnectionException("Cannot find deviceConfig for address " + deviceAddress);
            }

            return new BACnetServerConnection(localDevice, deviceConfig.get());
        } else {

            if (!remoteDevices.containsKey(remoteInstance)) {
                if (!hostIp.isEmpty()) {
                    addRemoteDevice(remoteInstance, hostIp, settings, localDevice);
                } else {
                    try {
                        Settings scanSettings = new Settings();
                        scanSettings.put(Settings.SETTING_BROADCAST_IP, settings.get(Settings.SETTING_BROADCAST_IP));
                        scanSettings.put(Settings.SETTING_LOCALBIND_ADDRESS, settings.get(Settings.SETTING_LOCALBIND_ADDRESS));
                        scanSettings.put(Settings.SETTING_SCAN_PORT, settings.get(Settings.SETTING_DEVICE_PORT));
                        scanForDevices(scanSettings.toSettingsString(), null);
                    } catch (UnsupportedOperationException ignore) {
                        throw new AssertionError();
                    } catch (ScanException e) {
                        throw new ConnectionException(e.getMessage());
                    } catch (ScanInterruptedException ignore) {
                    }
                }
            }

            RemoteDevice remoteDevice = remoteDevices.get(remoteInstance);
            if (remoteDevice == null)
                throw new ConnectionException("Could not find device " + deviceAddress);

            try {
                DiscoveryUtils.getExtendedDeviceInformation(localDevice, remoteDevice);
            } catch (BACnetException e) {
                throw new ConnectionException("Couldn't reach device " + deviceAddress, e);
            }

            BACnetRemoteConnection connection = new BACnetRemoteConnection(localDevice, remoteDevice);
            Integer writePriority = settings.containsKey(Settings.SETTING_WRITE_PRIORITY)
                    ? parseWritePriority(settings.get(Settings.SETTING_WRITE_PRIORITY))
                    : null;
            connection.setWritePriority(writePriority);

            return connection;
        }
    }

    private void addRemoteDevice(Integer remoteInstance, String hostIp, Settings settings, LocalDevice localDevice)
            throws ArgumentSyntaxException, ConnectionException {

        int port = settings.containsKey(Settings.SETTING_DEVICE_PORT)
                ? parsePort(settings.get(Settings.SETTING_DEVICE_PORT))
                : parsePort(settings.get(Settings.SETTING_REMOTE_PORT));

        Address address = IpNetworkUtils.toAddress(hostIp, port);
        RemoteDevice remoteDevice;
        try {
            remoteDevice = localDevice.findRemoteDevice(address, remoteInstance);
        } catch (BACnetTimeoutException e) {
            throw new ConnectionException("Failed to connect to remote device. Timeout.", e);
        } catch (BACnetException e) {
            throw new ConnectionException(e.getMessage(), e);
        }

        remoteDevices.put(remoteDevice.getInstanceNumber(), remoteDevice);
    }

    private void scanAtPort(String broadcastIP, Integer scanPort, DriverDeviceScanListener listener,
                            long discoverySleepTime) throws ScanException, ScanInterruptedException, ArgumentSyntaxException {

        if (scanPort == null)
            throw new IllegalArgumentException("scanPort must not be null");

        final LocalDevice localDevice;
        try {
            localDevice = LocalDeviceFactory.getInstance().obtainLocalDevice(broadcastIP, null, scanPort, null);
        } catch (Exception e) {
            throw new ScanException("Error while creating local device for scan: " + e.getMessage(), e);
        }

        try {
            localDevice.sendGlobalBroadcast(new WhoIsRequest());
            Thread.sleep(discoverySleepTime);
        } catch (InterruptedException ignore) {
            logger.warn("Device scan interrupted while waiting for responses");
        }

        logger.debug("Found {} remote device(s) from scan at port 0x{}", localDevice.getRemoteDevices().size(),
                Integer.toHexString(scanPort));

        for (RemoteDevice device : localDevice.getRemoteDevices()) {

            InetAddress hostIp = null;
            try {
                hostIp = IpNetworkUtils.getInetAddress(device.getAddress().getMacAddress());
            } catch (IllegalArgumentException e) {
            }

            if (deviceScanInterrupted)
                throw new ScanInterruptedException();

            try {
                DiscoveryUtils.getExtendedDeviceInformation(localDevice, device);
            } catch (BACnetException e) {
                logger.warn("Error reading extended info from device {} {}", device.getInstanceNumber(),
                        (hostIp == null) ? "no ip" : hostIp.getHostAddress());
            }

            remoteDevices.put(device.getInstanceNumber(), device);

            if (listener != null) {
                Settings scanSettings = new Settings();
                if (broadcastIP != null)
                    scanSettings.put(Settings.SETTING_BROADCAST_IP, broadcastIP);
                String deviceAddr = Integer.toString(device.getInstanceNumber());
                if (hostIp != null)
                    deviceAddr += ';' + hostIp.getHostAddress();
                scanSettings.put(Settings.SETTING_DEVICE_PORT, scanPort.toString());
                listener.deviceFound(new DeviceScanInfo(deviceAddr, scanSettings.toSettingsString(), device.getName()));
            }
        }

        if (localDevice.getRemoteDevices().isEmpty()) {
            logger.debug("Dismiss local device {} because no remote devices found",
                    localDevice.getConfiguration().getInstanceId());
            LocalDeviceFactory.getInstance().dismissLocalDevice(scanPort);
        }
    }

    private int parsePort(String port) throws ArgumentSyntaxException {
        try {
            return Integer.decode(port);
        } catch (NumberFormatException e) {
            throw new ArgumentSyntaxException("Port value is not a number: " + port);
        }
    }

    private Integer parseWritePriority(String priority) throws ArgumentSyntaxException {
        try {
            Integer writePriority = Integer.decode(priority);
            if (writePriority < 1 || writePriority > 16) {
                throw new ArgumentSyntaxException("Write priority must be between 1 and 16");
            }
            return writePriority;
        } catch (NumberFormatException e) {
            throw new ArgumentSyntaxException("Write priority value is not a number: " + priority);
        }
    }

    private DeviceAddress parseDeviceAddress(String deviceAddress) throws ArgumentSyntaxException {
        String[] parts = deviceAddress.split(";");
        if (parts.length == 0)
            throw new ArgumentSyntaxException("Device address is empty");

        int remoteInstance;
        try {
            remoteInstance = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            throw new ArgumentSyntaxException("First part of device address is not a number: " + parts[0]);
        }

        if (remoteInstance < 0 || remoteInstance > 4194302) {
            throw new ArgumentSyntaxException("Device instance number must be between 0 and 4194302");
        }

        String hostIp = (parts.length == 2) ? parts[1] : "";
        return new DeviceAddress(hostIp, remoteInstance);
    }

    private long getDiscoverySleepTime(Settings settings) {
        if (settings.containsKey(Settings.SETTING_SCAN_DISCOVERYSLEEPTIME)) {
            try {
                return Long.parseLong(settings.get(Settings.SETTING_SCAN_DISCOVERYSLEEPTIME));
            } catch (NumberFormatException e) {
                logger.warn("Invalid discoverySleepTime, using default {}", DEFAULT_DISCOVERY_SLEEP_TIME);
            }
        }
        return DEFAULT_DISCOVERY_SLEEP_TIME;
    }

    private String getBroadcastIP(Settings settings) {
        return settings.getOrDefault(Settings.SETTING_BROADCAST_IP, IpNetwork.DEFAULT_BROADCAST_IP);
    }

    private static class DeviceAddress {
        private final String hostIp;
        private final Integer remoteInstance;

        DeviceAddress(String hostIp, Integer remoteInstance) {
            this.hostIp = hostIp;
            this.remoteInstance = remoteInstance;
        }

        String hostIp() { return hostIp; }
        Integer remoteInstance() { return remoteInstance; }
    }
}
