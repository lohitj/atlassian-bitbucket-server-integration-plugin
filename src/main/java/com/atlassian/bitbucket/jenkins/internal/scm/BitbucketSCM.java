package com.atlassian.bitbucket.jenkins.internal.scm;

import com.atlassian.bitbucket.jenkins.internal.client.BitbucketClientFactoryProvider;
import com.atlassian.bitbucket.jenkins.internal.config.BitbucketPluginConfiguration;
import com.atlassian.bitbucket.jenkins.internal.config.BitbucketServerConfiguration;
import com.atlassian.bitbucket.jenkins.internal.config.BitbucketTokenCredentials;
import com.atlassian.bitbucket.jenkins.internal.credentials.GlobalCredentialsProvider;
import com.atlassian.bitbucket.jenkins.internal.credentials.JenkinsToBitbucketCredentials;
import com.atlassian.bitbucket.jenkins.internal.credentials.JenkinsToBitbucketCredentialsModule;
import com.atlassian.bitbucket.jenkins.internal.model.BitbucketNamedLink;
import com.atlassian.bitbucket.jenkins.internal.model.BitbucketProject;
import com.atlassian.bitbucket.jenkins.internal.model.BitbucketRepository;
import com.google.inject.Guice;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitTool;
import hudson.plugins.git.SubmoduleConfig;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.browser.Stash;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.GitSCMExtensionDescriptor;
import hudson.scm.*;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

import static com.atlassian.bitbucket.jenkins.internal.model.RepositoryState.AVAILABLE;
import static java.lang.Math.max;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.apache.commons.lang3.StringUtils.isBlank;

public class BitbucketSCM extends SCM {

    private static final Logger LOGGER = Logger.getLogger(BitbucketSCM.class.getName());

    private GitSCM gitSCM;
    // avoid a difficult upgrade task.
    private final List<BranchSpec> branches;
    private final List<GitSCMExtension> extensions;
    private final String gitTool;
    private final String id;
    // this is to enable us to support future multiple repositories
    private final List<BitbucketSCMRepository> repositories;
    private volatile boolean isWebhookRegistered;

    @DataBoundConstructor
    public BitbucketSCM(
            @CheckForNull String id,
            @CheckForNull List<BranchSpec> branches,
            @CheckForNull String credentialsId,
            @CheckForNull String sshCredentialsId,
            @CheckForNull List<GitSCMExtension> extensions,
            @CheckForNull String gitTool,
            @CheckForNull String projectName,
            @SuppressFBWarnings(value = "NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE", justification = "We handle null values properly in a way that Findbugs misses")
            @CheckForNull String repositoryName,
            @CheckForNull String serverId,
            @CheckForNull String mirrorName) {
        this(id, branches, extensions, gitTool, serverId, repositoryName);

        DescriptorImpl descriptor = (DescriptorImpl) getDescriptor();
        Optional<BitbucketServerConfiguration> mayBeServerConf = descriptor.getConfiguration(serverId);
        if (!mayBeServerConf.isPresent()) {
            LOGGER.info("No Bitbucket Server configuration for serverId " + serverId);
            setEmptyRepository(credentialsId, sshCredentialsId, projectName, repositoryName, serverId, mirrorName);
            return;
        }

        BitbucketServerConfiguration serverConfiguration = mayBeServerConf.get();
        GlobalCredentialsProvider globalCredentialsProvider = serverConfiguration.getGlobalCredentialsProvider(
                format("Bitbucket SCM: Query Bitbucket for project [%s] repo [%s] mirror[%s]",
                        projectName,
                        repositoryName,
                        mirrorName));
        BitbucketScmHelper scmHelper =
                descriptor.getBitbucketScmHelper(serverConfiguration.getBaseUrl(),
                        globalCredentialsProvider.getGlobalAdminCredentials().orElse(null));
        if (isBlank(projectName)) {
            LOGGER.info("Error creating the Bitbucket SCM: The project name is blank");
            setEmptyRepository(credentialsId, sshCredentialsId, projectName, repositoryName, serverId, mirrorName);
            return;
        }
        if (isBlank(repositoryName)) {
            LOGGER.info("Error creating the Bitbucket SCM: The repository name is blank");
            setEmptyRepository(credentialsId, sshCredentialsId, projectName, repositoryName, serverId, mirrorName);
            return;
        }

        if (!isBlank(mirrorName)) {
            try {
                EnrichedBitbucketMirroredRepository mirroredRepository =
                        descriptor.createMirrorHandler(scmHelper)
                                .fetchRepository(
                                        new MirrorFetchRequest(
                                                serverConfiguration.getBaseUrl(),
                                                credentialsId,
                                                globalCredentialsProvider,
                                                projectName,
                                                repositoryName,
                                                mirrorName));
                setRepositoryDetails(credentialsId, sshCredentialsId, serverId, mirroredRepository);
            } catch (MirrorFetchException ex) {
                setEmptyRepository(credentialsId, sshCredentialsId, projectName, repositoryName, serverId, mirrorName);
            }
        } else {
            BitbucketRepository repository = scmHelper.getRepository(projectName, repositoryName);
            setRepositoryDetails(credentialsId, sshCredentialsId, serverId, mirrorName, repository);
        }
    }

    public BitbucketSCM(
            @CheckForNull String id,
            @CheckForNull List<BranchSpec> branches,
            @CheckForNull String credentialsId,
            @CheckForNull String sshCredentialsId,
            @CheckForNull List<GitSCMExtension> extensions,
            @CheckForNull String gitTool,
            @CheckForNull String serverId,
            BitbucketRepository repository) {
        this(id, branches, extensions, gitTool, serverId, repository.getName());
        setRepositoryDetails(credentialsId, sshCredentialsId, serverId, "", repository);
    }

    /**
     * Regenerate SCM by looking up new repo URLs etc.
     *
     * @param oldScm old scm to copy values from
     */
    public BitbucketSCM(BitbucketSCM oldScm) {
        this(oldScm.getId(), oldScm.getBranches(), oldScm.getCredentialsId(), oldScm.getSshCredentialsId(), oldScm.getExtensions(),
                oldScm.getGitTool(), oldScm.getProjectName(), oldScm.getRepositoryName(), oldScm.getServerId(),
                oldScm.getMirrorName());
    }

    private BitbucketSCM(
            @CheckForNull String id,
            @CheckForNull List<BranchSpec> branches,
            @CheckForNull List<GitSCMExtension> extensions,
            @CheckForNull String gitTool,
            @CheckForNull String serverId,
            String repositoryName) {
        this.id = isBlank(id) ? UUID.randomUUID().toString() : id;
        this.branches = new ArrayList<>();
        this.extensions = new ArrayList<>();
        this.gitTool = gitTool;
        repositories = new ArrayList<>(1);

        if (branches != null) {
            this.branches.addAll(branches);
        }
        if (extensions != null) {
            this.extensions.addAll(extensions);
        }
    }

    @CheckForNull
    public String getGitTool() {
        return gitTool;
    }

    @Override
    public void buildEnvironment(Run<?, ?> build, Map<String, String> env) {
        gitSCM.buildEnvironment(build, env);
    }

    @CheckForNull
    @Override
    public SCMRevisionState calcRevisionsFromBuild(
            Run<?, ?> build,
            @Nullable FilePath workspace,
            @Nullable Launcher launcher,
            TaskListener listener)
            throws IOException, InterruptedException {
        return gitSCM.calcRevisionsFromBuild(build, workspace, launcher, listener);
    }

    @Override
    public void checkout(
            Run<?, ?> build,
            Launcher launcher,
            FilePath workspace,
            TaskListener listener,
            @CheckForNull File changelogFile,
            @CheckForNull SCMRevisionState baseline)
            throws IOException, InterruptedException {
        gitSCM.checkout(build, launcher, workspace, listener, changelogFile, baseline);
    }

    @Override
    public PollingResult compareRemoteRevisionWith(
            Job<?, ?> project,
            @Nullable Launcher launcher,
            @Nullable FilePath workspace,
            TaskListener listener,
            SCMRevisionState baseline)
            throws IOException, InterruptedException {
        return gitSCM.compareRemoteRevisionWith(project, launcher, workspace, listener, baseline);
    }

    @Override
    public ChangeLogParser createChangeLogParser() {
        return gitSCM.createChangeLogParser();
    }

    public List<BranchSpec> getBranches() {
        if (gitSCM == null) {
            return emptyList();
        }
        return gitSCM.getBranches();
    }

    @CheckForNull
    @Override
    public RepositoryBrowser<?> getBrowser() {
        return gitSCM.getBrowser();
    }

    @CheckForNull
    public GitSCM getGitSCM() {
        return gitSCM;
    }

    @CheckForNull
    public String getCredentialsId() {
        return getBitbucketSCMRepository().getCredentialsId();
    }

    @CheckForNull
    public String getSshCredentialsId() {
        return getBitbucketSCMRepository().getSshCredentialsId();
    }

    public List<GitSCMExtension> getExtensions() {
        return gitSCM.getExtensions();
    }

    public String getId() {
        return id;
    }

    public String getProjectKey() {
        return getBitbucketSCMRepository().getProjectKey();
    }

    public String getProjectName() {
        BitbucketSCMRepository repository = getBitbucketSCMRepository();
        return repository.isPersonal() ? repository.getProjectKey() : repository.getProjectName();
    }

    public List<BitbucketSCMRepository> getRepositories() {
        return repositories;
    }

    public String getRepositorySlug() {
        return getBitbucketSCMRepository().getRepositorySlug();
    }

    public String getRepositoryName() {
        return getBitbucketSCMRepository().getRepositoryName();
    }

    public String getMirrorName() {
        return getBitbucketSCMRepository().getMirrorName();
    }

    @CheckForNull
    public String getServerId() {
        return getBitbucketSCMRepository().getServerId();
    }

    public Collection<SubmoduleConfig> getSubmoduleCfg() {
        if (gitSCM == null) {
            return emptyList();
        }
        return gitSCM.getSubmoduleCfg();
    }

    public List<UserRemoteConfig> getUserRemoteConfigs() {
        if (gitSCM == null) {
            return emptyList();
        }
        return gitSCM.getUserRemoteConfigs();
    }

    public void setWebhookRegistered(boolean isWebhookRegistered) {
        this.isWebhookRegistered = isWebhookRegistered;
    }

    public boolean isWebhookRegistered() {
        return isWebhookRegistered;
    }

    public BitbucketSCMRepository getBitbucketSCMRepository() {
        return repositories.get(0);
    }

    private String getCloneUrl(List<BitbucketNamedLink> cloneUrls, CloneProtocol protocol) {
        return cloneUrls.stream()
                .filter(link -> Objects.equals(protocol.name, link.getName()))
                .findFirst()
                .map(BitbucketNamedLink::getHref)
                .orElse("");
    }

    private void initialize(String cloneUrl, String selfLink, BitbucketSCMRepository bitbucketSCMRepository) {
        repositories.add(bitbucketSCMRepository);
        String credentialsId = isBlank(bitbucketSCMRepository.getSshCredentialsId()) ?
                bitbucketSCMRepository.getCredentialsId() : bitbucketSCMRepository.getSshCredentialsId();

        UserRemoteConfig remoteConfig =
                new UserRemoteConfig(cloneUrl, bitbucketSCMRepository.getRepositorySlug(), null, credentialsId);
        // self-link include /browse which needs to be trimmed
        String repositoryUrl = selfLink.substring(0, max(selfLink.indexOf("/browse"), 0));
        gitSCM = new GitSCM(singletonList(remoteConfig), branches, false, emptyList(), new Stash(repositoryUrl),
                gitTool, extensions);
    }

    private void setEmptyRepository(@CheckForNull String credentialsId,
                                    @CheckForNull String sshCredentialsId,
                                    @CheckForNull String projectName,
                                    @CheckForNull String repositoryName,
                                    @CheckForNull String serverId,
                                    @CheckForNull String mirrorName) {
        projectName = Objects.toString(projectName, "");
        repositoryName = Objects.toString(repositoryName, "");
        mirrorName = Objects.toString(mirrorName, "");
        BitbucketRepository repository =
                new BitbucketRepository(-1, repositoryName, null, new BitbucketProject(projectName, null, projectName),
                        repositoryName, AVAILABLE);
        setRepositoryDetails(credentialsId, sshCredentialsId, serverId, mirrorName, repository);
    }

    private void setRepositoryDetails(@CheckForNull String credentialsId, @CheckForNull String sshCredentialsId,
                                      @Nullable String serverId, String mirrorName, BitbucketRepository repository) {
        CloneProtocol cloneProtocol = isBlank(sshCredentialsId) ? CloneProtocol.HTTP : CloneProtocol.SSH;
        String cloneUrl = getCloneUrl(repository.getCloneUrls(), cloneProtocol);
        BitbucketSCMRepository bitbucketSCMRepository =
                new BitbucketSCMRepository(credentialsId, sshCredentialsId, repository.getProject().getName(),
                        repository.getProject().getKey(), repository.getName(), repository.getSlug(),
                        serverId, mirrorName);
        initialize(cloneUrl, repository.getSelfLink(), bitbucketSCMRepository);
    }

    private void setRepositoryDetails(@CheckForNull String credentialsId, @CheckForNull String sshCredentialsId, @Nullable String serverId,
                                      EnrichedBitbucketMirroredRepository repository) {
        if (isBlank(serverId)) {
            return;
        }
        CloneProtocol cloneProtocol = isBlank(sshCredentialsId) ? CloneProtocol.HTTP : CloneProtocol.SSH;
        String cloneUrl = getCloneUrl(repository.getMirroringDetails().getCloneUrls(), cloneProtocol);
        BitbucketRepository underlyingRepo = repository.getRepository();
        BitbucketSCMRepository bitbucketSCMRepository =
                new BitbucketSCMRepository(credentialsId, sshCredentialsId, underlyingRepo.getProject().getName(),
                        underlyingRepo.getProject().getKey(), underlyingRepo.getName(), underlyingRepo.getSlug(),
                        serverId, repository.getMirroringDetails().getMirrorName());
        initialize(cloneUrl, underlyingRepo.getSelfLink(), bitbucketSCMRepository);
    }

    @Symbol("BbS")
    @Extension
    @SuppressWarnings({"unused"})
    public static class DescriptorImpl extends SCMDescriptor<BitbucketSCM> implements BitbucketScmFormValidation,
            BitbucketScmFormFill {

        private final GitSCM.DescriptorImpl gitScmDescriptor;
        @Inject
        private BitbucketScmFormFillDelegate formFill;
        @Inject
        private BitbucketScmFormValidationDelegate formValidation;
        @Inject
        private BitbucketClientFactoryProvider bitbucketClientFactoryProvider;
        @Inject
        private BitbucketPluginConfiguration bitbucketPluginConfiguration;
        private transient JenkinsToBitbucketCredentials jenkinsToBitbucketCredentials;

        public DescriptorImpl() {
            super(Stash.class);
            gitScmDescriptor = new GitSCM.DescriptorImpl();
            load();
        }

        @Override
        @POST
        public FormValidation doCheckCredentialsId(@AncestorInPath Item context,
                                                   @QueryParameter String credentialsId) {
            return formValidation.doCheckCredentialsId(context, credentialsId);
        }

        @Override
        @POST
        public FormValidation doCheckSshCredentialsId(@AncestorInPath Item context,
                                                      @QueryParameter String sshCredentialsId) {
            return formValidation.doCheckCredentialsId(context, sshCredentialsId);
        }

        @Override
        @POST
        public FormValidation doCheckProjectName(@AncestorInPath Item context,
                                                 @QueryParameter String serverId,
                                                 @QueryParameter String credentialsId,
                                                 @QueryParameter String projectName) {
            return formValidation.doCheckProjectName(context, serverId, credentialsId, projectName);
        }

        @Override
        @POST
        public FormValidation doCheckRepositoryName(@AncestorInPath Item context,
                                                    @QueryParameter String serverId,
                                                    @QueryParameter String credentialsId,
                                                    @QueryParameter String projectName,
                                                    @QueryParameter String repositoryName) {
            return formValidation.doCheckRepositoryName(context, serverId, credentialsId, projectName, repositoryName);
        }

        @Override
        @POST
        public FormValidation doCheckServerId(@AncestorInPath Item context,
                                              @QueryParameter String serverId) {
            return formValidation.doCheckServerId(context, serverId);
        }

        @Override
        @POST
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item context,
                                                     @QueryParameter String baseUrl,
                                                     @QueryParameter String credentialsId) {
            return formFill.doFillCredentialsIdItems(context, baseUrl, credentialsId);
        }

        @Override
        @POST
        public ListBoxModel doFillSshCredentialsIdItems(@AncestorInPath Item context,
                                                        @QueryParameter String baseUrl,
                                                        @QueryParameter String sshCredentialsId) {
            return formFill.doFillSshCredentialsIdItems(context, baseUrl, sshCredentialsId);
        }

        @Override
        @POST
        public FormValidation doTestConnection(@AncestorInPath Item context,
                                               @QueryParameter String serverId,
                                               @QueryParameter String credentialsId,
                                               @QueryParameter String projectName,
                                               @QueryParameter String repositoryName,
                                               @QueryParameter String mirrorName) {
            return formValidation.doTestConnection(context, serverId, credentialsId, projectName, repositoryName,
                    mirrorName);
        }

        @POST
        public ListBoxModel doFillGitToolItems() {
            return gitScmDescriptor.doFillGitToolItems();
        }

        @Override
        @POST
        public HttpResponse doFillProjectNameItems(@AncestorInPath Item context,
                                                   @QueryParameter String serverId,
                                                   @QueryParameter String credentialsId,
                                                   @QueryParameter String projectName) {
            return formFill.doFillProjectNameItems(context, serverId, credentialsId, projectName);
        }

        @Override
        @POST
        public HttpResponse doFillRepositoryNameItems(@AncestorInPath Item context,
                                                      @QueryParameter String serverId,
                                                      @QueryParameter String credentialsId,
                                                      @QueryParameter String projectName,
                                                      @QueryParameter String repositoryName) {
            return formFill.doFillRepositoryNameItems(context, serverId, credentialsId, projectName, repositoryName);
        }

        @Override
        @POST
        public ListBoxModel doFillServerIdItems(@AncestorInPath Item context, @QueryParameter String serverId) {
            return formFill.doFillServerIdItems(context, serverId);
        }

        @Override
        @POST
        public ListBoxModel doFillMirrorNameItems(@AncestorInPath Item context,
                                                  @QueryParameter String serverId,
                                                  @QueryParameter String credentialsId,
                                                  @QueryParameter String projectName,
                                                  @QueryParameter String repositoryName,
                                                  @QueryParameter String mirrorName) {
            return formFill.doFillMirrorNameItems(context, serverId, credentialsId, projectName, repositoryName,
                    mirrorName);
        }

        @Override
        public String getDisplayName() {
            return "Bitbucket Server";
        }

        @Override
        public List<GitSCMExtensionDescriptor> getExtensionDescriptors() {
            return gitScmDescriptor.getExtensionDescriptors();
        }

        @Override
        public List<GitTool> getGitTools() {
            return gitScmDescriptor.getGitTools();
        }

        @Override
        public boolean getShowGitToolOptions() {
            return gitScmDescriptor.showGitToolOptions();
        }

        @Override
        public boolean isApplicable(Job project) {
            return true;
        }

        @Inject
        public void setJenkinsToBitbucketCredentials(
                JenkinsToBitbucketCredentials jenkinsToBitbucketCredentials) {
            this.jenkinsToBitbucketCredentials = jenkinsToBitbucketCredentials;
        }

        BitbucketScmHelper getBitbucketScmHelper(String bitbucketUrl,
                                                 @Nullable BitbucketTokenCredentials tokenCredentials) {
            return new BitbucketScmHelper(bitbucketUrl,
                    bitbucketClientFactoryProvider,
                    jenkinsToBitbucketCredentials.toBitbucketCredentials(tokenCredentials));
        }

        private BitbucketMirrorHandler createMirrorHandler(BitbucketScmHelper helper) {
            return new BitbucketMirrorHandler(
                    bitbucketClientFactoryProvider,
                    jenkinsToBitbucketCredentials,
                    (client, project, repo) -> helper.getRepository(project, repo));
        }

        Optional<BitbucketServerConfiguration> getConfiguration(@Nullable String serverId) {
            return bitbucketPluginConfiguration.getServerById(serverId);
        }

        public void injectJenkinsToBitbucketCredentials() {
            if (jenkinsToBitbucketCredentials == null) {
                Guice.createInjector(new JenkinsToBitbucketCredentialsModule()).injectMembers(this);
            }
        }
    }
}
