/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.starburst;

import com.google.inject.Binder;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.starburst.authentication.Authentication;
import io.starburst.authentication.BasicAuthentication;
import io.starburst.authentication.BasicAuthenticationConfig;
import io.starburst.authentication.ClientCredentialsAuthentication;
import io.starburst.authentication.ClientCredentialsAuthenticationConfig;
import io.starburst.authentication.NoAuthentication;
import io.starburst.authentication.OpenApiAuthenticationClient;
import io.trino.spi.NodeManager;
import io.trino.spi.type.TypeManager;

import static com.google.inject.Scopes.SINGLETON;
import static io.airlift.configuration.ConditionalModule.conditionalModule;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.airlift.http.client.HttpClientBinder.httpClientBinder;
import static io.starburst.authentication.AuthenticationType.BASIC;
import static io.starburst.authentication.AuthenticationType.CLIENT_CREDENTIALS;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.isEqual;

public class OpenApiModule
        extends AbstractConfigurationAwareModule
{
    private final NodeManager nodeManager;
    private final TypeManager typeManager;

    public OpenApiModule(NodeManager nodeManager, TypeManager typeManager)
    {
        this.nodeManager = requireNonNull(nodeManager, "nodeManager is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
    }

    @Override
    protected void setup(Binder binder)
    {
        binder.bind(NodeManager.class).toInstance(nodeManager);
        binder.bind(TypeManager.class).toInstance(typeManager);

        binder.bind(OpenApiConnector.class).in(SINGLETON);
        binder.bind(OpenApiMetadata.class).in(SINGLETON);
        binder.bind(OpenApiSplitManager.class).in(SINGLETON);
        binder.bind(OpenApiRecordSetProvider.class).in(SINGLETON);
        configBinder(binder).bindConfig(OpenApiConfig.class);

        binder.bind(OpenApiSpec.class).in(SINGLETON);
        httpClientBinder(binder)
                .bindHttpClient("openApi", OpenApiClient.class)
                .withFilter(Authentication.class);

        install(conditionalModule(
                OpenApiConfig.class,
                config -> config.getAuthenticationType().isEmpty(),
                conditionalBinder -> conditionalBinder.bind(Authentication.class).to(NoAuthentication.class).in(SINGLETON)));
        install(conditionalModule(
                OpenApiConfig.class,
                config -> config.getAuthenticationType()
                        .filter(isEqual(CLIENT_CREDENTIALS))
                        .isPresent(),
                conditionalBinder -> {
                    httpClientBinder(binder).bindHttpClient("openApiAuthentication", OpenApiAuthenticationClient.class);
                    configBinder(conditionalBinder).bindConfig(ClientCredentialsAuthenticationConfig.class);
                    conditionalBinder.bind(Authentication.class).to(ClientCredentialsAuthentication.class).in(SINGLETON);
                }));
        install(conditionalModule(
                OpenApiConfig.class,
                config -> config.getAuthenticationType()
                        .filter(isEqual(BASIC))
                        .isPresent(),
                conditionalBinder -> {
                    configBinder(conditionalBinder).bindConfig(BasicAuthenticationConfig.class);
                    conditionalBinder.bind(Authentication.class).to(BasicAuthentication.class).in(SINGLETON);
                }));
    }
}
