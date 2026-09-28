import hudson.EnvVars
import hudson.model.Node
import hudson.slaves.DumbSlave
import hudson.slaves.EnvironmentVariablesNodeProperty
import hudson.slaves.JNLPLauncher
import hudson.slaves.RetentionStrategy
import jenkins.model.Jenkins
import jenkins.model.JenkinsLocationConfiguration

def jenkins = Jenkins.get()
jenkins.setNumExecutors(0)

def envOrDefault = { String name, String fallback ->
    def value = System.getenv(name)
    value == null || value.trim().isEmpty() ? fallback : value.trim()
}

def ensureInboundNode = { String name, String remoteFs, String labels ->
    def existing = jenkins.getNode(name)
    if (existing == null) {
        def node = new DumbSlave(name, "TestForge managed local agent ${name}", remoteFs, '1',
            Node.Mode.EXCLUSIVE, labels, new JNLPLauncher(true), RetentionStrategy.INSTANCE, [])
        jenkins.addNode(node)
    } else {
        existing.setLabelString(labels)
        existing.setNumExecutors(1)
        existing.setMode(Node.Mode.EXCLUSIVE)
        existing.save()
    }
}

ensureInboundNode('tf-build-host', envOrDefault('TESTFORGE_BUILD_AGENT_REMOTE_FS', 'C:\\Jenkins\\testforge-build'), 'testforge-builder windows-builder')
ensureInboundNode('tf-win-a', envOrDefault('TESTFORGE_WIN_A_REMOTE_FS', 'C:\\Jenkins'), 'testforge-deploy windows windows-vm tf-win-a')
ensureInboundNode('tf-win-b', envOrDefault('TESTFORGE_WIN_B_REMOTE_FS', 'C:\\Jenkins'), 'testforge-deploy windows windows-vm tf-win-b')

def globalProperties = jenkins.getGlobalNodeProperties()
def environmentProperty = globalProperties.get(EnvironmentVariablesNodeProperty.class)
if (environmentProperty == null) {
    environmentProperty = new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry[0])
    globalProperties.add(environmentProperty)
}
environmentProperty.getEnvVars().put('SKILL_SANDBOX_DEPLOY_NODES', envOrDefault('SKILL_SANDBOX_DEPLOY_NODES', 'tf-win-a,tf-win-b'))
environmentProperty.getEnvVars().put('TESTFORGE_GATEWAY_URL', envOrDefault('TESTFORGE_GATEWAY_URL', 'http://host.docker.internal:8081'))
def scmMirrorUrl = envOrDefault('TESTFORGE_SCM_MIRROR_URL', '')
if (!scmMirrorUrl.isEmpty()) {
    environmentProperty.getEnvVars().put('TESTFORGE_SCM_MIRROR_URL', scmMirrorUrl)
}

JenkinsLocationConfiguration.get().setUrl(envOrDefault('TESTFORGE_JENKINS_PUBLIC_URL', 'http://127.0.0.1:8082/'))
jenkins.save()
