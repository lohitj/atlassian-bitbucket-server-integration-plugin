package it.com.atlassian.bitbucket.jenkins.internal.fixture;

import com.atlassian.bitbucket.jenkins.internal.client.BitbucketClientFactoryProvider;
import com.atlassian.bitbucket.jenkins.internal.client.BitbucketRepositoryClient;
import com.atlassian.bitbucket.jenkins.internal.client.BitbucketWebhookClient;
import com.atlassian.bitbucket.jenkins.internal.credentials.BitbucketCredentials;
import com.atlassian.bitbucket.jenkins.internal.credentials.JenkinsToBitbucketCredentialsImpl;
import com.atlassian.bitbucket.jenkins.internal.http.HttpRequestExecutorImpl;
import com.atlassian.bitbucket.jenkins.internal.trigger.events.BitbucketWebhookEvent;

/**
 * To make communicating wth Bitbucket easier in tests.
 *
 * @since 3.0.0
 */
public class BitbucketTestClient {

    private final BitbucketCredentials adminToken;
    private final BitbucketClientFactoryProvider bitbucketClientFactoryProvider;
    private final BitbucketJenkinsRule bitbucketJenkinsRule;

    public BitbucketTestClient(BitbucketJenkinsRule bitbucketJenkinsRule) {
        this.bitbucketJenkinsRule = bitbucketJenkinsRule;
        JenkinsToBitbucketCredentialsImpl jenkinsToBitbucketCredentials = new JenkinsToBitbucketCredentialsImpl();
        adminToken = jenkinsToBitbucketCredentials.toBitbucketCredentials(bitbucketJenkinsRule.getAdminToken());
        bitbucketClientFactoryProvider = new BitbucketClientFactoryProvider(new HttpRequestExecutorImpl());
    }

    public boolean supportsWebhook(BitbucketWebhookEvent event) {
        return bitbucketClientFactoryProvider
                .getClient(bitbucketJenkinsRule.getBitbucketServerConfiguration().getBaseUrl(), adminToken)
                .getCapabilityClient()
                .getWebhookSupportedEvents()
                .getApplicationWebHooks()
                .contains(event.getEventId());
    }

    public BitbucketRepositoryClient getRepositoryClient(String projectKey, String repoSlug) {
        return bitbucketClientFactoryProvider
                .getClient(bitbucketJenkinsRule.getBitbucketServerConfiguration().getBaseUrl(), adminToken)
                .getProjectClient(projectKey)
                .getRepositoryClient(repoSlug);
    }

    public void removeAllWebHooks(String projectKey, String repoSlug) {
        BitbucketWebhookClient webhookClient = bitbucketClientFactoryProvider
                .getClient(bitbucketJenkinsRule.getBitbucketServerConfiguration().getBaseUrl(), adminToken)
                .getProjectClient(projectKey)
                .getRepositoryClient(repoSlug)
                .getWebhookClient();
        webhookClient.getWebhooks().forEach(hook -> webhookClient.deleteWebhook(hook.getId()));
    }
}
