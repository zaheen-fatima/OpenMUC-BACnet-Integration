//package org.openmuc.framework.app.bacnetdemo;
//
//import org.openmuc.framework.data.BooleanValue;
//import org.openmuc.framework.data.FloatValue;
//import org.openmuc.framework.data.Record;
//import org.openmuc.framework.data.Value;
//import org.openmuc.framework.dataaccess.Channel;
//import org.openmuc.framework.dataaccess.DataAccessService;
//import org.openmuc.framework.dataaccess.RecordListener;
//import org.osgi.service.component.annotations.Activate;
//import org.osgi.service.component.annotations.Component;
//import org.osgi.service.component.annotations.Deactivate;
//import org.osgi.service.component.annotations.Reference;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.util.Random;
//import java.util.concurrent.Executors;
//import java.util.concurrent.ScheduledExecutorService;
//import java.util.concurrent.TimeUnit;
//
//@Component(immediate = true, service = BacnetApp.class)
//public class BacnetApp {
//
//    private static final Logger logger = LoggerFactory.getLogger(BacnetApp.class);
//
//    private DataAccessService dataAccessService;
//
//    // Server channels (generate random values)
//    private Channel serverFloatChannel;
//    private Channel serverBoolChannel;
//
//    // Client channels (read server values)
//    private Channel clientFloatChannel;
//    private Channel clientBoolChannel;
//
//    private ScheduledExecutorService scheduler;
//    private final Random random = new Random();
//
//    @Reference
//    protected void setDataAccessService(DataAccessService service) {
//        this.dataAccessService = service;
//    }
//
//    protected void unsetDataAccessService(DataAccessService service) {
//        this.dataAccessService = null;
//    }
//
//    @Activate
//    public void activate() {
//        logger.info("BACnetApp activated.");
//
//        try {
//            // -------------------
//            // Server channels
//            // -------------------
//            serverFloatChannel = dataAccessService.getChannel("ServerFloat");
//            serverBoolChannel = dataAccessService.getChannel("ServerBool");
//
//            // -------------------
//            // Client channels
//            // -------------------
//            clientFloatChannel = dataAccessService.getChannel("ClientFloat");
//            clientBoolChannel = dataAccessService.getChannel("ClientBool");
//
//            if (serverFloatChannel == null || serverBoolChannel == null ||
//                    clientFloatChannel == null || clientBoolChannel == null) {
//                logger.error("BACnet channels not found in channels.xml.");
//                return;
//            }
//
//            // -------------------
//            // Scheduler: generate server values every 2 seconds
//            // -------------------
//            scheduler = Executors.newScheduledThreadPool(1);
//            scheduler.scheduleAtFixedRate(this::updateServerChannels, 0, 2, TimeUnit.SECONDS);
//
//            // -------------------
//            // Listener: when clientFloatChannel updates, write boolean to clientBoolChannel
//            // -------------------
//            clientFloatChannel.addListener(new RecordListener() {
//                @Override
//                public void newRecord(Record record) {
//                    if (record == null || record.getValue() == null) {
//                        logger.warn("Received null record from client float channel.");
//                        return;
//                    }
//
//                    Value value = record.getValue();
//                    logger.info("Client float channel updated: {}", value);
//
//                    try {
//                        boolean boolValue = value.asFloat() > 50; // threshold example
//                        clientBoolChannel.write(new BooleanValue(boolValue));
//                        logger.info("Wrote boolean value {} to clientBoolChannel", boolValue);
//                    } catch (Exception e) {
//                        logger.error("Error writing to clientBoolChannel", e);
//                    }
//                }
//            });
//
//            logger.info("Scheduler started and client listener registered.");
//
//        } catch (Exception e) {
//            logger.error("Error initializing BACnetApp", e);
//        }
//    }
//
//    // -------------------
//    // Generate random values on server channels
//    // -------------------
//    private void updateServerChannels() {
//        try {
//            float newFloatValue = random.nextFloat() * 100;  // 0-100
//            boolean newBoolValue = random.nextBoolean();
//
//            serverFloatChannel.write(new FloatValue(newFloatValue));
//            serverBoolChannel.write(new BooleanValue(newBoolValue));
//
//            logger.info("Server channels updated: Float={}, Bool={}", newFloatValue, newBoolValue);
//        } catch (Exception e) {
//            logger.error("Error updating server channels", e);
//        }
//    }
//
//    @Deactivate
//    public void deactivate() {
//        logger.info("BACnetApp deactivated.");
//        if (scheduler != null && !scheduler.isShutdown()) {
//            scheduler.shutdownNow();
//        }
//    }
//}
package org.openmuc.framework.app.bacnetdemo;

import org.openmuc.framework.data.BooleanValue;
import org.openmuc.framework.data.FloatValue;
import org.openmuc.framework.dataaccess.Channel;
import org.openmuc.framework.dataaccess.DataAccessService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
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
            // -------------------
            // Get channels from channels.xml
            // -------------------
            serverFloatChannel = dataAccessService.getChannel("ServerFloat");
            serverBoolChannel = dataAccessService.getChannel("ServerBool");

            clientFloatChannel = dataAccessService.getChannel("ClientFloat");
            clientBoolChannel = dataAccessService.getChannel("ClientBool");

            if (serverFloatChannel == null || serverBoolChannel == null ||
                    clientFloatChannel == null || clientBoolChannel == null) {
                logger.error("BACnet channels not found in channels.xml.");
                return;
            }

            // -------------------
            // Scheduler: update server channels every 2 seconds
            // -------------------
            scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(this::updateServerAndClientChannels, 0, 2, TimeUnit.SECONDS);

            logger.info("Scheduler started: server updates will propagate to client channels.");

        } catch (Exception e) {
            logger.error("Error initializing BACnetApp", e);
        }
    }

    // -------------------
    // Update server and client channels
    // -------------------
    private void updateServerAndClientChannels() {
        try {
            float newFloatValue = random.nextFloat() * 100; // 0-100
            boolean newBoolValue = random.nextBoolean();

            // Write to server channels
            serverFloatChannel.write(new FloatValue(newFloatValue));
            serverBoolChannel.write(new BooleanValue(newBoolValue));

            // Write same values to client channels
            clientFloatChannel.write(new FloatValue(newFloatValue));
            clientBoolChannel.write(new BooleanValue(newBoolValue));

            logger.info("Server channels updated: Float={}, Bool={}", newFloatValue, newBoolValue);
            logger.info("Client channels updated: Float={}, Bool={}", newFloatValue, newBoolValue);

        } catch (Exception e) {
            logger.error("Error updating channels", e);
        }
    }

    @Deactivate
    public void deactivate() {
        logger.info("BACnetApp deactivated.");
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }
}
