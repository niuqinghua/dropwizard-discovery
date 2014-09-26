package io.dropwizard.discovery.core;

import static com.google.common.base.Preconditions.checkNotNull;
import io.dropwizard.discovery.DiscoveryFactory;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Collection;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.curator.x.discovery.ServiceInstanceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.base.Strings;

@ThreadSafe
public class CuratorAdvertiser implements ConnectionStateListener {
    private static final Logger LOGGER = LoggerFactory
            .getLogger(CuratorAdvertiser.class);

    private static final UUID instanceId = UUID.randomUUID();
    private final ServiceDiscovery<InstanceMetadata> discovery;
    private final DiscoveryFactory configuration;

    @GuardedBy("this")
    private String listenAddress;

    @GuardedBy("this")
    private int listenPort = 0;

    @GuardedBy("this")
    private ServiceInstance<InstanceMetadata> instance;

    /**
     * Constructor
     * 
     * @param configuration
     *            {@link DiscoveryFactory}
     * @param discovery
     *            {@link ServiceDiscovery}
     */
    public CuratorAdvertiser(@Nonnull final DiscoveryFactory configuration,
            @Nonnull final ServiceDiscovery<InstanceMetadata> discovery) {
        this.configuration = checkNotNull(configuration);
        this.discovery = checkNotNull(discovery);
    }

    /**
     * Set the listen port and set the listen address from the configuration
     * file or attempt to auto-detect the first IPv4 address that is found.
     * 
     * @param port
     *            port this instance is listening on
     */
    public synchronized void initListenInfo(final int port) {
        listenPort = port;

        if (!Strings.isNullOrEmpty(configuration.getListenAddress())) {
            LOGGER.info("Using '{}' as listenAddress from configuration file",
                    configuration.getListenAddress());
            listenAddress = configuration.getListenAddress();
            return;
        }

        LOGGER.warn("listenAddress not found in configuration file, attempting to auto-detect");

        try {
            final Collection<InetAddress> ips = ServiceInstanceBuilder
                    .getAllLocalIPs();
            for (final InetAddress ip : ips) {
                String ipAddr = String.valueOf(ip);
                // crude hack to look for IPv6 addresses
                if (ipAddr.indexOf(":") != -1) {
                    continue;
                }
                if (ipAddr.startsWith("/")) {
                    ipAddr = ipAddr.substring(1);
                }
                listenAddress = ipAddr;
                LOGGER.info(
                        "Using '{}' as listenAddress from found addresses: {}",
                        listenAddress, ips);
                break;
            }
        } catch (final SocketException e) {
            LOGGER.error("Error getting local IP addresses", e);
        }
    }

    /**
     * Register the instance in Zookeeper
     * 
     * @throws Exception
     */
    public synchronized void registerAvailability() throws Exception {
        registerAvailability(getInstance());
    }

    /**
     * Register a specific instance in Zookeeper
     * 
     * @param instance
     *            Service Instance
     * @throws Exception
     */
    public synchronized void registerAvailability(
            @Nonnull final ServiceInstance<InstanceMetadata> instance)
            throws Exception {
        checkInitialized();
        LOGGER.info("Registering service ({}) at <{}:{}>",
                configuration.getServiceName(), listenAddress, listenPort);

        discovery.registerService(instance);
        LOGGER.debug("Successfully registered service ({}) in ZK",
                configuration.getServiceName());
    }

    /**
     * Remove the instance from Zookeeper
     * 
     * @throws Exception
     */
    public synchronized void unregisterAvailability() throws Exception {
        unregisterAvailability(getInstance());
    }

    /**
     * Remove the specific instance from Zookeeper
     * 
     * @param instance
     *            Service Instance
     * @throws Exception
     */
    public synchronized void unregisterAvailability(
            @Nonnull final ServiceInstance<InstanceMetadata> instance)
            throws Exception {
        checkInitialized();
        LOGGER.info("Unregistering service ({}) at <{}:{}>",
                configuration.getServiceName(), listenAddress, listenPort);

        discovery.unregisterService(instance);
        LOGGER.debug("Successfully unregistered service ({}) from ZK",
                configuration.getServiceName());
    }

    /**
     * Return the instance ID
     * 
     * @return {@link UUID}
     */
    public UUID getInstanceId() {
        return instanceId;
    }

    /**
     * Return the listening port
     * 
     * @return port number
     */
    public int getListenPort() {
        return listenPort;
    }

    /**
     * Return the listening IP address
     * 
     * @return IP address
     */
    public String getListenAddress() {
        return listenAddress;
    }

    /**
     * Return the {@link ServiceInstance} that will be registered with the
     * {@link ServiceDiscovery} instance.
     * 
     * @return {@link ServiceInstance}
     * @throws Exception
     */
    public synchronized ServiceInstance<InstanceMetadata> getInstance()
            throws Exception {
        if (instance != null) {
            return instance;
        }

        final InstanceMetadata metadata = new InstanceMetadata(instanceId,
                listenAddress, listenPort);

        instance = ServiceInstance.<InstanceMetadata> builder()
                .name(configuration.getServiceName()).address(listenAddress)
                .port(listenPort).id(instanceId.toString()).payload(metadata)
                .build();
        return instance;
    }

    /**
     * Check that the {@link #initListenInfo} method has been called by
     * validating that the listenPort is greater than 1.
     * 
     * @throws IllegalStateException
     */
    public void checkInitialized() {
        if (Strings.isNullOrEmpty(listenAddress) || listenPort < 1) {
            throw new IllegalStateException("Not initialized");
        }
    }

    /**
     * TODO - figure out how to register this listener
     */
    @Override
    public void stateChanged(final CuratorFramework client,
            final ConnectionState newState) {
        if (newState == ConnectionState.RECONNECTED) {
            try {
                registerAvailability();
            } catch (final Exception e) {
                LOGGER.error("Unable to register service", e);
            }
        }
    }
}
