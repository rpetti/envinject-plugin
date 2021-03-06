package org.jenkinsci.plugins.envinject.service;

import hudson.FilePath;
import hudson.matrix.MatrixRun;
import hudson.model.*;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.util.LogTaskListener;
import hudson.util.Secret;
import org.jenkinsci.lib.envinject.EnvInjectAction;
import org.jenkinsci.lib.envinject.EnvInjectException;
import org.jenkinsci.lib.envinject.EnvInjectLogger;
import org.jenkinsci.lib.envinject.service.EnvInjectActionRetriever;
import org.jenkinsci.lib.envinject.service.EnvInjectDetector;
import org.jenkinsci.plugins.envinject.EnvInjectJobProperty;
import org.jenkinsci.plugins.envinject.EnvInjectJobPropertyInfo;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Gregory Boissinot
 */
public class EnvInjectVariableGetter {

    private static Logger LOG = Logger.getLogger(EnvInjectVariableGetter.class.getName());

    public Map<String, String> getJenkinsSystemVariablesCurrentNode(AbstractBuild build) throws IOException, InterruptedException {

        Map<String, String> result = new TreeMap<String, String>();

        //Environment variables
        result.putAll(build.getEnvironment(new LogTaskListener(LOG, Level.ALL)));

        //Global properties
        for (NodeProperty<?> nodeProperty : Hudson.getInstance().getGlobalNodeProperties()) {
            if (nodeProperty instanceof EnvironmentVariablesNodeProperty) {
                EnvironmentVariablesNodeProperty environmentVariablesNodeProperty = (EnvironmentVariablesNodeProperty) nodeProperty;
                result.putAll(environmentVariablesNodeProperty.getEnvVars());
            }
        }

        //Node properties
        Computer computer = Computer.currentComputer();
        if (computer != null) {
            Node node = computer.getNode();
            if (node != null) {
                for (NodeProperty<?> nodeProperty : node.getNodeProperties()) {
                    if (nodeProperty instanceof EnvironmentVariablesNodeProperty) {
                        EnvironmentVariablesNodeProperty environmentVariablesNodeProperty = (EnvironmentVariablesNodeProperty) nodeProperty;
                        result.putAll(environmentVariablesNodeProperty.getEnvVars());
                    }
                }
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getBuildVariables(AbstractBuild build, EnvInjectLogger logger) throws EnvInjectException {
        Map<String, String> result = new HashMap<String, String>();

        //Add build process variables
        result.putAll(build.getCharacteristicEnvVars());

        //Add build variables such as parameters, plugins contributions, ...
        result.putAll(build.getBuildVariables());

        //Add workspace variable
        String workspace = getWorkspaceWithCreation(build, logger);
        if (workspace != null) {
            result.put("WORKSPACE", workspace);
        }

        //Retrieve triggered cause
        Map<String, String> triggerVariable = new BuildCauseRetriever().getTriggeredCause(build);
        result.putAll(triggerVariable);

        return result;
    }

    public Map<String, String> overrideParametersVariablesWithSecret(AbstractBuild build) {
        Map<String, String> result = new HashMap<String, String>();
        ParametersAction params = build.getAction(ParametersAction.class);
        if (params != null) {
            for (ParameterValue p : params) {
                try {
                    Field valueField = p.getClass().getDeclaredField("value");
                    valueField.setAccessible(true);
                    Object valueObject = valueField.get(p);
                    if (valueObject instanceof Secret) {
                        Secret secretValue = (Secret) valueObject;
                        result.put(p.getName(), secretValue.getEncryptedValue());
                    }
                } catch (NoSuchFieldException e) {
                    //the field doesn't exist
                    //test the next param
                    continue;
                } catch (IllegalAccessException e) {
                    continue;
                }
            }
        }
        return result;
    }

    private String getWorkspaceWithCreation(AbstractBuild build, EnvInjectLogger logger) throws EnvInjectException {
        try {
            Node node = build.getBuiltOn();
            if (node != null) {
                Job job = build.getParent();
                if (job instanceof TopLevelItem) {
                    FilePath workspace = decideWorkspace(build, (TopLevelItem) job, node, logger.getListener());
                    workspace.mkdirs();
                    return workspace.getRemote();
                }
            }
            return null;
        } catch (InterruptedException ie) {
            throw new EnvInjectException(ie);
        } catch (IOException ie) {
            throw new EnvInjectException(ie);
        }
    }

    private FilePath decideWorkspace(AbstractBuild build, TopLevelItem item, Node n, TaskListener listener) throws InterruptedException, IOException {
        if (item instanceof AbstractProject) {
            String customWorkspace = ((AbstractProject) item).getCustomWorkspace();
            if (customWorkspace != null) {
                return n.getRootPath().child(build.getEnvironment(listener).expand(customWorkspace));
            }
        }
        return n.getWorkspaceFor(item);
    }

    public boolean isEnvInjectJobPropertyActive(AbstractBuild build) {

        if (build == null) {
            throw new IllegalArgumentException("A build object must be set.");
        }

        Job job;
        if (build instanceof MatrixRun) {
            job = ((MatrixRun) build).getParentBuild().getParent();
        } else {
            job = build.getParent();
        }

        EnvInjectJobProperty envInjectJobProperty = getEnvInjectJobProperty(job);
        if (envInjectJobProperty != null) {
            EnvInjectJobPropertyInfo info = envInjectJobProperty.getInfo();
            if (info != null && envInjectJobProperty.isOn()) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public EnvInjectJobProperty getEnvInjectJobProperty(Job project) {
        return (EnvInjectJobProperty) project.getProperty(EnvInjectJobProperty.class);
    }

    public Map<String, String> getEnvVarsPreviousSteps(AbstractBuild build, EnvInjectLogger logger) throws IOException, InterruptedException, EnvInjectException {
        Map<String, String> result = new HashMap<String, String>();
        EnvInjectDetector envInjectDetector = new EnvInjectDetector();
        if (envInjectDetector.isEnvInjectActivated(build)) {
            result.putAll(getCurrentInjectedEnvVars(build));
        } else {
            result.putAll(getJenkinsSystemVariablesCurrentNode(build));
            result.putAll(getBuildVariables(build, logger));
        }
        return result;
    }

    private Map<String, String> getCurrentInjectedEnvVars(AbstractBuild<?, ?> build) {
        EnvInjectActionRetriever retriever = new EnvInjectActionRetriever();
        EnvInjectAction envInjectAction = retriever.getEnvInjectAction(build);
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (envInjectAction == null) {
            return result;
        } else {
            result.putAll(envInjectAction.getEnvMap());
            return result;
        }
    }

}
