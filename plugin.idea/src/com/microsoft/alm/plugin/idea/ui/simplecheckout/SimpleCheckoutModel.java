// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.alm.plugin.idea.ui.simplecheckout;

import com.intellij.dvcs.DvcsUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.progress.PerformInBackgroundOption;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.CheckoutProvider;
import com.intellij.openapi.vcs.VcsNotifier;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.microsoft.alm.plugin.context.ServerContext;
import com.microsoft.alm.plugin.context.ServerContextManager;
import com.microsoft.alm.plugin.idea.resources.TfPluginBundle;
import com.microsoft.alm.plugin.idea.ui.common.AbstractModel;
import com.microsoft.alm.plugin.idea.ui.common.ModelValidationInfo;
import com.microsoft.alm.plugin.services.PluginServiceProvider;
import com.microsoft.alm.plugin.services.PropertyService;
import com.microsoft.alm.plugin.telemetry.TfsTelemetryHelper;
import git4idea.GitVcs;
import git4idea.commands.Git;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model for the SimpleCheckout dialog UI
 */
public class SimpleCheckoutModel extends AbstractModel {
    public final static String DEFAULT_SOURCE_PATH = System.getProperty("user.home");
    public final static String PROP_DIRECTORY_NAME = "directoryName";
    public final static String PROP_PARENT_DIR = "parentDirectory";
    public final static String COMMANDLINE_CLONE_ACTION = "commandline-clone";
    public final static Pattern GIT_URL_PATTERN = Pattern.compile("/_git/(.*)");

    private final Project project;
    private final CheckoutProvider.Listener listener;
    private final String gitUrl;
    private String parentDirectory;
    private String directoryName;

    protected SimpleCheckoutModel(final Project project, final CheckoutProvider.Listener listener, final String gitUrl) {
        super();
        this.project = project;
        this.listener = listener;
        this.gitUrl = gitUrl;

        this.parentDirectory = PluginServiceProvider.getInstance().getPropertyService().getProperty(PropertyService.PROP_REPO_ROOT);
        // use default root if no repo root is found
        if (StringUtils.isEmpty(this.parentDirectory)) {
            this.parentDirectory = DEFAULT_SOURCE_PATH;
        }

        // try and parse for the repo name to use as the directory name
        final Matcher matcher = GIT_URL_PATTERN.matcher(gitUrl);
        if (matcher.find() && matcher.groupCount() == 1) {
            this.directoryName = matcher.group(1);
        } else {
            this.directoryName = StringUtils.EMPTY;
        }
    }

    public Project getProject() {
        return project;
    }

    public String getParentDirectory() {
        return parentDirectory;
    }

    public void setParentDirectory(final String parentDirectory) {
        if (!StringUtils.equals(this.parentDirectory, parentDirectory)) {
            this.parentDirectory = parentDirectory;
            setChangedAndNotify(PROP_PARENT_DIR);
        }
    }

    public String getDirectoryName() {
        return directoryName;
    }

    public void setDirectoryName(final String directoryName) {
        if (!StringUtils.equals(this.directoryName, directoryName)) {
            this.directoryName = directoryName;
            setChangedAndNotify(PROP_DIRECTORY_NAME);
        }
    }

    public String getRepoUrl() {
        return gitUrl;
    }

    public ModelValidationInfo validate() {
        final String parentDirectory = getParentDirectory();
        if (parentDirectory == null || parentDirectory.isEmpty()) {
            return ModelValidationInfo.createWithResource(PROP_PARENT_DIR,
                    TfPluginBundle.KEY_CHECKOUT_DIALOG_ERRORS_PARENT_DIR_EMPTY);
        }

        final File parentDirectoryOnDisk = new File(parentDirectory);
        if (!parentDirectoryOnDisk.exists()) {
            return ModelValidationInfo.createWithResource(PROP_PARENT_DIR,
                    TfPluginBundle.KEY_CHECKOUT_DIALOG_ERRORS_PARENT_DIR_NOT_FOUND);
        }

        // We test this method and so we need to check to see if we are in IntelliJ before using VirtualFileManager
        // ApplicationManager is null if we are not in IntelliJ
        if (ApplicationManager.getApplication() != null) {
            final VirtualFile destinationParent = LocalFileSystem.getInstance().findFileByPath(parentDirectory);
            if (destinationParent == null) {
                return ModelValidationInfo.createWithResource(PROP_PARENT_DIR,
                        TfPluginBundle.KEY_CHECKOUT_DIALOG_ERRORS_PARENT_DIR_NOT_FOUND);
            }
        }

        final String directoryName = getDirectoryName();
        if (directoryName == null || directoryName.isEmpty()) {
            return ModelValidationInfo.createWithResource(PROP_DIRECTORY_NAME,
                    TfPluginBundle.KEY_CHECKOUT_DIALOG_ERRORS_DIR_NAME_EMPTY);
        }

        final File destDirectoryOnDisk = new File(parentDirectory, directoryName);
        //verify the destination directory does not exist
        if (destDirectoryOnDisk.exists() && destDirectoryOnDisk.isDirectory()) {
            return ModelValidationInfo.createWithResource(PROP_DIRECTORY_NAME,
                    TfPluginBundle.KEY_CHECKOUT_DIALOG_ERRORS_DESTINATION_EXISTS, directoryName);
        }
        //verify destination directory parent exists, we can reach this condition if user specifies a path for directory name
        if (destDirectoryOnDisk.getParentFile() == null || !destDirectoryOnDisk.getParentFile().exists()) {
            return ModelValidationInfo.createWithResource(PROP_DIRECTORY_NAME,
                    TfPluginBundle.KEY_CHECKOUT_DIALOG_ERRORS_DIR_NAME_INVALID,
                    directoryName, destDirectoryOnDisk.getParent());
        }

        return ModelValidationInfo.NO_ERRORS;
    }

    public void cloneRepo() {
        final ModelValidationInfo validationInfo = validate();
        if (validationInfo == null) {
            final Task.Backgroundable createCloneTask = new Task.Backgroundable(project, TfPluginBundle.message(TfPluginBundle.KEY_CHECKOUT_DIALOG_TITLE),
                    true, PerformInBackgroundOption.DEAF) {
                final AtomicBoolean cloneResult = new AtomicBoolean();

                @Override
                public void run(@NotNull final ProgressIndicator progressIndicator) {
                    progressIndicator.setText(TfPluginBundle.message(TfPluginBundle.KEY_CHECKOUT_DIALOG_TITLE));
                    // get context from manager, and store in active context
                    final ServerContext context = ServerContextManager.getInstance().getAuthenticatedContext(
                            gitUrl, true);

                    if (context == null) {
                        VcsNotifier.getInstance(project).notifyError(TfPluginBundle.message(TfPluginBundle.KEY_CHECKOUT_ERRORS_AUTHENTICATION_FAILED_TITLE), TfPluginBundle.message(TfPluginBundle.KEY_ERRORS_AUTH_NOT_SUCCESSFUL, gitUrl));
                        return;
                    }

                    final String gitRepositoryStr = context.getUsableGitUrl();
                    final Git git = ServiceManager.getService(Git.class);
                    cloneResult.set(git4idea.checkout.GitCheckoutProvider.doClone(project, git, getDirectoryName(), getParentDirectory(), gitRepositoryStr));

                    // Add Telemetry for the clone call along with it's success/failure
                    TfsTelemetryHelper.getInstance().sendEvent(COMMANDLINE_CLONE_ACTION, new TfsTelemetryHelper.PropertyMapBuilder()
                            .currentOrActiveContext(context)
                            .actionName(COMMANDLINE_CLONE_ACTION)
                            .success(cloneResult.get()).build());
                }

                @Override
                public void onSuccess() {
                    // if clone was successful then complete the checkout process which gives the option to open the project
                    if (cloneResult.get()) {
                        DvcsUtil.addMappingIfSubRoot(project, FileUtil.join(new String[]{parentDirectory, directoryName}), "Git");
                        listener.directoryCheckedOut(new File(parentDirectory, directoryName), GitVcs.getKey());
                        listener.checkoutCompleted();
                    }
                }
            };
            createCloneTask.queue();
        }
    }
}