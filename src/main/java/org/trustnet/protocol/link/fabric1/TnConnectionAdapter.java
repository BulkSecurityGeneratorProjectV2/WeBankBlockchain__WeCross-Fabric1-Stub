package org.trustnet.protocol.link.fabric1;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wecross.common.FabricType;
import com.webank.wecross.stub.ObjectMapperFactory;
import com.webank.wecross.stub.Request;
import com.webank.wecross.stub.ResourceInfo;
import com.webank.wecross.stub.Response;
import com.webank.wecross.stub.fabric.ChaincodeEventManager;
import com.webank.wecross.stub.fabric.FabricConnection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trustnet.protocol.common.STATUS;
import org.trustnet.protocol.link.Connection;
import org.trustnet.protocol.network.Resource;

public class TnConnectionAdapter implements Connection {
    private static Logger logger = LoggerFactory.getLogger(TnConnectionAdapter.class);

    private com.webank.wecross.stub.Connection wecrossConnection;
    private ChaincodeEventManager eventManager;
    private static ExecutorService executor = Executors.newFixedThreadPool(1);
    private ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();
    private Map<String, Resource> resourceConfigs = new HashMap<>();

    public static int SUCCESS = 0;
    public static int ON_RESOURCES_CHANGE = 1001;
    public static int ON_CHAINCODEVENT = 1002;

    public TnConnectionAdapter(com.webank.wecross.stub.Connection wecrossConnection) {
        this.wecrossConnection = wecrossConnection;

        if (wecrossConnection instanceof FabricConnection) {
            logger.info("Init ChaincodeEventManager");
            eventManager =
                    new ChaincodeEventManager(((FabricConnection) wecrossConnection).getChannel());
        }
    }

    @Override
    public void start() throws RuntimeException {}

    @Override
    public void stop() throws RuntimeException {}

    @Override
    public void asyncSend(String path, int type, byte[] data, Callback callback) {

        if (type == TnDefault.GET_PROPERTIES) {
            handleGetProperties(callback);
        } else {
            handleNormalSend(path, type, data, callback);
        }
    }

    @Override
    public void subscribe(int type, byte[] data, Callback callback) {
        if (type == ON_RESOURCES_CHANGE) {

            wecrossConnection.setConnectionEventHandler(
                    new com.webank.wecross.stub.Connection.ConnectionEventHandler() {
                        @Override
                        public void onResourcesChange(List<ResourceInfo> resourceInfos) {
                            try {
                                for (ResourceInfo resourceInfo : resourceInfos) {
                                    // write luyu-connection config into properties
                                    Resource resourceConfig =
                                            resourceConfigs.get(resourceInfo.getName());
                                    if (resourceConfig != null) {
                                        resourceInfo
                                                .getProperties()
                                                .put("methods", resourceConfig.getMethods());
                                        if (resourceConfig.getProperties() != null) {
                                            for (Map.Entry entry :
                                                    resourceConfig.getProperties().entrySet()) {
                                                resourceInfo
                                                        .getProperties()
                                                        .put(entry.getKey(), entry.getValue());
                                            }
                                        }
                                    }
                                }

                                byte[] resourceInfosBytes =
                                        objectMapper.writeValueAsBytes(resourceInfos);

                                callback.onResponse(SUCCESS, "success", resourceInfosBytes);
                            } catch (Exception e) {
                                logger.error("Handle ON_RESOURCES_CHANGE event exception: ", e);
                            }
                        }
                    });

        } else if (type == ON_CHAINCODEVENT) {
            if (eventManager != null) {
                try {
                    String resourceName = new String(data);
                    logger.debug("handle register chain event, resourceName {}", resourceName);
                    eventManager.registerEvent(
                            resourceName,
                            new ChaincodeEventManager.ChaincodeEvent() {

                                @Override
                                public void onEvent(String name, byte[] data) {
                                    callback.onResponse(STATUS.OK, "success", data);
                                }
                            });
                } catch (Exception e) {
                    callback.onResponse(
                            STATUS.INTERNAL_ERROR,
                            "register chaincode event failed: " + e.getMessage(),
                            null);
                }
            }

        } else {
            logger.error("Unrecognized subscribe type: {}", type);
        }
    }

    private void handleNormalSend(String path, int type, byte[] data, Callback callback) {
        Request request = new Request();
        try {
            request = objectMapper.readValue(data, new TypeReference<Request>() {});

        } catch (Exception e) {
            callback.onResponse(
                    FabricType.TransactionResponseStatus.REQUEST_DECODE_EXCEPTION,
                    "TnConnectionAdapter decode exception" + e.getMessage(),
                    null);
            return;
        }
        wecrossConnection.asyncSend(
                request,
                new com.webank.wecross.stub.Connection.Callback() {
                    @Override
                    public void onResponse(Response response) {
                        callback.onResponse(
                                response.getErrorCode(),
                                response.getErrorMessage(),
                                response.getData());
                    }
                });
    }

    private void handleGetProperties(Callback callback) {
        try {
            Map<String, String> properties = wecrossConnection.getProperties();
            byte[] propertiesBytes = objectMapper.writeValueAsBytes(properties);

            executor.submit(
                    () -> {
                        callback.onResponse(
                                FabricType.TransactionResponseStatus.SUCCESS,
                                "success",
                                propertiesBytes);
                    });
        } catch (Exception e) {
            executor.submit(
                    () -> {
                        callback.onResponse(
                                FabricType.TransactionResponseStatus.GET_PROPERTIES_FAILED,
                                e.getMessage(),
                                new byte[] {});
                    });
        }
    }

    public void addTnResourceConfig(String name, Resource resource) {
        resourceConfigs.put(name, resource);
    }
}
