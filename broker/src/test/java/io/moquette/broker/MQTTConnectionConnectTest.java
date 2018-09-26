package io.moquette.broker;

import io.moquette.persistence.MemoryStorageService;
import io.moquette.spi.ISessionsStore;
import io.moquette.spi.impl.MockAuthenticator;
import io.moquette.spi.impl.SessionsRepository;
import io.moquette.spi.impl.security.PermitAllAuthorizatorPolicy;
import io.moquette.spi.impl.subscriptions.CTrieSubscriptionDirectory;
import io.moquette.spi.impl.subscriptions.ISubscriptionsDirectory;
import io.moquette.spi.security.IAuthenticator;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttVersion;
import org.junit.Before;
import org.junit.Test;

import static io.moquette.spi.impl.NettyChannelAssertions.assertEqualsConnAck;
import static io.netty.handler.codec.mqtt.MqttConnectReturnCode.*;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;
import static org.junit.Assert.*;

public class MQTTConnectionConnectTest {

    private static final String FAKE_CLIENT_ID = "FAKE_123";
    private static final String TEST_USER = "fakeuser";
    private static final String TEST_PWD = "fakepwd";
    private static final String EVIL_TEST_USER = "eviluser";
    private static final String EVIL_TEST_PWD = "unsecret";

    private MQTTConnection sut;
    private EmbeddedChannel channel;
    private SessionRegistry sessionRegistry;
    private MqttMessageBuilders.ConnectBuilder connMsg;

    @Before
    public void setUp() {
        connMsg = MqttMessageBuilders.connect().protocolVersion(MqttVersion.MQTT_3_1).cleanSession(true);

        BrokerConfiguration config = new BrokerConfiguration(true, true, false);

        createMQTTConnection(config);
    }

    private void createMQTTConnection(BrokerConfiguration config) {
        channel = new EmbeddedChannel();
        sut = createMQTTConnection(config, channel);
    }

    private MQTTConnection createMQTTConnection(BrokerConfiguration config, Channel channel) {
        MemoryStorageService memStorage = new MemoryStorageService(null, null);
        ISessionsStore sessionStore = memStorage.sessionsStore();
        IAuthenticator mockAuthenticator = new MockAuthenticator(singleton(FAKE_CLIENT_ID), singletonMap(TEST_USER, TEST_PWD));

        ISubscriptionsDirectory subscriptions = new CTrieSubscriptionDirectory();
        SessionsRepository sessionsRepository = new SessionsRepository(sessionStore, null);
        subscriptions.init(sessionsRepository);

        final PostOffice postOffice = new PostOffice(subscriptions, new PermitAllAuthorizatorPolicy(), new MemoryRetainedRepository());
        sessionRegistry = new SessionRegistry(subscriptions, postOffice);
        return new MQTTConnection(channel, config, mockAuthenticator, sessionRegistry, postOffice);
    }

    @Test
    public void testZeroByteClientIdWithCleanSession() {
        // Connect message with clean session set to true and client id is null.
        MqttConnectMessage msg = MqttMessageBuilders.connect()
            .protocolVersion(MqttVersion.MQTT_3_1_1)
            .clientId(null)
            .cleanSession(true)
            .build();

        sut.processConnect(msg);
        assertEqualsConnAck("Connection must be accepted", CONNECTION_ACCEPTED, channel.readOutbound());
        assertNotNull("unique clientid must be generated", sut.getClientId());
        assertTrue("clean session flag must be true", sessionRegistry.retrieve(sut.getClientId()).isClean());
        assertTrue("Connection must be open", channel.isOpen());
    }

    @Test
    public void invalidAuthentication() {
        MqttConnectMessage msg = connMsg.clientId(FAKE_CLIENT_ID)
            .username(TEST_USER + "_fake")
            .password(TEST_PWD)
            .build();

        // Exercise
        sut.processConnect(msg);

        // Verify
        assertEqualsConnAck(CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD, channel.readOutbound());
        assertFalse("Connection should be closed by the broker.", channel.isOpen());
    }

    @Test
    public void testConnect_badClientID() {
        connMsg.clientId("extremely_long_clientID_greater_than_23").build();

        // Exercise
        sut.processConnect(connMsg.clientId("extremely_long_clientID_greater_than_23").build());

        // Verify
        assertEqualsConnAck(CONNECTION_ACCEPTED, channel.readOutbound());
    }

    @Test
    public void testWill() {
        MqttConnectMessage msg = connMsg.clientId(FAKE_CLIENT_ID).willFlag(true)
            .willTopic("topic").willMessage("Topic message").build();

        // Exercise
        // m_handler.setMessaging(mockedMessaging);
        sut.processConnect(msg);

        // Verify
        assertEqualsConnAck(CONNECTION_ACCEPTED, channel.readOutbound());
        assertTrue("Connection is accepted and therefore should remain open", channel.isOpen());
    }

    @Test
    public void acceptAnonymousClient() {
        MqttConnectMessage msg = connMsg.clientId(FAKE_CLIENT_ID).build();

        // Exercise
        sut.processConnect(msg);

        // Verify
        assertEqualsConnAck(CONNECTION_ACCEPTED, channel.readOutbound());
        assertTrue("Connection is accepted and therefore must remain open", channel.isOpen());
    }

    @Test
    public void validAuthentication() {
        MqttConnectMessage msg = connMsg.clientId(FAKE_CLIENT_ID)
            .username(TEST_USER).password(TEST_PWD).build();

        // Exercise
        sut.processConnect(msg);

        // Verify
        assertEqualsConnAck(CONNECTION_ACCEPTED, channel.readOutbound());
        assertTrue("Connection is accepted and therefore must remain open", channel.isOpen());
    }

    @Test
    public void noPasswdAuthentication() {
        MqttConnectMessage msg = connMsg.clientId(FAKE_CLIENT_ID)
            .username(TEST_USER)
            .build();

        // Exercise
        sut.processConnect(msg);

        // Verify
        assertEqualsConnAck(CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD, channel.readOutbound());
        assertFalse("Connection must be closed by the broker", channel.isOpen());
    }

    @Test
    public void prohibitAnonymousClient() {
        MqttConnectMessage msg = connMsg.clientId(FAKE_CLIENT_ID).build();
        BrokerConfiguration config = new BrokerConfiguration(false, true, false);

        createMQTTConnection(config);

        // Exercise
        sut.processConnect(msg);

        // Verify
        assertEqualsConnAck(CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD, channel.readOutbound());
        assertFalse("Connection must be closed by the broker", channel.isOpen());
    }

    @Test
    public void prohibitAnonymousClient_providingUsername() {
        MqttConnectMessage msg = connMsg.clientId(FAKE_CLIENT_ID)
            .username(TEST_USER + "_fake")
            .build();
        BrokerConfiguration config = new BrokerConfiguration(false, true, false);

        createMQTTConnection(config);

        // Exercise
        sut.processConnect(msg);

        // Verify
        assertEqualsConnAck(CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD, channel.readOutbound());
        assertFalse("Connection should be closed by the broker.", channel.isOpen());
    }

    @Test
    public void testZeroByteClientIdNotAllowed() {
        BrokerConfiguration config = new BrokerConfiguration(false, false, false);

        createMQTTConnection(config);

        // Connect message with clean session set to true and client id is null.
        MqttConnectMessage msg = connMsg.clientId(null)
            .protocolVersion(MqttVersion.MQTT_3_1_1)
            .cleanSession(true)
            .build();

        sut.processConnect(msg);
        assertEqualsConnAck("Zero byte client identifiers are not allowed",
                            CONNECTION_REFUSED_IDENTIFIER_REJECTED, channel.readOutbound());
        assertFalse("Connection must closed", channel.isOpen());
    }

    @Test
    public void testZeroByteClientIdWithoutCleanSession() {
        // Allow zero byte client ids
        // Connect message without clean session set to true but client id is still null
        MqttConnectMessage msg = MqttMessageBuilders.connect().clientId(null).protocolVersion(MqttVersion.MQTT_3_1_1)
            .build();

        sut.processConnect(msg);
        assertEqualsConnAck("Identifier must be rejected due to having clean session set to false",
                            CONNECTION_REFUSED_IDENTIFIER_REJECTED, channel.readOutbound());
        assertFalse("Connection must be closed by the broker", channel.isOpen());
    }

    @Test
    public void testBindWithSameClientIDBadCredentialsDoesntDropExistingClient() {
        // Connect a client1
        MqttConnectMessage msg = connMsg.clientId(FAKE_CLIENT_ID)
            .username(TEST_USER)
            .password(TEST_PWD)
            .build();
        sut.processConnect(msg);
        assertEqualsConnAck(CONNECTION_ACCEPTED, channel.readOutbound());

        // create another connect same clientID but with bad credentials
        MqttConnectMessage evilClientConnMsg = MqttMessageBuilders.connect()
            .protocolVersion(MqttVersion.MQTT_3_1)
            .clientId(FAKE_CLIENT_ID)
            .username(EVIL_TEST_USER)
            .password(EVIL_TEST_PWD)
            .build();

        EmbeddedChannel evilChannel = new EmbeddedChannel();

        // Exercise
        BrokerConfiguration config = new BrokerConfiguration(true, true, false);
        final MQTTConnection evilConnection = createMQTTConnection(config, evilChannel);
        evilConnection.processConnect(evilClientConnMsg);

        // Verify
        // the evil client gets a not auth notification
        assertEqualsConnAck(CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD, evilChannel.readOutbound());
        // the good client remains connected
        assertTrue("Original connected client must remain connected", channel.isOpen());
        assertFalse("Channel trying to connect with bad credentials must be closed", evilChannel.isOpen());
    }
}
