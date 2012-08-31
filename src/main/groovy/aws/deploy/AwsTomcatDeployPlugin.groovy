package aws.deploy

import org.gradle.api.Project
import org.gradle.api.Plugin

class AwsTomcatDeployPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.extensions.create("awsTomcatDeploy", AwsTomcatDeployPluginExtension)
        project.task('hello', type: AwsTask)
    }
}

class AwsTomcatDeployPluginExtension {
   String deployUser = 'ec2-user'
   List loadBalancers = []
   String accessKey = ''
   String secretKey = ''
}