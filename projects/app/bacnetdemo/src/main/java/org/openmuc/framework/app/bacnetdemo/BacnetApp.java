package org.openmuc.framework.app.bacnetdemo;

import org.openmuc.framework.data.BooleanValue;
import org.openmuc.framework.data.FloatValue;
import org.openmuc.framework.dataaccess.Channel;
import org.openmuc.framework.dataaccess.ChannelState;
import org.openmuc.framework.dataaccess.DataAccessService;
import org.osgi.service.component.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component(immediate = true, service = BacnetApp.class)
public class BacnetApp {

    private static final Logger logger = LoggerFactory.getLogger(BacnetApp.class);

    private DataAccessService dataAccessService;

    private Channel serverFloatChannel;
    private Channel serverBoolChannel;
    private Channel clientFloatChannel;
    private Channel clientBoolChannel;

    private ScheduledExecutorService scheduler;
    private final Random random = new Random();

    private boolean allConnectedLogged = false;

    @Reference
    protected void setDataAccessService(DataAccessService service) {
        this.dataAccessService = service;
    }

    protected void unsetDataAccessService(DataAccessService service) {
        this.dataAccessService = null;
    }

    @Activate
    public void activate() {
        logger.info("BACnetApp activated.");

        try {
            // Server channels
            serverFloatChannel = dataAccessService.getChannel("ServerFloat");
            serverBoolChannel = dataAccessService.getChannel("ServerBool");

            if (serverFloatChannel == null || serverBoolChannel == null) {
                logger.error("Server channels not found.");
                return;
            }

            // Client channels (optional)
            clientFloatChannel = dataAccessService.getChannel("ClientFloat");
            clientBoolChannel = dataAccessService.getChannel("ClientBool");

            // Scheduler to check channel states and update values every 2 seconds
            scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(this::updateChannelsIfConnected, 0, 2, TimeUnit.SECONDS);

        } catch (Exception e) {
            logger.error("Error initializing BACnetApp", e);
        }
    }

    private void updateChannelsIfConnected() {
        try {
            // Check if all required channels are ready (LISTENING for BACnet)
            boolean serverFloatReady = isConnected(serverFloatChannel);
            boolean serverBoolReady = isConnected(serverBoolChannel);
            boolean clientFloatReady = clientFloatChannel == null || isConnected(clientFloatChannel);
            boolean clientBoolReady = clientBoolChannel == null || isConnected(clientBoolChannel);

            if (!serverFloatReady || !serverBoolReady || !clientFloatReady || !clientBoolReady) {
                logger.warn("One or more devices not connected. Current states -> " +
                                "ServerFloat: {}, ServerBool: {}, ClientFloat: {}, ClientBool: {}",
                        channelState(serverFloatChannel),
                        channelState(serverBoolChannel),
                        channelState(clientFloatChannel),
                        channelState(clientBoolChannel));
                allConnectedLogged = false; // reset the flag
                return;
            }

            // Log once when all channels become connected
            if (!allConnectedLogged) {
                logger.info("All devices connected. Starting updates.");
                allConnectedLogged = true;
            }

            // Generate new random values
            float newFloatValue = random.nextFloat() * 100;
            boolean newBoolValue = random.nextBoolean();

            // Update server channels
            serverFloatChannel.write(new FloatValue(newFloatValue));
            serverBoolChannel.write(new BooleanValue(newBoolValue));
            logger.info("Server channels updated: Float={}, Bool={}", newFloatValue, newBoolValue);

            // Update client channels if available
            if (clientFloatChannel != null && clientBoolChannel != null) {
                clientFloatChannel.write(new FloatValue(newFloatValue));
                clientBoolChannel.write(new BooleanValue(newBoolValue));
                logger.info("Client channels updated: Float={}, Bool={}", newFloatValue, newBoolValue);
            }

        } catch (Exception e) {
            logger.error("Error updating channels", e);
        }
    }

    private boolean isConnected(Channel channel) {
        return channel != null && channel.getChannelState() == ChannelState.LISTENING;
    }

    private String channelState(Channel channel) {
        return channel != null ? channel.getChannelState().toString() : "N/A";
    }

    @Deactivate
    public void deactivate() {
        logger.info("BACnetApp deactivated.");
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }
}
