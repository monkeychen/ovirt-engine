package org.ovirt.engine.core.bll.provider;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ovirt.engine.core.bll.validator.ValidationResultMatchers.failsWith;
import static org.ovirt.engine.core.bll.validator.ValidationResultMatchers.isValid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ovirt.engine.core.common.businessentities.OpenstackNetworkPluginType;
import org.ovirt.engine.core.common.businessentities.OpenstackNetworkProviderProperties;
import org.ovirt.engine.core.common.businessentities.OpenstackNetworkProviderProperties.AgentConfiguration;
import org.ovirt.engine.core.common.businessentities.OpenstackNetworkProviderProperties.MessagingConfiguration;
import org.ovirt.engine.core.common.businessentities.ProviderType;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.utils.RandomUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class NetworkProviderValidatorTest extends ProviderValidatorTest {

    private static final ProviderType NON_NETWORK_PROVIDER_TYPE = ProviderType.FOREMAN;

    private NetworkProviderValidator validator = new NetworkProviderValidator(provider);
    @Mock
    private OpenstackNetworkProviderProperties properties;

    @Test
    public void validNetworkProviderType() {
        when(provider.getType()).thenReturn(ProviderType.OPENSTACK_NETWORK);
        assertThat(validator.providerTypeIsNetwork(), isValid());
    }

    @Test
    public void invalidNetworkProviderType() {
        when(provider.getType()).thenReturn(NON_NETWORK_PROVIDER_TYPE);
        assertThat(validator.providerTypeIsNetwork(),
                failsWith(EngineMessage.ACTION_TYPE_FAILED_PROVIDER_NOT_NETWORK));
    }

    @Test
    public void networkMappingsProvidedByParameters() {
        assertThat(validator.networkMappingsProvided(RandomUtils.instance().nextString(10)), isValid());
    }

    @Test
    public void networkMappingsProvidedByProvider() {
        mockProviderAdditionalProperties();
        when(getProviderAgentConfiguration().getNetworkMappings()).thenReturn(RandomUtils.instance().nextString(10));
        assertThat(validator.networkMappingsProvided(null), isValid());
    }

    @Test
    public void missingNetworkMappings() {
        assertThat(validator.networkMappingsProvided(null),
                failsWith(EngineMessage.ACTION_TYPE_FAILED_MISSING_NETWORK_MAPPINGS));
    }

    @Test
    public void messagingBrokerProvided() {
        mockMessagingBrokerAddress("1.1.1.1");

        assertThat(validator.messagingBrokerProvided(), isValid());
    }

    @Test
    public void missingAgentConfigurationForMessagingBrokerValidation() {
        mockProviderAdditionalProperties();
        assertThat(validator.messagingBrokerProvided(),
                failsWith(EngineMessage.ACTION_TYPE_FAILED_MISSING_MESSAGING_BROKER_PROPERTIES));
    }

    @Test
    public void missingMessagingConfigurationForMessagingBrokerValidation() {
        mockMessagingConfiguration();

        assertThat(validator.messagingBrokerProvided(),
                failsWith(EngineMessage.ACTION_TYPE_FAILED_MISSING_MESSAGING_BROKER_PROPERTIES));
    }

    private void mockProviderAdditionalProperties() {
        AgentConfiguration agentConfiguration = mock(AgentConfiguration.class);
        OpenstackNetworkProviderProperties properties = mock(OpenstackNetworkProviderProperties.class);
        when(properties.getAgentConfiguration()).thenReturn(agentConfiguration);
        when(provider.getAdditionalProperties()).thenReturn(properties);
    }

    private void mockMessagingConfiguration() {
        mockProviderAdditionalProperties();
        MessagingConfiguration messagingConfiguration = mock(MessagingConfiguration.class);
        when(getProviderAgentConfiguration().getMessagingConfiguration()).thenReturn(messagingConfiguration);
    }

    private void mockMessagingBrokerAddress(String address) {
        mockMessagingConfiguration();
        when(getProviderAgentConfiguration().getMessagingConfiguration().getAddress()).thenReturn(address);
    }

    private AgentConfiguration getProviderAgentConfiguration() {
        return ((OpenstackNetworkProviderProperties) provider.getAdditionalProperties()).getAgentConfiguration();
    }

    @Test
    public void validPluginType() {
        when(provider.getAdditionalProperties()).thenReturn(properties);
        when(properties.getPluginType()).thenReturn(OpenstackNetworkPluginType.OPEN_VSWITCH.name());
        assertThat(validator.validateAddProvider(), isValid());
    }

    @Test
    public void invalidPluginType() {
        when(provider.getAdditionalProperties()).thenReturn(properties);
        assertThat(validator.validatePluginType(),
                failsWith(EngineMessage.ACTION_TYPE_FAILED_PROVIDER_NO_PLUGIN_TYPE));
    }

    @Test
    public void validAuthentication() {
        when(provider.getType()).thenReturn(ProviderType.OPENSTACK_NETWORK);
        when(provider.isRequiringAuthentication()).thenReturn(true);
        when(provider.getUsername()).thenReturn("user");
        when(provider.getPassword()).thenReturn("pass");
        when(provider.getAuthUrl()).thenReturn("url");
        when(provider.getAdditionalProperties()).thenReturn(properties);
        when(properties.getTenantName()).thenReturn("tenant");
        assertThat(validator.validateAuthentication(), isValid());
    }

    @Test
    public void validAuthenticationNotRequired() {
        when(provider.isRequiringAuthentication()).thenReturn(false);
        assertThat(validator.validateAuthentication(), isValid());
    }

    @Test
    public void invalidAuthenticationUserName() {
        when(provider.isRequiringAuthentication()).thenReturn(true);
        assertThat(validator.validateAuthentication(),
                failsWith(EngineMessage.ACTION_TYPE_FAILED_PROVIDER_NO_USER));
    }

    @Test
    public void invalidAuthenticationPassword() {
        when(provider.isRequiringAuthentication()).thenReturn(true);
        when(provider.getUsername()).thenReturn("user");
        assertThat(validator.validateAuthentication(),
                failsWith(EngineMessage.ACTION_TYPE_FAILED_PROVIDER_NO_PASSWORD));
    }

    @Test
    public void invalidAuthenticationUrl() {
        when(provider.isRequiringAuthentication()).thenReturn(true);
        when(provider.getUsername()).thenReturn("user");
        when(provider.getPassword()).thenReturn("pass");
        assertThat(validator.validateAuthentication(),
                failsWith(EngineMessage.ACTION_TYPE_FAILED_PROVIDER_NO_AUTH_URL));
    }

    @Test
    public void invalidAuthenticationTenant() {
        when(provider.getType()).thenReturn(ProviderType.OPENSTACK_NETWORK);
        when(provider.isRequiringAuthentication()).thenReturn(true);
        when(provider.getUsername()).thenReturn("user");
        when(provider.getPassword()).thenReturn("pass");
        when(provider.getAuthUrl()).thenReturn("url");
        when(provider.getAdditionalProperties()).thenReturn(properties);
        assertThat(validator.validateAuthentication(),
                failsWith(EngineMessage.ACTION_TYPE_FAILED_PROVIDER_NO_TENANT_NAME));
    }
}
