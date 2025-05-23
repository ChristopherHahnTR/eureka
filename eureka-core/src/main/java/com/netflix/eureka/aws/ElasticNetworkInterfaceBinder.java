package com.netflix.eureka.aws;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.net.InetAddresses;
import com.netflix.appinfo.AmazonInfo;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.endpoint.EndpointUtils;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.servo.monitor.Monitors;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.Ec2ClientBuilder;
import software.amazon.awssdk.services.ec2.model.AttachNetworkInterfaceRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeNetworkInterfacesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeNetworkInterfacesResponse;
import software.amazon.awssdk.services.ec2.model.DetachNetworkInterfaceRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterface;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;

/**
 * Amazon ENI binder for instances.
 *
 * Candidate ENI's discovery is done using the same mechanism as Elastic ip binder, via dns records or service urls.
 *
 * The dns records and the service urls should use the ENI private dns or private ip
 *
 * Dns record examples
 *  txt.us-east-1.eureka="us-east-1a.eureka" "us-east-1b.eureka"
 *  txt.us-east-1a.eureka="ip-172-31-y-y.ec2.internal"
 *  txt.us-east-1b.eureka="ip-172-31-x-x.ec2.internal"
 * where "ip-172-31-x-x.ec2.internal" is the ENI private dns
 *
 * Service url example:
 *  eureka.serviceUrl.us-east-1a=http://ip-172-31-x-x.ec2.internal:7001/eureka/v2/
 *
 * ENI Binding strategy should be configured via property like:
 *
 * eureka.awsBindingStrategy=ENI
 *
 * If there are no available ENI's for the availability zone, it will not attach any already attached ENI
 */
public class ElasticNetworkInterfaceBinder implements AwsBinder {
    private static final Logger logger = LoggerFactory.getLogger(ElasticNetworkInterfaceBinder.class);
    private static final int IP_BIND_SLEEP_TIME_MS = 1000;
    private static final Timer timer = new Timer("Eureka-ElasticNetworkInterfaceBinder", true);

    private final EurekaServerConfig serverConfig;
    private final EurekaClientConfig clientConfig;
    private final PeerAwareInstanceRegistry registry;
    private final ApplicationInfoManager applicationInfoManager;

    @Inject
    public ElasticNetworkInterfaceBinder(
            EurekaServerConfig serverConfig,
            EurekaClientConfig clientConfig,
            PeerAwareInstanceRegistry registry,
            ApplicationInfoManager applicationInfoManager) {
        this.serverConfig = serverConfig;
        this.clientConfig = clientConfig;
        this.registry = registry;
        this.applicationInfoManager = applicationInfoManager;
        try {
            Monitors.registerObject(this);
        } catch (Throwable e) {
            logger.warn("Cannot register the JMX monitor for the InstanceRegistry", e);
        }
    }

    @PostConstruct
    public void start()  {
        int retries = serverConfig.getEIPBindRebindRetries();
        for (int i = 0; i < retries; i++) {
            try {
                if (alreadyBound()) {
                    break;
                } else {
                    bind();
                }
            } catch (Throwable e) {
                logger.error("Cannot bind to IP", e);
                try {
                    Thread.sleep(IP_BIND_SLEEP_TIME_MS);
                } catch (InterruptedException e1) {
                    throw new RuntimeException(e1);
                }
            }
        }
        // Schedule a timer which periodically checks for IP binding.
        timer.schedule(new IPBindingTask(), serverConfig.getEIPBindingRetryIntervalMsWhenUnbound());
    }

    @PreDestroy
    public void shutdown() {
        timer.cancel();
        for (int i = 0; i < serverConfig.getEIPBindRebindRetries(); i++) {
            try {
                unbind();
                break;
            } catch (Exception e) {
                logger.warn("Cannot unbind the IP from the instance");
                try {
                    Thread.sleep(IP_BIND_SLEEP_TIME_MS);
                } catch (InterruptedException e1) {
                    throw new RuntimeException(e1);
                }
            }
        }
    }


    public boolean alreadyBound() throws MalformedURLException {
        InstanceInfo myInfo = applicationInfoManager.getInfo();
        String myInstanceId = ((AmazonInfo) myInfo.getDataCenterInfo()).get(AmazonInfo.MetaDataKey.instanceId);
        Ec2Client ec2Service = getEC2Service();
        List<InstanceNetworkInterface> instanceNetworkInterfaces = instanceData(myInstanceId, ec2Service).networkInterfaces();
        List<String> candidateIPs = getCandidateIps();
        for (String ip : candidateIPs) {
            for (InstanceNetworkInterface ini : instanceNetworkInterfaces) {
                if (ip.equals(ini.privateIpAddress())) {
                    logger.info("My instance {} seems to be already associated with the ip {}", myInstanceId, ip);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Binds an ENI to the instance.
     *
     * The candidate ENI's are deduced in the same wa the EIP binder works: Via dns records or via service urls,
     * depending on configuration.
     *
     * It will try to attach the first ENI that is:
     *      Available
     *      For this subnet
     *      In the list of candidate ENI's
     *
     * @throws MalformedURLException
     */
    public void bind() throws MalformedURLException {
        InstanceInfo myInfo = ApplicationInfoManager.getInstance().getInfo();
        String myInstanceId = ((AmazonInfo) myInfo.getDataCenterInfo()).get(AmazonInfo.MetaDataKey.instanceId);
        String myZone = ((AmazonInfo) myInfo.getDataCenterInfo()).get(AmazonInfo.MetaDataKey.availabilityZone);

        final List<String> ips = getCandidateIps();
        Ordering<NetworkInterface> ipsOrder = Ordering.natural().onResultOf(new Function<NetworkInterface, Integer>() {
            public Integer apply(NetworkInterface networkInterface) {
                return ips.indexOf(networkInterface.privateIpAddress());
            }
        });

        Ec2Client ec2Service = getEC2Service();
        String subnetId = instanceData(myInstanceId, ec2Service).subnetId();

        DescribeNetworkInterfacesResponse result = ec2Service.describeNetworkInterfaces(DescribeNetworkInterfacesRequest.builder()
                .filters(Filter.builder().name("private-ip-address").values(ips).build(),
                        Filter.builder().name("status").values("available").build(),
                        Filter.builder().name("subnet-id").values(subnetId).build())
                .build());

        if (result.networkInterfaces().isEmpty()) {
            logger.info("No ip is free to be associated with this instance. Candidate ips are: {} for zone: {}", ips, myZone);
        } else {
            NetworkInterface selected = ipsOrder.min(result.networkInterfaces());
            ec2Service.attachNetworkInterface(AttachNetworkInterfaceRequest.builder()
                    .networkInterfaceId(selected.networkInterfaceId())
                    .deviceIndex(1)
                    .instanceId(myInstanceId)
                    .build());
        }
    }

    /**
     * Unbind the IP that this instance is associated with.
     */
    public void unbind() throws Exception {
        InstanceInfo myInfo = applicationInfoManager.getInfo();
        String myInstanceId = ((AmazonInfo) myInfo.getDataCenterInfo()).get(AmazonInfo.MetaDataKey.instanceId);

        Ec2Client ec2 = getEC2Service();

        List<InstanceNetworkInterface> result = instanceData(myInstanceId, ec2).networkInterfaces();

        List<String> ips = getCandidateIps();

        for (InstanceNetworkInterface networkInterface : result) {
            if (ips.contains(networkInterface.privateIpAddress())) {
                String attachmentId = networkInterface.attachment().attachmentId();
                ec2.detachNetworkInterface(DetachNetworkInterfaceRequest.builder()
                        .attachmentId(attachmentId)
                        .build());
                break;
            }
        }
    }


    private Instance instanceData(String myInstanceId, Ec2Client ec2) {
        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .instanceIds(myInstanceId)
                .build();
        DescribeInstancesResponse response = ec2.describeInstances(request);
        return response.reservations().get(0).instances().get(0);
    }

    /**
     * Based on shouldUseDnsForFetchingServiceUrls configuration, either retrieves candidates from dns records or from
     * configuration properties.
     *
     *
     */
    public List<String> getCandidateIps() throws MalformedURLException {
        InstanceInfo myInfo = applicationInfoManager.getInfo();
        String myZone = ((AmazonInfo) myInfo.getDataCenterInfo()).get(AmazonInfo.MetaDataKey.availabilityZone);

        Collection<String> candidates = clientConfig.shouldUseDnsForFetchingServiceUrls()
                ? getIPsForZoneFromDNS(myZone)
                : getIPsForZoneFromConfig(myZone);

        if (candidates == null || candidates.size() == 0) {
            throw new RuntimeException("Could not get any ips from the pool for zone :" + myZone);
        }
        List<String> ips = Lists.newArrayList();

        for(String candidate : candidates) {
            String host = new URL(candidate).getHost();
            if (InetAddresses.isInetAddress(host)) {
                ips.add(host);
            } else {
                // ip-172-31-55-172.ec2.internal -> ip-172-31-55-172
                String firstPartOfHost = Splitter.on(".").splitToList(host).get(0);
                // ip-172-31-55-172 -> [172,31,55,172]
                List<String> noIpPrefix = Splitter.on("-").splitToList(firstPartOfHost).subList(1, 5);
                // [172,31,55,172] -> 172.31.55.172
                String ip = Joiner.on(".").join(noIpPrefix);
                if (InetAddresses.isInetAddress(ip)) {
                    ips.add(ip);
                } else {
                    throw new IllegalArgumentException("Illegal internal hostname " + host + " translated to '" + ip + "'");
                }
            }
        }
        return ips;
    }


    private Collection<String> getIPsForZoneFromConfig(String myZone) {
        return clientConfig.getEurekaServerServiceUrls(myZone);
    }


    private Collection<String> getIPsForZoneFromDNS(String myZone) {
        return EndpointUtils.getServiceUrlsFromDNS(
                clientConfig,
                myZone,
                true,
                new EndpointUtils.InstanceInfoBasedUrlRandomizer(applicationInfoManager.getInfo())
        );
    }

    private Ec2Client getEC2Service() {
        String awsAccessId = serverConfig.getAWSAccessId();
        String awsSecretKey = serverConfig.getAWSSecretKey();

        final Ec2ClientBuilder ec2ServiceBuilder = Ec2Client.builder()
                .region(Region.of(clientConfig.getRegion().trim().toLowerCase()));
        if (awsAccessId != null && !awsAccessId.isEmpty() && awsSecretKey != null && !awsSecretKey.isEmpty()) {
            AwsBasicCredentials awsBasicCredentials = AwsBasicCredentials.create(awsAccessId, awsSecretKey);
            ec2ServiceBuilder.credentialsProvider(StaticCredentialsProvider.create(awsBasicCredentials));
        }
        else {
            ec2ServiceBuilder.credentialsProvider(InstanceProfileCredentialsProvider.create());
        }
        return ec2ServiceBuilder.build();
    }

    private class IPBindingTask extends TimerTask {
        @Override
        public void run() {
            boolean alreadyBound = false;
            try {
                alreadyBound = alreadyBound();
                // If the ip is not bound, the registry could  be stale. First sync up the registry from the
                // neighboring node before trying to bind the IP
                if (!alreadyBound) {
                    registry.clearRegistry();
                    int count = registry.syncUp();
                    registry.openForTraffic(applicationInfoManager, count);
                } else {
                    // An ip is already bound
                    return;
                }
                bind();
            } catch (Throwable e) {
                logger.error("Could not bind to IP", e);
            } finally {
                if (alreadyBound) {
                    timer.schedule(new IPBindingTask(), serverConfig.getEIPBindingRetryIntervalMs());
                } else {
                    timer.schedule(new IPBindingTask(), serverConfig.getEIPBindingRetryIntervalMsWhenUnbound());
                }
            }
        }
    }
}
