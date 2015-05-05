package aws.deploy

import org.gradle.api.Project
import com.amazonaws.services.ec2.*
import com.amazonaws.services.ec2.model.*
import com.amazonaws.services.elasticloadbalancing.*
import com.amazonaws.services.elasticloadbalancing.model.*
import com.amazonaws.services.s3.*

class ServerTask {
   AwsTomcatDeployPluginExtension options
   Project project

   def deploy() {
      def serverIds = loadServerIds()
      def hostMap = loadHostMap(serverIds)
      if(serverIds.size() > 0) {
         def key = System.currentTimeMillis().toString()
         options.useS3 = options.useS3 && serverIds.size() > 1
         options.warPath = options.useS3 ? uploadWarFile(key) : project.file(options.warPath)
         try {
            for(id in serverIds)
               deployToServer(hostMap[id], id)
         } finally {
            if(options.useS3)
               deleteUploadedWarFile(key)
         }
      } else {
         println 'Did not find any servers to update. You must specify the serverId or loadBalancer property. If you are using a load balancer, the servers you want to update must already be associated with the load balancer.'
      }
   }
   
   private loadHostMap(serverIds) {
      def client = new AmazonEC2Client(options.awsCredentials)
      def request = new DescribeInstancesRequest().withInstanceIds(serverIds)
      def result = client.describeInstances(request)
      return result.reservations*.instances.flatten().collectEntries{[it.instanceId, (options.usePrivateIpAddress ? it.privateIpAddress : it.publicIpAddress)]}
   }
   
   private loadServerIds() {
      if(!options.loadBalancer)
         return options.serverId ? [options.serverId] : []
      def client = new AmazonElasticLoadBalancingClient(options.awsCredentials)
      def request = new DescribeInstanceHealthRequest()
      request.setLoadBalancerName(options.loadBalancer)
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
      def client = new AmazonS3Client(options.awsCredentials)
      client.putObject(options.s3bucket, key, project.file(options.warPath))
      def url = client.generatePresignedUrl(options.s3bucket, key, new Date(System.currentTimeMillis() + 600000))
      url.toString()
   }

   private deleteUploadedWarFile(key) {
      new AmazonS3Client(options.awsCredentials).deleteObject(options.s3bucket, key)
   }
   
   def addServer() {
      if(!options.serverId) {
         println 'You must set the serverId property to add a server to a load balancer'
      } else if(!options.loadBalancer) {
         println 'You must set the loadBalancer property to the name of the load balancer you want this server added to'
      } else {
         println 'Adding ' + options.serverId + ' to ' + options.loadBalancer
         options.useS3 = false
         options.warPath = project.file(options.warPath)
         def hostMap = loadHostMap([options.serverId])
         deployToServer(hostMap[options.serverId], options.serverId, true)
      }
   }
   
   def restartServer() {
	  if (!options.serverId) {
		println 'You must set the serverId property in order to restart a server'
	  } else if(!options.loadBalancer) {
         println 'You must set the loadBalancer property to the name of the load balancer you want this server added to'
      } else {
         println 'Adding ' + options.serverId + ' to ' + options.loadBalancer
         def hostMap = loadHostMap([options.serverId])
         doRestart(hostMap[options.serverId], options.serverId)
      }
   }
   
   private deployToServer(serverHost, serverId, newServer = false) {
      new Server(options: options, host: serverHost, id: serverId).deploy(newServer)
   }
   
   private doRestart(serverHost, serverId) {
	  new Server(options: options, host: serverHost, id: serverId).restart()
   }
   
   def getLogs () {
      def serverIds = loadServerIds()
      def hostMap = loadHostMap(serverIds)
      if(serverIds.size() > 0) {
         for(id in serverIds)
            getLog(hostMap[id], id)
      } else {
         println 'Did not find any servers to get logs for. You must specify the serverId or loadBalancer property.'
      }
   }
   
   private getLog(serverHost, serverId) {
      def destDir = options.localLogsDir ? options.localLogsDir : options.loadBalancer ? 'logs/' + options.loadBalancer : 'logs'
      new Server(options: options, host: serverHost, id: serverId).copyLogFile(project.file(destDir))
   }
}
