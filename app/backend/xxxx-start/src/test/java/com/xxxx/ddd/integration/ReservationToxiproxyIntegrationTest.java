package com.xxxx.ddd.integration;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import org.apache.kafka.common.requests.ApiVersionsRequest;
import org.apache.kafka.common.requests.RequestHeader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import eu.rekawek.toxiproxy.model.ToxicDirection;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@EnabledIfSystemProperty(named = "flashsale.integration", matches = "true")
class ReservationToxiproxyIntegrationTest {

    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withNetwork(NETWORK)
            .withNetworkAliases("reservation-redis")
            .withExposedPorts(6379);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
            .withNetwork(NETWORK)
            .withNetworkAliases("reservation-kafka");

    @Container
    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
            .withNetwork(NETWORK);

    private static ToxiproxyContainer.ContainerProxy redisProxy;
    private static ToxiproxyContainer.ContainerProxy kafkaProxy;
    private static RedisClient client;

    @BeforeAll
    static void verifyProxyControlAndPath() throws Exception {
        redisProxy = TOXIPROXY.getProxy(REDIS, 6379);
        kafkaProxy = TOXIPROXY.getProxy(KAFKA, KafkaContainer.KAFKA_PORT);
        client = RedisClient.create("redis://" + redisProxy.getContainerIpAddress() + ":" + redisProxy.getProxyPort());

        try (var connection = client.connect()) {
            assertThat(connection.sync().ping()).isEqualTo("PONG");
        }
        assertThat(kafkaApiVersions(kafkaProxy.getProxyPort())).isTrue();
        assertThat(TOXIPROXY.getControlPort()).isPositive();
        assertThat(redisProxy.toxics().getAll()).isEmpty();
        assertThat(kafkaProxy.toxics().getAll()).isEmpty();
    }

    @AfterAll
    static void closeProxyClient() {
        if (client != null) {
            client.shutdown();
        }
        NETWORK.close();
    }

    @Test
    void redisProtocolPartitionFailsThroughProxyAndRecoversAfterToxicRemoval() throws Exception {
        redisProxy.toxics().timeout("redis-partition", ToxicDirection.DOWNSTREAM, 0);
        RedisClient partitionedClient = RedisClient.create(
                "redis://" + redisProxy.getContainerIpAddress() + ":" + redisProxy.getProxyPort());
        try {
            assertThatThrownBy(() -> partitionedClient.connect().sync().ping())
                    .isInstanceOf(RedisConnectionException.class);
        } finally {
            partitionedClient.shutdown();
            redisProxy.toxics().get("redis-partition").remove();
        }

        try (var recovered = client.connect()) {
            assertThat(recovered.sync().ping()).isEqualTo("PONG");
        }
        assertThat(redisProxy.toxics().getAll()).isEmpty();
    }

    @Test
    void kafkaProtocolPartitionFailsThroughProxyAndRecoversAfterToxicRemoval() throws Exception {
        kafkaProxy.toxics().timeout("kafka-partition", ToxicDirection.DOWNSTREAM, 0);
        try {
            assertThatThrownBy(() -> kafkaApiVersions(kafkaProxy.getProxyPort()))
                    .isInstanceOf(IOException.class);
        } finally {
            kafkaProxy.toxics().get("kafka-partition").remove();
        }

        assertThat(kafkaApiVersions(kafkaProxy.getProxyPort())).isTrue();
        assertThat(kafkaProxy.toxics().getAll()).isEmpty();
    }

    private static boolean kafkaApiVersions(int port) throws IOException {
        try (Socket socket = new Socket(kafkaProxy.getContainerIpAddress(), port)) {
            socket.setSoTimeout(3_000);
            ApiVersionsRequest request = new ApiVersionsRequest.Builder((short) 0).build((short) 0);
            ByteBuffer payload = request.serializeWithHeader(
                    new RequestHeader(request.apiKey(), request.version(), "reservation-toxiproxy", 1));
            socket.getOutputStream().write(frame(payload));
            socket.getOutputStream().flush();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            int responseSize = input.readInt();
            if (responseSize <= 0 || responseSize > 1_000_000) {
                throw new IOException("invalid Kafka response frame size: " + responseSize);
            }
            byte[] response = input.readNBytes(responseSize);
            if (response.length != responseSize) {
                throw new EOFException("Kafka response frame was truncated");
            }
            return true;
        }
    }

    private static byte[] frame(ByteBuffer payload) {
        ByteBuffer frame = ByteBuffer.allocate(Integer.BYTES + payload.remaining());
        frame.putInt(payload.remaining());
        frame.put(payload.duplicate());
        return frame.array();
    }
}
