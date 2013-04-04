package aws.deploy

import org.gradle.api.Project
import org.gradle.api.Plugin

class AwsTomcatDeployPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.extensions.create("awsTomcatDeploy", AwsTomcatDeployPluginExtension)
        def serverTask = new ServerTask(project: project, options: project.awsTomcatDeploy)
        project.task('deploy') << {
        	serverTask.deploy()
        }
        project.task('addServer') << {
        	serverTask.addServer()
        }
        project.task('getLogs') << {
        	serverTask.getLogs()
        }
    }
}

class AwsTomcatDeployPluginExtension {
	String deployLevel = ''
	String deployUser = 'ec2-user'
	String sshKeyPath = '~/.ssh/id_rsa'
	Map loadBalancers = [:]
	String accessKey = ''
	String secretKey = ''
	String tomcatPath = '/opt/tomcat'
	String tomcatServiceName = 'tomcat'
	String warPath = ''
	String appContext = 'ROOT'
	boolean deleteOldLogs = true
	boolean useS3 = true
	String s3bucket = ''
	boolean pingServer = true
	String pingProtocol = 'http'
	String pingPath = '/'
	String newServerId = System.properties['serverId']
}