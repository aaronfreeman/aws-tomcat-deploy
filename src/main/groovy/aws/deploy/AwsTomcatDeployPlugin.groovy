package aws.deploy

import org.gradle.api.Project
import org.gradle.api.Plugin
import com.amazonaws.auth.*

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
		project.task('restart') << {
			serverTask.restartServer()
		}
    }
}

class AwsTomcatDeployPluginExtension {
    private AWSCredentials credentials
	
	String loadBalancer = null
	String serverId = null
	String deployUser = 'ec2-user'
	String sshKeyPath = System.getProperty('user.home') + '/.ssh/id_rsa'
	String accessKey = ''
	String secretKey = ''
	String tomcatPath = '/opt/tomcat'
	String pidPath = '/opt/tomcat/tomcat.pid'
	String tomcatServiceName = 'tomcat'
	String warPath = null
	String appContext = 'ROOT'
	boolean deleteOldLogs = true
	boolean useS3 = false
	String s3bucket = ''
	boolean pingServer = true
	String pingProtocol = 'http'
	String pingPath = '/'
	String localLogsDir = null
  int maxServerStartWaitTime = 90

   def getAwsCredentials() {
      if(credentials == null)
         credentials = new BasicAWSCredentials(accessKey, secretKey)
      credentials
   }
}