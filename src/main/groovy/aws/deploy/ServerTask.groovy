package aws.deploy

import org.gradle.api.Project
import com.amazonaws.services.ec2.*
import com.amazonaws.services.ec2.model.*
import com.amazonaws.services.elasticloadbalancing.*
import com.amazonaws.services.elasticloadbalancing.model.*
import com.amazonaws.services.s3.*
import com.amazonaws.auth.*

class ServerTask {
   private AWSCredentials credentials
   AwsTomcatDeployPluginExtension options
   Project project

   def deploy() {
      def loadBalancer = options.loadBalancers[options.deployLevel]
      def serverIds = loadServerIds(loadBalancer)
      def hostMap = loadHostMap(serverIds)
      if(serverIds.size() > 0) {
         def key = System.currentTimeMillis().toString()
         if(options.useS3)
            options.warPath = uploadWarFile(key)
         try {
            for(id in serverIds)
               deployToServer(loadBalancer, hostMap[id], id)
         } finally {
            if(options.useS3)
               deleteUploadedWarFile(key)
         }
      }
   }
   
   private loadHostMap(serverIds) {
      def client = new AmazonEC2Client(awsCredentials)
      def request = new DescribeInstancesRequest().withInstanceIds(serverIds)
      def result = client.describeInstances(request)
      return result.reservations*.instances.flatten().collectEntries{[it.instanceId,it.publicDnsName]}
   }
   
   private loadServerIds(loadBalancer) {
      def client = new AmazonElasticLoadBalancingClient(awsCredentials)
      def request = new DescribeInstanceHealthRequest()
      request.setLoadBalancerName(loadBalancer)
      def result = client.describeInstanceHealth(request)
      def ids = []
      result.instanceStates.each {
         if (it.state == 'InService')
            ids.add(it.instanceId)
         else
            ids.add(0, it.instanceId)
      }
      return ids
   }

   private uploadWarFile(key) {
      println 'Uploading new war file'
      def client = new AmazonS3Client(awsCredentials)
      client.putObject(options.s3bucket, key, project.file(options.warPath))
      def url = client.generatePresignedUrl(options.s3bucket, key, new Date(System.currentTimeMillis() + 600000))
      url.toString()
   }

   private deleteUploadedWarFile(key) {
      new AmazonS3Client(awsCredentials).deleteObject(options.s3bucket, key)
   }
   
   def addServer() {
      def hostMap = loadHostMap([options.newServerId])
      deployToServer(loadBalancers[options.deployLevel], hostMap[options.newServerId], options.newServerId, true)
   }
   
   private deployToServer(loadBalancer, serverHost, serverId, newServer = false) {
      new Server(awsCredentials, loadBalancer, serverHost, serverId, project, options).deploy(options.warPath, newServer)
   }
   
   def getLogs () {
      def loadBalancer = options.loadBalancers[options.deployLevel]
      def serverIds = loadServerIds(loadBalancer)
      def hostMap = loadHostMap(serverIds)
      for(id in serverIds)
         getLog(loadBalancer, hostMap[id], id)
   }
   
   private getLog(loadBalancer, serverHost, serverId) {
      new Server(awsCredentials, loadBalancer, serverHost, serverId, project, options).copyLogFile('logs/' + options.deployLevel)
   }

   private getAwsCredentials() {
      if(credentials == null)
         credentials = new BasicAWSCredentials(options.accessKey, options.secretKey)
      credentials
   }
}
